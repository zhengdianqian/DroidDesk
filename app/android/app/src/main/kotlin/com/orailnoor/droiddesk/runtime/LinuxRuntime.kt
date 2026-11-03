package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * Core Linux runtime engine.
 *
 * Runs native Termux Linux on Android without PRoot.
 * Uses a C hook (libsocket_hook.so) via LD_PRELOAD to redirect file operations
 * and Unix socket connections from /data/data/com.termux/files/usr to DroidDesk's prefix.
 */
class LinuxRuntime(private val context: Context) {

    companion object {
        private const val TAG = "LinuxRuntime"
        private const val BOOTSTRAP_MARKER = ".bootstrap_extracted"
        private const val SHEBANG_MARKER = ".relocated_text_paths_v4"
        private const val ELF_PATCH_MARKER = ".elf_runpaths_patched"
        private const val DE_MARKER = ".de_installed"

        // ELF64 constants
        private const val ELFMAG0: Byte = 0x7f
        private const val ELFMAG1: Byte = 'E'.code.toByte()
        private const val ELFMAG2: Byte = 'L'.code.toByte()
        private const val ELFMAG3: Byte = 'F'.code.toByte()
        private const val ELFCLASS64: Byte = 2
        private const val ELFDATA2LSB: Byte = 1

        private const val PT_LOAD = 1
        private const val PT_DYNAMIC = 2

        private const val DT_NULL = 0L
        private const val DT_STRTAB = 5L
        private const val DT_STRSZ = 10L
        private const val DT_RPATH = 15L
        private const val DT_RUNPATH = 29L

        private const val EI_MAG0 = 0
        private const val EI_MAG1 = 1
        private const val EI_MAG2 = 2
        private const val EI_MAG3 = 3
        private const val EI_CLASS = 4
        private const val EI_DATA = 5

        private const val E_PHOFF_OFFSET = 32
        private const val E_PHENTSIZE_OFFSET = 54
        private const val E_PHNUM_OFFSET = 56

        private const val P_TYPE_OFFSET = 0
        private const val P_OFFSET_OFFSET = 8
        private const val P_VADDR_OFFSET = 16
        private const val P_FILESZ_OFFSET = 32
        private const val PH_SIZE = 56

        private const val D_TAG_OFFSET = 0
        private const val D_VAL_OFFSET = 8
        private const val DYN_SIZE = 16

        // The desktop is launched from DesktopActivity but queried/stopped from
        // MainActivity. Keep process ownership application-wide so both runtime
        // wrappers operate on the same session.
        @Volatile private var sessionProcess: Process? = null
        @Volatile private var dbusProcess: Process? = null
        @Volatile private var a11yBusProcess: Process? = null
    }

    @Volatile private var activeCommandProcess: Process? = null
    @Volatile private var installLogSink: ((String) -> Unit)? = null
    @Volatile private var packageOperationCancelled = false

    /**
     * Last real apt/dpkg failure, in Chinese. The store used to collapse every failure
     * into "<pkg> installation failed", which told the user nothing — "Unable to locate
     * package" (wrong repository) and "Could not resolve hosts" (network) need different
     * fixes, so the raw reason is captured here and surfaced by the progress UI.
     */
    @Volatile var lastPackageError: String = ""
        private set

    fun setInstallLogSink(sink: ((String) -> Unit)?) {
        installLogSink = sink
    }

    fun beginPackageOperation() {
        // A previous App Store screen may have been closed while apt was still
        // running. Stop that process before accepting a new transaction.
        if (activeCommandProcess?.isAlive == true) interruptCommand()
        packageOperationCancelled = false
        lastPackageError = ""
        clearStalePackageLocks()
    }

    fun finishPackageOperation() {
        packageOperationCancelled = false
    }

    fun cancelPackageOperation(): Boolean {
        packageOperationCancelled = true
        val wasRunning = activeCommandProcess?.isAlive == true
        interruptCommand()
        return wasRunning
    }

    // ── Base directories (all inside app's private storage) ──

    private val baseDir: File get() = context.filesDir
    private val prefixDir: File get() = File(baseDir, "usr")
    private val binDir: File get() = File(prefixDir, "bin")
    private val libDir: File get() = File(prefixDir, "lib")
    // Shared with X11ServerService. X clients resolve /tmp/.X11-unix/X0 here
    // through libsocket_hook, so this must not be $PREFIX/tmp.
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val homeDir: File get() = File(baseDir, "home")

    /** Qualcomm exposes the Adreno render device through KGSL on Android. */
    private fun hasAdrenoGpu(): Boolean = File("/dev/kgsl-3d0").exists()

    // The non-root product ships one supported, touch-tuned desktop.
    // Ignore legacy callers that may still request an older DE option.
    private fun normalizedDesktop(@Suppress("UNUSED_PARAMETER") desktopEnv: String): String = "xfce4"

    // ── Status ──

    fun isBootstrapped(): Boolean {
        return File(baseDir, BOOTSTRAP_MARKER).exists() && File(prefixDir, "bin/bash").exists()
    }

    fun isRunning(): Boolean {
        return sessionProcess?.isAlive == true
    }

    /**
     * Kills guest processes a previous session left behind.
     *
     * DesktopActivity can be destroyed (recents swipe, low-memory) while xfce4-session keeps
     * running. A stale session manager then refuses to start a new one ("another session
     * manager is already running") which is the main reason a launch ended on the loading
     * screen. Cheap: a few pkill calls through the app's own shell, no root required.
     */
    fun cleanupStaleSession() {
        val leftovers = listOf(
            "xfce4-session", "xfce4-panel", "xfdesktop", "xfwm4", "xfsettingsd",
            "xfce4-power-manager", "xfce4-notifyd", "xfconfd", "pulseaudio",
        )
        val script = buildString {
            leftovers.forEach { append("pkill -9 -x '$it' >/dev/null 2>&1 || true\n") }
            append("pkill -9 -f 'android_intent_pipe' >/dev/null 2>&1 || true\n")
            // Stale watchers are worse than leftover apps: an old ime-watch keeps its own
            // ARMED/LAST state and rewrites ime-focus to 1 across the next session, which
            // is one of the sources of the keyboard opening at launch. Selection helpers
            // linger the same way and would answer clipboard reads with dead content.
            append("pkill -9 -f 'ime-watch' >/dev/null 2>&1 || true\n")
            append("pkill -9 -f 'droiddesk-x11-focus' >/dev/null 2>&1 || true\n")
            append("pkill -9 -x xclip >/dev/null 2>&1 || true\n")
            append("pkill -9 -x xsel >/dev/null 2>&1 || true\n")
            append("rm -f \"${tmpDir.absolutePath}/dbus-session\"\n")
            append("rm -f \"${tmpDir.absolutePath}\"/.xfsm-ICE-* 2>/dev/null || true\n")
        }
        runCatching {
            ProcessBuilder(File(binDir, "bash").absolutePath, "-c", script)
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(getTermuxEnv())
                }
                .start()
                .waitFor(20, java.util.concurrent.TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "Stale session cleanup failed: ${it.message}") }
    }

    /**
     * Idempotent environment preparation: the expensive migrations already carry their own
     * markers, so a launch on an installed image only pays for a handful of file checks.
     */
    private fun prepareSessionEnvironment(onPhase: (String) -> Unit) {
        val stamp = "${File(prefixDir, "var/lib/dpkg/status").lastModified()}-" +
            File(binDir, "bash").lastModified()
        val marker = File(baseDir, ".session_prepared")
        if (!marker.exists() || marker.readTextOrNull() != stamp) {
            onPhase("整理 Linux 运行环境（首次启动较慢）")
            patchShebangs()
            patchElfRunpaths(prefixDir)
            compileSocketHook()
            patchEmbeddedXfcePaths()
            marker.writeText(stamp)
        }
        applyChineseEnvironment(onPhase)
    }

    /**
     * Existing images were installed before the Chinese defaults existed, so this runs on
     * every launch (fast path: two tiny file writes) and only touches the network when a
     * CJK font is genuinely missing.
     */
    fun applyChineseEnvironment(onLog: (String) -> Unit = {}) {
        runCatching {
            homeDir.mkdirs()
            val xprofile = File(homeDir, ".xprofile")
            val wanted = """
                export LANG=zh_CN.UTF-8
                export LC_ALL=zh_CN.UTF-8
                export LANGUAGE=zh_CN:zh
            """.trimIndent()
            val existing = if (xprofile.exists()) xprofile.readText() else ""
            if (!existing.contains("LANG=zh_CN.UTF-8")) {
                xprofile.appendText((if (existing.isBlank()) "" else "\n") + wanted + "\n")
            }
            val profile = File(homeDir, ".profile")
            if (!profile.exists() || !profile.readText().contains("LANG=zh_CN.UTF-8")) {
                profile.appendText("\n$wanted\n")
            }
            onLog("已写入中文环境变量（LANG=zh_CN.UTF-8）")
            DesktopLog.append(context, TAG, "Chinese environment applied to native runtime")
        }.onFailure {
            Log.w(TAG, "Failed to write Chinese environment: ${it.message}")
            onLog("写入中文环境变量失败：${it.message}")
        }
    }

    fun getInstalledDE(): String {
        val marker = File(prefixDir, DE_MARKER)
        if (marker.exists()) return marker.readText().trim().ifEmpty { "xfce4" }
        // Individual desktop binaries may already exist after a partially failed
        // package transaction. Only the marker written at the end of the complete
        // setup flow means the runtime is ready to launch.
        return ""
    }

    fun getGraphicsMode(): String {
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        return if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            "Turnip + Zink"
        } else {
            "Software (llvmpipe)"
        }
    }

    fun getOptionalAppsStatus(): Map<String, Boolean> = mapOf(
        "firefox" to File(binDir, "firefox").exists(),
        "code_oss" to (File(binDir, "code-oss").exists() || File(binDir, "code").exists()),
        "nodejs" to (File(binDir, "node").exists() && File(binDir, "npm").exists()),
        "imagemagick" to (File(binDir, "magick").exists() || File(binDir, "convert").exists()),
        "proot_debian" to isMinimalDebianInstalled(),
    )

    private fun isMinimalDebianInstalled(): Boolean {
        val installed = debianRootfsMarkers().any(File::exists) &&
            File(binDir, "start-debian").exists()
        if (installed) {
            relocateProotExecutable()
            writeDebianLauncher()
            clearProotDownloadCache()
        }
        return installed
    }

    private fun relocateProotExecutable() {
        val proot = File(binDir, "proot")
        if (!proot.isFile) return
        patchElfFile(
            proot,
            "/data/data/com.termux/files/usr/lib",
            File(prefixDir, "lib").absolutePath,
            "/data/data/com.termux/files/usr/lib/dri",
        )
    }

    private fun writeDebianLauncher() {
        val launcher = File(binDir, "start-debian")
        launcher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export DISPLAY="${'$'}{DISPLAY:-:0}"
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --bind "${prefixDir.absolutePath}:/data/data/com.termux/files/usr" \
                --bind "${homeDir.absolutePath}:/data/data/com.termux/files/home" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
                env DISPLAY="${'$'}DISPLAY" TERM="${'$'}{TERM:-xterm-256color}" bash -l
            """.trimIndent() + "\n",
        )
        launcher.setExecutable(true, false)

        val appsLauncher = File(binDir, "debian-apps")
        appsLauncher.writeText(
            """
            #!${File(binDir, "bash").absolutePath}
            export TMPDIR="${tmpDir.absolutePath}"
            mkdir -p "${tmpDir.absolutePath}/proot"
            mode="${'$'}{1:-gui}"
            exec "${File(binDir, "proot-distro").absolutePath}" login debian \
                --bind "${tmpDir.absolutePath}:/tmp" \
                --bind "${prefixDir.absolutePath}:/data/data/com.termux/files/usr" \
                --bind "${homeDir.absolutePath}:/data/data/com.termux/files/home" \
                --env PROOT_TMP_DIR="${tmpDir.absolutePath}/proot" \
                --env PROOT_LOADER="${File(prefixDir, "libexec/proot/loader").absolutePath}" \
                --env PROOT_LOADER_32="${File(prefixDir, "libexec/proot/loader32").absolutePath}" -- \
                bash -s -- "${'$'}mode" <<'DROIDDESK_DEBIAN_APPS'
            mode="${'$'}1"
            case "${'$'}mode" in
                --all)
                    dpkg-query -W -f='${'$'}{binary:Package}\t${'$'}{Version}\n' | sort
                    ;;
                --manual)
                    apt-mark showmanual | sort
                    ;;
                gui)
                    for desktop in \
                        /usr/share/applications/*.desktop \
                        /usr/local/share/applications/*.desktop
                    do
                        [ -f "${'$'}desktop" ] || continue
                        no_display=${'$'}(sed -n 's/^NoDisplay=//p' "${'$'}desktop" | head -n 1)
                        [ "${'$'}no_display" = "true" ] && continue
                        name=${'$'}(sed -n 's/^Name=//p' "${'$'}desktop" | head -n 1)
                        command=${'$'}(sed -n 's/^Exec=//p' "${'$'}desktop" | head -n 1)
                        [ -n "${'$'}name" ] && [ -n "${'$'}command" ] &&
                            printf '%-32s %s\n' "${'$'}name" "${'$'}command"
                    done | sort -f
                    ;;
                *)
                    echo "Usage: debian-apps [--manual|--all]" >&2
                    exit 2
                    ;;
            esac
            DROIDDESK_DEBIAN_APPS
            """.trimIndent() + "\n",
        )
        appsLauncher.setExecutable(true, false)
    }

    private fun clearProotDownloadCache() {
        File(prefixDir, "var/lib/proot-distro/dlcache").deleteRecursively()
        File(prefixDir, "var/lib/proot-distro/cache").deleteRecursively()
    }

    private fun debianRootfsMarkers(): List<File> = listOf(
        // proot-distro 5.4+
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/usr/lib/os-release"),
        File(prefixDir, "var/lib/proot-distro/containers/debian/rootfs/etc/os-release"),
        // Legacy proot-distro releases
        File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian/etc/os-release"),
    )

    // ── Bootstrap ──

    fun setupBootstrap() {
        Log.i(TAG, "Setting up bootstrap environment...")
        listOf(prefixDir, binDir, libDir, tmpDir, homeDir).forEach { it.mkdirs() }
        Log.i(TAG, "Bootstrap directories ready. Base: ${baseDir.absolutePath}")
    }

    fun extractBootstrapIfNeeded(context: Context) {
        val bashBin = File(prefixDir, "bin/bash")
        if (bashBin.exists()) {
            Log.i(TAG, "Bootstrap already extracted at ${prefixDir.absolutePath}")
            // Refresh wrapper/config in case the app was updated
            createAptConfigOverride()
            ensureAptDirectories()
            wrapDpkgForPath()
            wrapUpdateAlternatives()
            ensureSocketHookPrebuilt()
            return
        }

        val marker = File(baseDir, BOOTSTRAP_MARKER)
        if (marker.exists()) {
            marker.delete()
        }

        Log.i(TAG, "Extracting bootstrap from assets to ${prefixDir.absolutePath}...")

        // Remove any partial extraction to ensure a clean bootstrap
        if (prefixDir.exists()) {
            prefixDir.deleteRecursively()
        }
        prefixDir.mkdirs()

        // Flutter assets are located under "flutter_assets/" in the APK asset tree
        val assetName = "flutter_assets/assets/bootstrap-aarch64.zip"
        val tmpZip = File(tmpDir, "bootstrap-aarch64.zip")
        tmpZip.parentFile?.mkdirs()

        try {
            context.assets.open(assetName).use { input ->
                tmpZip.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy bootstrap asset: ${e.message}", e)
            return
        }

        // Extract the bootstrap so the top-level bin/lib/etc directories land under prefixDir/usr
        extractZip(tmpZip, prefixDir)

        // Restore symlinks from SYMLINKS.txt
        restoreSymlinks(prefixDir)

        // Create apt config override so apt uses our prefix
        createAptConfigOverride()

        // Ensure apt cache/state directories exist (bootstrap zip may omit empty dirs)
        ensureAptDirectories()

        // Set executable permissions on binaries
        setExecutableRecursively(binDir)
        setExecutableRecursively(File(prefixDir, "libexec"))

        // Wrap dpkg so it and its children (e.g. dpkg-split) always see the right PATH/env
        wrapDpkgForPath()

        // Wrap update-alternatives so its postinst invocations don't fail under
        // our relocated root (it otherwise double-prefixes absolute paths).
        wrapUpdateAlternatives()

        // Copy prebuilt socket hook from jniLibs to prefix/lib
        ensureSocketHookPrebuilt()

        marker.writeText("DroidDesk native bootstrap\n")
        tmpZip.delete()
        Log.i(TAG, "Bootstrap extraction complete")
    }

    private fun ensureSocketHookPrebuilt() {
        try {
            val libDir = File(prefixDir, "lib")
            libDir.mkdirs()
            val destHook = File(libDir, "libsocket_hook.so")
            if (destHook.exists()) return

            // Find the hook in jniLibs
            val abi = android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a"
            val jniDir = File(context.applicationInfo.nativeLibraryDir)
            val srcHook = File(jniDir, "libsocket_hook.so")
            if (srcHook.exists()) {
                srcHook.inputStream().use { input ->
                    destHook.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                destHook.setExecutable(true, false)
                Log.i(TAG, "Copied prebuilt socket hook to ${destHook.absolutePath}")
            } else {
                Log.w(TAG, "Prebuilt socket hook not found in jniLibs at ${srcHook.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy prebuilt socket hook: ${e.message}")
        }
    }

    private fun extractZip(zipFile: File, destDir: File) {
        destDir.mkdirs()
        ZipInputStream(zipFile.inputStream()).use { zis ->
            var entry: ZipEntry? = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    outFile.outputStream().use { output ->
                        zis.copyTo(output)
                    }
                }
                entry = zis.nextEntry
            }
        }
    }

    private fun createAptConfigOverride() {
        try {
            val aptConfDir = File(prefixDir, "etc/apt/apt.conf.d")
            aptConfDir.mkdirs()
            val confFile = File(aptConfDir, "99-droiddesk-paths.conf")
            confFile.writeText(
                """
                # Every apt directory is named explicitly: the bootstrap's apt was
                # compiled for /data/data/com.termux/files/usr, and the LD_PRELOAD
                # path hook does not intercept every libc call on every Android
                # build. Explicit paths keep apt off the unreadable literal prefix.
                Dir "${prefixDir.absolutePath}";
                Dir::Etc "${prefixDir.absolutePath}/etc/apt";
                Dir::Etc::main "${prefixDir.absolutePath}/etc/apt/apt.conf";
                Dir::Etc::parts "${prefixDir.absolutePath}/etc/apt/apt.conf.d";
                Dir::Etc::sourcelist "${prefixDir.absolutePath}/etc/apt/sources.list";
                Dir::Etc::sourceparts "${prefixDir.absolutePath}/etc/apt/sources.list.d";
                Dir::Etc::preferences "${prefixDir.absolutePath}/etc/apt/preferences";
                Dir::Etc::preferencesparts "${prefixDir.absolutePath}/etc/apt/preferences.d";
                Dir::Etc::trusted "${prefixDir.absolutePath}/etc/apt/trusted.gpg";
                Dir::Etc::trustedparts "${prefixDir.absolutePath}/etc/apt/trusted.gpg.d";
                Dir::State "${prefixDir.absolutePath}/var/lib/apt";
                Dir::State::status "${prefixDir.absolutePath}/var/lib/dpkg/status";
                Dir::State::dpkg "${prefixDir.absolutePath}/var/lib/dpkg";
                Dir::Cache "${prefixDir.absolutePath}/var/cache/apt";
                Dir::Cache::archives "${prefixDir.absolutePath}/var/cache/apt/archives";
                Dir::Log "${prefixDir.absolutePath}/var/log/apt";
                Dir::Bin::Methods "${prefixDir.absolutePath}/lib/apt/methods";
                Dir::Bin::dpkg "${prefixDir.absolutePath}/bin/dpkg";
                Dir::Bin::apt-key "${prefixDir.absolutePath}/bin/apt-key";
                Acquire::gpgv::Options:: "--homedir=${prefixDir.absolutePath}/etc/apt/trusted.gpg.d";
                Acquire::Retries "3";
                Acquire::http::Timeout "30";
                Acquire::https::Timeout "30";
                DPkg::Lock::Timeout "60";
                """.trimIndent()
            )
            Log.i(TAG, "Created apt config override at ${confFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create apt config override: ${e.message}")
        }
    }

    private fun wrapDpkgForPath() {
        try {
            val dpkgBin = File(prefixDir, "bin/dpkg")
            val dpkgReal = File(prefixDir, "bin/dpkg.real")
            val relocateShebangs = File(prefixDir, "bin/droiddesk-relocate-shebangs")

            // Build a mini root tree so dpkg can use Termux-style paths internally
            // while the actual files land in our private prefix.
            val dpkgRoot = File(baseDir, "dpkgroot")
            mapOf(
                File(dpkgRoot, "data/data/com.termux/files/usr") to prefixDir.absolutePath,
                File(dpkgRoot, "var/lib/dpkg") to File(prefixDir, "var/lib/dpkg").absolutePath,
                File(dpkgRoot, "tmp") to tmpDir.absolutePath
            ).forEach { (linkDir, target) ->
                linkDir.parentFile?.mkdirs()
                // Remove an existing symlink so we can update its target on app upgrades.
                if (linkDir.exists()) {
                    linkDir.delete()
                }
                if (!linkDir.exists()) {
                    android.system.Os.symlink(target, linkDir.absolutePath)
                }
            }

            // Make sure the real dpkg binary is saved as dpkg.real, then always
            // rewrite the wrapper so updates take effect on app upgrade/reinstall.
            if (!dpkgReal.exists() && dpkgBin.exists()) {
                dpkgBin.renameTo(dpkgReal)
            }

            // dpkg enumerates its compile-time configuration directory through
            // libc APIs that are not consistently intercepted by the path hook
            // on every Android build. Point that one embedded path at the
            // wrapper's working directory instead.
            patchEmbeddedCommand(
                dpkgReal,
                "/data/data/com.termux/files/usr/etc/dpkg",
                "/proc/self/cwd/etc/dpkg",
            )

            // Package installations started inside the XFCE terminal bypass the
            // Kotlin installation flow. Run this after every dpkg transaction so
            // newly unpacked scripts never keep Termux's inaccessible prefix.
            // apt unpacks and configures in separate dpkg runs, so maintainer
            // scripts are fixed before they execute. The whole script is
            // rewritten, not just the #! line: bodies such as cups' postinst
            // ("mkdir -p /data/data/com.termux/...") or the code-oss and
            // hangover-wine launchers otherwise fail with "Permission denied".
            relocateShebangs.writeText(
                """
                #!/system/bin/sh
                old_prefix="/data/data/com.termux/files/usr"
                new_prefix="${prefixDir.absolutePath}"
                old_home="/data/data/com.termux/files/home"
                new_home="${homeDir.absolutePath}"
                # "--all" rescans everything once (repairs older installs);
                # otherwise only files changed since the dpkg run began.
                if [ "${'$'}1" = "--all" ]; then
                    changed=""
                else
                    [ -f "${'$'}1" ] || exit 0
                    changed="-cnewer ${'$'}1"
                fi
                for root in \
                    "${prefixDir.absolutePath}/bin" \
                    "${prefixDir.absolutePath}/libexec" \
                    "${prefixDir.absolutePath}/lib" \
                    "${prefixDir.absolutePath}/opt" \
                    "${prefixDir.absolutePath}/share" \
                    "${prefixDir.absolutePath}/var/lib/dpkg/info"
                do
                    [ -d "${'$'}root" ] || continue
                    "${prefixDir.absolutePath}/bin/find" "${'$'}root" \
                        -type f -perm -100 -size -2M ${'$'}changed \
                        ! -name droiddesk-relocate-shebangs \
                        -exec "${prefixDir.absolutePath}/bin/grep" -lIF "/data/data/com.termux/files" {} + 2>/dev/null |
                    while IFS= read -r file; do
                        first_line=$("${prefixDir.absolutePath}/bin/head" -n 1 "${'$'}file" 2>/dev/null)
                        # dpkg's *.list/*.md5sums must keep Termux paths; only
                        # maintainer scripts and other #! scripts are rewritten.
                        case "${'$'}file:${'$'}first_line" in
                            "${prefixDir.absolutePath}/var/lib/dpkg/info/"*.list:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.md5sums:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.conffiles:*)
                                ;;
                            "${prefixDir.absolutePath}/var/lib/dpkg/info/"*inst:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*rm:*|"${prefixDir.absolutePath}/var/lib/dpkg/info/"*.config:*|*":#!"*)
                                "${prefixDir.absolutePath}/bin/sed" -i \
                                    -e "s|${'$'}old_prefix|${'$'}new_prefix|g" \
                                    -e "s|${'$'}old_home|${'$'}new_home|g" "${'$'}file"
                                ;;
                        esac
                    done
                done
                exit 0
                """.trimIndent() + "\n",
            )
            relocateShebangs.setExecutable(true, false)

            val wrapper = """
                #!/system/bin/sh
                export PATH="${prefixDir.absolutePath}/bin:${'$'}PATH"
                export LD_LIBRARY_PATH="${prefixDir.absolutePath}/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
                export LD_PRELOAD="${prefixDir.absolutePath}/lib/libsocket_hook.so${'$'}{LD_PRELOAD:+:${'$'}LD_PRELOAD}"
                # dpkg requires admindir to be inside root. Strip any caller-provided
                # --root/--admindir (and their values) and prepend our own before any
                # trailing filenames/apt separators so dpkg parses them as options.
                caller_dir="${'$'}PWD"
                args=""
                while [ ${'$'}# -gt 0 ]; do
                    case "${'$'}1" in
                        --admindir=*|--root=*)
                            ;;
                        --admindir|--root)
                            shift
                            ;;
                        *)
                            arg="${'$'}1"
                            case "${'$'}arg" in
                                /*)
                                    ;;
                                *)
                                    if [ -e "${'$'}caller_dir/${'$'}arg" ]; then
                                        arg="${'$'}caller_dir/${'$'}arg"
                                    fi
                                    ;;
                            esac
                            args="${'$'}args ${'$'}arg"
                            ;;
                    esac
                    shift
                done
                cd "${prefixDir.absolutePath}" || exit 1
                scan_marker="${tmpDir.absolutePath}/dpkg-shebang-scan-${'$'}${'$'}"
                : > "${'$'}scan_marker"
                "${dpkgReal.absolutePath}" --force-not-root --force-script-chrootless --root="${dpkgRoot.absolutePath}" --admindir="${dpkgRoot.absolutePath}/var/lib/dpkg" ${'$'}args
                status=${'$'}?
                "${relocateShebangs.absolutePath}" "${'$'}scan_marker"
                rm -f "${'$'}scan_marker"
                exit ${'$'}status
            """.trimIndent()

            dpkgBin.writeText(wrapper)
            dpkgBin.setExecutable(true, false)
            Log.i(TAG, "Installed dpkg wrapper at ${dpkgBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install dpkg wrapper: ${e.message}")
        }
    }

    private fun wrapUpdateAlternatives() {
        try {
            val uaBin = File(prefixDir, "bin/update-alternatives")
            val uaReal = File(prefixDir, "bin/update-alternatives.real")

            if (!uaBin.exists() || uaReal.exists()) return

            uaBin.renameTo(uaReal)
            uaBin.writeText(
                """
                #!/system/bin/sh
                # update-alternatives is not needed for DroidDesk's single-prefix
                # environment and fails when dpkg is run with a relocated root.
                exit 0
                """.trimIndent()
            )
            uaBin.setExecutable(true, false)
            Log.i(TAG, "Installed update-alternatives wrapper at ${uaBin.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update-alternatives wrapper: ${e.message}")
        }
    }

    private fun ensureAptDirectories() {
        listOf(
            "etc/dpkg/dpkg.cfg.d",
            "var/cache/apt/archives/partial",
            "var/lib/apt/lists/partial",
            "var/lib/dpkg/info",
            "var/lib/dpkg/alternatives",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/parts",
            "var/lib/dpkg/triggers",
            "var/log/apt"
        ).forEach { relativePath ->
            File(prefixDir, relativePath).mkdirs()
        }
        // dpkg requires these files to exist even if empty
        listOf(
            "var/lib/dpkg/status",
            "var/lib/dpkg/available",
            "var/lib/dpkg/diversions"
        ).forEach { relativePath ->
            val f = File(prefixDir, relativePath)
            if (!f.exists()) f.createNewFile()
        }
        Log.i(TAG, "Ensured apt/dpkg cache/state directories exist")
    }

    private fun setExecutableRecursively(dir: File) {
        if (!dir.exists()) return
        dir.walkTopDown().forEach { file ->
            if (file.isFile) {
                file.setExecutable(true, false)
            }
        }
    }

    private fun restoreSymlinks(prefixDir: File) {
        val symlinksFile = File(prefixDir, "SYMLINKS.txt")
        if (!symlinksFile.exists()) {
            Log.w(TAG, "SYMLINKS.txt not found, skipping symlink restoration")
            return
        }

        val termuxPrefix = "/data/data/com.termux/files/usr"
        var created = 0
        var failed = 0
        symlinksFile.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach

            // Format: /data/data/com.termux/files/usr/.../target←./linkpath
            val arrow = "\u2190"
            val parts = trimmed.split(arrow)
            if (parts.size != 2) return@forEach

            val targetPath = parts[0].trim()
            val linkPath = parts[1].trim()

            // linkPath is relative to prefix root (starts with ./)
            val cleanLinkPath = if (linkPath.startsWith("./")) linkPath.substring(2) else linkPath
            val linkFile = File(prefixDir, cleanLinkPath)

            // Rewrite target from Termux prefix to app prefix;
            // bare filenames are kept relative so the symlink resolves correctly.
            val newTarget = when {
                targetPath.startsWith(termuxPrefix) -> {
                    prefixDir.absolutePath + targetPath.substring(termuxPrefix.length)
                }
                targetPath.startsWith("/") -> targetPath
                else -> targetPath
            }

            try {
                if (linkFile.exists()) {
                    linkFile.deleteRecursively()
                }
                linkFile.parentFile?.mkdirs()
                android.system.Os.symlink(newTarget, linkFile.absolutePath)
                created++
            } catch (e: Exception) {
                failed++
                Log.w(TAG, "Failed to create symlink ${linkFile.absolutePath} -> $newTarget: ${e.message}")
            }
        }

        Log.i(TAG, "Restored $created symlinks, $failed failed")
    }

    // ── Shebang Patching ──
    fun patchShebangs(force: Boolean = false) {
        val oldPrefix = "/data/data/com.termux/files/usr"
        val newPrefix = prefixDir.absolutePath

        if (oldPrefix == newPrefix) return

        val markerFile = File(prefixDir, SHEBANG_MARKER)
        val migrating = !markerFile.exists()
        if (!force && !migrating) {
            Log.i(TAG, "Shebangs already patched, skipping")
            return
        }

        Log.i(TAG, "Patching shebangs: $oldPrefix -> $newPrefix")
        var patchCount = 0

        val dirsToScan = listOf("bin", "libexec", "share", "etc", "var/lib/dpkg/info")
        for (dirName in dirsToScan) {
            val dir = File(prefixDir, dirName)
            if (!dir.exists()) continue

            dir.walkTopDown().forEach { file ->
                if (file.isFile && file.canRead()) {
                    try {
                        val bytes = file.inputStream().use {
                            val buf = ByteArray(256)
                            val n = it.read(buf)
                            if (n > 0) buf.copyOf(n) else ByteArray(0)
                        }

                        val isScript = bytes.size >= 2 &&
                            bytes[0] == '#'.code.toByte() && bytes[1] == '!'.code.toByte()
                        val isPathConfig = file.extension.lowercase() in setOf(
                            "service", "desktop", "conf", "xml", "pc", "cmake",
                            "la", "prl", "sh", "pl", "py", "rb", "json", "ini",
                        )
                        if (isScript || isPathConfig) {
                            val content = file.readText()
                            if (content.contains(oldPrefix)) {
                                val updated = content.replace(oldPrefix, newPrefix)
                                file.writeText(updated)
                                patchCount++
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore binary or read-only files
                    }
                }
            }
        }
        // New dpkg runs relocate their own files; the full scan is a one-time migration.
        if (migrating) relocateAllScripts()
        markerFile.writeText("done")
        Log.i(TAG, "Patched $patchCount scripts.")
    }

    private fun hasUnfinishedPackages(): Boolean = runCatching {
        File(prefixDir, "var/lib/dpkg/status").useLines { lines ->
            lines.any { it.startsWith("Status: ") && !it.endsWith(" installed") && !it.endsWith(" not-installed") && !it.endsWith(" config-files") }
        }
    }.getOrDefault(false)

    /**
     * Older releases only relocated the #! line of new scripts, so script bodies (and launchers
     * under lib/ and opt/) could still point at Termux and fail with "Permission denied".
     */
    private fun relocateAllScripts() {
        val relocate = File(prefixDir, "bin/droiddesk-relocate-shebangs")
        if (!relocate.canExecute()) return
        runCatching {
            val process = ProcessBuilder("/system/bin/sh", relocate.absolutePath, "--all")
                .redirectErrorStream(true)
                .also { builder ->
                    builder.environment().clear()
                    builder.environment().putAll(getTermuxEnv())
                }
                .start()
            process.inputStream.bufferedReader().readText()
            process.waitFor()
        }.onFailure { Log.w(TAG, "Full script relocation failed: ${it.message}") }
    }

    /** Relocate commands that Xfce compiled as absolute Termux paths. */
    private fun patchEmbeddedXfcePaths() {
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-session"),
            "/data/data/com.termux/files/usr/bin/iceauth",
            "iceauth",
        )
        patchEmbeddedCommand(
            File(prefixDir, "bin/xfce4-panel"),
            "/data/data/com.termux/files/usr/lib/xfce4/panel/migrate",
            "migrate",
        )
    }

    private fun patchEmbeddedCommand(file: File, oldCommand: String, newCommand: String) {
        if (!file.exists() || newCommand.length > oldCommand.length) return
        val oldValue = oldCommand.toByteArray()
        val newValue = newCommand.toByteArray()
        val bytes = file.readBytes()
        var patched = false
        var offset = 0
        while (offset <= bytes.size - oldValue.size) {
            var matches = true
            for (index in oldValue.indices) {
                if (bytes[offset + index] != oldValue[index]) {
                    matches = false
                    break
                }
            }
            if (matches) {
                bytes.fill(0, offset, offset + oldValue.size)
                newValue.copyInto(bytes, offset)
                patched = true
                offset += oldValue.size
            } else {
                offset++
            }
        }
        if (patched) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            Log.i(TAG, "Relocated embedded command in ${file.name}: $newCommand")
        }
    }

    // ── ELF RUNPATH/RPATH Patching ──

    fun patchElfRunpaths(prefixDir: File) {
        val marker = File(prefixDir, ELF_PATCH_MARKER)
        if (marker.exists()) {
            Log.i(TAG, "ELF runpaths already patched, skipping")
            return
        }

        val libDir = File(prefixDir, "lib")
        if (!libDir.exists()) {
            Log.w(TAG, "lib directory not found, skipping ELF patch")
            return
        }

        val oldPath = "/data/data/com.termux/files/usr/lib"
        val newPath = libDir.absolutePath
        val driOldPath = "/data/data/com.termux/files/usr/lib/dri"

        var patched = 0
        libDir.walkTopDown()
            .filter { it.isFile && it.canRead() && isElf64(it) }
            .forEach { file ->
                try {
                    if (patchElfFile(file, oldPath, newPath, driOldPath)) {
                        patched++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to patch ${file.absolutePath}: ${e.message}")
                }
            }

        marker.writeText("patched $patched files")
        Log.i(TAG, "Patched ELF runpaths in $patched files")
    }

    private fun isElf64(file: File): Boolean {
        if (file.length() < 6) return false
        return file.inputStream().use { input ->
            val buf = ByteArray(6)
            val n = input.read(buf)
            if (n < 6) return@use false
            buf[EI_MAG0] == ELFMAG0 &&
                    buf[EI_MAG1] == ELFMAG1 &&
                    buf[EI_MAG2] == ELFMAG2 &&
                    buf[EI_MAG3] == ELFMAG3 &&
                    buf[EI_CLASS] == ELFCLASS64 &&
                    buf[EI_DATA] == ELFDATA2LSB
        }
    }

    private fun patchElfFile(file: File, oldPath: String, newPath: String, driOldPath: String): Boolean {
        val bytes = file.readBytes()
        if (bytes.size < 64) return false

        val ePhoff = getLongLe(bytes, E_PHOFF_OFFSET)
        val ePhentsize = getShortLe(bytes, E_PHENTSIZE_OFFSET).toInt() and 0xFFFF
        val ePhnum = getShortLe(bytes, E_PHNUM_OFFSET).toInt() and 0xFFFF

        if (ePhoff < 0 || ePhoff + ePhnum * ePhentsize.toLong() > bytes.size) return false

        var strTabAddr: Long? = null
        var strTabSize: Long? = null
        val runpathOffsets = mutableListOf<Long>()
        val rpathOffsets = mutableListOf<Long>()

        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_DYNAMIC) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (pOffset < 0 || pOffset + pFilesz > bytes.size) continue

                val dynCount = pFilesz / DYN_SIZE
                for (j in 0 until dynCount) {
                    val dynOffset = (pOffset + j * DYN_SIZE).toInt()
                    val dTag = getLongLe(bytes, dynOffset + D_TAG_OFFSET)
                    val dVal = getLongLe(bytes, dynOffset + D_VAL_OFFSET)
                    when (dTag) {
                        DT_STRTAB -> strTabAddr = dVal
                        DT_STRSZ -> strTabSize = dVal
                        DT_RPATH -> rpathOffsets.add(dVal)
                        DT_RUNPATH -> runpathOffsets.add(dVal)
                        DT_NULL -> break
                    }
                }
            }
        }

        if (strTabAddr == null || strTabSize == null || strTabSize <= 0) return false

        var strTabFileOffset: Long? = null
        for (i in 0 until ePhnum) {
            val phOffset = (ePhoff + i * ePhentsize).toInt()
            val pType = getIntLe(bytes, phOffset + P_TYPE_OFFSET)
            if (pType == PT_LOAD) {
                val pOffset = getLongLe(bytes, phOffset + P_OFFSET_OFFSET)
                val pVaddr = getLongLe(bytes, phOffset + P_VADDR_OFFSET)
                val pFilesz = getLongLe(bytes, phOffset + P_FILESZ_OFFSET)
                if (strTabAddr >= pVaddr && strTabAddr < pVaddr + pFilesz) {
                    strTabFileOffset = pOffset + (strTabAddr - pVaddr)
                    break
                }
            }
        }

        if (strTabFileOffset == null) return false

        val origin = "\${ORIGIN}"
        val relativeLib = "\${ORIGIN}/../lib"
        val replacement = when {
            newPath.length <= oldPath.length -> newPath
            file.parentFile?.absolutePath == newPath -> origin
            else -> relativeLib
        }
        var modified = false

        for (offset in runpathOffsets + rpathOffsets) {
            val fileOffset = (strTabFileOffset + offset).toInt()
            if (fileOffset < 0 || fileOffset >= bytes.size) continue
            val endOffset = minOf((strTabFileOffset + strTabSize).toInt(), bytes.size)
            val current = readNullTerminatedString(bytes, fileOffset, endOffset)

            when {
                current == driOldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, origin, current.length)
                    modified = true
                }
                current == oldPath -> {
                    writeNullTerminatedString(bytes, fileOffset, replacement, current.length)
                    modified = true
                }
                current.contains(driOldPath) -> {
                    val replaced = current.replace(driOldPath, origin)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
                current.contains(oldPath) -> {
                    val replaced = current.replace(oldPath, replacement)
                    if (replaced.length <= current.length) {
                        writeNullTerminatedString(bytes, fileOffset, replaced, current.length)
                        modified = true
                    }
                }
            }
        }

        if (modified) {
            file.writeBytes(bytes)
            file.setExecutable(true, false)
            return true
        }
        return false
    }

    private fun readNullTerminatedString(bytes: ByteArray, start: Int, end: Int): String {
        var i = start
        while (i < end && bytes[i] != 0.toByte()) i++
        return String(bytes, start, i - start, Charsets.UTF_8)
    }

    private fun writeNullTerminatedString(bytes: ByteArray, offset: Int, value: String, padTo: Int) {
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        for (i in valueBytes.indices) {
            bytes[offset + i] = valueBytes[i]
        }
        for (i in valueBytes.size until padTo) {
            bytes[offset + i] = 0
        }
    }

    private fun getLongLe(bytes: ByteArray, offset: Int): Long {
        var result = 0L
        for (i in 7 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toLong() and 0xFF)
        }
        return result
    }

    private fun getIntLe(bytes: ByteArray, offset: Int): Int {
        var result = 0
        for (i in 3 downTo 0) {
            result = (result shl 8) or (bytes[offset + i].toInt() and 0xFF)
        }
        return result
    }

    private fun getShortLe(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset + 1].toInt() and 0xFF) shl 8 or (bytes[offset].toInt() and 0xFF)).toShort()
    }

    // ── C Socket Hook Native Compilation ──
    fun compileSocketHook() {
        val hookC = File(tmpDir, "socket_hook.c")
        val hookBuildC = File(tmpDir, "socket_hook_build.c")
        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        val buildMarker = File(prefixDir, "lib/.socket_hook_build")
        File(prefixDir, "lib").mkdirs()

        try {
            context.assets.open("flutter_assets/assets/socket_hook.c").use { input ->
                hookC.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy socket_hook.c asset: ${e.message}")
            return
        }

        val buildSignature = java.security.MessageDigest.getInstance("SHA-256")
            .digest((hookC.readText() + "\n" + prefixDir.absolutePath).toByteArray())
            .joinToString("") { "%02x".format(it) }
        if (hookSo.isFile && buildMarker.readTextOrNull() == buildSignature) {
            Log.d(TAG, "Native socket hook is current; skipping compilation")
            return
        }

        hookBuildC.writeText(
            """
            #define _GNU_SOURCE
            #define NEW_PREFIX "${prefixDir.absolutePath}"
            #include "${hookC.absolutePath}"
            """.trimIndent()
        )

        val clang = File(prefixDir, "bin/clang")
        if (clang.exists()) {
            Log.i(TAG, "Compiling socket_hook.c natively using clang...")
            val compileCmd = listOf(
                clang.absolutePath,
                "-shared", "-fPIC",
                hookBuildC.absolutePath,
                "-I", tmpDir.absolutePath,
                "-o", hookSo.absolutePath,
                "-ldl", "-llog"
            )
            try {
                val pb = ProcessBuilder(compileCmd)
                    .redirectErrorStream(true)
                    .also {
                        it.environment().clear()
                        it.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                        it.environment()["PATH"] = "${prefixDir.absolutePath}/bin:${System.getenv("PATH")}"
                        it.environment()["TMPDIR"] = tmpDir.absolutePath
                    }
                val process = pb.start()
                val log = process.inputStream.bufferedReader().readText()
                val exitCode = process.waitFor()
                if (exitCode == 0) {
                    buildMarker.writeText(buildSignature)
                    Log.i(TAG, "Native compilation of libsocket_hook.so successful!")
                } else {
                    Log.e(TAG, "Native compilation failed (code $exitCode): $log")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error compiling natively: ${e.message}")
            }
        } else {
            Log.w(TAG, "clang binary not found yet. Cannot compile socket_hook.c.")
        }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText() }.getOrNull()

    /**
     * Builds a tiny X11 helper that prints the WM_CLASS of the focused window.
     *
     * AT-SPI is not usable in this bootstrap: PyGObject is missing and at-spi's launcher
     * is hard-wired to Termux's dbus path, so no GTK app ever registers on the bus. The
     * IME bridge therefore falls back to reading X focus (same heuristic the desktop
     * input code has always used) and only pops the keyboard for editable app classes.
     */
    private fun ensureFocusProbe(): File? {
        val bin = File(binDir, "droiddesk-x11-focus")
        val src = File(tmpDir, "x11_focus.c")
        val marker = File(binDir, ".x11_focus_build")
        val source = """
            #include <X11/Xlib.h>
            #include <X11/Xutil.h>
            #include <stdio.h>
            #include <string.h>

            int main(void) {
                Display *d = XOpenDisplay(NULL);
                if (!d) { printf("-\n"); return 0; }
                Window w; int revert = 0;
                XGetInputFocus(d, &w, &revert);
                int depth = 0;
                while (w != None && w != PointerRoot && depth < 16) {
                    XClassHint hint;
                    memset(&hint, 0, sizeof(hint));
                    if (XGetClassHint(d, w, &hint)) {
                        printf("%s.%s\n",
                               hint.res_name ? hint.res_name : "",
                               hint.res_class ? hint.res_class : "");
                        if (hint.res_name) XFree(hint.res_name);
                        if (hint.res_class) XFree(hint.res_class);
                        XCloseDisplay(d);
                        return 0;
                    }
                    Window root = 0, parent = 0, *children = NULL;
                    unsigned int n = 0;
                    if (!XQueryTree(d, w, &root, &parent, &children, &n)) break;
                    if (children) XFree(children);
                    if (parent == 0 || parent == w) break;
                    w = parent;
                    depth++;
                }
                printf("-\n");
                XCloseDisplay(d);
                return 0;
            }
        """.trimIndent() + "\n"

        if (bin.isFile && marker.isFile &&
            marker.readTextOrNull().orEmpty() == source.hashCode().toString()
        ) {
            return bin
        }
        src.writeText(source)
        val compiler = File(binDir, "clang")
        if (!compiler.exists()) {
            DesktopLog.append(context, TAG, "focus probe skipped: clang missing")
            return null
        }
        return runCatching {
            val process = ProcessBuilder(
                compiler.absolutePath,
                src.absolutePath,
                "-O2",
                "-o", bin.absolutePath,
                "-I", File(prefixDir, "include").absolutePath,
                "-L", File(prefixDir, "lib").absolutePath,
                "-lX11",
            )
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                    pb.environment()["TMPDIR"] = tmpDir.absolutePath
                }
                .start()
            val log = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code == 0) {
                marker.writeText(source.hashCode().toString())
                DesktopLog.append(context, TAG, "focus probe compiled")
                bin
            } else {
                DesktopLog.append(context, TAG, "focus probe build failed ($code): $log")
                null
            }
        }.getOrElse {
            DesktopLog.append(context, TAG, "focus probe build error: ${it.message}")
            null
        }
    }

    /**
     * Builds the XTest helper the Android IME bridge uses to type into X11 apps.
     *
     * Termux:X11's sendTextEvent() never reaches VTE, so committed IME text is replayed
     * as real key events. ASCII keysyms keep their physical keycode (pressing Shift when
     * the symbol sits on the upper level); every other character (Chinese, non Latin-1
     * punctuation) is mapped onto a spare keycode as a Unicode keysym
     * (0x01000000 | codepoint) and then pressed with XTest.
     */
    private fun ensureTextInjector(): File? {
        val bin = File(binDir, "droiddesk-x11-type")
        val src = File(tmpDir, "x11_type.c")
        val marker = File(binDir, ".x11_type_build")
        val source = """
            #include <X11/Xlib.h>
            #include <X11/Xutil.h>
            #include <X11/keysym.h>
            #include <X11/extensions/XTest.h>
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            #include <unistd.h>

            static Display *dpy = NULL;
            static int shift_kc = 0;
            /* Spare keycodes are claimed for the duration of one string, remapped once
               with a single XSync + wait, then restored. Never overwrite keycode 255:
               a hard fallback would clobber a real key on keyboards that use it. */
            #define MAX_SPARES 96
            #define MAX_TEXT 1024
            static KeyCode spares[MAX_SPARES];
            static int spare_count = 0;
            static int spare_used = 0;

            static void tap(int kc, int shift) {
                if (shift && shift_kc) XTestFakeKeyEvent(dpy, shift_kc, True, CurrentTime);
                XTestFakeKeyEvent(dpy, kc, True, CurrentTime);
                XTestFakeKeyEvent(dpy, kc, False, CurrentTime);
                if (shift && shift_kc) XTestFakeKeyEvent(dpy, shift_kc, False, CurrentTime);
                XFlush(dpy);
                usleep(8000);
            }

            static void collect_spares(void) {
                int min_kc = 0, max_kc = 0, per = 0;
                XDisplayKeycodes(dpy, &min_kc, &max_kc);
                KeySym *map = XGetKeyboardMapping(dpy, min_kc, max_kc - min_kc + 1, &per);
                if (!map) return;
                spare_count = 0;
                for (int kc = min_kc; kc <= max_kc && spare_count < MAX_SPARES; kc++) {
                    int free_slot = 1;
                    for (int j = 0; j < per; j++) {
                        if (map[(kc - min_kc) * per + j] != NoSymbol) { free_slot = 0; break; }
                    }
                    if (free_slot) spares[spare_count++] = kc;
                }
                XFree(map);
            }

            /* Returns the keycode when the keysym is already reachable, else 0. */
            static int lookup_key(KeySym ks, int *shift) {
                KeyCode kc = XKeysymToKeycode(dpy, ks);
                int per = 0;
                if (!kc) return 0;
                KeySym *map = XGetKeyboardMapping(dpy, kc, 1, &per);
                if (!map) return 0;
                int result = 0;
                if (per > 0 && map[0] == ks) { *shift = 0; result = kc; }
                else if (per > 1 && map[1] == ks) { *shift = 1; result = kc; }
                XFree(map);
                return result;
            }

            static KeySym keysym_for(unsigned cp) {
                if (cp == '\n' || cp == '\r') return XK_Return;
                if (cp == '\t') return XK_Tab;
                if (cp == 8 || cp == 127) return XK_BackSpace;
                if (cp >= 0x20 && cp <= 0x7e) return (KeySym)cp;
                return (KeySym)(0x01000000u | cp);
            }

            static int utf8_to_codepoints(const char *text, unsigned *out, int max) {
                const unsigned char *p = (const unsigned char *)text;
                int n = 0;
                while (*p && n < max) {
                    unsigned cp = 0;
                    if (*p < 0x80) {
                        cp = *p++;
                    } else if ((*p & 0xE0) == 0xC0 && p[1]) {
                        cp = ((p[0] & 0x1F) << 6) | (p[1] & 0x3F);
                        p += 2;
                    } else if ((*p & 0xF0) == 0xE0 && p[1] && p[2]) {
                        cp = ((p[0] & 0x0F) << 12) | ((p[1] & 0x3F) << 6) | (p[2] & 0x3F);
                        p += 3;
                    } else if ((*p & 0xF8) == 0xF0 && p[1] && p[2] && p[3]) {
                        cp = ((p[0] & 0x07) << 18) | ((p[1] & 0x3F) << 12) |
                             ((p[2] & 0x3F) << 6) | (p[3] & 0x3F);
                        p += 4;
                    } else {
                        p++;
                        continue;
                    }
                    out[n++] = cp;
                }
                return n;
            }

            static int send_text(const char *text) {
                unsigned cps[MAX_TEXT];
                int n = utf8_to_codepoints(text, cps, MAX_TEXT);
                KeyCode plan[MAX_TEXT];
                int plan_shift[MAX_TEXT];
                spare_used = 0;
                /* Pass 1: decide which characters can ride an existing key and which
                   need a temporary mapping. All remaps are queued before the single
                   XSync so a long Chinese commit costs one wait, not one per glyph. */
                for (int i = 0; i < n; i++) {
                    KeySym ks = keysym_for(cps[i]);
                    int shift = 0;
                    int kc = lookup_key(ks, &shift);
                    if (kc) {
                        plan[i] = kc;
                        plan_shift[i] = shift;
                        continue;
                    }
                    if (spare_used >= spare_count) {
                        fprintf(stderr, "no spare keycode for U+%04X (%d spares)\n", cps[i], spare_count);
                        /* Restore the keys already claimed before giving up, otherwise
                           every failed commit leaks mappings for the whole session. */
                        if (spare_used > 0) {
                            KeySym none[2] = { NoSymbol, NoSymbol };
                            for (int j = 0; j < spare_used; j++) {
                                XChangeKeyboardMapping(dpy, spares[j], 2, none, 1);
                            }
                            XSync(dpy, False);
                        }
                        return 5;
                    }
                    KeyCode spare = spares[spare_used++];
                    KeySym syms[2] = { ks, ks };
                    XChangeKeyboardMapping(dpy, spare, 2, syms, 1);
                    plan[i] = spare;
                    plan_shift[i] = 0;
                }
                if (spare_used > 0) {
                    XSync(dpy, False);
                    /* Clients (GTK/VTE) only see a new keysym after processing
                       MappingNotify; wait one frame for the whole batch. */
                    usleep(150000);
                }
                for (int i = 0; i < n; i++) {
                    tap(plan[i], plan_shift[i]);
                }
                if (spare_used > 0) {
                    KeySym none[2] = { NoSymbol, NoSymbol };
                    for (int i = 0; i < spare_used; i++) {
                        XChangeKeyboardMapping(dpy, spares[i], 2, none, 1);
                    }
                    XSync(dpy, False);
                }
                return 0;
            }

            int main(int argc, char **argv) {
                if (argc < 2) {
                    fprintf(stderr, "usage: --type TEXT | --backspace N | --return N\n");
                    return 2;
                }
                dpy = XOpenDisplay(NULL);
                if (!dpy) { fprintf(stderr, "cannot open display\n"); return 3; }
                int ev = 0, er = 0, major = 0, minor = 0;
                if (!XTestQueryExtension(dpy, &ev, &er, &major, &minor)) {
                    fprintf(stderr, "XTEST unavailable\n");
                    XCloseDisplay(dpy);
                    return 4;
                }
                shift_kc = XKeysymToKeycode(dpy, XK_Shift_L);
                collect_spares();
                if (spare_count == 0) {
                    fprintf(stderr, "no free keycode available\n");
                    XCloseDisplay(dpy);
                    return 5;
                }

                const char *mode = argv[1];
                int result = 0;
                if (strcmp(mode, "--type") == 0 && argc > 2) {
                    result = send_text(argv[2]);
                    if (result != 0) { XCloseDisplay(dpy); return result; }
                } else if (strcmp(mode, "--backspace") == 0) {
                    int n = (argc > 2) ? atoi(argv[2]) : 1;
                    KeyCode kc = XKeysymToKeycode(dpy, XK_BackSpace);
                    for (int i = 0; i < n; i++) tap(kc, 0);
                } else if (strcmp(mode, "--return") == 0) {
                    int n = (argc > 2) ? atoi(argv[2]) : 1;
                    KeyCode kc = XKeysymToKeycode(dpy, XK_Return);
                    for (int i = 0; i < n; i++) tap(kc, 0);
                } else {
                    fprintf(stderr, "unknown mode\n");
                    XCloseDisplay(dpy);
                    return 2;
                }
                XSync(dpy, False);
                XCloseDisplay(dpy);
                return 0;
            }
        """.trimIndent() + "\n"

        if (bin.isFile && marker.isFile &&
            marker.readTextOrNull().orEmpty() == source.hashCode().toString()
        ) {
            return bin
        }
        src.writeText(source)
        val compiler = File(binDir, "clang")
        if (!compiler.exists()) {
            DesktopLog.append(context, TAG, "text injector skipped: clang missing")
            return null
        }
        return runCatching {
            val process = ProcessBuilder(
                compiler.absolutePath,
                src.absolutePath,
                "-O2",
                "-o", bin.absolutePath,
                "-I", File(prefixDir, "include").absolutePath,
                "-L", File(prefixDir, "lib").absolutePath,
                "-lX11",
                "-lXtst",
            )
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment()["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
                    pb.environment()["TMPDIR"] = tmpDir.absolutePath
                }
                .start()
            val log = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            if (code == 0) {
                marker.writeText(source.hashCode().toString())
                DesktopLog.append(context, TAG, "text injector compiled")
                bin
            } else {
                DesktopLog.append(context, TAG, "text injector build failed ($code): $log")
                null
            }
        }.getOrElse {
            DesktopLog.append(context, TAG, "text injector build error: ${it.message}")
            null
        }
    }

    /** Replays committed IME text as real X key events. False when injection is unavailable. */
    fun injectX11Text(text: String): Boolean {
        if (text.isEmpty()) return true
        return runTextInjector("--type", text)
    }

    fun injectX11Backspace(count: Int): Boolean {
        if (count <= 0) return true
        return runTextInjector("--backspace", count.toString())
    }

    fun injectX11Return(count: Int): Boolean {
        if (count <= 0) return true
        return runTextInjector("--return", count.toString())
    }

    private fun runTextInjector(mode: String, payload: String): Boolean {
        val bin = File(binDir, "droiddesk-x11-type")
        if (!bin.isFile) return false
        return runCatching {
            val builder = ProcessBuilder(bin.absolutePath, mode, payload)
                .redirectErrorStream(true)
            builder.environment().apply {
                clear()
                putAll(getTermuxEnv())
            }
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val code = process.waitFor()
            if (output.isNotBlank()) {
                DesktopLog.append(context, TAG, "X11TYPE $mode: ${output.trim()}")
            }
            code == 0
        }.getOrElse {
            DesktopLog.append(context, TAG, "X11TYPE $mode failed: ${it.message}")
            false
        }
    }

    /**
     * The guest has no working resolver (UDP/53 is blocked for the app uid), so package
     * managers are pointed at the device-wide HTTP proxy when one is actually listening.
     * Probing first keeps a dead proxy from shadowing a direct connection.
     */
    @Volatile private var storeProxyUsable = false
    @Volatile private var storeProxyCheckedAt = 0L

    private fun storeProxyAvailable(): Boolean {
        val now = System.currentTimeMillis()
        if (now - storeProxyCheckedAt in 0 until 60_000L) return storeProxyUsable
        storeProxyCheckedAt = now
        storeProxyUsable = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 2080), 400)
            }
            true
        }.getOrDefault(false)
        if (!storeProxyUsable) {
            DesktopLog.append(context, TAG, "store proxy 127.0.0.1:2080 unreachable; using direct")
        }
        return storeProxyUsable
    }

    private fun ensureStoreProxyConfig() {
        val aptConf = File(prefixDir, "etc/apt/apt.conf.d/99droiddesk-proxy")
        val bashrc = File(homeDir, ".bashrc")
        val marker = "# droiddesk-store-proxy"
        if (!storeProxyAvailable()) {
            runCatching {
                if (aptConf.exists() && aptConf.delete()) {
                    DesktopLog.append(context, TAG, "store proxy config removed (proxy down)")
                }
                // The apt conf alone is not enough: shells (and apt started from a
                // terminal or xfce) read http_proxy from .bashrc. A dead proxy left
                // there kept overriding a working direct connection, which is why
                // `apt-get update` failed on every mirror even after the proxy died.
                if (bashrc.exists()) {
                    val current = bashrc.readText()
                    if (current.contains(marker)) {
                        val cleaned = current.replace(
                            Regex(
                                Regex.escape(marker) + "[\\s\\S]*?export no_proxy=[^\\n]*\\n?",
                            ),
                            "",
                        )
                        bashrc.writeText(cleaned)
                        DesktopLog.append(context, TAG, "store proxy block removed from .bashrc")
                    }
                }
            }
            return
        }
        runCatching {
            aptConf.parentFile?.mkdirs()
            aptConf.writeText(
                "# DroidDesk: guest has no DNS, let the host proxy resolve and fetch.\n" +
                    "Acquire::http::Proxy \"http://127.0.0.1:2080\";\n" +
                    "Acquire::https::Proxy \"http://127.0.0.1:2080\";\n"
            )
            val current = bashrc.readTextOrNull().orEmpty()
            if (!current.contains(marker)) {
                val block = marker + "\n" +
                    "export http_proxy=http://127.0.0.1:2080\n" +
                    "export https_proxy=http://127.0.0.1:2080\n" +
                    "export HTTP_PROXY=http://127.0.0.1:2080\n" +
                    "export HTTPS_PROXY=http://127.0.0.1:2080\n" +
                    "export no_proxy=127.0.0.1,localhost,::1\n"
                bashrc.parentFile?.mkdirs()
                bashrc.writeText(current + (if (current.isEmpty() || current.endsWith("\n")) "" else "\n") + block)
            }
            DesktopLog.append(context, TAG, "store proxy config written")
        }.onFailure {
            DesktopLog.append(context, TAG, "store proxy config failed: ${it.message}")
        }
    }

    /**
     * Export the relocated prefix to every interactive shell, unconditionally.
     *
     * The bootstrap's apt/dpkg were compiled with Termux's literal prefix. When the
     * LD_PRELOAD hook is not active (some Android builds ignore it for new binaries)
     * apt tries to read /data/data/com.termux/files/usr/... and fails with EACCES.
     * APT_CONFIG + DPKG_ADMINDIR + PREFIX make apt use this app's writable prefix even
     * in shells that do not inherit the Kotlin-side environment. The block is
     * idempotent and bounded by markers so repeated launches do not grow .bashrc.
     */
    private fun ensurePackageManagerEnv() {
        val bashrc = File(homeDir, ".bashrc")
        val startMarker = "# droiddesk-package-env:start"
        val endMarker = "# droiddesk-package-env:end"
        runCatching {
            val current = bashrc.readTextOrNull().orEmpty()
            val cleaned = current.replace(
                Regex(
                    Regex.escape(startMarker) + "[\\s\\S]*?" + Regex.escape(endMarker) + "\\n?",
                ),
                "",
            )
            val block = buildString {
                appendLine(startMarker)
                appendLine("export PREFIX=${prefixDir.absolutePath}")
                appendLine("export TERMUX_PREFIX=${prefixDir.absolutePath}")
                appendLine("export TERMUX__PREFIX=${prefixDir.absolutePath}")
                appendLine("export APT_CONFIG=${prefixDir.absolutePath}/etc/apt/apt.conf.d/99-droiddesk-paths.conf")
                appendLine("export DPKG_ADMINDIR=${prefixDir.absolutePath}/var/lib/dpkg")
                appendLine("export TMPDIR=${tmpDir.absolutePath}")
                appendLine("export PATH=${prefixDir.absolutePath}/bin:\$PATH")
                // The socket/path hook must be active in every shell: apt and dpkg were
                // built against /data/data/com.termux/files/usr, which does not exist for
                // this app. Kotlin passes LD_PRELOAD only to processes it spawns, so apt
                // started from a terminal or from the xfce panel fell back to the literal
                // prefix and died with "Permission denied". Keep any existing value.
                appendLine("if [ -f \"\$PREFIX/lib/libsocket_hook.so\" ]; then")
                appendLine("  case \":\$LD_PRELOAD:\" in")
                appendLine("    *:libsocket_hook.so:*) ;;")
                appendLine("    *) export LD_PRELOAD=\"\$PREFIX/lib/libsocket_hook.so\${LD_PRELOAD:+:\$LD_PRELOAD}\" ;;")
                appendLine("  esac")
                appendLine("fi")
                append(endMarker)
                appendLine()
            }
            bashrc.parentFile?.mkdirs()
            bashrc.writeText(cleaned + (if (cleaned.isEmpty() || cleaned.endsWith("\n")) "" else "\n") + block)
        }.onFailure {
            DesktopLog.append(context, TAG, "package env block failed: ${it.message}")
        }
    }

    /**
     * Copy the desktop entries of common Linux apps into ~/Desktop so a freshly
     * installed app is reachable without digging through the application menu.
     * Only a short whitelist is mirrored: dumping every .desktop file would clutter
     * the phone-sized desktop with Xfce internals. A directory listing is cheap, so
     * this runs on every session start and after each store install.
     */
    private fun exportDesktopShortcuts() {
        val desktopDir = File(homeDir, "Desktop").apply { mkdirs() }
        val appDirs = listOf(
            File(prefixDir, "share/applications"),
            File(homeDir, ".local/share/applications"),
        )
        val wanted = listOf(
            "firefox", "chromium", "google-chrome", "code", "gimp", "vlc", "mpv",
            "libreoffice-writer", "libreoffice-calc", "libreoffice-impress",
        )
        runCatching {
            appDirs.asSequence()
                .flatMap { dir ->
                    dir.listFiles { file -> file.isFile && file.name.endsWith(".desktop") }
                        ?.asSequence() ?: emptySequence<File>()
                }
                .filter { file -> wanted.any { file.name.startsWith(it) } }
                .forEach { file ->
                    val target = File(desktopDir, file.name)
                    if (!target.exists()) {
                        file.copyTo(target)
                        target.setExecutable(true, false)
                    }
                }
        }.onFailure {
            DesktopLog.append(context, TAG, "desktop shortcut export failed: ${it.message}")
        }
    }

    // ── Environment Configuration ──

    private fun t(zh: String, en: String): String = L10n.tr(context, zh, en)

    /** Termux apt repositories served from domestic mirrors. */
    private val TERMUX_MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/apt-main",
        "https://mirrors.ustc.edu.cn/termux/apt/apt-main",
        "https://mirrors.aliyun.com/termux/apt/apt-main",
    )

    /**
     * Rewrite the Termux sources and refresh the index, returning a structured code
     * (Ok / NoNetwork / AptFailed / Unsupported).
     *
     * The native runtime (no root) previously returned a bare false, which the UI turned
     * into "no usable mirror" even when the network was perfectly fine. Now the mirror is
     * actually written and apt decides; only a real failure is reported.
     */
    fun applyTermuxMirror(onLog: (String) -> Unit = {}): String {
        val aptGet = File(prefixDir, "bin/apt-get")
        if (!aptGet.exists()) return "Unsupported"
        val sources = File(prefixDir, "etc/apt/sources.list")
        // Reachability decides "NoNetwork". Reporting it from the apt exit code was wrong:
        // 127 means apt-get is missing and -1 means the run timed out, and both were shown
        // to the user as "network unreachable" even with a perfectly working connection.
        val reachable = TERMUX_MIRRORS.filter { mirrorHostReachable(it) }
        if (reachable.isEmpty()) {
            onLog(
                t(
                    "无法连接到任何 Termux 镜像（DNS 或网络被阻断），已保留原软件源。\n",
                    "Could not reach any Termux mirror (DNS or network blocked); the original sources were kept.\n",
                ),
            )
            return "NoNetwork"
        }
        runCatching {
            sources.parentFile?.mkdirs()
            val backup = File(prefixDir, "etc/apt/sources.list.droiddesk-before-mirror")
            if (sources.exists() && !backup.exists()) sources.copyTo(backup, overwrite = false)
        }
        var lastExit = -1
        var started = false
        for (base in reachable) {
            runCatching {
                sources.writeText("# DroidDesk termux mirror\n# droiddesk-store-mirror\ndeb $base stable main\n")
            }.onFailure {
                DesktopLog.append(context, TAG, "termux mirror write failed: ${it.message}")
                return "AptFailed"
            }
            started = true
            onLog(t("已写入 Termux 镜像 $base，正在刷新索引…\n", "Termux mirror $base written; refreshing index...\n"))
            lastExit = runTermuxCommand("DEBIAN_FRONTEND=noninteractive apt-get update -o Dpkg::Use-Pty=0")
            if (lastExit == 0) {
                onLog(t("软件源已切换到 $base\n", "APT mirror switched to $base\n"))
                return "Ok"
            }
        }
        if (!started) return "AptFailed"
        return when (lastExit) {
            127 -> "Unsupported"
            -1 -> "AptFailed"
            0 -> "Ok"
            else -> "AptFailed"
        }
    }

    /**
     * Cheap HTTP reachability probe (3s timeout) used to tell "no network" apart from
     * "apt failed for some other reason" before any source file is touched.
     */
    private fun mirrorHostReachable(baseUrl: String): Boolean = try {
        val url = java.net.URL(baseUrl)
        val connection = url.openConnection() as java.net.HttpURLConnection
        try {
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("Range", "bytes=0-0")
            connection.setRequestProperty("User-Agent", "DroidDesk")
            // Any HTTP answer (200/206/301/403…) proves DNS + routing work; only an
            // IOException means the host is actually unreachable.
            connection.responseCode in 100..599
        } finally {
            connection.disconnect()
        }
    } catch (error: Throwable) {
        DesktopLog.append(context, TAG, "mirror probe $baseUrl failed: ${error.message}")
        false
    }

    /**
     * Runs [command] through the prefix shell. [onLog] receives each non-blank line so
     * callers that must surface the reason to the UI (deb install, mirror switching) can
     * forward dpkg/apt diagnostics instead of swallowing them. Returns the exit code,
     * or -1 when the command could not be started or timed out.
     */
    private fun runTermuxCommand(command: String, onLog: ((String) -> Unit)? = null): Int {
        return try {
            val process = ProcessBuilder("${prefixDir.absolutePath}/bin/sh", "-c", command)
                .directory(homeDir)
                .apply { environment().putAll(getTermuxEnv()) }
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    if (line.isNotBlank()) {
                        Log.i(TAG, "apt: $line")
                        onLog?.invoke(line)
                    }
                }
            }
            if (!process.waitFor(90, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly()
                -1
            } else {
                process.exitValue()
            }
        } catch (e: Exception) {
            Log.w(TAG, "runTermuxCommand failed: ${e.message}")
            -1
        }
    }

    /**
     * Write $PREFIX/etc/resolv.conf and pin mirror hostnames into $PREFIX/etc/hosts.
     *
     * The native runtime never wrote a resolver file (only the chroot did), so apt inside
     * the guest inherited whatever the bootstrap shipped and every DNS lookup failed.
     * Resolution itself happens on the Android side, where the app's own DNS works, and
     * the pinned IPs keep apt usable even if in-guest DNS stays broken.
     */
    private fun ensureGuestDns() {
        runCatching {
            val etc = File(prefixDir, "etc").apply { mkdirs() }
            val resolv = File(etc, "resolv.conf")
            resolv.delete()
            val servers = DnsConfig.FALLBACK_DNS.take(3)
            resolv.writeText(
                buildString {
                    servers.forEach { append("nameserver $it\n") }
                    append("options timeout:2 attempts:3\n")
                },
            )
            val pins = mutableListOf<String>()
            (MirrorSelector.MIRROR_HOSTS + TERMUX_MIRRORS.mapNotNull {
                runCatching { java.net.URI(it).host }.getOrNull()
            }).distinct().forEach { host ->
                runCatching {
                    java.net.InetAddress.getAllByName(host)
                        .mapNotNull { it.hostAddress }
                        .filter { address -> address.contains('.') }
                        .forEach { address -> pins.add("$address $host") }
                }
            }
            val content = buildString {
                appendLine("127.0.0.1   localhost")
                appendLine("127.0.0.1   droiddesk")
                appendLine("::1         localhost ip6-localhost ip6-loopback")
                if (pins.isNotEmpty()) {
                    appendLine("# droiddesk-pin (mirror IPs resolved on Android)")
                    pins.forEach { appendLine(it) }
                }
            }
            File(etc, "hosts").writeText(content)
            DesktopLog.append(
                context,
                TAG,
                "guest resolv.conf written (${servers.joinToString(",")}), pinned ${pins.size} mirror IPs",
            )
        }.onFailure {
            DesktopLog.append(context, TAG, "guest DNS setup failed: ${it.message}")
        }
    }


    private fun getTermuxEnv(): Map<String, String> {
        val env = mutableMapOf<String, String>()

        env["ANDROID_DATA"] = System.getenv("ANDROID_DATA") ?: "/data"
        env["ANDROID_ROOT"] = System.getenv("ANDROID_ROOT") ?: "/system"
        env["EXTERNAL_STORAGE"] = System.getenv("EXTERNAL_STORAGE") ?: "/sdcard"

        env["PREFIX"] = prefixDir.absolutePath
        env["TMPDIR"] = tmpDir.absolutePath
        // proot-distro 5.x derives all container paths from these variables.
        // Without them it falls back to Termux's original com.termux sandbox.
        env["TERMUX_APP__PACKAGE_NAME"] = context.packageName
        env["TERMUX__PREFIX"] = prefixDir.absolutePath
        env["TERMUX__HOME"] = homeDir.absolutePath
        // Legacy spellings still used by bootstrap scripts and pkg/apt wrappers.
        env["TERMUX_PREFIX"] = prefixDir.absolutePath
        env["TERMUX_HOME"] = homeDir.absolutePath
        env["TERMUX_VERSION"] = "DroidDesk"
        env["LD_LIBRARY_PATH"] = "${prefixDir.absolutePath}/lib"
        env["PATH"] = listOf(
            "${prefixDir.absolutePath}/bin",
            "${prefixDir.absolutePath}/lib/xfce4/panel",
            System.getenv("PATH") ?: "/system/bin",
        ).joinToString(":")
        env["HOME"] = homeDir.absolutePath
        // VTE-based terminals use SHELL to spawn their child process. Android's
        // account database points at /system/bin/sh, which is not the relocated
        // Termux userspace expected by XFCE Terminal.
        env["SHELL"] = File(binDir, "bash").absolutePath
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        // The product ships a Chinese desktop. Older sessions exported en_US here, which is
        // why an already-installed image stayed English even after the app was localized.
        env["LANG"] = "zh_CN.UTF-8"
        env["LC_ALL"] = "zh_CN.UTF-8"
        env["LANGUAGE"] = "zh_CN:zh"

        // Desktop input: GTK/Qt must route keys through IBus so the Android IME bridge
        // can commit composed Chinese text instead of raw scancodes.
        val bridgeDir = File(baseDir, "guest-bridge").apply { mkdirs() }
        env["DROIDDESK_BRIDGE"] = bridgeDir.absolutePath
        env["GTK_IM_MODULE"] = "ibus"
        env["QT_IM_MODULE"] = "ibus"
        env["XMODIFIERS"] = "@im=ibus"

        env["DISPLAY"] = ":0"
        env["XDG_RUNTIME_DIR"] = tmpDir.absolutePath

        // Package managers run inside the guest, which has no working resolver. When the
        // device-wide HTTP proxy is up it also resolves host names, so export it here for
        // apt-get/pkg/curl/wget launched from this runtime.
        if (storeProxyAvailable()) {
            val proxy = "http://127.0.0.1:2080"
            env["http_proxy"] = proxy
            env["https_proxy"] = proxy
            env["HTTP_PROXY"] = proxy
            env["HTTPS_PROXY"] = proxy
            env["all_proxy"] = proxy
            env["no_proxy"] = "127.0.0.1,localhost,::1"
            env["NO_PROXY"] = "127.0.0.1,localhost,::1"
        }

        env["PERL5LIB"] = "${prefixDir.absolutePath}/lib/perl5/core_perl:${prefixDir.absolutePath}/lib/perl5/site_perl:${prefixDir.absolutePath}/lib/perl5/vendor_perl:${prefixDir.absolutePath}/lib/perl5"
        env["PYTHONHOME"] = prefixDir.absolutePath
        env["PIP_CONFIG_FILE"] = "${prefixDir.absolutePath}/etc/pip.conf"
        env["XDG_DATA_DIRS"] = "${prefixDir.absolutePath}/share"
        // Xfce validates that its compile-time SYSCONFDIR is present literally,
        // even though actual file access is relocated to this app's prefix.
        env["XDG_CONFIG_DIRS"] = listOf(
            "${prefixDir.absolutePath}/etc/xdg",
            "${prefixDir.absolutePath}/etc",
            "/data/data/com.termux/files/usr/etc",
        ).joinToString(":")
        env["GDK_PIXBUF_MODULEDIR"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders"
        env["GDK_PIXBUF_MODULE_FILE"] = "${prefixDir.absolutePath}/lib/gdk-pixbuf-2.0/2.10.0/loaders.cache"

        // The official Mesa package contains both Zink and llvmpipe. Qualcomm
        // devices pair Zink with the Freedreno/Turnip Vulkan ICD; every other
        // GPU uses llvmpipe and must not inherit a vendor Vulkan selection.
        env["LIBGL_DRIVERS_PATH"] = "${prefixDir.absolutePath}/lib/dri"
        val freedrenoIcd = File(prefixDir, "share/vulkan/icd.d/freedreno_icd.aarch64.json")
        if (hasAdrenoGpu() && freedrenoIcd.exists()) {
            env["VK_ICD_FILENAMES"] = freedrenoIcd.absolutePath
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "zink"
            env["GALLIUM_DRIVER"] = "zink"
        } else {
            env["LIBGL_ALWAYS_SOFTWARE"] = "true"
            // Mesa exposes llvmpipe through swrast_dri.so. "llvmpipe" is the
            // Gallium driver name, not a DRI loader module name.
            env["MESA_LOADER_DRIVER_OVERRIDE"] = "swrast"
            env["GALLIUM_DRIVER"] = "llvmpipe"
        }

        env["DBUS_SESSION_BUS_ADDRESS"] = "unix:path=${tmpDir.absolutePath}/dbus-session"

        env["DPKG_ADMINDIR"] = "${prefixDir.absolutePath}/var/lib/dpkg"
        env["APT_CONFIG"] = "${prefixDir.absolutePath}/etc/apt/apt.conf.d/99-droiddesk-paths.conf"

        val hookSo = File(prefixDir, "lib/libsocket_hook.so")
        if (hookSo.exists()) {
            env["LD_PRELOAD"] = hookSo.absolutePath
        }

        return env
    }

    /**
     * Keep the graphics stack aligned with the device GPU.
     *
     * The old TUR mesa-zink package conflicts with and removes the current Mesa
     * package. That left non-Adreno devices configured for llvmpipe without the
     * llvmpipe driver. Installing official Mesa on every device provides both
     * Zink and llvmpipe; only Adreno devices additionally need Turnip/Freedreno.
     */
    private fun ensureGraphicsPackages(
        onProgress: ((Double, String) -> Unit)? = null,
        progress: Double = 0.72,
    ): Boolean {
        val startedAt = System.currentTimeMillis()
        fun mark(step: String) = DesktopLog.event(context, "GRAPHICS", step, startedAt)
        mark("check begin")
        // File presence is a millisecond check; dpkg-query spawns a shell (and can be
        // routed into a leftover interactive process), which used to trigger a full
        // "pkg install" on every launch and added ~40 s to boot.
        val mesaInstalled = mesaPresent() || isDpkgPackageInstalled("mesa")
        mark("mesa installed=$mesaInstalled")
        if (!mesaInstalled) {
            onProgress?.invoke(progress, "Installing Mesa graphics runtime...")
            // libc++ too: apt keeps an older installed copy, which Mesa can't load against.
            if (!installPackageGroup("pkg install -y mesa libc++")) {
                Log.e(TAG, "Mesa graphics runtime installation failed")
                return false
            }
            mark("mesa installed")
        }
        val needsLibcxx = mesaNeedsNewerLibcxx()
        mark("libcxx probe needs-upgrade=$needsLibcxx")
        if (needsLibcxx) {
            onProgress?.invoke(progress, "Updating the C++ runtime for graphics...")
            Log.w(TAG, "Mesa needs a newer libc++ than installed; upgrading it")
            if (!installPackageGroup("pkg install -y libc++") || mesaNeedsNewerLibcxx()) {
                Log.e(TAG, "libc++ is still too old for Mesa; OpenGL will be unavailable")
            }
            mark("libcxx done")
        }

        if (hasAdrenoGpu()) {
            val freedrenoIcd = File(
                prefixDir,
                "share/vulkan/icd.d/freedreno_icd.aarch64.json",
            )
            // The ICD file is proof enough that the package is installed. dpkg-query
            // through this shell can block for ~40 s (the largest single chunk of the
            // 62 s session start), so never run it when the file is already there.
            val adrenoReady = freedrenoIcd.isFile ||
                isDpkgPackageInstalled("mesa-vulkan-icd-freedreno")
            if (!adrenoReady) {
                onProgress?.invoke(progress, "Installing Adreno Turnip acceleration...")
                if (!installPackageGroup("pkg install -y mesa-vulkan-icd-freedreno")) {
                    Log.e(TAG, "Adreno Turnip/Freedreno installation failed")
                    return false
                }
            }
            Log.i(TAG, "Graphics mode: Mesa Zink with Turnip/Freedreno")
        } else {
            Log.i(TAG, "Graphics mode: Mesa llvmpipe software rendering")
        }
        return true
    }

    /** Fast, shell-free proof that the Mesa runtime files are already on disk. */
    private fun mesaPresent(): Boolean {
        val lib = File(prefixDir, "lib")
        val gallium = lib.listFiles { f -> f.name.startsWith("libgallium-") && f.name.endsWith(".so") }
        return gallium?.isNotEmpty() == true || File(lib, "libGL.so.1").isFile
    }

    /**
     * Mesa from the current repo links against libc++ symbols that older installed
     * libc++_shared.so builds don't export (seen: std::__ndk1::__hash_memory). Then
     * libGLX_mesa fails to load and every OpenGL client gets no context at all.
     */
    private fun mesaNeedsNewerLibcxx(): Boolean {
        val lib = File(prefixDir, "lib")
        val libcxx = File(lib, "libc++_shared.so")
        if (!libcxx.isFile) return false
        val mesaLibs = (lib.listFiles { f -> f.name.startsWith("libgallium-") && f.name.endsWith(".so") }
            ?.toList().orEmpty()) + listOf(File(lib, "libvulkan_freedreno.so")).filter { it.isFile }
        if (mesaLibs.isEmpty()) return false
        val symbol = "_ZNSt6__ndk113__hash_memoryEPKvm"
        // Scanning tens of megabytes of Mesa binaries on every launch is pure boot
        // latency. Cache a positive ("libc++ is new enough") result keyed to the files.
        val signature = mesaLibs.joinToString(":") { "${it.name}=${it.lastModified()}" } +
            "|libcxx=${libcxx.lastModified()}"
        val marker = File(baseDir, ".mesa_libcxx_ok")
        if (marker.isFile && marker.readText().trim() == signature) return false
        val quoted = mesaLibs.joinToString(" ") { "'${it.absolutePath}'" }
        val needed = runQuickCommand("grep -lqa '$symbol' $quoted && echo yes").contains("yes")
        if (!needed) {
            runCatching { marker.writeText(signature) }
            return false
        }
        val libcxxOk = runQuickCommand("grep -qa '$symbol' '${libcxx.absolutePath}' && echo yes").contains("yes")
        if (!libcxxOk) return true
        runCatching { marker.writeText(signature) }
        return false
    }

    // ── Native Package Installation ──

    private fun installRepoPackages(): Boolean {
        val pkgs = listOf("x11-repo", "tur-repo")

        // Ensure main package list is up to date before downloading the repo packages.
        if (executeCommand("apt-get update").startsWith("Error:")) {
            Log.e(TAG, "apt-get update failed before installing repo packages")
            return false
        }

        // Download the .debs to the prefix root.
        val downloadCmd = "cd \"${prefixDir.absolutePath}\" && apt-get download ${pkgs.joinToString(" ")}"
        if (executeCommand(downloadCmd).startsWith("Error:")) {
            Log.e(TAG, "Failed to download x11-repo/tur-repo .debs")
            return false
        }

        // Unpack without configuring so we can edit the maintainer scripts first.
        val debs = pkgs.joinToString(" ") { "${it}_*.deb" }
        if (executeCommand("dpkg --unpack $debs").startsWith("Error:")) {
            Log.e(TAG, "Failed to unpack x11-repo/tur-repo .debs")
            return false
        }

        // Replace the postinst scripts with no-ops. The originals just run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter.
        for (pkg in pkgs) {
            val postinst = File(prefixDir, "var/lib/dpkg/info/$pkg.postinst")
            postinst.writeText("#!/system/bin/sh\nexit 0\n")
            postinst.setExecutable(true, false)
        }

        // Now configure the repo packages and refresh apt's package lists.
        if (executeCommand("dpkg --configure ${pkgs.joinToString(" ")}").startsWith("Error:")) {
            Log.e(TAG, "Failed to configure x11-repo/tur-repo")
            return false
        }
        if (executeCommand("pkg update -y").startsWith("Error:")) {
            Log.e(TAG, "Failed to update package lists after installing repo packages")
            return false
        }

        return true
    }

    private fun installPackageGroup(cmd: String): Boolean {
        // pkg install downloads, unpacks and configures in one go. Newly unpacked
        // maintainer scripts still contain the original Termux shebang, so after
        // the command finishes (successfully or not) we patch them and run a
        // configuration pass to finish any half-configured packages. The first
        // command is therefore allowed to fail specifically when that recovery
        // pass succeeds and all requested packages are present.
        Log.i(TAG, "Running: $cmd")
        val boundedCommand = if (cmd.startsWith("dpkg --configure")) {
            "timeout --signal=TERM --kill-after=5s 90s $cmd"
        } else {
            cmd
        }
        val installOutput = executeCommand(boundedCommand)
        patchShebangs(force = true)
        if (packageOperationCancelled) return false
        val configureOutput = if (cmd.startsWith("dpkg --configure")) {
            installOutput
        } else {
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
        }
        if (configureOutput.startsWith("Error:")) {
            return false
        }
        if (!installOutput.startsWith("Error:")) {
            return true
        }

        // A configure command is itself the recovery operation. If the patched
        // second pass completed, dpkg has no remaining unconfigured packages.
        if (cmd.startsWith("dpkg --configure")) {
            Log.i(TAG, "dpkg configuration recovered after shebang patching")
            return true
        }

        val installPrefix = "pkg install -y "
        if (!cmd.startsWith(installPrefix)) {
            return false
        }

        val requestedPackages = cmd.removePrefix(installPrefix).trim()
        if (requestedPackages.isEmpty()) {
            return false
        }

        // Do not mistake an unrelated successful `dpkg --configure -a` for a
        // recovered install: every package requested by this group must now be
        // registered in dpkg's database.
        val queryOutput = executeCommand("dpkg-query -W $requestedPackages")
        val recovered = !queryOutput.startsWith("Error:")
        if (recovered) {
            Log.i(TAG, "Package group recovered after shebang patching: $requestedPackages")
        }
        return recovered
    }

    /**
     * Installs optional packages without `pkg`'s implicit repository refresh.
     *
     * Desktop setup already creates the package indexes. Refreshing every repo for
     * every optional app made an unrelated, temporarily syncing TUR mirror block
     * packages from the healthy main/X11 repositories. apt-get uses the last
     * verified indexes and only refreshes as a recovery step if installation fails.
     */
    private fun installOptionalPackages(
        packages: List<String>,
        onProgress: ((Double, String) -> Unit)? = null,
        retryProgress: Double = 0.5,
    ): Boolean {
        if (packages.isEmpty()) return true
        val packageNames = packages.joinToString(" ")

        fun installAndRecover(): Boolean {
            val installOutput = executeCommand(
                "DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold install -y $packageNames",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            // A relocated package may already be unpacked while dependencies are
            // still missing (notably Node.js -> c-ares). Let apt complete that
            // dependency transaction after its maintainer scripts are patched.
            executeCommand(
                "timeout --signal=TERM --kill-after=5s 120s " +
                    "env DEBIAN_FRONTEND=noninteractive apt-get " +
                    "-o Dpkg::Options::=--force-confdef " +
                    "-o Dpkg::Options::=--force-confold --fix-broken install -y",
            )
            if (packageOperationCancelled) return false
            patchShebangs(force = true)
            val configureOutput = executeCommand(
                "timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a",
            )
            val installed = packages.all(::isDpkgPackageInstalled)
            val ok = installed && !configureOutput.startsWith("Error:") &&
                (!installOutput.startsWith("Error:") || installed)
            if (!ok && !packageOperationCancelled) {
                lastPackageError = explainPackageFailure(
                    packages,
                    listOf(installOutput, configureOutput),
                )
            }
            return ok
        }

        if (installAndRecover()) return true
        if (packageOperationCancelled) return false

        onProgress?.invoke(retryProgress, t("正在刷新软件源索引并重试…", "Refreshing package indexes and retrying..."))
        // apt may update main/X11 successfully while a third-party repository is
        // temporarily inconsistent. Retrying is still useful with those newly
        // refreshed lists and apt's last verified TUR index.
        val updateOutput = executeCommand("timeout --signal=TERM --kill-after=5s 120s apt-get update")
        if (packageOperationCancelled) return false
        if (installAndRecover()) return true
        // "Unable to locate package" after a failed update means the index refresh is the
        // root cause (DNS/proxy/seccomp), not the package itself — say so instead of
        // repeating a generic install failure.
        if (updateOutput.startsWith("Error:")) {
            lastPackageError = t(
                "软件源索引刷新失败，无法定位软件包：${aptFailureReason(updateOutput)}",
                "Package index refresh failed, so the package cannot be located: ${aptFailureReason(updateOutput)}",
            )
        }
        return false
    }

    /**
     * Turns raw apt/dpkg output into one short Chinese reason the store can display.
     * The English originals are kept inside the translation keys so logs stay greppable.
     */
    private fun explainPackageFailure(packages: List<String>, outputs: List<String>): String {
        val joined = outputs.joinToString("\n")
        val reason = aptFailureReason(joined)
        val names = packages.joinToString("、")
        return t("安装 $names 失败：$reason", "Installing $names failed: $reason")
    }

    private fun aptFailureReason(output: String): String = when {
        output.contains("Unable to locate package") || output.contains("没有找到与匹配") ->
            t("当前软件源中没有这个软件包（仓库里不存在该名称）", "the package does not exist in the current repositories")
        output.contains("Could not resolve") || output.contains("Temporary failure resolving") ||
            output.contains("Failed to fetch") || output.contains("Connection failed") ->
            t("无法连接软件源服务器（DNS 或网络异常）", "cannot reach the mirror servers (DNS or network problem)")
        output.contains("404") -> t("软件源返回 404，索引可能过期，请重试一键换源", "the mirror returned 404; the index is probably stale — re-run mirror switching")
        output.contains("GPG error") || output.contains("NO_PUBKEY") || output.contains("not signed") ->
            t("软件源签名校验失败", "repository signature check failed")
        output.contains("dpkg: dependency problems") || output.contains("依赖关系的问题") ->
            t("依赖关系未满足，可尝试安装 .deb 后由 apt 自动补齐依赖", "unmet dependencies; let apt fix them after the .deb install")
        output.contains("held broken") || output.contains("kept back") ->
            t("有被锁定的软件包阻止了升级", "a held/broken package is blocking the upgrade")
        output.contains("space") && output.contains("free") ->
            t("设备存储空间不足", "not enough free storage on the device")
        output.contains("Exit code") || output.contains("exit code") || output.contains("Error: Package operation cancelled") ->
            output.lineSequence().lastOrNull { it.isNotBlank() }?.take(160)
                ?: t("命令执行失败", "the command failed")
        else -> output.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotBlank() && !it.startsWith("Reading") && !it.startsWith("Building") }
            ?.take(160) ?: t("未知错误，请查看安装日志", "unknown error; see the install log")
    }

    private fun isDpkgPackageInstalled(packageName: String): Boolean {
        val result = executeCommand(
            "dpkg-query -W -f='${'$'}{Status}' $packageName",
        )
        if (result.isNotBlank() && !result.startsWith("Error:")) {
            return result.trim() == "install ok installed"
        }
        // A blank answer means the query was routed into a leftover interactive shell.
        // Falling back to dpkg's file list keeps a restart from reinstalling Mesa again.
        return File(prefixDir, "var/lib/dpkg/info/$packageName.list").isFile ||
            File(prefixDir, "var/lib/dpkg/info/$packageName:arm64.list").isFile
    }

    /**
     * Node.js currently ships a preinst script with Termux's absolute shebang.
     * Preinst runs before dpkg exposes the script in its admin directory, so it
     * must be relocated inside the deb before installation.
     */
    private fun installRelocatedNodejs(): Boolean {
        // Install Node's native dependency before unpacking the relocated deb.
        // Running apt --fix-broken with an unpacked local Node package can make
        // apt replace it with the repository deb, whose absolute preinst shebang
        // is exactly what this relocation path must avoid.
        if (!isDpkgPackageInstalled("c-ares") &&
            !installOptionalPackages(listOf("c-ares"))) return false

        val workDir = File(tmpDir, "nodejs-relocated-deb")
        workDir.deleteRecursively()
        workDir.mkdirs()

        if (executeCommand("cd \"${workDir.absolutePath}\" && apt-get download nodejs")
                .startsWith("Error:")) return false
        val sourceDeb = workDir.listFiles()
            ?.firstOrNull { it.name.startsWith("nodejs_") && it.extension == "deb" }
            ?: return false
        val unpacked = File(workDir, "unpacked")
        if (executeCommand(
                "dpkg-deb -R \"${sourceDeb.absolutePath}\" \"${unpacked.absolutePath}\""
            ).startsWith("Error:")) return false

        val oldPrefix = "/data/data/com.termux/files/usr"
        val controlDir = File(unpacked, "DEBIAN")
        controlDir.listFiles()?.filter { it.isFile }?.forEach { script ->
            val content = script.readText()
            if (content.contains(oldPrefix)) {
                script.writeText(content.replace(oldPrefix, prefixDir.absolutePath))
                script.setExecutable(true, false)
            }
        }

        val rebuiltDeb = File(workDir, "nodejs-relocated.deb")
        if (executeCommand(
                "dpkg-deb -b \"${unpacked.absolutePath}\" \"${rebuiltDeb.absolutePath}\""
            ).startsWith("Error:")) return false
        if (executeCommand("dpkg --unpack \"${rebuiltDeb.absolutePath}\"")
                .startsWith("Error:")) return false

        patchShebangs(force = true)
        if (executeCommand("dpkg --configure nodejs").startsWith("Error:")) return false
        return isDpkgPackageInstalled("nodejs")
    }

    /** Installs only PRoot, proot-distro and Debian's base rootfs. */
    private fun installMinimalDebian(
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        onProgress?.invoke(0.12, "Installing lightweight PRoot runtime...")
        if (!installOptionalPackages(listOf("proot", "proot-distro"), onProgress, 0.28)) {
            return false
        }

        patchShebangs(force = true)
        relocateProotExecutable()
        if (executeCommand("proot --version").startsWith("Error:")) {
            Log.e(TAG, "Installed PRoot executable could not start")
            return false
        }

        if (debianRootfsMarkers().none(File::exists)) {
            // Remove an interrupted extraction so proot-distro can safely retry.
            File(prefixDir, "var/lib/proot-distro/containers/debian").deleteRecursively()
            File(prefixDir, "var/lib/proot-distro/installed-rootfs/debian").deleteRecursively()
            onProgress?.invoke(0.38, "Downloading minimal Debian base system...")
            if (executeCommand("proot-distro install debian").startsWith("Error:")) {
                Log.e(TAG, "Minimal Debian rootfs installation failed")
                return false
            }
        }

        onProgress?.invoke(0.9, "Creating Debian shell shortcut...")
        writeDebianLauncher()

        // The downloaded archive is not needed after extraction.
        clearProotDownloadCache()
        onProgress?.invoke(1.0, "Minimal Debian compatibility is ready")
        return isMinimalDebianInstalled()
    }

    fun installDesktopEnvironmentNative(
        desktopEnv: String = "xfce4",
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        val marker = File(prefixDir, DE_MARKER)

        if (getInstalledDE() == selectedDesktop) {
            onProgress?.invoke(1.0, "$selectedDesktop is already installed")
            Log.i(TAG, "$selectedDesktop desktop environment already installed")
            return true
        }

        if (!isBootstrapped()) {
            Log.e(TAG, "Cannot install DE — bootstrap not extracted")
            return false
        }

        patchShebangs()
        patchElfRunpaths(prefixDir)
        compileSocketHook()
        onProgress?.invoke(0.12, "Configuring X11 and TUR repositories...")

        // Install the x11/tur repository packages. Their postinst scripts run
        // `apt update`, which triggers SIGSYS under the app's seccomp filter, so we
        // unpack the .debs, neutralise the postinst scripts, configure them, and
        // then update the package lists ourselves.
        if (!installRepoPackages()) {
            Log.e(TAG, "Failed to install x11-repo/tur-repo")
            return false
        }
        onProgress?.invoke(0.24, "Updating native package database...")

        // Finish configuring anything left over from a previous run, then install
        // the desktop, GPU drivers, and build tools. Each install is followed by a
        // shebang patch + configure pass so postinst scripts find our prefix.
        if (!installPackageGroup("dpkg --configure -a")) {
            Log.e(TAG, "Initial dpkg --configure -a failed")
            return false
        }
        if (!installPackageGroup("pkg update -y")) {
            Log.e(TAG, "pkg update failed")
            return false
        }
        onProgress?.invoke(0.34, "Installing X11 and audio packages...")
        // DroidDesk embeds the X server, so termux-x11-nightly is deliberately
        // not installed. All desktops connect to the service's DISPLAY=:0.
        if (!installPackageGroup("pkg install -y xorg-xrandr pulseaudio xclip")) {
            Log.e(TAG, "Native X11 runtime package install failed")
            return false
        }
        onProgress?.invoke(0.46, "Installing $selectedDesktop desktop packages...")

        val desktopPackages =
            "xfce4 xfce4-terminal xfce4-whiskermenu-plugin xfce4-notifyd thunar mousepad devilspie2"
        if (!installPackageGroup("pkg install -y $desktopPackages")) {
            Log.e(TAG, "$selectedDesktop package install failed")
            return false
        }
        onProgress?.invoke(0.70, "Configuring graphics for this device...")
        if (!ensureGraphicsPackages(onProgress, 0.74)) return false

        val nativeTools = "git wget curl openssh htop python clang"
        onProgress?.invoke(
            0.84,
            "Installing Desktop Essentials tools...",
        )
        if (!installPackageGroup("pkg install -y $nativeTools")) {
            Log.e(TAG, "Native Termux utility package install failed")
            return false
        }
        onProgress?.invoke(0.94, "Finalizing native Linux environment...")

        // Rebuild the hook with the installed clang, then persist the selected DE.
        compileSocketHook()
        patchEmbeddedXfcePaths()
        marker.writeText(selectedDesktop)
        onProgress?.invoke(1.0, "Native Linux setup complete")
        Log.i(TAG, "Native Termux $selectedDesktop installation complete")
        return true
    }

    fun installOptionalApp(
        appId: String,
        onProgress: ((Double, String) -> Unit)? = null,
    ): Boolean {
        if (getInstalledDE().isEmpty()) return false
        if (getOptionalAppsStatus()[appId] == true) {
            onProgress?.invoke(1.0, "Already installed")
            return true
        }

        // Code OSS can pull npm, and npm can be left unpacked while Node's
        // original Termux preinst path is invalid in our relocated prefix.
        // Repair/install Node before the generic dpkg configure pass.
        if (appId == "nodejs" || appId == "code_oss") {
            onProgress?.invoke(0.08, "Preparing relocated Node.js dependency...")
            if (!isDpkgPackageInstalled("nodejs") && !installRelocatedNodejs()) return false
        }

        onProgress?.invoke(0.18, "Repairing interrupted packages...")
        if (!installPackageGroup("dpkg --configure -a")) return false

        val ok = when (appId) {
            "firefox" -> {
                onProgress?.invoke(0.25, "Installing Firefox...")
                installOptionalPackages(listOf("firefox"), onProgress, 0.55)
            }
            "code_oss" -> {
                onProgress?.invoke(0.45, "Installing npm dependency...")
                installOptionalPackages(listOf("npm"), onProgress, 0.55) && run {
                    onProgress?.invoke(0.65, "Installing Code OSS...")
                    installOptionalPackages(listOf("code-oss"), onProgress, 0.78)
                }
            }
            "nodejs" -> {
                onProgress?.invoke(0.65, "Installing npm...")
                installOptionalPackages(listOf("npm"), onProgress, 0.78)
            }
            "imagemagick" -> {
                onProgress?.invoke(0.25, "Installing ImageMagick...")
                installOptionalPackages(listOf("imagemagick"), onProgress, 0.55)
            }
            "proot_debian" -> installMinimalDebian(onProgress)
            else -> false
        }

        val verified = ok && getOptionalAppsStatus()[appId] == true
        if (verified) {
            patchShebangs(force = true)
            onProgress?.invoke(1.0, "Installation complete")
        } else {
            onProgress?.invoke(-1.0, "Installation failed. Review the package log and retry.")
        }
        return verified
    }

    /**
     * Installs a local .deb into the (non-root) Termux-style prefix with dpkg.
     *
     * The chroot runtime needs root and used to be the only supported path, so every
     * non-root user got "deb 安装需要 root" even though dpkg lives in this prefix and the
     * app owns it. dpkg -i reports its own diagnostics, which are forwarded verbatim so
     * the UI can show the real reason instead of a generic failure.
     *
     * Returns true when dpkg accepted the package; [message] always carries dpkg's tail.
     */
    fun installDeb(
        hostFile: File,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Pair<Boolean, String> {
        if (!isBootstrapped()) return false to t("运行时尚未初始化，请先启动 Linux 桌面。", "Runtime not initialised yet; start the Linux desktop first.")
        val dpkg = File(prefixDir, "bin/dpkg")
        if (!dpkg.exists()) {
            return false to t("运行时缺少 dpkg，无法安装 .deb。", "This runtime has no dpkg, so .deb files cannot be installed.")
        }
        if (!hostFile.isFile || hostFile.length() == 0L) {
            return false to t("未找到有效的 .deb 文件，请重新选择。", "The selected .deb file is missing or empty; pick it again.")
        }
        // dpkg refuses paths with control characters and needs a name it can read back
        // inside our own prefix; normalise to a fixed, predictable location.
        val staged = File(tmpDir, "droiddesk-install.deb")
        return try {
            hostFile.copyTo(staged, overwrite = true)
            staged.setReadable(true, false)
            onProgress(0.2, t("正在安装 ${hostFile.name}…", "Installing ${hostFile.name}..."))
            val installOutput = StringBuilder()
            val install = runTermuxCommand(
                "dpkg -i --force-confold '${staged.absolutePath}'",
            ) { chunk ->
                installOutput.append(chunk)
                onLog(chunk)
            }
            onProgress(0.7, t("正在补齐依赖…", "Fixing dependencies..."))
            val fixOutput = StringBuilder()
            // dpkg -i leaves unmet dependencies unresolved; this is not an error by itself.
            runTermuxCommand("DEBIAN_FRONTEND=noninteractive apt-get -f install -y") { chunk ->
                fixOutput.append(chunk)
                onLog(chunk)
            }
            onProgress(0.9, t("正在收尾配置…", "Finalising configuration..."))
            runTermuxCommand("dpkg --configure -a") { chunk ->
                installOutput.append(chunk)
                onLog(chunk)
            }
            // .desktop files and helper scripts shipped by the package may still carry the
            // original Termux shebangs, which would break them at launch time.
            runCatching { patchShebangs(force = true) }

            val detail = (installOutput + fixOutput).toString()
                .lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n")
            val accepted = install == 0
            val ok = accepted || detail.contains("already the newest version", ignoreCase = true)
            if (ok) {
                onProgress(1.0, t("安装完成", "Installation complete"))
            } else {
                onProgress(-1.0, t("安装失败", "Installation failed"))
            }
            DesktopLog.append(
                context, TAG,
                "deb install ${hostFile.name}: exit=$install ok=$ok",
            )
            ok to if (detail.isBlank()) {
                if (ok) t("安装完成", "Installation complete")
                else t("dpkg 未能安装该包，请查看日志。", "dpkg could not install the package; see the log.")
            } else detail
        } catch (error: Throwable) {
            Log.e(TAG, "deb install failed", error)
            DesktopLog.append(context, TAG, "deb install failed: ${error.message}")
            onProgress(-1.0, t("安装失败：{error}", "Install failed: {error}"))
            false to (error.message ?: t("未知错误", "Unknown error"))
        }
    }

    // ── Native package store ──

    fun searchNativePackages(query: String, limit: Int = 60): List<Map<String, Any>> {
        if (!isBootstrapped()) return emptyList()
        val cleanQuery = query.trim().take(60)
        if (!cleanQuery.matches(Regex("[A-Za-z0-9+._ -]*"))) return emptyList()
        val output = runQuickCommand("apt-cache search --names-only '${cleanQuery.ifEmpty { "." }}'")
        val installed = installedPackageRecords().associateBy { it["name"] as String }
        return output.lineSequence().mapNotNull { line ->
            val separator = line.indexOf(" - ")
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).substringBefore(':').trim()
            if (!isSafePackageName(name)) return@mapNotNull null
            val description = line.substring(separator + 3).trim()
            val record = installed[name]
            mapOf(
                "name" to name,
                "description" to description,
                "version" to (record?.get("version") ?: ""),
                "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                "installed" to (record != null),
                "gui" to providesDesktopEntry(name),
            )
        }.distinctBy { it["name"] }.take(limit.coerceIn(1, 100)).toList()
    }

    fun getInstalledNativePackages(limit: Int = 500): List<Map<String, Any>> {
        val visible = storeInstalledPackages() + setOf(
            "firefox", "code-oss", "libreoffice", "gimp", "blender", "vlc",
            "nodejs", "python", "imagemagick",
        )
        return installedPackageRecords()
            .filter { record ->
                val name = record["name"] as? String ?: return@filter false
                visible.contains(name)
            }
            .take(limit.coerceIn(1, 1000))
    }

    fun installStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName)) {
            onProgress(-1.0, t("软件包名称不合法", "Invalid package name"))
            return false
        }
        lastPackageError = ""
        onProgress(0.08, t("正在修复未完成的安装事务…", "Repairing interrupted package operations..."))
        installPackageGroup("dpkg --configure -a")
        if (packageOperationCancelled) {
            onProgress(-1.0, t("已取消安装", "Installation cancelled"))
            return false
        }
        onProgress(0.22, t("正在安装 $packageName 及其依赖…", "Installing $packageName and dependencies..."))
        val optionalId = when (packageName) {
            "firefox" -> "firefox"
            "code-oss", "code" -> "code_oss"
            "nodejs" -> "nodejs"
            "imagemagick" -> "imagemagick"
            else -> null
        }
        val ok = if (optionalId != null) {
            installOptionalApp(optionalId) { progress, status ->
                onProgress(0.22 + progress.coerceIn(0.0, 1.0) * 0.68, status)
            }
        } else {
            installOptionalPackages(listOf(packageName), onProgress, 0.5)
        }
        if (packageOperationCancelled) {
            onProgress(-1.0, t("已取消安装", "Installation cancelled"))
            return false
        }
        patchShebangs(force = true)
        refreshDesktopMenus()
        val installed = ok && isDpkgPackageInstalled(packageName)
        if (installed) setStorePackageInstalled(packageName, true)
        // Surface the captured apt reason instead of a bare "installation failed":
        // "package not in this repository" and "network unreachable" look identical
        // otherwise, and the user cannot tell which one to act on.
        val failure = lastPackageError.ifBlank {
            t("$packageName 安装失败，请查看安装日志。", "$packageName installation failed; see the install log.")
        }
        onProgress(
            if (installed) 1.0 else -1.0,
            if (installed) t("$packageName 安装成功", "$packageName installed") else failure,
        )
        return installed
    }

    fun removeStorePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit,
    ): Boolean {
        if (!isSafePackageName(packageName) || isProtectedPackage(packageName)) {
            onProgress(-1.0, "This package is required by DroidDesk")
            return false
        }
        onProgress(0.15, "Removing $packageName...")
        val output = executeCommand("apt-get remove -y $packageName")
        if (packageOperationCancelled) {
            onProgress(-1.0, "Removal cancelled")
            return false
        }
        patchShebangs(force = true)
        executeCommand("dpkg --configure -a")
        refreshDesktopMenus()
        val removed = !isDpkgPackageInstalled(packageName)
        if (removed) setStorePackageInstalled(packageName, false)
        onProgress(if (removed) 1.0 else -1.0, if (removed) "$packageName removed" else "$packageName removal failed")
        return removed && !output.startsWith("Error:")
    }

    /**
     * Installed packages with a newer version available. Core packages DroidDesk depends on
     * (see [isProtectedPackage]) are left out: upgrading them can break the relocated runtime.
     */
    fun getUpgradablePackages(refresh: Boolean): List<Map<String, Any>> {
        if (!isBootstrapped()) return emptyList()
        if (refresh) {
            // Do not swallow update failures: `>/dev/null 2>&1; true` made a broken
            // resolver look like "there are no updates", which is why updating never
            // found anything after the repository indexes went stale. Keep the output,
            // record the real reason, and let the updates screen show it.
            lastPackageError = ""
            val updateOutput = executeCommand("timeout --signal=TERM --kill-after=5s 120s apt-get update")
            if (updateOutput.startsWith("Error:")) {
                lastPackageError = t(
                    "刷新软件源失败：${aptFailureReason(updateOutput)}",
                    "Refreshing package sources failed: ${aptFailureReason(updateOutput)}",
                )
                DesktopLog.append(context, TAG, "apt-get update failed: ${aptFailureReason(updateOutput)}")
            }
        }
        val installed = installedPackageRecords().associateBy { it["name"] as String }
        // Lines look like: name/stable 1.2-1 aarch64 [upgradable from: 1.1-1]
        return runQuickCommand("apt list --upgradable 2>/dev/null").lineSequence().mapNotNull { line ->
            if (!line.contains("[upgradable from:")) return@mapNotNull null
            val name = line.substringBefore('/').trim()
            if (!isSafePackageName(name) || isProtectedPackage(name)) return@mapNotNull null
            val newVersion = line.substringAfter('/').split(' ').getOrNull(1).orEmpty()
            val current = line.substringAfter("upgradable from: ").substringBefore(']').trim()
            val record = installed[name]
            val description = record?.get("description") as? String ?: ""
            mapOf(
                "name" to name,
                "version" to newVersion,
                "currentVersion" to current,
                "description" to description,
                "section" to (record?.get("section") ?: categoryForPackage(name, description)),
                "installed" to true,
                "gui" to providesDesktopEntry(name),
            )
        }.distinctBy { it["name"] }.toList()
    }

    /** Upgrades every package from [getUpgradablePackages]; core packages are never touched. */
    fun upgradeAllPackages(onProgress: (Double, String) -> Unit): Boolean {
        onProgress(0.06, t("正在修复未完成的安装事务…", "Repairing interrupted package operations..."))
        installPackageGroup("dpkg --configure -a")
        if (packageOperationCancelled) return cancelled(onProgress)
        onProgress(0.14, t("正在检查可用更新…", "Checking for updates..."))
        val packages = getUpgradablePackages(refresh = true).map { it["name"] as String }
        if (packageOperationCancelled) return cancelled(onProgress)
        if (packages.isEmpty()) {
            // An empty list right after a failed refresh is not "up to date".
            val reason = lastPackageError
            if (reason.isNotBlank()) {
                onProgress(-1.0, reason)
                return false
            }
            onProgress(1.0, t("所有软件已是最新", "Everything is up to date"))
            return true
        }
        onProgress(0.3, t("正在更新 ${packages.size} 个软件包…", "Updating ${packages.size} package(s)..."))
        val output = executeCommand(
            "DEBIAN_FRONTEND=noninteractive apt-get " +
                "-o Dpkg::Options::=--force-confdef " +
                "-o Dpkg::Options::=--force-confold " +
                "install --only-upgrade -y ${packages.joinToString(" ")}",
        )
        if (packageOperationCancelled) return cancelled(onProgress)
        onProgress(0.88, t("正在收尾…", "Finishing up..."))
        patchShebangs(force = true)
        executeCommand("timeout --signal=TERM --kill-after=5s 90s dpkg --configure -a")
        refreshDesktopMenus()
        val remaining = getUpgradablePackages(refresh = false).size
        val ok = !output.startsWith("Error:") && remaining < packages.size
        onProgress(
            if (ok) 1.0 else -1.0,
            when {
                !ok -> lastPackageError.ifBlank {
                    t("更新失败：${aptFailureReason(output)}", "Update failed: ${aptFailureReason(output)}")
                }
                remaining == 0 -> t("全部更新完成", "All packages updated")
                else -> t("有 $remaining 个软件包未能更新", "$remaining package(s) could not be updated")
            },
        )
        return ok
    }

    private fun cancelled(onProgress: (Double, String) -> Unit): Boolean {
        onProgress(-1.0, "Update cancelled")
        return false
    }

    /** Disk usage of the Linux runtime and the device, in bytes. */
    fun getStorageInfo(): Map<String, Any> {
        val stat = android.os.StatFs(baseDir.absolutePath)
        val info = mutableMapOf<String, Any>(
            "deviceFree" to stat.availableBytes,
            "deviceTotal" to stat.totalBytes,
            "available" to isBootstrapped(),
        )
        if (!isBootstrapped()) return info
        // Separate du runs: one du call counts each inode once, so a nested path listed after
        // its parent would report 0.
        val output = runQuickCommand(
            "cd '${baseDir.absolutePath}' && for d in usr home usr/var/cache/apt usr/var/lib/apt/lists; " +
                "do du -sk \"\$d\" 2>/dev/null; done; true",
        )
        val sizes = output.lineSequence().mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"), limit = 2)
            val kb = parts.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            parts.getOrNull(1)?.let { it to kb * 1024 }
        }.toMap()
        val cache = (sizes["usr/var/cache/apt"] ?: 0L) + (sizes["usr/var/lib/apt/lists"] ?: 0L)
        info["system"] = ((sizes["usr"] ?: 0L) - cache).coerceAtLeast(0L)
        info["home"] = sizes["home"] ?: 0L
        info["cache"] = cache
        info["packages"] = installedPackageRecords().size
        return info
    }

    /** Deletes downloaded package files (apt-get clean). Returns bytes freed, or -1 on failure. */
    fun cleanPackageCache(): Long {
        val before = (getStorageInfo()["cache"] as? Long) ?: 0L
        val output = executeCommand("apt-get clean")
        if (output.startsWith("Error:")) return -1L
        val after = (getStorageInfo()["cache"] as? Long) ?: 0L
        return (before - after).coerceAtLeast(0L)
    }

    private fun installedPackageRecords(): List<Map<String, Any>> {
        val statusFile = File(prefixDir, "var/lib/dpkg/status")
        if (!statusFile.isFile) return emptyList()
        return statusFile.readText().split(Regex("\\n\\s*\\n")).mapNotNull { paragraph ->
            val fields = mutableMapOf<String, String>()
            var currentKey: String? = null
            paragraph.lineSequence().forEach { line ->
                if (line.startsWith(" ") && currentKey != null) {
                    fields[currentKey!!] = fields[currentKey!!].orEmpty() + " " + line.trim()
                } else {
                    val colon = line.indexOf(':')
                    if (colon > 0) {
                        currentKey = line.substring(0, colon)
                        fields[currentKey!!] = line.substring(colon + 1).trim()
                    }
                }
            }
            if (fields["Status"] != "install ok installed") return@mapNotNull null
            val name = fields["Package"]?.substringBefore(':') ?: return@mapNotNull null
            val description = fields["Description"].orEmpty().substringBefore(" . ").trim()
            mapOf(
                "name" to name,
                "description" to description,
                "version" to fields["Version"].orEmpty(),
                "section" to fields["Section"].orEmpty().ifEmpty { categoryForPackage(name, description) },
                "installed" to true,
                "gui" to providesDesktopEntry(name),
                "removable" to !isProtectedPackage(name),
            )
        }.sortedBy { (it["name"] as String).lowercase() }
    }

    private fun providesDesktopEntry(packageName: String): Boolean {
        val fileList = File(prefixDir, "var/lib/dpkg/info/$packageName.list")
        return fileList.isFile && fileList.useLines { lines ->
            lines.any { it.contains("/share/applications/") && it.endsWith(".desktop") }
        }
    }

    private fun categoryForPackage(name: String, description: String): String {
        val text = "$name $description".lowercase()
        return when {
            listOf("browser", "firefox", "chromium").any(text::contains) -> "Internet"
            listOf("editor", "compiler", "git", "python", "nodejs", "ide").any(text::contains) -> "Development"
            listOf("image", "photo", "graphics", "gimp", "blender").any(text::contains) -> "Graphics"
            listOf("audio", "video", "media", "music", "vlc").any(text::contains) -> "Multimedia"
            listOf("office", "document", "spreadsheet", "pdf").any(text::contains) -> "Office"
            else -> "System"
        }
    }

    private fun isSafePackageName(packageName: String): Boolean =
        packageName.matches(Regex("[a-z0-9][a-z0-9+.-]{0,127}"))

    private fun isProtectedPackage(packageName: String): Boolean =
        packageName in setOf(
            "apt", "bash", "coreutils", "dpkg", "termux-tools", "termux-am", "glibc-repo",
            "x11-repo", "tur-repo", "pulseaudio", "dbus", "python", "xfce4", "xfce4-session",
            "xfce4-panel", "xfdesktop", "xfwm4", "xfconf", "thunar", "xorg-xrandr",
        )

    private fun storeInstalledPackages(): Set<String> =
        context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
            .getStringSet("installed", emptySet())
            ?.toSet()
            .orEmpty()

    private fun setStorePackageInstalled(packageName: String, installed: Boolean) {
        val preferences = context.getSharedPreferences("package_store", Context.MODE_PRIVATE)
        val packages = preferences.getStringSet("installed", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (installed) packages.add(packageName) else packages.remove(packageName)
        preferences.edit().putStringSet("installed", packages).commit()
    }

    private fun runQuickCommand(command: String): String = runCatching {
        ProcessBuilder(File(binDir, "bash").absolutePath, "-c", command)
            .directory(prefixDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
            }
            .start().let { process ->
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                if (process.exitValue() == 0) output else ""
            }
    }.getOrDefault("")

    private fun refreshDesktopMenus() {
        executeCommand(
            "update-desktop-database ${prefixDir.absolutePath}/share/applications >/dev/null 2>&1 || true",
        )
    }

    fun readLinuxClipboard(): String? = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return null
        ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-o")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }
            .start().let { process ->
                val value = process.inputStream.bufferedReader().readText()
                if (process.waitFor() == 0) value else ""
            }
    }.getOrNull()

    fun writeLinuxClipboard(value: String): Boolean = runCatching {
        val xclip = File(binDir, "xclip")
        if (!xclip.canExecute()) return false
        val process = ProcessBuilder(xclip.absolutePath, "-selection", "clipboard", "-in")
            .directory(homeDir)
            .redirectErrorStream(true)
            .also { builder ->
                builder.environment().clear()
                builder.environment().putAll(getTermuxEnv())
                builder.environment()["DISPLAY"] = ":0"
            }.start()
        process.outputStream.use { it.write(value.toByteArray()) }
        process.waitFor() == 0
    }.getOrDefault(false)

    // ── Session Management ──

    fun startSession(
        desktopEnv: String = "xfce4",
        mode: String = "x11",
        width: Int = 1920,
        height: Int = 1080,
        onPhase: (String) -> Unit = {},
    ) {
        val selectedDesktop = normalizedDesktop(desktopEnv)
        val timingStart = android.os.SystemClock.elapsedRealtime()
        fun mark(label: String) {
            val ms = android.os.SystemClock.elapsedRealtime() - timingStart
            DesktopLog.append(context, TAG, "TIMING $label +${ms}ms")
        }
        onPhase("检查 Linux 运行环境")
        DesktopLog.append(context, TAG, "native session start requested (${width}x$height)")
        extractBootstrapIfNeeded(context)
        mark("bootstrap")

        if (isRunning()) {
            Log.w(TAG, "Session already running")
            return
        }

        if (!isBootstrapped()) {
            throw IllegalStateException("Linux 运行环境未安装完成（缺少 bootstrap），请先在设置中重新安装")
        }

        onPhase("清理上次会话残留")
        cleanupStaleSession()
        prepareSessionEnvironment(onPhase)
        mark("cleanup+env")

        // Packages whose maintainer scripts failed on older releases stay half-configured and
        // block every later apt install (and with it the Adreno driver). Finish them once
        // their scripts have been relocated.
        if (hasUnfinishedPackages()) {
            onPhase("修复上次中断的软件包")
            Log.i(TAG, "Finishing packages left half-configured by an earlier install")
            installPackageGroup("dpkg --configure -a")
        }

        onPhase("检查图形运行时")
        // Repair installations made by older releases, where mesa-zink could
        // remove Mesa and leave the configured llvmpipe fallback unavailable.
        if (!ensureGraphicsPackages()) {
            throw IllegalStateException("图形运行时不可用（Mesa 缺失且安装失败），请检查网络后重试")
        }
        mark("graphics")

        val clipboardTool = File(binDir, "xclip")
        if (!clipboardTool.isFile) {
            Log.i(TAG, "Installing the desktop clipboard compatibility helper")
            if (installPackageGroup("pkg install -y xclip")) {
                patchShebangs(force = true)
            } else {
                Log.w(TAG, "Clipboard helper unavailable; clipboard sync will retry next session")
            }
        }

        if (selectedDesktop == "xfce4") {
            onPhase("应用桌面配置")
            // Older installs predate the auto-maximize helper; try once while upgrading the profile.
            if (!File(binDir, "devilspie2").isFile) {
                Log.i(TAG, "Installing the window fitting helper")
                if (installPackageGroup("pkg install -y devilspie2")) {
                    patchShebangs(force = true)
                } else {
                    Log.w(TAG, "Window fitting helper unavailable; windows will keep their default size")
                }
            }
            mark("profile:begin")
            XfceMobileProfile.install(
                context = context,
                homeDir = homeDir.apply { mkdirs() },
                wallpaperFile = File(
                    homeDir,
                    ".local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
                ),
                // Passing the user's scale and power mode matters: XfceMobileProfile
                // defaults to 1.5/standard, so without them choosing "saver" in the UI
                // still installed animations and the compositor.
                uiScale = DisplayProfile.uiScale(context),
                powerMode = DisplayProfile.powerMode(context),
            )
            mark("profile:end")
            exportDesktopShortcuts()
            // Enumerating every installed Android app through python costs ~40 s and
            // changes rarely, so refresh at most once every six hours.
            val syncMarker = File(baseDir, ".android_apps_synced")
            val syncFresh = syncMarker.isFile &&
                (System.currentTimeMillis() - syncMarker.lastModified()) < 6 * 60 * 60 * 1000L
            mark("launcher-sync:begin")
            if (!syncFresh) {
                AndroidAppBridge.syncLaunchers(
                    context = context,
                    homeDir = homeDir,
                    python = File(prefixDir, "bin/python3"),
                )
                runCatching { syncMarker.writeText(System.currentTimeMillis().toString()) }
            } else {
                DesktopLog.append(context, TAG, "android launcher sync skipped (cache fresh)")
            }
            mark("launcher-sync:end")
        }

        // X11ServerService owns this socket. Never delete it from the client runtime.
        File(tmpDir, ".X11-unix").mkdirs()

        mark("dbus:begin")
        // AT-SPI cannot register in this bootstrap; the X-focus helper is what the IME
        // watcher actually uses. Build it before the session so the first launch pays
        // the compile cost only once.
        val focusProbe = ensureFocusProbe()
        mark("focus-probe")
        // IME text never reached VTE through sendTextEvent(); compile the XTest injector
        // once so committed characters can be replayed as real key events.
        ensureTextInjector()
        mark("type-probe")
        ensureStoreProxyConfig()
        ensurePackageManagerEnv()
        ensureGuestDns()
        mark("store-proxy")
        onPhase("启动会话总线")

        // Remove stale Xfce ICE listeners left by a killed/restarted activity.
        tmpDir.listFiles { file -> file.name.startsWith(".xfsm-ICE-") }
            ?.forEach { it.delete() }

        // Start a session dbus-daemon and keep it as a child process. Do not use
        // --fork: a forked daemon can outlive the Android activity and leave an
        // orphaned bus behind after its socket is replaced.
        val dbusSocket = File(tmpDir, "dbus-session")
        try {
            dbusProcess?.destroyForcibly()
            if (dbusSocket.exists()) dbusSocket.delete()
            val dbusConfig = File(tmpDir, "dbus-session.conf")
            dbusConfig.writeText(
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>session</type>
                  <keep_umask/>
                  <listen>unix:path=${dbusSocket.absolutePath}</listen>
                  <auth>EXTERNAL</auth>
                  <servicedir>${File(prefixDir, "share/dbus-1/services").absolutePath}</servicedir>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent() + "\n",
            )
            val dbusCmd = listOf(
                File(prefixDir, "bin/dbus-daemon").absolutePath,
                "--config-file=${dbusConfig.absolutePath}",
                "--nofork",
                "--nopidfile"
            )
            val startedDbus = ProcessBuilder(dbusCmd)
                .directory(homeDir.apply { mkdirs() })
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()
            dbusProcess = startedDbus

            Thread {
                try {
                    startedDbus.inputStream.bufferedReader().forEachLine {
                        Log.d(TAG, "DBUS: $it")
                    }
                } catch (error: java.io.IOException) {
                    // Closing the pipe is expected when Stop Server terminates dbus.
                    Log.d(TAG, "D-Bus output stream closed")
                }
            }.start()

            val readyDeadline = System.currentTimeMillis() + 2_000
            while (!dbusSocket.exists() && startedDbus.isAlive &&
                System.currentTimeMillis() < readyDeadline) {
                Thread.sleep(20)
            }
            check(dbusSocket.exists() && startedDbus.isAlive) {
                "dbus-daemon did not create its session socket"
            }
            Log.i(TAG, "Session dbus-daemon ready")
        } catch (e: Exception) {
            Log.e(TAG, "dbus-daemon start failed: ${e.message}")
            DesktopLog.append(context, TAG, "dbus-daemon start failed: ${e.message}")
            dbusProcess?.destroyForcibly()
            dbusProcess = null
            throw IllegalStateException("会话总线（dbus）启动失败：${e.message}", e)
        }

        mark("dbus:end")

        // GTK3 loads its accessibility bridge as a GtkModule named "atk-bridge". The
        // Termux bootstrap ships libatk-bridge-2.0.so but not the module directory GTK
        // searches, so without this link no focus event ever reaches the a11y bus.
        runCatching {
            val moduleDir = File(prefixDir, "lib/gtk-3.0/3.0.0/modules").apply { mkdirs() }
            val link = File(moduleDir, "libatk-bridge.so")
            if (File(prefixDir, "lib/libatk-bridge-2.0.so").isFile && !link.exists()) {
                java.nio.file.Files.createSymbolicLink(
                    link.toPath(),
                    java.nio.file.Paths.get("../../../libatk-bridge-2.0.so"),
                )
                DesktopLog.append(context, TAG, "linked GTK atk-bridge module")
            }
        }.onFailure { DesktopLog.append(context, TAG, "atk-bridge link failed: ${it.message}") }

        // Accessibility needs its own bus. at-spi's launcher is hard-wired to
        // /data/data/com.termux/files/usr/bin/dbus-daemon, which does not exist here, so
        // DroidDesk starts the bus itself and exports AT_SPI_BUS_ADDRESS (libatspi honours
        // it). The IME bridge reads focus events from that bus.
        val a11ySocket = File(tmpDir, "a11y-bus")
        try {
            a11yBusProcess?.destroyForcibly()
            if (a11ySocket.exists()) a11ySocket.delete()
            val a11yConfig = File(tmpDir, "dbus-a11y.conf")
            a11yConfig.writeText(
                """
                <!DOCTYPE busconfig PUBLIC "-//freedesktop//DTD D-Bus Bus Configuration 1.0//EN"
                 "http://www.freedesktop.org/standards/dbus/1.0/busconfig.dtd">
                <busconfig>
                  <type>session</type>
                  <listen>unix:path=${a11ySocket.absolutePath}</listen>
                  <auth>EXTERNAL</auth>
                  <policy context="default">
                    <allow send_destination="*" eavesdrop="true"/>
                    <allow eavesdrop="true"/>
                    <allow own="*"/>
                  </policy>
                </busconfig>
                """.trimIndent() + "\n",
            )
            val startedA11y = ProcessBuilder(
                listOf(
                    File(prefixDir, "bin/dbus-daemon").absolutePath,
                    "--config-file=${a11yConfig.absolutePath}",
                    "--nofork",
                    "--nopidfile",
                ),
            )
                .directory(homeDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()
            a11yBusProcess = startedA11y
            Thread {
                runCatching {
                    startedA11y.inputStream.bufferedReader().forEachLine {
                        Log.d(TAG, "A11Y: $it")
                    }
                }
            }.start()
            val a11yDeadline = System.currentTimeMillis() + 2_000
            while (!a11ySocket.exists() && startedA11y.isAlive &&
                System.currentTimeMillis() < a11yDeadline
            ) {
                Thread.sleep(20)
            }
            if (a11ySocket.exists()) {
                DesktopLog.append(context, TAG, "a11y bus ready at ${a11ySocket.absolutePath}")
            } else {
                DesktopLog.append(context, TAG, "a11y bus did not create its socket")
            }
        } catch (e: Exception) {
            Log.w(TAG, "a11y bus start failed: ${e.message}")
            DesktopLog.append(context, TAG, "a11y bus start failed: ${e.message}")
            a11yBusProcess = null
        }

        // The Termux startxfce4 wrapper falls back to Android's /bin/sh when an
        // X server already exists, which corrupts DISPLAY on recent Android
        // releases. The session binary starts the same XFCE components and
        // preserves DroidDesk's prepared environment.
        val desktopCommand = "xfce4-session"
        // Same path the Xfce profile writes into xfce4-desktop.xml; the wallpaper
        // enforcement block below re-applies it to the channels xfdesktop created.
        val wallpaperInSession = File(
            homeDir,
            ".local/share/backgrounds/droiddesk-ubuntu-touch.jpg",
        ).absolutePath

        val runScript = """
            # ── AT-SPI accessibility bus (used by the Android IME bridge) ──
            # It must stay enabled: Termux's GTK3 has no IBus input module, so focus
            # state is read from at-spi instead of from an input context.
            unset NO_AT_BRIDGE
            unset GTK_A11Y
            export GTK_MODULES=gail:atk-bridge
            export DISPLAY=:0
            export AT_SPI_BUS_ADDRESS="unix:path=${a11ySocket.absolutePath}"

            # Use the session bus DroidDesk already started
            export DBUS_SESSION_BUS_ADDRESS="unix:path=${dbusSocket.absolutePath}"

            # PulseAudio daemonizes, so it outlives a stopped session. A wedged
            # one makes every pactl/pulseaudio call (and XFCE's event sounds)
            # block forever, leaving a black desktop. Never wait on audio
            # without a time limit, and always start from a fresh daemon.
            limit() {
                if command -v timeout >/dev/null 2>&1; then timeout "${'$'}@"; else shift; "${'$'}@"; fi
            }
            pkill -9 -x pulseaudio >/dev/null 2>&1 || true

            # Native Android audio. AAudio is reliable on modern Android while
            # OpenSL ES remains the compatibility fallback for older devices.
            if [ "${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) "1" else "0"}" = "1" ]; then
                limit 10 pulseaudio --start --exit-idle-time=-1 \
                    --load=module-aaudio-sink >/dev/null 2>&1 || true
                audio_ready=0
                for attempt in 1 2 3 4 5 6 7 8 9 10; do
                    if limit 2 pactl list short sinks 2>/dev/null | grep -q AAudio_sink; then
                        audio_ready=1
                        break
                    fi
                    sleep 0.1
                done
                if [ "${'$'}audio_ready" != "1" ]; then
                    pkill -9 -x pulseaudio >/dev/null 2>&1 || true
                    limit 10 pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
                fi
            else
                limit 10 pulseaudio --start --exit-idle-time=-1 >/dev/null 2>&1 || true
            fi
            echo "DIAG: PulseAudio sinks: ${'$'}(limit 2 pactl list short sinks 2>/dev/null | cut -f2 | tr '\n' ' ')"

            # Android IME bridge. The IME is only requested while an EDITABLE widget
            # (text/entry/terminal/...) holds focus, so tapping a window background or
            # the desktop never pops the keyboard. Focus comes from the AT-SPI
            # accessibility bus: GTK3 (XFCE, VTE terminal) and Qt both report it there,
            # which is why this works without an IBus input module.
            BRIDGE="${'$'}{DROIDDESK_BRIDGE:-/tmp/droiddesk-bridge}"
            mkdir -p "${'$'}BRIDGE" 2>/dev/null || true
            printf '0\n' > "${'$'}BRIDGE/ime-focus" 2>/dev/null || true
            # PyGObject is not installed in this bootstrap, so the watcher talks to the
            # AT-SPI bus with dbus-send/dbus-monitor directly. Only roles that accept text
            # (38 password text, 56 terminal, 57 text, 69 paragraph) count as editable.
            cat > "${'$'}BRIDGE/ime-watch.sh" <<'DROIDDESK_SH'
#!/system/bin/sh
BRIDGE="${'$'}{DROIDDESK_BRIDGE:-/tmp/droiddesk-bridge}"
FOCUS="${'$'}BRIDGE/ime-focus"
ROLE="${'$'}BRIDGE/ime-role"
LAST="none"
write_state() {
    if [ "${'$'}LAST" != "${'$'}1" ]; then
        LAST="${'$'}1"
        printf '%s\n' "${'$'}1" > "${'$'}FOCUS" 2>/dev/null || true
    fi
}
ADDR="${'$'}{AT_SPI_BUS_ADDRESS:-}"
i=0
while [ -z "${'$'}ADDR" ] && [ "${'$'}i" -lt 10 ]; do
    ADDR="${'$'}(dbus-send --session --print-reply --dest=org.a11y.Bus \
        /org/a11y/bus org.a11y.Bus.GetAddress 2>/dev/null |
        sed -n 's/^ *string "\(.*\)"${'$'}/\1/p')"
    [ -n "${'$'}ADDR" ] && break
    i=${'$'}((i + 1))
    sleep 1
done
if [ -z "${'$'}ADDR" ]; then
    echo "watcher-error:no-a11y-bus" > "${'$'}ROLE" 2>/dev/null || true
    exit 0
fi
printf '-\n' > "${'$'}ROLE" 2>/dev/null || true
echo "DIAG: a11y bus ${'$'}ADDR"
dbus-monitor --address "${'$'}ADDR" \
    "type=signal,interface=org.a11y.atspi.Event.Object,member=StateChanged" 2>/dev/null |
awk '
/^signal/ {
    sender=""; path=""; detail=-1; focused=0
    if (match($0, /sender=[^ ]+/)) sender=substr($0, RSTART+7, RLENGTH-7)
    if (match($0, /path=[^ ]+/)) path=substr($0, RSTART+5, RLENGTH-5)
    for (i=0; i<8; i++) {
        if ((getline line) <= 0) break
        if (line ~ /string "focused"/) focused=1
        if (line ~ /^   int32 0${'$'}/) detail=0
        if (line ~ /^   int32 1${'$'}/) detail=1
    }
    if (focused==1 && path!="") print sender "|" path "|" detail
}' |
while IFS='|' read -r sender path detail; do
    [ -z "${'$'}path" ] && continue
    if [ "${'$'}detail" = "0" ]; then
        write_state 0
        continue
    fi
    role="${'$'}(dbus-send --bus="${'$'}ADDR" --print-reply --dest="${'$'}sender "${'$'}path" \
        org.a11y.atspi.Accessible.GetRole 2>/dev/null | awk '/uint32/ {print ${'$'}2; exit}')"
    printf '%s\n' "${'$'}{role:-?}" > "${'$'}ROLE" 2>/dev/null || true
    case "${'$'}role" in
        38|56|57|69) write_state 1 ;;
        *) write_state 0 ;;
    esac
done
DROIDDESK_SH
            FOCUS_PROBE="${focusProbe?.absolutePath ?: ""}"
            # X focus fallback: WM_CLASS whitelist. AT-SPI never receives events in this
            # bootstrap (no PyGObject, launcher bound to Termux's dbus), so the focus
            # probe built above is the working path. Only whitelisted editable app
            # classes pop the keyboard; tapping the desktop or a panel writes 0.
            cat > "${'$'}BRIDGE/ime-watch-x11.sh" <<'DROIDDESK_X11'
#!/system/bin/sh
BRIDGE="${'$'}{DROIDDESK_BRIDGE:-/tmp/droiddesk-bridge}"
FOCUS="${'$'}BRIDGE/ime-focus"
TAPFILE="${'$'}BRIDGE/ime-tap"
ACTIVE="${'$'}BRIDGE/ime-active"
LAST="none"
LASTTAP=""
LASTACTIVE=""
# A window class alone is not proof that the user aimed at a text widget: xfce4
# auto-focuses a window during startup, and every window click inside a whitelisted
# app looks the same. Only report editable focus after a real touch landed on the
# X surface (ime-tap is rewritten on each tap by the Android side), so a cold start
# can never pop the keyboard. Tapping inside a whitelisted app keeps the arm; tapping
# anywhere else clears it.
#
# Power: the expensive part is the focus probe (a full X client). It now runs only
# when the active window or the tap token changes, never on a timer. The active
# window is tracked by one long-lived `xprop -root -spy`, which replaces the old
# probe-every-300ms loop (~12000 forked X clients per hour).
ARMED=0
SPY=0
if command -v xprop >/dev/null 2>&1; then
    SPY=1
fi
if [ "${'$'}SPY" = "1" ]; then
    (
        xprop -root -spy _NET_ACTIVE_WINDOW 2>/dev/null |
        while IFS= read -r line; do
            printf '%s\n' "${'$'}line" > "${'$'}ACTIVE" 2>/dev/null || true
        done
    ) &
fi
while true; do
    tap=""
    read -r tap < "${'$'}TAPFILE" 2>/dev/null || true
    active=""
    read -r active < "${'$'}ACTIVE" 2>/dev/null || true
    probe=0
    if [ -n "${'$'}tap" ] && [ "${'$'}tap" != "${'$'}LASTTAP" ]; then
        LASTTAP="${'$'}tap"
        ARMED=1
        # Force one rewrite after every tap: the Android side resets ime-focus to 0
        # when the keyboard is dismissed, and an unchanged LAST would otherwise keep
        # the second tap in the same window from re-triggering the keyboard.
        LAST="none"
        probe=1
    fi
    if [ "${'$'}SPY" = "0" ] || { [ -n "${'$'}active" ] && [ "${'$'}active" != "${'$'}LASTACTIVE" ]; }; then
        LASTACTIVE="${'$'}active"
        probe=1
    fi
    if [ "${'$'}probe" = "1" ]; then
        cls="${'$'}("${'$'}{DROIDDESK_X11_FOCUS:-}" 2>/dev/null)"
        case "${'$'}cls" in
            *xfce4-terminal*|*zenity*|*Zenity*|*mousepad*|*Mousepad*|\
*xfce4-settings*|*Xfce4-settings*|*gedit*|*XTerm*|*xterm*|*urxvt*|*leafpad*|\
*droiddesk*|*code-oss*|*Code*)
                if [ "${'$'}ARMED" = "1" ]; then state=1; else state=0; fi ;;
            *) state=0; ARMED=0 ;;
        esac
        if [ "${'$'}LAST" != "${'$'}state" ]; then
            LAST="${'$'}state"
            printf '%s\n' "${'$'}state" > "${'$'}FOCUS" 2>/dev/null || true
        fi
    fi
    sleep 0.3
done
DROIDDESK_X11
            if [ -n "${'$'}FOCUS_PROBE" ] && [ -x "${'$'}FOCUS_PROBE" ]; then
                ( DROIDDESK_X11_FOCUS="${'$'}FOCUS_PROBE" sh "${'$'}BRIDGE/ime-watch-x11.sh" >/dev/null 2>&1 ) &
                echo "DIAG: x11 focus ime watcher started"
            elif command -v dbus-monitor >/dev/null 2>&1 && command -v dbus-send >/dev/null 2>&1; then
                ( sh "${'$'}BRIDGE/ime-watch.sh" >/dev/null 2>&1 ) &
                echo "DIAG: at-spi ime watcher started (x11 probe unavailable)"
            else
                echo "DIAG: no ime watcher available"
            fi

            echo "DIAG: Launching $desktopCommand natively on DISPLAY=:0 ..."
            display_ready=0
            for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30 31 32 33 34 35 36 37 38 39 40; do
                if command -v xdpyinfo >/dev/null 2>&1; then
                    DISPLAY=:0 xdpyinfo >/dev/null 2>&1 && { display_ready=1; break; }
                elif [ -S /tmp/.X11-unix/X0 ]; then
                    display_ready=1; break
                fi
                sleep 0.25
            done
            echo "DIAG: display ready=${'$'}display_ready"

            # xfdesktop names its backdrop channels after the framebuffer size, so the
            # profile's monitorbuiltin/monitor0..3 entries can all miss the channel that
            # actually exists and the desktop falls back to pure black after a switch.
            # Enumerate the channels xfconfd really created and write the wallpaper into
            # every one of them, then ask xfdesktop to reload.
            WALLPAPER="${wallpaperInSession}"
            (
                i=0
                while [ "${'$'}i" -lt 60 ]; do
                    if command -v xfconf-query >/dev/null 2>&1 && \
                        xfconf-query -c xfce4-desktop -l 2>/dev/null | grep -q last-image; then
                        break
                    fi
                    i=${'$'}((i + 1))
                    sleep 0.5
                done
                if command -v xfconf-query >/dev/null 2>&1; then
                    n=0
                    for prop in ${'$'}(xfconf-query -c xfce4-desktop -l 2>/dev/null | grep last-image); do
                        xfconf-query -c xfce4-desktop -p "${'$'}prop" -s "${'$'}WALLPAPER" >/dev/null 2>&1 || true
                        base=${'$'}{prop%/last-image}
                        xfconf-query -c xfce4-desktop -p "${'$'}base/image-style" -s 5 >/dev/null 2>&1 || true
                        xfconf-query -c xfce4-desktop -p "${'$'}base/color-style" -s 0 >/dev/null 2>&1 || true
                        n=${'$'}((n + 1))
                    done
                    echo "DIAG: wallpaper channels patched=${'$'}n"
                    # This xfdesktop build has no --reload, and SIGUSR1 terminates it
                    # (default action), which is exactly why the desktop stayed black.
                    # Reload when supported, otherwise restart it so it re-reads xfconf.
                    if xfdesktop --reload >/dev/null 2>&1; then
                        echo "DIAG: xfdesktop reloaded"
                    else
                        pkill -TERM -x xfdesktop >/dev/null 2>&1 || true
                        sleep 0.2
                        ( xfdesktop >/dev/null 2>&1 & )
                        echo "DIAG: xfdesktop restarted"
                    fi
                fi
            ) &
            # xfwm4 can die during cold start (Gdk-ERROR). A desktop without a window
            # manager never gives keyboard focus to a widget, so the IME bridge reads
            # "no editable focus" and the Android keyboard stops popping up. Verify the
            # WM once with a bounded loop and restart it a single time; no persistent
            # watchdog is installed, by design.
            #
            # _NET_SUPPORTING_WM_CHECK survives on the root window after the WM exits,
            # so its mere presence proves nothing. Read the window id it points at and
            # ask xprop about that window: when the WM is gone the id is dead and xprop
            # fails, which is the only reliable "WM is gone" signal.
            wm_alive() {
                if command -v xprop >/dev/null 2>&1; then
                    win=$(xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | awk '/window id #/ {print ${'$'}NF}')
                    [ -n "${'$'}win" ] || return 1
                    xprop -id "${'$'}win" _NET_WM_NAME 2>/dev/null | grep -q '_NET_WM_NAME'
                    return ${'$'}?
                fi
                ps -A -o comm= 2>/dev/null | grep -qx xfwm4
            }
            (
                # Start counting only once the panel exists: that is the first moment a
                # window manager is actually expected, so the window is much less likely
                # to be missed on a slow cold boot. The overall cap is 40s.
                i=0
                while [ "${'$'}i" -lt 80 ]; do
                    sleep 0.5
                    i=${'$'}((i + 1))
                    if ps -A -o comm= 2>/dev/null | grep -qx xfce4-panel; then
                        break
                    fi
                done
                i=0
                while [ "${'$'}i" -lt 80 ]; do
                    wm_alive && break
                    i=${'$'}((i + 1))
                    sleep 0.5
                done
                if [ "${'$'}i" -lt 80 ]; then
                    printf '1\n' > "${tmpDir.absolutePath}/droiddesk-bridge/wm-ok" 2>/dev/null || true
                    echo "DIAG: xfwm4 alive"
                else
                    echo "DIAG: xfwm4 not alive after 40s, restarting once"
                    (
                        xfwm4 --replace --sm-client-disable 2>&1 | tee -a "${tmpDir.absolutePath}/droiddesk-xfwm4.log"
                    ) &
                    sleep 3
                    if wm_alive; then
                        printf '1\n' > "${tmpDir.absolutePath}/droiddesk-bridge/wm-ok" 2>/dev/null || true
                        echo "DIAG: xfwm4 recovered"
                    else
                        printf '0\n' > "${tmpDir.absolutePath}/droiddesk-bridge/wm-ok" 2>/dev/null || true
                        echo "DIAG: xfwm4 restart failed, see ${tmpDir.absolutePath}/droiddesk-xfwm4.log"
                    fi
                fi
            ) &
            exec $desktopCommand
        """.trimIndent()

        onPhase("启动桌面会话")
        Log.i(TAG, "Starting native Termux session for $selectedDesktop")
        DesktopLog.append(context, TAG, "native session launching $desktopCommand")

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val command = listOf(bashBin, "-c", runScript)

        val startedSession = ProcessBuilder(command)
            .directory(homeDir.apply { mkdirs() })
            .redirectErrorStream(true)
            .also { pb ->
                pb.environment().clear()
                pb.environment().putAll(getTermuxEnv())
            }
            .start()
        sessionProcess = startedSession
        mark("session-spawned")

        Thread {
            try {
                val reader = java.io.InputStreamReader(startedSession.inputStream)
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "DESKTOP: " + String(buffer, 0, charsRead))
                    DesktopLog.append(context, "desktop", String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                // destroyForcibly() closes the pipe during a normal shutdown.
                Log.d(TAG, "Desktop output stream closed")
            }
        }.start()

        Log.i(TAG, "Termux session started")
    }

    /** Wait until the desktop shell processes that paint the first frame exist. */
    fun waitForDesktopReady(desktopEnv: String = "xfce4", timeoutMs: Long = 45_000): Boolean {
        val (required, alternatives) = desktopReadinessChecks(normalizedDesktop(desktopEnv))
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val names = runCatching {
                val check = "ps -A -o comm= 2>/dev/null"
                val output = ProcessBuilder(File(binDir, "bash").absolutePath, "-c", check)
                    .directory(homeDir)
                    .redirectErrorStream(true)
                    .also { builder ->
                        builder.environment().clear()
                        builder.environment().putAll(getTermuxEnv())
                    }
                    .start()
                    .inputStream.bufferedReader().readText()
                output.lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            }.getOrDefault(emptySet())
            // A desktop without xfdesktop (e.g. after a wallpaper crash) is still usable;
            // waiting the full timeout for it wasted 45 s on every launch.
            if (required.all { it in names } && (alternatives.isEmpty() || alternatives.any { it in names })) {
                Thread.sleep(650)
                return true
            }
            Thread.sleep(350)
        }
        Log.w(TAG, "Timed out waiting for $desktopEnv to paint its first desktop frame")
        return false
    }

    private fun desktopReadinessChecks(desktop: String): Pair<List<String>, List<String>> = when (desktop) {
        "lxqt" -> listOf("lxqt-panel") to listOf("lxqt-session")
        "mate" -> listOf("mate-panel") to listOf("mate-session")
        "kde" -> listOf("plasmashell") to emptyList()
        // xfwm4 is no longer optional: without a window manager the desktop never hands
        // keyboard focus to a widget, so revealing the surface would show a usable-looking
        // desktop in which typing silently does nothing. xfdesktop stays optional.
        else -> listOf("xfce4-panel", "xfwm4") to listOf("xfdesktop")
    }

    fun stopSession() {
        Log.i(TAG, "Stopping Linux session...")
        sessionProcess?.let {
            it.destroyForcibly()
            it.waitFor()
        }
        sessionProcess = null
        dbusProcess?.destroyForcibly()
        dbusProcess = null
        a11yBusProcess?.destroyForcibly()
        a11yBusProcess = null
        // A killed session manager often leaves panels/wm alive; the next launch would
        // attach to those zombies instead of painting a fresh desktop.
        cleanupStaleSession()
        Log.i(TAG, "Session stopped")
    }

    // ── Command Execution ──

    /**
     * Prints the actual package-manager state of the Termux prefix so a failed
     * install can be explained instead of silently rolling back to "失败".
     */
    fun diagnosePackageManager(onLog: (String) -> Unit = {}) {
        val command = """
            echo "[arch] ${'$'}(dpkg --print-architecture 2>&1)"
            echo "[prefix] ${'$'}{PREFIX:-unknown}"
            echo "[sources]"
            grep -vE '^#|^${'$'}' ${'$'}{PREFIX}/etc/apt/sources.list 2>/dev/null || echo "sources.list missing"
            ls ${'$'}{PREFIX}/etc/apt/sources.list.d 2>/dev/null || true
            echo "[network]"
            getprop net.dns1 2>/dev/null || true
            echo "[locks]"
            ls -l ${'$'}{PREFIX}/var/lib/dpkg/lock ${'$'}{PREFIX}/var/lib/dpkg/lock-frontend 2>/dev/null || true
            echo "[dpkg-configure]"
            DEBIAN_FRONTEND=noninteractive dpkg --configure -a >/tmp/droiddesk-dpkg.log 2>&1
            echo "exit=${'$'}?"
            tail -n 20 /tmp/droiddesk-dpkg.log
            echo "[apt-update]"
            apt-get update -o Acquire::Retries=1 >/tmp/droiddesk-apt-update.log 2>&1
            echo "exit=${'$'}?"
            tail -n 20 /tmp/droiddesk-apt-update.log
        """.trimIndent()
        DesktopLog.append(context, TAG, "diagnose package manager (termux)")
        executeCommand(command) { chunk -> onLog(chunk) }
    }

    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (packageOperationCancelled) return "Error: Package operation cancelled"
        activeCommandProcess?.let { process ->
            try {
                Log.d(TAG, "Routing input to active command: $command")
                val os = process.outputStream
                os.write((command + "\n").toByteArray())
                os.flush()
                return ""
            } catch (e: Exception) {
                Log.w(TAG, "Active process closed or failed to receive input: ${e.message}")
            }
        }

        if (!isBootstrapped()) return "Error: Runtime not bootstrapped"

        compileSocketHook()

        val bashBin = File(prefixDir, "bin/bash").absolutePath
        val fullCommand = listOf(bashBin, "-c", command)

        return try {
            Log.d(TAG, "Executing command natively: $command")

            val process = ProcessBuilder(fullCommand)
                .directory(prefixDir)
                .redirectErrorStream(true)
                .also { pb ->
                    pb.environment().clear()
                    pb.environment().putAll(getTermuxEnv())
                }
                .start()

            activeCommandProcess = process

            val output = StringBuilder()
            val reader = java.io.InputStreamReader(process.inputStream)
            val buffer = CharArray(1024)
            var charsRead: Int
            while (reader.read(buffer).also { charsRead = it } != -1) {
                val chunk = String(buffer, 0, charsRead)
                Log.d(TAG, "CHUNK: $chunk")
                output.append(chunk)
                installLogSink?.invoke(chunk)
                onOutput?.invoke(chunk)
            }
            process.waitFor()
            activeCommandProcess = null
            Log.d(TAG, "Command finished with exit code: ${process.exitValue()}")

            if (process.exitValue() != 0) {
                throw Exception("Command failed with exit code ${process.exitValue()}. Output: \n$output")
            }

            output.toString()
        } catch (e: Exception) {
            activeCommandProcess = null
            Log.e(TAG, "Command execution failed: ${e.message}")
            "Error: ${e.message}"
        }
    }

    fun interruptCommand() {
        activeCommandProcess?.let { process ->
            Log.d(TAG, "Interrupting active command...")
            val rootPid = processPid(process)
            val descendants = rootPid?.let(::descendantPids).orEmpty()
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 15) }
            rootPid?.let { android.os.Process.sendSignal(it, 15) } ?: process.destroy()
            try {
                Thread.sleep(200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            descendants.asReversed().forEach { pid -> android.os.Process.sendSignal(pid, 9) }
            if (process.isAlive) process.destroyForcibly()
        }
        activeCommandProcess = null
    }

    private fun processPid(process: Process): Int? = runCatching {
        var type: Class<*>? = process.javaClass
        var pidField: java.lang.reflect.Field? = null
        while (type != null && pidField == null) {
            pidField = runCatching { type.getDeclaredField("pid") }.getOrNull()
            type = type.superclass
        }
        requireNotNull(pidField).apply { isAccessible = true }.getInt(process)
    }.onFailure { Log.w(TAG, "Could not inspect package process PID", it) }
        .getOrNull()

    private fun descendantPids(parentPid: Int): List<Int> {
        val result = mutableListOf<Int>()
        fun collect(pid: Int) {
            val children = runCatching {
                File("/proc/$pid/task/$pid/children").readText()
                    .trim().split(Regex("\\s+"))
                    .mapNotNull(String::toIntOrNull)
            }.getOrDefault(emptyList())
            children.forEach { child ->
                result += child
                collect(child)
            }
        }
        collect(parentPid)
        return result
    }

    private fun clearStalePackageLocks() {
        listOf(
            File(prefixDir, "var/lib/dpkg/lock"),
            File(prefixDir, "var/lib/dpkg/lock-frontend"),
            File(prefixDir, "var/cache/apt/archives/lock"),
            File(prefixDir, "var/lib/apt/lists/lock"),
        ).forEach { lock ->
            if (lock.exists() && !lock.delete()) {
                Log.w(TAG, "Could not remove stale package lock: ${lock.absolutePath}")
            }
        }
    }
}
