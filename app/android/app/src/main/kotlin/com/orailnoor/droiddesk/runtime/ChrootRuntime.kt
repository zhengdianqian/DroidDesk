package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import android.util.Base64
import java.io.File
import kotlin.concurrent.thread

/**
 * Real chroot-based Linux runtime for rooted Android devices.
 *
 * This runtime downloads a standard ARM64 Ubuntu rootfs, mounts the necessary
 * kernel filesystems via root access, and runs the desktop environment inside a
 * real chroot. X11 output is sent to the app's embedded LorieView X server
 * through a bind-mounted Unix socket.
 */
class ChrootRuntime(private val context: Context) {

    companion object {
        private const val TAG = "ChrootRuntime"
        private const val CHROOT_DE_MARKER = ".chroot_de_installed"

        // Ubuntu 24.04 ARM64 minimal rootfs
        const val ROOTFS_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"

        // DesktopActivity starts the chroot while MainActivity owns status and
        // stop controls, so the process handle must be shared app-wide.
        @Volatile private var sessionProcess: Process? = null
    }

    private val rootShell = RootShell(context)
    private val rootfsManager = RootfsManager(context)

    /** Localized text for install progress / log lines. */
    private fun t(zh: String, en: String): String = L10n.tr(context, zh, en)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")

    // ── Status ──

    fun hasRoot(): Boolean = rootShell.hasRoot()

    fun isRootfsReady(): Boolean = rootfsManager.isRootfsReady()

    fun isDesktopInstalled(): Boolean {
        return File(rootfsDir, CHROOT_DE_MARKER).exists() ||
                File(rootfsDir, "usr/bin/startxfce4").exists()
    }

    fun isRunning(): Boolean = sessionProcess?.isAlive == true

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun getRootfsSizeMB(): Long = rootfsManager.getRootfsSizeMB()

    fun readLinuxClipboard(): String? = runCatching {
        val encoded = rootShell.exec(
            "chroot ${rootfsDir.absolutePath} /bin/bash -c " +
                shellQuote("DISPLAY=:0 xclip -selection clipboard -o 2>/dev/null | base64 -w0"),
        ).trim()
        if (encoded.isEmpty()) "" else String(Base64.decode(encoded, Base64.DEFAULT))
    }.getOrNull()

    fun writeLinuxClipboard(value: String): Boolean = runCatching {
        val encoded = Base64.encodeToString(value.toByteArray(), Base64.NO_WRAP)
        rootShell.exec(
            "chroot ${rootfsDir.absolutePath} /bin/bash -c " +
                shellQuote("printf %s ${shellQuote(encoded)} | base64 -d | DISPLAY=:0 xclip -selection clipboard -in"),
        )
        true
    }.getOrDefault(false)

    fun getOptionalAppsStatus(): Map<String, Boolean> = mapOf(
        "firefox" to File(rootfsDir, "usr/bin/firefox").exists(),
        "code_oss" to (File(rootfsDir, "usr/bin/code").exists() || File(rootfsDir, "usr/bin/code-oss").exists()),
        "nodejs" to (File(rootfsDir, "usr/bin/node").exists() && File(rootfsDir, "usr/bin/npm").exists()),
        "imagemagick" to (File(rootfsDir, "usr/bin/convert").exists() || File(rootfsDir, "usr/bin/magick").exists()),
    )

    // ── Rootfs setup ──

    /**
     * Download the Ubuntu rootfs with progress callbacks.
     */
    fun downloadRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.downloadRootfs("ubuntu", onProgress)
    }

    /**
     * Extract the downloaded rootfs and configure it for chroot.
     */
    fun extractRootfs(onProgress: (Double, String) -> Unit) {
        rootfsManager.extractRootfs { progress, status ->
            onProgress(progress, status)
            if (progress == 1.0) {
                // Additional chroot-specific configuration
                configureChrootRootfs()
            }
        }
    }

    private fun configureChrootRootfs() {
        Log.i(TAG, "Applying chroot-specific rootfs configuration")

        // Ensure critical mount points exist
        listOf(
            "dev", "dev/pts", "dev/shm",
            "proc", "sys", "run",
            "tmp", "tmp/.X11-unix", "tmp/runtime-root",
            "root", "mnt/android", "mnt/sdcard"
        ).forEach {
            File(rootfsDir, it).mkdirs()
        }

        // Portable software-rendering profile. Android vendor GPU libraries do
        // not automatically become usable inside an Ubuntu chroot.
        File(rootfsDir, "etc/profile.d/droiddesk-ha.sh").apply {
            parentFile?.mkdirs()
            writeText(
                """
                #!/bin/bash
                # DroidDesk portable graphics environment
                export DISPLAY=:0
                export XDG_RUNTIME_DIR=/tmp/runtime-root
                export XDG_SESSION_TYPE=x11
                export XDG_DATA_DIRS=/usr/share:/usr/local/share
                export XDG_CONFIG_DIRS=/etc/xdg

                # Conservative Mesa fallback that works across GPU vendors
                export LIBGL_ALWAYS_SOFTWARE=true
                export GALLIUM_DRIVER=llvmpipe
                export MESA_LOADER_DRIVER_OVERRIDE=llvmpipe

                # Disable accessibility bus spam
                export NO_AT_BRIDGE=1
                export GTK_A11Y=none

                # Locale — Chinese by default, with an English C.UTF-8 fallback for
                # tools that reject a not-yet-generated locale during first boot.
                export LANG=zh_CN.UTF-8
                export LANGUAGE=zh_CN:zh
                export LC_ALL=zh_CN.UTF-8
                export LC_CTYPE=zh_CN.UTF-8

                # Input method bridge (IBus) so GTK/Qt apps can type Chinese
                export GTK_IM_MODULE=ibus
                export QT_IM_MODULE=ibus
                export XMODIFIERS=@im=ibus
                export DROIDDESK_BRIDGE=/tmp/droiddesk-bridge

                # Prompt
                export PS1='\[\033[01;32m\]droiddesk\[\033[00m\]:\[\033[01;34m\]\w\[\033[00m\]\$ '
                """.trimIndent()
            )
        }




        // Make sure apt works without _apt sandbox user
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/apt/apt.conf.d/99-droiddesk-reliability").writeText(
            "Acquire::Retries \"3\";\n" +
                    "Acquire::http::Timeout \"30\";\n" +
                    "Acquire::https::Timeout \"30\";\n" +
                    "Acquire::ForceIPv4 \"true\";\n" +
                    "DPkg::Lock::Timeout \"60\";\n"
        )

        Log.i(TAG, "Chroot rootfs configuration complete")
    }

    /**
     * Install the desktop environment and GPU drivers inside the chroot.
     */
    fun installDesktopEnvironment(
        desktopEnv: String = "xfce4",
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {}
    ) {
        if (!hasRoot()) {
            onProgress(-1.0, "Root access required for chroot mode")
            return
        }
        if (!isRootfsReady()) {
            onProgress(-1.0, "Rootfs not ready. Download and extract first.")
            return
        }

        thread(name = "chroot-de-install") {
            try {
                onProgress(0.0, t("正在挂载 rootfs…", "Mounting rootfs..."))
                ensureMounts()

                onProgress(0.05, t("正在修复 Android 网络权限…", "Fixing Android network permissions..."))
                execChroot("groupadd -g 3003 inet 2>/dev/null || true; usermod -a -G inet _apt 2>/dev/null || true; usermod -a -G inet root 2>/dev/null || true", onLog)

                onProgress(0.05, t("正在修复中断的软件包…", "Repairing interrupted packages..."))
                execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)

                onProgress(0.05, t("正在探测最快的 APT 镜像…", "Selecting fastest APT mirror..."))
                val codename = MirrorSelector.detectCodename(rootfsDir)
                val probed = MirrorSelector.probeReachable(codename)
                // A failed probe means the network is unreachable, not that the mirror
                // is gone. Try every candidate in preference order and let apt-get
                // update be the judge.
                val mirrors = MirrorSelector.orderedCandidates(codename)
                if (probed.isEmpty()) {
                    onLog(t("镜像探测全部失败（网络不可达），将按优先级尝试…\n", "Mirror probes failed (network unreachable); trying candidates in order...\n"))
                }
                var aptUpdated = execChroot("DEBIAN_FRONTEND=noninteractive apt-get update", onLog) == 0
                if (!aptUpdated) {
                    for (mirror in mirrors) {
                        onLog(t("正在使用 APT 镜像 $mirror（$codename，arm64）\n", "Using APT mirror $mirror ($codename, arm64)\n"))
                        MirrorSelector.writeSources(rootfsDir, mirror, codename)
                        if (execChroot("DEBIAN_FRONTEND=noninteractive apt-get update", onLog) == 0) {
                            aptUpdated = true
                            break
                        }
                        onLog(t("通过 $mirror 更新索引失败，正在尝试下一个镜像…\n", "apt-get update failed through $mirror, trying next mirror...\n"))
                        // A DNS failure inside the chroot looks exactly like this;
                        // refresh resolv.conf and re-pin mirror IPs before retrying.
                        try {
                            DnsConfig.applyTo(rootfsDir, rootShell, L10n.isChinese(context)) { onLog(it) }
                        } catch (e: Exception) {
                            Log.w(TAG, "DNS refresh during failover failed: ${e.message}")
                        }
                    }
                }
                if (!aptUpdated) {
                    throw IllegalStateException(t("软件包索引更新失败：网络不可达或所有镜像均不可用", "Package index update failed: network unreachable or every mirror is down"))
                }

                onProgress(0.1, t("正在安装 Android 内核兼容组件…", "Installing Android kernel compatibility..."))
                if (!ensureCloseRangeCompatibility(onLog)) {
                    throw IllegalStateException(t("Android 内核兼容组件安装失败", "Android kernel compatibility setup failed"))
                }

                onProgress(0.15, t("正在安装基础工具…", "Installing core tools..."))
                if (!isPackageInstalled("x11-utils")) {
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "locales ca-certificates wget curl dbus-x11 xclip x11-utils",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        if (!isPackageInstalled("x11-utils")) throw IllegalStateException(t("基础工具安装失败", "Core package installation failed"))
                    }
                }

                onProgress(0.2, t("正在安装 Mesa GPU 驱动…", "Installing Mesa GPU drivers..."))
                if (!isPackageInstalled("mesa-vulkan-drivers")) {
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "mesa-vulkan-drivers mesa-opencl-icd libgl1-mesa-dri libglx-mesa0 libgl1 vulkan-tools",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        Log.w(TAG, "Mesa packages unavailable; desktop will use available software rendering")
                    }
                }

                onProgress(0.4, t("正在安装桌面环境…", "Installing desktop environment..."))
                val checkPkg = when (desktopEnv) {
                    "lxqt" -> "lxqt"
                    "mate" -> "mate-desktop-environment"
                    "kde" -> "plasma-desktop"
                    else -> "xfce4"
                }
                if (!isPackageInstalled(checkPkg)) {
                    val dePackages = when (desktopEnv) {
                        "lxqt" -> "lxqt qterminal pcmanfm-qt featherpad"
                        "mate" -> "mate-desktop-environment mate-terminal"
                        "kde" -> "plasma-desktop konsole dolphin"
                        else -> "xfce4 xfce4-terminal xfce4-whiskermenu-plugin thunar mousepad devilspie2"
                    }
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends $dePackages",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        if (!isPackageInstalled(checkPkg)) throw IllegalStateException(t("桌面环境安装失败", "Desktop package installation failed"))
                    }
                }

                onProgress(0.6, t("正在安装高清图标主题…", "Installing HiDPI icon theme..."))
                if (!isPackageInstalled("papirus-icon-theme")) {
                    if (execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "papirus-icon-theme adwaita-icon-theme-full fonts-noto-core",
                        onLog
                    ) != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        Log.w(TAG, "HiDPI icon theme install failed; will use default icons")
                    }
                }

                onProgress(0.7, t("正在配置中文环境与输入法…", "Configuring Chinese locale and input method..."))
                ensureChineseEnvironment(onLog, onProgress)

                onProgress(0.8, t("正在安装桌面常用软件…", "Installing Desktop Essentials tools..."))
                if (!isPackageInstalled("htop")) {
                    val essentialsExit = execChroot(
                        "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                                "git nano htop wget curl python3 python3-pip openssh-client",
                        onLog
                    )
                    if (essentialsExit != 0) {
                        execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                        if (!isPackageInstalled("htop")) throw IllegalStateException(t("桌面常用软件包安装失败", "Desktop Essentials package installation failed"))
                    }
                }

                onProgress(0.9, t("正在清理缓存…", "Cleaning up..."))
                execChroot("apt-get clean", onLog)

                File(rootfsDir, CHROOT_DE_MARKER).writeText("$desktopEnv\n")
                onProgress(1.0, t("$desktopEnv 已在 chroot 中安装完成", "$desktopEnv installed in chroot"))
                Log.i(TAG, "Desktop environment installation complete")
            } catch (e: Exception) {
                Log.e(TAG, "DE install failed", e)
                onProgress(-1.0, t("安装失败：${e.message}", "Installation failed: ${e.message}"))
            }
        }
    }

    fun installOptionalApp(
        appId: String,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot() || !isDesktopInstalled()) return false
        if (getOptionalAppsStatus()[appId] == true) {
            onProgress(1.0, "Already installed")
            return true
        }

        return try {
            ensureMounts()
            onProgress(0.05, "Repairing interrupted packages...")
            execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)

            val command = when (appId) {
                "firefox" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get install -y --no-install-recommends ca-certificates wget gpg
                    install -d -m 0755 /etc/apt/keyrings
                    wget -q https://packages.mozilla.org/apt/repo-signing-key.gpg -O /etc/apt/keyrings/packages.mozilla.org.asc
                    echo 'deb [signed-by=/etc/apt/keyrings/packages.mozilla.org.asc] https://packages.mozilla.org/apt mozilla main' > /etc/apt/sources.list.d/mozilla.list
                    printf 'Package: *\nPin: origin packages.mozilla.org\nPin-Priority: 1000\n' > /etc/apt/preferences.d/mozilla
                    apt-get update -y
                    apt-get install -y --no-install-recommends firefox
                """.trimIndent()
                "code_oss" -> """
                    set -e
                    export DEBIAN_FRONTEND=noninteractive
                    apt-get install -y --no-install-recommends ca-certificates wget gpg apt-transport-https
                    install -d -m 0755 /etc/apt/keyrings
                    wget -qO- https://packages.microsoft.com/keys/microsoft.asc | gpg --dearmor -o /etc/apt/keyrings/packages.microsoft.gpg
                    echo 'deb [arch=arm64 signed-by=/etc/apt/keyrings/packages.microsoft.gpg] https://packages.microsoft.com/repos/code stable main' > /etc/apt/sources.list.d/vscode.list
                    apt-get update -y
                    apt-get install -y --no-install-recommends code
                """.trimIndent()
                "nodejs" -> "DEBIAN_FRONTEND=noninteractive apt-get update -y && apt-get install -y --no-install-recommends nodejs npm"
                "imagemagick" -> "DEBIAN_FRONTEND=noninteractive apt-get update -y && apt-get install -y --no-install-recommends imagemagick"
                else -> return false
            }

            onProgress(0.25, "Installing optional application...")
            val exitCode = execChroot(command, onLog)
            if (exitCode != 0) throw IllegalStateException("Package manager exited with code $exitCode")
            onProgress(1.0, "Installation complete")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Optional app installation failed: $appId", error)
            onProgress(-1.0, "Installation failed: ${error.message}")
            false
        }
    }

    // ── Session management ──

    /**
     * Start the chrooted desktop session.
     * The caller should ensure the X11 socket directory is mounted before this.
     */
    fun startSession(
        desktopEnv: String = "xfce4",
        width: Int = 1920,
        height: Int = 1080,
        onPhase: (String) -> Unit = {},
    ) {
        if (!hasRoot()) {
            throw IllegalStateException("未获得 root 权限，无法启动 Ubuntu 桌面")
        }
        if (!isRootfsReady()) {
            throw IllegalStateException("Ubuntu 镜像尚未安装完成，请先在设置中安装/修复 Ubuntu")
        }
        if (isRunning()) {
            Log.w(TAG, "Chroot session already running")
            return
        }

        onPhase("清理上次会话残留")
        DesktopLog.append(context, TAG, "chroot session start requested (${width}x$height)")

        onPhase("检查兼容库")
        // Existing rootfs installations predate the compatibility library.
        // Build it once before starting XFCE so they do not need a reinstall.
        if (!ensureCloseRangeCompatibility()) {
            throw IllegalStateException("兼容库初始化失败（close_range），请重试")
        }

        onPhase("应用桌面配置")

        // The profile is staged in app storage (see installChrootXfceMobileProfile), so its
        // version marker lives there rather than in the root-owned chroot home.
        val uiScale = DisplayProfile.uiScale(context)
        val powerMode = DisplayProfile.powerMode(context)
        val upgradingXfceProfile = desktopEnv == "xfce4" &&
            XfceMobileProfile.needsInstall(
                File(context.cacheDir, "droiddesk-xfce-profile/root"),
                uiScale,
                powerMode,
            )
        if (desktopEnv == "xfce4") installChrootXfceMobileProfile(uiScale, powerMode)
        if (desktopEnv == "xfce4") {
            runCatching {
                AndroidAppBridge.syncLaunchers(
                    context = context,
                    homeDir = File(rootfsDir, "root"),
                    python = File(rootfsDir, "usr/bin/python3"),
                    sessionRoot = rootfsDir,
                )
            }.onFailure { Log.w(TAG, "Android desktop launchers could not be refreshed", it) }
        }

        onPhase("挂载运行环境")
        ensureMounts()
        // Only after /proc is mounted inside the chroot can the previous session's
        // processes be seen and killed. Running this before the mounts (as the
        // original order did) left orphans behind, and the new session then died
        // with "another session manager is already running".
        cleanupStaleSession()
        bindX11Socket()
        // The X connection fd reaches LorieView before libXlorie has created the
        // socket file. Starting the guest in that window makes every X client die
        // silently, so the socket (not a fixed sleep) is the gate for the desktop.
        if (!waitForX11Socket()) {
            throw IllegalStateException(
                "X 服务器未就绪：${x11HostDir.absolutePath}/X0 在 8 秒内未出现，请重试或检查残留 X 进程",
            )
        }

        onPhase("配置中文环境")
        // Idempotent: existing images never got 1.4.0's locale/font/IBus setup.
        runCatching { applyChineseEnvironment { msg -> Log.i(TAG, msg.trim()) } }
            .onFailure { Log.w(TAG, "Chinese environment step failed", it) }

        if (!File(rootfsDir, "usr/bin/xclip").canExecute()) {
            Log.i(TAG, "Installing the chroot clipboard compatibility helper")
            val installed = execChroot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends xclip",
                {},
            ) == 0
            if (!installed) Log.w(TAG, "Clipboard helper unavailable; clipboard sync will retry next session")
        }

        // Older installs predate the auto-maximize helper; try once while upgrading the profile.
        if (upgradingXfceProfile && !File(rootfsDir, "usr/bin/devilspie2").canExecute()) {
            Log.i(TAG, "Installing the window fitting helper")
            val installed = execChroot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends devilspie2",
                {},
            ) == 0
            if (!installed) Log.w(TAG, "Window fitting helper unavailable; windows will keep their default size")
        }

        val deBin = when (desktopEnv) {
            "lxqt" -> "lxqt-session"
            "mate" -> "mate-session"
            "kde" -> "startplasma-x11"
            "xfce4" -> "startxfce4"
            else -> desktopEnv
        }

        val xftDpi = DisplayProfile.xftDpi(uiScale)
        val cursorSize = (32.0 * XfceMobileProfile.scaleFactor(uiScale)).toInt().coerceAtLeast(24)
        // Cheap (no apt): keeps the desktop store, .deb handler and IME watcher present
        // even on rootfs images that were installed before this version.
        ensureGuestScripts()
        applyHostProxyConfig()
        exportDesktopShortcuts()

        onPhase("启动桌面会话")

        val runScript = """
            # Standard FHS PATH (inherited Android PATH lacks /usr/bin)
            export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

            # Reset environment variables leaked from Android app
            export TMPDIR=/tmp
            export HOME=/root
            export PREFIX=/usr
            export LANG=zh_CN.UTF-8
            export LC_ALL=zh_CN.UTF-8
            export LANGUAGE=zh_CN:zh
            # This is a normal Ubuntu userspace, not the relocated native
            # Termux runtime.  Load only the glibc compatibility shim built
            # for this rooted Ubuntu filesystem.
            export LD_PRELOAD=/usr/local/lib/libdroiddesk-close-range.so

            # Source DroidDesk environment
            . /etc/profile.d/droiddesk-ha.sh 2>/dev/null || true

            # The chroot shares the device network stack. When the host-wide proxy is
            # actually listening it also resolves names, so expose it to apt/curl/wget
            # launched from the desktop. The config file is only written while the
            # probe succeeds, so a dead proxy can never shadow a direct connection.
            if [ -f /etc/apt/apt.conf.d/99droiddesk-proxy ]; then
                export http_proxy=http://127.0.0.1:2080
                export https_proxy=http://127.0.0.1:2080
                export HTTP_PROXY=http://127.0.0.1:2080
                export HTTPS_PROXY=http://127.0.0.1:2080
                export no_proxy=127.0.0.1,localhost,::1
                export NO_PROXY=127.0.0.1,localhost,::1
            fi

            # Make sure X11 socket dir exists in case bind mount was late
            mkdir -p /tmp/.X11-unix

            # ── Wait for X server to be ready with the correct resolution ──
            # The LorieView X server may still be initializing when the chroot
            # process starts. Poll until the socket exists and xdpyinfo reports
            # the phone's scaled resolution instead of the 1280x1024 fallback.
            echo "DIAG: Waiting for X server on DISPLAY=:0 ..."
            for attempt in ${'$'}(seq 1 50); do
                if [ -e /tmp/.X11-unix/X0 ]; then
                    current_res=${'$'}(xdpyinfo 2>/dev/null | grep 'dimensions:' | awk '{print ${'$'}2}')
                    if [ -n "${'$'}current_res" ] && [ "${'$'}current_res" != "1280x1024" ]; then
                        echo "DIAG: X server ready at ${'$'}current_res"
                        break
                    fi
                fi
                sleep 0.1
            done

            echo "DIAG: Starting $desktopEnv in chroot on DISPLAY=:0 ..."

            # ── UI scale ──
            # The framebuffer is physical-pixel 1:1; readability comes from Xft DPI
            # (integer UI scale), never from stretching the final image.
            cat > ~/.Xresources << 'XRES'
Xft.dpi: $xftDpi
Xft.antialias: 1
Xft.hinting: 1
Xft.hintstyle: hintslight
Xft.rgba: rgb
Xcursor.size: $cursorSize
XRES
            xrdb -merge ~/.Xresources 2>/dev/null || true
            xsetroot -cursor_name left_ptr 2>/dev/null || true

            # Force xrandr to refresh screen geometry so xfwm4 knows the
            # correct resolution for maximize calculations
            xrandr --auto 2>/dev/null || true

            # Disable GTK client-side decorations so all apps use xfwm4's
            # title bar (whose maximize button actually works on Xwayland)
            export GTK_CSD=0

            # Create a wrapper that starts the DE and applies settings after
            # xfconfd is running on the same D-Bus session
            DE_BIN="$deBin"
            cat > /tmp/droiddesk-session.sh << SESSEOF
#!/bin/bash
# Apply UI scale, start IBus, then the desktop. Runs on the session bus.
(
    sleep 5
    if command -v xfconf-query >/dev/null 2>&1; then
        xfconf-query -c xsettings -p /Xft/DPI -s $xftDpi -n -t int 2>/dev/null || true
        xfconf-query -c xsettings -p /Net/IconThemeName -s Papirus -n -t string 2>/dev/null || true
        xfconf-query -c xsettings -p /Net/CursorSize -s $cursorSize -n -t int 2>/dev/null || true
        xfconf-query -c xsettings -p /Gtk/CursorThemeSize -s $cursorSize -n -t int 2>/dev/null || true
    fi
    # Refresh xrandr again after XFCE panels have loaded
    xrandr --auto 2>/dev/null || true
) &
if command -v ibus-daemon >/dev/null 2>&1; then
    ibus-daemon -drx >/tmp/droiddesk-ibus.log 2>&1 &
    sleep 1
    ibus engine libpinyin >/dev/null 2>&1 || true
fi
# The focus watcher only needs dbus-monitor; start it even when IBus is absent so an
# older image still reports editable-focus changes (it keeps writing 0 otherwise).
command -v droiddesk-ime-watch >/dev/null 2>&1 && droiddesk-ime-watch >/dev/null 2>&1 &
# xfwm4 occasionally dies at cold start with a Gdk-ERROR. Without a window
# manager there is no keyboard focus, so the IME bridge never sees an editable
# widget and the keyboard stops popping up. Check once (bounded loop, ~15 s)
# and restart xfwm4 a single time inside this session bus; no persistent
# watchdog is installed, by design. No shell variables are used here because
# this file is written through an expanding heredoc.
(
    for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21 22 23 24 25 26 27 28 29 30; do
        sleep 0.5
        if command -v xprop >/dev/null 2>&1; then
            xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' && exit 0
        else
            ps -A -o comm= 2>/dev/null | grep -qx xfwm4 && exit 0
        fi
    done
    echo "DIAG: xfwm4 not alive after 15s, restarting once"
    (
        xfwm4 --replace --sm-client-disable 2>&1 | tee -a /tmp/droiddesk-xfwm4.log
    ) &
    sleep 3
    if command -v xprop >/dev/null 2>&1; then
        xprop -root _NET_SUPPORTING_WM_CHECK 2>/dev/null | grep -q 'window id' \
            && echo "DIAG: xfwm4 recovered" \
            || echo "DIAG: xfwm4 restart failed, see /tmp/droiddesk-xfwm4.log"
    else
        ps -A -o comm= 2>/dev/null | grep -qx xfwm4 \
            && echo "DIAG: xfwm4 recovered" \
            || echo "DIAG: xfwm4 restart failed, see /tmp/droiddesk-xfwm4.log"
    fi
) &
exec ${'$'}DE_BIN
SESSEOF
            chmod +x /tmp/droiddesk-session.sh

            # dbus-run-session owns the one session bus for Xfce
            exec dbus-run-session -- /tmp/droiddesk-session.sh
        """.trimIndent()

        Log.i(TAG, "Starting chroot session for $desktopEnv")

        // Launch via ProcessBuilder through su so we get a Process handle we can monitor.
        val su = rootShell.findSuPath()
            ?: throw IllegalStateException("未找到 su，无法以 root 启动桌面")
        val fullCommand = "chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(runScript)}"
        val startedSession = ProcessBuilder(su, "-c", fullCommand)
            .redirectErrorStream(true)
            .start()
        sessionProcess = startedSession
        DesktopSessionState.markStarted("chroot", desktopEnv)
        DesktopLog.append(context, TAG, "chroot session started ($desktopEnv)")

        Thread {
            try {
                val reader = startedSession.inputStream.bufferedReader()
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    val chunk = String(buffer, 0, charsRead)
                    Log.d(TAG, "CHROOT DESKTOP: $chunk")
                    DesktopLog.append(context, "desktop", chunk)
                }
            } catch (error: java.io.IOException) {
                Log.d(TAG, "Chroot desktop output stream closed")
            } finally {
                val exit = runCatching { startedSession.exitValue() }.getOrDefault(-1)
                DesktopLog.append(context, TAG, "chroot session output closed (exit=$exit)")
            }
        }.start()
    }

    /** Wait until the chroot desktop shell has created its visible components. */
    fun waitForDesktopReady(desktopEnv: String = "xfce4", timeoutMs: Long = 45_000): Boolean {
        val (required, alternatives) = when (desktopEnv) {
            "lxqt" -> listOf("lxqt-panel") to listOf("lxqt-session")
            "mate" -> listOf("mate-panel") to listOf("mate-session")
            "kde" -> listOf("plasmashell") to emptyList()
            else -> listOf("xfce4-panel") to listOf("xfdesktop", "xfwm4")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // A session that already died can never paint a first frame; fail now
            // instead of burning the whole 45 s timeout and reporting a crash that
            // did not happen.
            sessionProcess?.let { session ->
                if (!session.isAlive) {
                    val exit = runCatching { session.exitValue() }.getOrNull()
                    Log.w(TAG, "chroot session exited (code=$exit) before $desktopEnv was ready")
                    return false
                }
            }
            val names = runCatching {
                val command = "${getPreloadEnv()}export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
                    "ps -A -o comm="
                rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(command)}")
                    .lines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toSet()
            }.getOrDefault(emptySet())
            // xfdesktop can die while xfce4-panel and xfwm4 keep the desktop usable; a missing
            // wallpaper must not turn into a 45 s "starting" screen on every launch.
            if (required.all { it in names } && (alternatives.isEmpty() || alternatives.any { it in names })) {
                Thread.sleep(650)
                return true
            }
            Thread.sleep(400)
        }
        Log.w(TAG, "Timed out waiting for chroot $desktopEnv to paint its first desktop frame")
        return false
    }

    /**
     * Stage the profile as the app user, then copy it into the root-owned
     * Ubuntu filesystem through su.  apt makes /root and /usr root-owned, so
     * writing the profile directly from Kotlin fails on real rooted devices.
     */
    private fun installChrootXfceMobileProfile(uiScale: Double = 1.5, powerMode: String = "standard") {
        val stagingDir = File(context.cacheDir, "droiddesk-xfce-profile")
        val stagingHome = File(stagingDir, "root")
        val stagingWallpaper = File(stagingDir, "ubuntu-touch.jpg")
        val sessionWallpaper = "/usr/share/backgrounds/droiddesk/ubuntu-touch.jpg"

        val staged = XfceMobileProfile.install(
            context = context,
            homeDir = stagingHome,
            wallpaperFile = stagingWallpaper,
            wallpaperPathInSession = sessionWallpaper,
            uiScale = uiScale,
            powerMode = powerMode,
        )
        if (!staged) {
            Log.w(TAG, "Could not stage the XFCE mobile profile; starting with stock XFCE")
            return
        }

        val chrootHome = File(rootfsDir, "root")
        val chrootWallpaper = File(rootfsDir, sessionWallpaper.removePrefix("/"))
        val command = """
            set -e
            mkdir -p ${shellQuote(chrootHome.absolutePath)}
            mkdir -p ${shellQuote(chrootWallpaper.parentFile!!.absolutePath)}
            cp -f ${shellQuote(stagingWallpaper.absolutePath)} ${shellQuote(chrootWallpaper.absolutePath)}
            chmod 0644 ${shellQuote(chrootWallpaper.absolutePath)}
            cp -R ${shellQuote(File(stagingHome, ".config").absolutePath)} ${shellQuote(chrootHome.absolutePath)}
            chown -R 0:0 ${shellQuote(File(chrootHome, ".config").absolutePath)}
        """.trimIndent()

        try {
            rootShell.exec(command)
            if (!chrootWallpaper.exists()) {
                Log.w(TAG, "XFCE wallpaper was not copied into the chroot")
            }
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install XFCE mobile profile into chroot", error)
        }
    }

    /**
     * Android 5.4-derived kernels reject Ubuntu glibc's close_range() call
     * with EINVAL. GLib then cannot spawn any XFCE process. This tiny
     * glibc-side preload returns ENOSYS instead, so GLib uses its own safe
     * file-descriptor-walk fallback. It is only used inside the rooted Ubuntu
     * chroot; Termux's Android-native runtime is unaffected.
     */
    private fun ensureCloseRangeCompatibility(onLog: (String) -> Unit = {}): Boolean {
        val target = File(rootfsDir, "usr/local/lib/libdroiddesk-close-range.so")
        if (target.exists()) return true

        return try {
            val stagingDir = File(context.cacheDir, "droiddesk-close-range").apply { mkdirs() }
            val stagedSource = File(stagingDir, "close_range_compat.c")
            context.assets.open("droiddesk/close_range_compat.c").use { input ->
                stagedSource.outputStream().use(input::copyTo)
            }

            val sourceInRootfs = File(rootfsDir, "usr/local/src/droiddesk/close_range_compat.c")
            rootShell.exec(
                "mkdir -p ${shellQuote(sourceInRootfs.parentFile!!.absolutePath)} && " +
                        "cp -f ${shellQuote(stagedSource.absolutePath)} ${shellQuote(sourceInRootfs.absolutePath)}"
            )

            onLog(t("正在安装 rooted 桌面兼容组件…\n", "Installing rooted-desktop compatibility support...\n"))
            var gccInstalled = execChroot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gcc libc6-dev",
                onLog,
            ) == 0
            if (!gccInstalled) {
                // One retry for transient mirror/network hiccups.
                onLog(t("gcc 安装失败，正在重试一次…\n", "gcc install failed, retrying once...\n"))
                execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                gccInstalled = execChroot(
                    "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gcc libc6-dev",
                    onLog,
                ) == 0
            }
            if (!gccInstalled) return false

            val buildCommand = """
                set -e
                mkdir -p /usr/local/lib /tmp/droiddesk-close-range-build
                printf 'GLIBC_2.34 { global: close_range; };\n' > /tmp/droiddesk-close-range-build/version.map
                gcc -shared -fPIC -O2 -Wl,--version-script=/tmp/droiddesk-close-range-build/version.map -o /usr/local/lib/libdroiddesk-close-range.so /usr/local/src/droiddesk/close_range_compat.c
                chmod 0644 /usr/local/lib/libdroiddesk-close-range.so
                rm -f /tmp/droiddesk-close-range-build/version.map
                rmdir /tmp/droiddesk-close-range-build
            """.trimIndent()
            val built = execChroot(buildCommand, onLog) == 0
            if (built) Log.i(TAG, "Installed rooted close_range compatibility library")
            built
        } catch (error: Exception) {
            Log.e(TAG, "Failed to build rooted close_range compatibility library", error)
            false
        }
    }

    /**
     * Stop the chroot session and unmount bind mounts.
     */
    fun stopSession() {
        Log.i(TAG, "Stopping chroot session...")
        sessionProcess?.let {
            try {
                it.destroyForcibly()
                it.waitFor()
            } catch (e: Exception) {
                Log.w(TAG, "Error stopping session: ${e.message}")
            }
        }
        sessionProcess = null
        DesktopSessionState.markStopped()
        DesktopLog.append(context, TAG, "chroot session stopped by user")
        // destroyForcibly() only kills the session leader; xfce4-session/panel/xfwm4
        // keep running and own the display socket and the chroot mounts. Unmounting
        // before they exit detaches /tmp under live processes, and the next launch
        // then dies with "another session manager is already running".
        killGuestSession(5_000)
        unmountAll()
        Log.i(TAG, "Chroot session stopped")
    }

    /** Guest processes that outlive the session leader when the session is stopped. */
    private val sessionProcessNames = listOf(
        "xfce4-session", "xfce4-panel", "xfdesktop", "xfwm4", "xfsettingsd", "xfconfd",
        "xfce4-power-manager", "xfce4-notifyd", "startxfce4", "dbus-run-session",
        "startplasma-x11", "plasmashell", "kwin_x11", "ksmserver", "klauncher",
    )

    private fun guestProcessNames(): Set<String> = runCatching {
        rootShell.exec(
            "chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote("ps -A -o comm=")}",
        ).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    }.getOrDefault(emptySet())

    private fun guestSessionAlive(): Boolean {
        val names = guestProcessNames()
        return sessionProcessNames.any { it in names }
    }

    /** Terminate the guest session, then wait (bounded) until it is really gone. */
    private fun killGuestSession(timeoutMs: Long) {
        if (!hasRoot() || !isRootfsReady()) return
        runCatching {
            execChroot(sessionProcessNames.joinToString("; ") { "pkill -9 -x $it 2>/dev/null" } + "; true")
        }
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!guestSessionAlive()) return
            Thread.sleep(100)
        }
        Log.w(TAG, "Guest session still alive after ${timeoutMs}ms; unmount may stay busy")
    }

    // ── Mount handling ──

    /**
     * Ensure /dev, /proc, /sys, /dev/pts and tmpfs mounts are active.
     */
    fun ensureMounts() {
        if (!hasRoot()) return

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "-t devpts devpts") { isMounted("dev/pts") }
        mountIfNeeded("/dev/shm", "-t tmpfs tmpfs") { isMounted("dev/shm") }
        mountIfNeeded("/proc", "--bind /proc") { isMounted("proc") }
        mountIfNeeded("/sys", "--bind /sys") { isMounted("sys") }
        mountIfNeeded("/run", "-t tmpfs tmpfs") { isMounted("run") }
        mountIfNeeded("/tmp", "-t tmpfs tmpfs") { isMounted("tmp") }

        // Fix missing symlinks in Android's /dev for chroot compatibility
        val devPath = File(rootfsDir, "dev").absolutePath
        rootShell.exec("ln -snf /proc/self/fd $devPath/fd")
        rootShell.exec("ln -snf /proc/self/fd/0 $devPath/stdin")
        rootShell.exec("ln -snf /proc/self/fd/1 $devPath/stdout")
        rootShell.exec("ln -snf /proc/self/fd/2 $devPath/stderr")

        // Create runtime dirs after tmpfs is mounted
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /root")

        // Small host↔guest mailbox (IME focus flag, diagnostics). The guest scripts
        // write here; the app reads the same files without needing root.
        val bridgeHost = File(context.filesDir, "guest-bridge").apply { mkdirs() }
        val bridgeTarget = File(rootfsDir, "tmp/droiddesk-bridge")
        if (!mounts.any { it.contains(" on ${bridgeTarget.absolutePath} ") }) {
            runCatching {
                rootShell.exec(
                    "mkdir -p ${bridgeTarget.absolutePath} && " +
                        "mount --bind ${bridgeHost.absolutePath} ${bridgeTarget.absolutePath}",
                )
            }.onFailure { Log.w(TAG, "Guest bridge mount failed: ${it.message}") }
        }

        // Refresh DNS — the phone's network may have changed since the
        // rootfs was first extracted. Use the resolvers Android is actually
        // using (plus domestic fallbacks and mirror IP pins) instead of a
        // hardcoded 8.8.8.8, which is unreachable on many networks.
        try {
            DnsConfig.applyTo(rootfsDir, rootShell)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh chroot DNS: ${e.message}")
            DnsConfig.writeResolvConf(rootfsDir, DnsConfig.FALLBACK_DNS)
            DesktopLog.append(context, "MOUNT", "DNS refresh failed: ${e.message}")
        }
        DesktopLog.append(context, "MOUNT", "mounts ready (rootfs=${rootfsDir.name})")
    }

    private fun mountIfNeeded(relative: String, mountArgs: String, alreadyMounted: () -> Boolean) {
        if (alreadyMounted()) return
        val target = File(rootfsDir, relative).absolutePath
        try {
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
            Log.i(TAG, "Mounted $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount $target: ${e.message}")
        }
    }

    /**
     * Bind-mount the host X11 socket directory into the chroot.
     */
    fun bindX11Socket() {
        if (!hasRoot()) return
        x11HostDir.mkdirs()
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        val hostX11 = x11HostDir.absolutePath

        // If already mounted, leave it
        val mounts = rootShell.exec("mount").lines()
        if (mounts.any { it.contains(" on $chrootX11 ") }) return

        rootShell.exec("mkdir -p $chrootX11 && mount --bind $hostX11 $chrootX11")
        Log.i(TAG, "Bound X11 socket: $hostX11 -> $chrootX11")
    }

    /**
     * Event-driven gate for the display link: LorieView receives the X connection fd
     * before libXlorie creates the socket file, and a guest started in that window
     * cannot connect. Poll the socket instead of sleeping a fixed time.
     */
    private fun waitForX11Socket(timeoutMs: Long = 8_000): Boolean {
        val socket = File(x11HostDir, "X0")
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (socket.exists() && socket.canRead()) return true
            Thread.sleep(100)
        }
        return socket.exists()
    }

    /**
     * Unmount all DroidDesk-related mounts.
     */
    fun unmountAll() {
        if (!hasRoot()) return
        val mounts = rootShell.exec("mount").lines()
        // Deepest mount first. The old order unmounted /tmp before /tmp/.X11-unix:
        // a lazy detach of /tmp takes the socket bind with it, so the bind survived
        // as a detached mount and the next session never saw a clean display socket.
        val targets = listOf(
            File(rootfsDir, "tmp/.X11-unix").absolutePath,
            File(rootfsDir, "tmp/droiddesk-bridge").absolutePath,
            File(rootfsDir, "dev/pts").absolutePath,
            File(rootfsDir, "dev/shm").absolutePath,
            File(rootfsDir, "dev").absolutePath,
            File(rootfsDir, "proc").absolutePath,
            File(rootfsDir, "sys").absolutePath,
            File(rootfsDir, "run").absolutePath,
            File(rootfsDir, "tmp").absolutePath
        )
        targets.forEach { target ->
            if (!mounts.any { it.contains(" on $target ") }) return@forEach
            try {
                // A plain umount first: it fails loudly on a busy mount instead of
                // silently detaching a parent that still has mounted children.
                rootShell.exec("umount $target 2>/dev/null || umount -l $target 2>/dev/null || true")
                Log.i(TAG, "Unmounted $target")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unmount $target: ${e.message}")
            }
        }
        val left = rootShell.exec("mount").lines().count { it.contains(" on ${rootfsDir.absolutePath}") }
        Log.i(TAG, "Chroot mounts remaining after unmount: $left")
    }

    // ── Guest Chinese environment, desktop store, and package management ──

    /**
     * Kills desktop processes a previous chroot session left behind.
     *
     * A surviving xfce4-session makes the next `startxfce4` exit immediately ("another
     * session manager is already running"), which is why launches intermittently ended on
     * the loading screen with no desktop at all.
     */
    fun cleanupStaleSession() {
        if (!hasRoot()) return
        runCatching {
            rootShell.exec("pkill -9 -f 'android_intent_pipe' >/dev/null 2>&1 || true")
            if (isRootfsReady()) {
                execChroot(
                    "pkill -9 -x xfce4-session; pkill -9 -x xfce4-panel; pkill -9 -x xfdesktop; " +
                        "pkill -9 -x xfwm4; pkill -9 -x xfsettingsd; pkill -9 -x xfce4-power-manager; " +
                        "pkill -9 -x xfce4-notifyd; pkill -9 -x xfconfd; pkill -9 -x pulseaudio; " +
                        "pkill -9 -x startxfce4; pkill -9 -x dbus-run-session; " +
                        "rm -f /tmp/.xfsm-ICE-* /tmp/dbus-session 2>/dev/null; true",
                )
                // startxfce4 aborts with "another session manager is already running"
                // while the previous one is still exiting, so wait for the kill to
                // really land before the new session is launched.
                val deadline = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < deadline && guestSessionAlive()) {
                    Thread.sleep(100)
                }
            }
            Log.i(TAG, "Stale chroot session cleaned")
        }.onFailure { Log.w(TAG, "Stale chroot cleanup failed: ${it.message}") }
    }

    /**
     * Applies the Chinese desktop defaults to an *already installed* rootfs.
     *
     * 1.4.0 only configured this inside the install flow, so images installed by earlier
     * releases kept an English XFCE. The heavy part (apt + locale-gen) runs once and is
     * guarded by a marker; every launch only refreshes the two tiny locale files.
     */
    fun applyChineseEnvironment(onLog: (String) -> Unit = {}): Boolean {
        if (!hasRoot() || !isRootfsReady()) return false
        return try {
            ensureMounts()
            val marker = File(rootfsDir, "var/lib/droiddesk-zh-v1")
            val hasFonts = File(rootfsDir, "usr/share/fonts/truetype/noto").exists() ||
                File(rootfsDir, "usr/share/fonts/opentype/noto").exists()
            val hasIbus = File(rootfsDir, "usr/bin/ibus-daemon").exists()
            val localeReady = execChroot(
                "locale -a 2>/dev/null | grep -qi 'zh_CN.utf8'",
            ) == 0
            if (!marker.exists() || !hasFonts || !hasIbus || !localeReady) {
                onLog(t("正在为已安装的 Ubuntu 应用中文环境…\n", "Applying Chinese environment to the installed Ubuntu...\n"))
                ensureChineseEnvironment(onLog)
                marker.writeText("v1")
            } else {
                execChroot(
                    "printf '%s\\n' 'LANG=zh_CN.UTF-8' 'LANGUAGE=zh_CN:zh' 'LC_ALL=zh_CN.UTF-8' > /etc/default/locale; " +
                        "mkdir -p /root; printf '%s\\n' 'export LANG=zh_CN.UTF-8' " +
                        "'export LC_ALL=zh_CN.UTF-8' 'export LANGUAGE=zh_CN:zh' > /root/.xprofile",
                    onLog,
                )
            }
            DesktopLog.append(
                context,
                TAG,
                "Chinese environment ensured (fonts=$hasFonts ibus=$hasIbus locale=$localeReady)",
            )
            onLog(t("中文环境已就绪（zh_CN.UTF-8）\n", "Chinese environment ready (zh_CN.UTF-8)\n"))
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Chinese environment migration failed", e)
            DesktopLog.append(context, TAG, "Chinese environment failed: ${e.message}")
            onLog(t("中文环境应用失败：${e.message}\n", "Chinese environment failed: ${e.message}\n"))
            false
        }
    }


    /**
     * Installs the Chinese locale, CJK fonts, IBus with libpinyin, and the GTK runtime used
     * by the desktop store. Failures are reported but do not abort the whole DE install:
     * an English desktop is better than no desktop.
     */
    private fun ensureChineseEnvironment(
        onLog: (String) -> Unit = {},
        onProgress: (Double, String) -> Unit = { _, _ -> },
    ) {
        if (!isPackageInstalled("ibus") || !isPackageInstalled("fonts-noto-cjk")) {
            onLog(t("正在下载中文字体与输入法组件…\n", "Installing CJK fonts and input method...\n"))
            val exit = execChroot(
                "DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends " +
                    "locales fonts-noto-cjk fonts-noto-color-emoji ibus ibus-libpinyin ibus-gtk3 ibus-gtk4 " +
                    "python3-gi gir1.2-gtk-3.0 dbus-x11 xdg-utils",
                onLog,
            )
            if (exit != 0) {
                execChroot("DEBIAN_FRONTEND=noninteractive dpkg --configure -a", onLog)
                execChroot("DEBIAN_FRONTEND=noninteractive apt-get --fix-broken install -y", onLog)
            }
        }

        execChroot(
            "locale-gen zh_CN.UTF-8 >/dev/null 2>&1 || true; " +
                "update-locale LANG=zh_CN.UTF-8 LANGUAGE=zh_CN:zh LC_ALL=zh_CN.UTF-8 >/dev/null 2>&1 || true; " +
                "printf '%s\\n' 'LANG=zh_CN.UTF-8' 'LANGUAGE=zh_CN:zh' 'LC_ALL=zh_CN.UTF-8' > /etc/default/locale; " +
                "fc-cache -f >/dev/null 2>&1 || true",
            onLog,
        )
        ensureGuestScripts()
        onProgress(0.75, t("中文环境已配置", "Chinese environment configured"))
    }

    /**
     * Point apt at the device-wide HTTP proxy only while it is actually listening.
     * The chroot has no resolver of its own; the proxy resolves host names. Probing
     * first (and deleting the config when it stops responding) keeps a dead proxy
     * from shadowing a direct connection.
     */
    private fun applyHostProxyConfig() {
        if (!hasRoot() || !isRootfsReady()) return
        val reachable = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress("127.0.0.1", 2080), 400)
            }
            true
        }.getOrDefault(false)
        val path = "/etc/apt/apt.conf.d/99droiddesk-proxy"
        if (!reachable) {
            val removed = execChroot("rm -f $path") == 0
            if (removed) DesktopLog.append(context, TAG, "host proxy unreachable; apt uses direct")
            return
        }
        val command = """
            mkdir -p /etc/apt/apt.conf.d
            cat > $path <<'DROIDDESK_PROXY'
            # DroidDesk: the guest has no resolver; let the host proxy resolve and fetch.
            Acquire::http::Proxy "http://127.0.0.1:2080";
            Acquire::https::Proxy "http://127.0.0.1:2080";
            DROIDDESK_PROXY
        """.trimIndent()
        val code = execChroot(command)
        DesktopLog.append(
            context,
            TAG,
            if (code == 0) "host proxy config written" else "host proxy config failed ($code)",
        )
    }

    /**
     * Mirror the desktop entries of common Linux apps into /root/Desktop. Runs on
     * each session start and after each install; a whitelist keeps phone-sized
     * desktops free of Xfce internals.
     */
    private fun exportDesktopShortcuts() {
        if (!hasRoot() || !isRootfsReady()) return
        val command = """
            mkdir -p /root/Desktop
            for d in /usr/share/applications /usr/local/share/applications /root/.local/share/applications; do
                [ -d "${'$'}d" ] || continue
                for f in "${'$'}d"/*.desktop; do
                    [ -f "${'$'}f" ] || continue
                    grep -qi '^NoDisplay=true' "${'$'}f" && continue
                    name=${'$'}(basename "${'$'}f")
                    case "${'$'}name" in
                        firefox*|chromium*|google-chrome*|code*|gimp*|vlc*|mpv*|libreoffice-*)
                            [ -e "/root/Desktop/${'$'}name" ] || cp -f "${'$'}f" "/root/Desktop/${'$'}name"
                            ;;
                    esac
                done
            done
            chmod +x /root/Desktop/*.desktop 2>/dev/null || true
            chown root:root /root/Desktop/*.desktop 2>/dev/null || true
        """.trimIndent()
        execChroot(command)
    }

    /**
     * Writes the in-guest helper programs (desktop store, .deb handler, IBus focus watcher)
     * plus their menu entries and the .deb MIME association. The scripts are staged in app
     * storage and copied through `su`, because /usr and /root are root-owned.
     */
    fun ensureGuestScripts(): Boolean {
        if (!hasRoot() || !isRootfsReady()) return false
        return try {
            val staging = File(context.cacheDir, "droiddesk-guest").apply { mkdirs() }
            val store = File(staging, "droiddesk-store")
            store.writeText(storeScript())
            store.setExecutable(true, false)

            val deb = File(staging, "droiddesk-install-deb")
            deb.writeText(debInstallerScript())
            deb.setExecutable(true, false)

            val ime = File(staging, "droiddesk-ime-watch")
            ime.writeText(imeWatcherScript())
            ime.setExecutable(true, false)

            val repair = File(staging, "droiddesk-apt-repair")
            repair.writeText(aptRepairScript())
            repair.setExecutable(true, false)

            val storeDesktop = File(staging, "droiddesk-store.desktop")
            storeDesktop.writeText(desktopEntry(
                name = "软件商店 / App Store",
                comment = "搜索并安装 apt 软件包，安装本地 .deb",
                exec = "/usr/local/bin/droiddesk-store",
                icon = "system-software-install",
                categories = "System;PackageManager;",
            ))
            val debDesktop = File(staging, "droiddesk-deb.desktop")
            debDesktop.writeText(desktopEntry(
                name = "安装 Debian 软件包",
                comment = "Install a local .deb package",
                exec = "/usr/local/bin/droiddesk-install-deb %f",
                icon = "application-x-deb",
                categories = "System;PackageManager;",
                mimeTypes = "application/vnd.debian.binary-package;",
            ))
            val mimeApps = File(staging, "mimeapps.list")
            mimeApps.writeText(
                """
                [Default Applications]
                application/vnd.debian.binary-package=droiddesk-deb.desktop
                """.trimIndent() + "\n",
            )

            val commands = """
                set -e
                install -d -m 0755 /usr/local/bin /usr/share/applications /root/Desktop /root/.config
                install -m 0755 ${shellQuote(store.absolutePath)} /usr/local/bin/droiddesk-store
                install -m 0755 ${shellQuote(deb.absolutePath)} /usr/local/bin/droiddesk-install-deb
                install -m 0755 ${shellQuote(ime.absolutePath)} /usr/local/bin/droiddesk-ime-watch
                install -m 0755 ${shellQuote(repair.absolutePath)} /usr/local/bin/droiddesk-apt-repair
                install -m 0644 ${shellQuote(storeDesktop.absolutePath)} /usr/share/applications/droiddesk-store.desktop
                install -m 0644 ${shellQuote(debDesktop.absolutePath)} /usr/share/applications/droiddesk-deb.desktop
                install -m 0755 ${shellQuote(storeDesktop.absolutePath)} /root/Desktop/droiddesk-store.desktop
                install -m 0644 ${shellQuote(mimeApps.absolutePath)} /root/.config/mimeapps.list
                cp -f ${shellQuote(mimeApps.absolutePath)} /etc/xdg/mimeapps.list 2>/dev/null || true
                update-desktop-database /usr/share/applications >/dev/null 2>&1 || true
                chown -R 0:0 /usr/local/bin/droiddesk-store /usr/local/bin/droiddesk-install-deb /usr/local/bin/droiddesk-ime-watch /usr/local/bin/droiddesk-apt-repair /root/Desktop /root/.config/mimeapps.list
            """.trimIndent()
            rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(commands)}")
            true
        } catch (error: Exception) {
            Log.e(TAG, "Failed to install guest helper scripts", error)
            DesktopLog.append(context, TAG, "guest scripts failed: ${error.message}")
            false
        }
    }

    private fun desktopEntry(
        name: String,
        comment: String,
        exec: String,
        icon: String,
        categories: String,
        mimeTypes: String? = null,
    ): String = buildString {
        append("[Desktop Entry]\n")
        append("Type=Application\n")
        append("Version=1.0\n")
        append("Name=$name\n")
        append("Comment=$comment\n")
        append("Exec=$exec\n")
        append("Icon=$icon\n")
        append("Terminal=false\n")
        append("StartupNotify=true\n")
        append("Categories=$categories\n")
        if (mimeTypes != null) append("MimeType=$mimeTypes\n")
    }

    /** apt-cache search inside the guest, with installed-state decoration. */
    fun searchPackages(query: String, limit: Int = 60): List<Map<String, Any>> {
        if (!hasRoot()) return emptyList()
        ensureMounts()
        val safe = query.replace(Regex("[^A-Za-z0-9+._-]"), " ").trim()
        val command = if (safe.isEmpty()) {
            "apt-cache search --names-only . | head -n $limit"
        } else {
            "apt-cache search --names-only ${shellQuote(safe)} | head -n $limit"
        }
        val output = executeCommand(command)
        val installed = installedPackageSet()
        return output.lines().mapNotNull { line ->
            val separator = line.indexOf(" - ")
            if (separator <= 0) return@mapNotNull null
            val name = line.substring(0, separator).trim()
            if (name.isEmpty()) return@mapNotNull null
            mapOf(
                "name" to name,
                "description" to line.substring(separator + 3).trim(),
                "installed" to installed.contains(name),
                "version" to "",
            )
        }
    }

    fun installedPackages(limit: Int = 500): List<Map<String, Any>> {
        if (!hasRoot()) return emptyList()
        val output = executeCommand(
            "dpkg-query -W -f='\${Package}|\${Version}|\${binary:Summary}\\n' 2>/dev/null | head -n $limit",
        )
        return output.lines().mapNotNull { line ->
            val parts = line.split("|", limit = 3)
            if (parts.size < 2 || parts[0].isBlank()) return@mapNotNull null
            mapOf(
                "name" to parts[0].trim(),
                "version" to parts[1].trim(),
                "description" to parts.getOrElse(2) { "" }.trim(),
                "installed" to true,
            )
        }
    }

    fun upgradablePackages(refresh: Boolean): List<Map<String, Any>> {
        if (!hasRoot()) return emptyList()
        if (refresh) execChroot("DEBIAN_FRONTEND=noninteractive apt-get update -q")
        val output = executeCommand("apt list --upgradable 2>/dev/null | tail -n +2")
        return output.lines().mapNotNull { line ->
            val name = line.substringBefore('/').trim()
            if (name.isEmpty()) return@mapNotNull null
            val version = Regex("upgradable from: ([^\\]]+)").find(line)?.groupValues?.get(1) ?: ""
            mapOf("name" to name, "version" to version, "description" to "", "installed" to true)
        }
    }

    /** Install one package, repairing a broken dpkg/apt state before retrying once. */
    fun installPackage(
        packageName: String,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot() || !isSafePackageName(packageName)) return false
        return try {
            ensureMounts()
            // Keep the full apt output so a failure can be shown (and copied) verbatim
            // instead of the useless "安装失败" toast the store used to produce.
            val captured = StringBuilder()
            val capture: (String) -> Unit = { chunk ->
                captured.append(chunk)
                if (captured.length > 64_000) captured.delete(0, captured.length - 48_000)
                onLog(chunk)
            }
            onProgress(0.05, t("正在更新软件包索引…", "Updating package index..."))
            if (execChroot("DEBIAN_FRONTEND=noninteractive apt-get update -q", capture) != 0) {
                onLog(t("软件包索引更新失败，尝试换源后重试…\n", "apt-get update failed; trying another mirror...\n"))
                applyBestMirror(capture)
                execChroot("DEBIAN_FRONTEND=noninteractive apt-get update -q", capture)
            }
            onProgress(0.2, t("正在安装 $packageName…", "Installing $packageName..."))
            val first = execChroot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${shellQuote(packageName)}",
                capture,
            )
            if (first == 0) {
                exportDesktopShortcuts()
                onProgress(1.0, t("$packageName 安装完成", "$packageName installed"))
                return true
            }

            onLog(t("安装失败（退出码 $first），正在自动修复依赖后重试…\n", "Install failed (exit $first); repairing and retrying...\n"))
            diagnosePackageManager(capture)
            repairPackageManager(onProgress, capture)
            val second = execChroot(
                "DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ${shellQuote(packageName)}",
                capture,
            )
            val ok = second == 0
            if (ok) {
                exportDesktopShortcuts()
                onProgress(1.0, t("$packageName 安装完成", "$packageName installed"))
            } else {
                val tail = captured.lines()
                    .filter { it.isNotBlank() }
                    .takeLast(18)
                    .joinToString("\n")
                onLog("\n===== apt 输出（失败原文）=====\n$tail\n===== end =====\n")
                DesktopLog.append(context, TAG, "install $packageName failed (exit $second):\n$tail")
                onProgress(
                    -1.0,
                    t(
                        "$packageName 安装失败（退出码 $second）\n\n$tail",
                        "$packageName install failed (exit $second)\n\n$tail",
                    ),
                )
            }
            ok
        } catch (error: Throwable) {
            Log.e(TAG, "Package install failed: $packageName", error)
            DesktopLog.append(context, TAG, "install $packageName failed: ${error.message}")
            onProgress(-1.0, "安装失败：${error.message}")
            false
        }
    }

    /**
     * Read-only package-manager health check used when the store fails.
     *
     * Prints architecture, effective sources, DNS, dpkg locks, `dpkg --configure -a` and
     * `apt-get update` results so the user can see (and copy) the real cause.
     */
    fun diagnosePackageManager(onLog: (String) -> Unit = {}) {
        if (!hasRoot() || !isRootfsReady()) {
            onLog("诊断：root 权限或 Ubuntu 镜像不可用\n")
            return
        }
        val command = """
            echo "[arch] ${'$'}(dpkg --print-architecture 2>&1)"
            echo "[sources]"
            grep -vE '^#|^$' /etc/apt/sources.list 2>/dev/null || true
            ls /etc/apt/sources.list.d 2>/dev/null || true
            echo "[resolv]"
            cat /etc/resolv.conf 2>/dev/null || echo missing
            echo "[locks]"
            ls -l /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend /var/lib/apt/lists/lock 2>/dev/null || true
            echo "[dpkg-configure]"
            DEBIAN_FRONTEND=noninteractive dpkg --configure -a >/tmp/droiddesk-dpkg.log 2>&1
            echo "exit=${'$'}?"
            tail -n 20 /tmp/droiddesk-dpkg.log
            echo "[apt-update]"
            apt-get update -o Acquire::Retries=1 >/tmp/droiddesk-apt-update.log 2>&1
            echo "exit=${'$'}?"
            tail -n 20 /tmp/droiddesk-apt-update.log
        """.trimIndent()
        execChroot(command, onLog)
    }

    fun removePackage(
        packageName: String,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot() || !isSafePackageName(packageName)) return false
        val exit = execChroot(
            "DEBIAN_FRONTEND=noninteractive apt-get remove -y ${shellQuote(packageName)}",
            onLog,
        )
        onProgress(if (exit == 0) 1.0 else -1.0, if (exit == 0) "已卸载 $packageName" else "卸载失败")
        return exit == 0
    }

    /** Upgrade every installed package inside the chroot. */
    fun upgradeAll(
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot()) return false
        ensureMounts()
        onProgress(0.1, t("正在更新软件包索引…", "Updating package index..."))
        if (execChroot("DEBIAN_FRONTEND=noninteractive apt-get update -q", onLog) != 0) {
            applyBestMirror(onLog)
        }
        onProgress(0.4, t("正在升级所有软件包…", "Upgrading all packages..."))
        val exit = execChroot(
            "DEBIAN_FRONTEND=noninteractive apt-get -y " +
                "-o Dpkg::Options::=--force-confdef -o Dpkg::Options::=--force-confold upgrade",
            onLog,
        )
        onProgress(if (exit == 0) 1.0 else -1.0, if (exit == 0) t("升级完成", "Upgrade complete") else t("升级失败", "Upgrade failed"))
        return exit == 0
    }

    /** `dpkg --configure -a`, fix-broken, source sanity check, then a fresh index. */
    fun repairPackageManager(
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot()) return false
        ensureMounts()
        try {
            onProgress(0.1, t("正在解除 apt/dpkg 锁…", "Releasing apt/dpkg locks..."))
            execChroot(
                "rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock 2>/dev/null || true; " +
                    "dpkg --configure -a",
                onLog,
            )
            onProgress(0.35, t("正在修复损坏的依赖…", "Fixing broken dependencies..."))
            execChroot("DEBIAN_FRONTEND=noninteractive apt-get --fix-broken install -y", onLog)

            onProgress(0.55, t("正在检查软件源与架构…", "Checking sources and architecture..."))
            val arch = executeCommand("dpkg --print-architecture").trim()
            onLog("dpkg architecture: $arch\n")
            if (arch != "arm64") {
                onLog(t("警告：架构为 $arch，当前软件源只提供 arm64 包。\n", "Warning: architecture is $arch, but the sources target arm64.\n"))
                val codename = MirrorSelector.detectCodename(rootfsDir)
                val mirror = MirrorSelector.probeReachable(codename).firstOrNull()
                    ?: "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports"
                MirrorSelector.writeSources(rootfsDir, mirror, codename)
            }
            // deb822 files from the stock image can re-enable the slow global mirrors.
            listOf("ubuntu.sources", "ubuntu.sources.save").forEach { name ->
                val file = File(rootfsDir, "etc/apt/sources.list.d/$name")
                if (file.exists()) file.renameTo(File(file.parentFile, "$name.disabled"))
            }
            runCatching { DnsConfig.applyTo(rootfsDir, rootShell, L10n.isChinese(context)) { onLog(it) } }

            onProgress(0.8, t("正在重新获取软件包索引…", "Refreshing package index..."))
            val updated = execChroot("DEBIAN_FRONTEND=noninteractive apt-get update", onLog) == 0
            if (!updated) applyBestMirror(onLog)
            onProgress(1.0, t("软件源修复完成", "Package sources repaired"))
            return updated
        } catch (error: Throwable) {
            Log.e(TAG, "Package manager repair failed", error)
            DesktopLog.append(context, TAG, "repair failed: ${error.message}")
            onProgress(-1.0, "修复失败：${error.message}")
            return false
        }
    }

    /**
     * Switches the rootfs to the best domestic Ubuntu mirror.
     *
     * Returns a structured code instead of a bare Boolean so the UI can tell "the device
     * has no network" apart from "the mirror index is broken"; both used to be reported
     * as "no usable mirror" even when the mirror itself was fine.
     */
    fun applyBestMirror(onLog: (String) -> Unit = {}): String {
        if (!hasRoot()) return "Unsupported"
        ensureMounts()
        val codename = MirrorSelector.detectCodename(rootfsDir)
        val mirrors = MirrorSelector.orderedCandidates(codename)
        // Probes run on the Android side and fail as a group when the device resolver or
        // the host proxy is broken. They only word the final message; they never decide.
        val reachability = MirrorSelector.reachability(codename)
        if (reachability == MirrorSelector.Reachability.UNREACHABLE) {
            onLog(t("镜像探测不可达（可能是设备 DNS/代理问题），仍按优先级逐个尝试…\n", "Mirror probes are unreachable (device DNS/proxy?); still trying mirrors in order...\n"))
        }
        for (mirror in mirrors) {
            MirrorSelector.writeSources(rootfsDir, mirror, codename)
            onLog(t("已写入镜像 $mirror，正在刷新索引…\n", "Mirror $mirror written; refreshing index...\n"))
            if (execChroot("DEBIAN_FRONTEND=noninteractive apt-get update", onLog) == 0) {
                onLog(t("软件源已切换到 $mirror\n", "APT mirror switched to $mirror\n"))
                return "Ok"
            }
        }
        return if (reachability == MirrorSelector.Reachability.UNREACHABLE) {
            onLog(t("网络不可达：所有镜像均无法刷新索引，请检查网络或代理。\n", "Network unreachable: no mirror could refresh its index; check the network or proxy.\n"))
            "NoNetwork"
        } else {
            onLog(t("镜像不可用：网络可达但索引刷新失败，请查看 apt 输出。\n", "Mirror unavailable: the network is reachable but index refresh failed; see apt output.\n"))
            "AptFailed"
        }
    }

    /** Installs a .deb selected in the Android file picker, then fixes dependencies. */
    fun installDeb(
        hostFile: File,
        onProgress: (Double, String) -> Unit = { _, _ -> },
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (!hasRoot()) return false
        return try {
            ensureMounts()
            ensureGuestScripts()
            val target = File(rootfsDir, "tmp/droiddesk-install.deb")
            rootShell.exec(
                "cp -f ${shellQuote(hostFile.absolutePath)} ${shellQuote(target.absolutePath)} && " +
                    "chmod 0644 ${shellQuote(target.absolutePath)}",
            )
            onProgress(0.2, t("正在安装 ${hostFile.name}…", "Installing ${hostFile.name}..."))
            val dpkgExit = execChroot("DEBIAN_FRONTEND=noninteractive dpkg -i /tmp/droiddesk-install.deb", onLog)
            onProgress(0.7, t("正在补齐依赖…", "Fixing dependencies..."))
            execChroot("DEBIAN_FRONTEND=noninteractive apt-get --fix-broken install -y", onLog)
            val ok = dpkgExit == 0 || execChroot("dpkg --configure -a", onLog) == 0
            onProgress(if (ok) 1.0 else -1.0, if (ok) "安装完成" else "安装失败，请查看日志")
            DesktopLog.append(context, TAG, "deb install ${hostFile.name}: ok=$ok dpkg=$dpkgExit")
            ok
        } catch (error: Throwable) {
            Log.e(TAG, "deb install failed", error)
            DesktopLog.append(context, TAG, "deb install failed: ${error.message}")
            onProgress(-1.0, "安装失败：${error.message}")
            false
        }
    }

    private fun installedPackageSet(): Set<String> {
        val output = runCatching {
            executeCommand("dpkg-query -W -f='\${db:Status-Abbrev}\${Package}\\n' 2>/dev/null")
        }.getOrDefault("")
        return output.lines()
            .filter { it.startsWith("ii") }
            .map { it.removePrefix("ii").trim() }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    private fun isSafePackageName(name: String): Boolean =
        name.isNotBlank() && name.matches(Regex("[A-Za-z0-9][A-Za-z0-9+._:-]*"))

    // ── Guest helper scripts ──

    private fun storeScript(): String = """
        #!/usr/bin/env python3
        # DroidDesk App Store — search/install apt packages and local .deb files.
        import os, subprocess, sys, threading
        try:
            import gi
            gi.require_version("Gtk", "3.0")
            from gi.repository import Gtk, GLib
        except Exception:
            subprocess.call(["bash", "-lc",
                "apt-get install -y python3-gi gir1.2-gtk-3.0 || true"])
            os.execv(sys.executable, [sys.executable] + sys.argv)

        def run(cmd):
            return subprocess.run(["bash", "-lc", cmd], capture_output=True, text=True).stdout

        class Store(Gtk.Window):
            def __init__(self):
                super().__init__(title="DroidDesk 软件商店")
                self.set_default_size(520, 720)
                self.set_border_width(12)
                header = Gtk.HeaderBar(title="软件商店", subtitle="apt 软件包与本地 .deb")
                header.set_show_close_button(True)
                self.set_titlebar(header)

                box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=10)
                self.add(box)

                searchrow = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
                self.search = Gtk.SearchEntry(placeholder_text="搜索软件包，例如 firefox、gimp")
                self.search.set_hexpand(True)
                self.search.connect("activate", lambda w: self.do_search(w.get_text()))
                searchrow.pack_start(self.search, True, True, 0)
                button = Gtk.Button(label="搜索")
                button.connect("clicked", lambda b: self.do_search(self.search.get_text()))
                searchrow.pack_start(button, False, False, 0)
                box.pack_start(searchrow, False, False, 0)

                actions = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
                for label, handler in (
                    ("安装选中", self.install_selected),
                    ("安装 .deb", self.install_deb),
                    ("修复软件源", self.repair),
                ):
                    btn = Gtk.Button(label=label)
                    btn.connect("clicked", handler)
                    actions.pack_start(btn, False, False, 0)
                box.pack_start(actions, False, False, 0)

                sw = Gtk.ScrolledWindow()
                sw.set_hexpand(True)
                sw.set_vexpand(True)
                self.listbox = Gtk.ListBox()
                self.listbox.connect("row-activated", lambda *a: self.install_selected(None))
                sw.add(self.listbox)
                box.pack_start(sw, True, True, 0)

                self.status = Gtk.Label(label="输入关键字搜索；双击可直接安装。", xalign=0)
                box.pack_start(self.status, False, False, 0)
                self.log = Gtk.TextView(editable=False, monospace=True)
                logsw = Gtk.ScrolledWindow()
                logsw.set_size_request(-1, 150)
                logsw.add(self.log)
                box.pack_start(logsw, False, False, 0)

            def append_log(self, text):
                buf = self.log.get_buffer()
                buf.insert(buf.get_end_iter(), text + "\n")
                self.log.scroll_to_iter(buf.get_end_iter(), 0.0, False, 0.0, 0.0)

            def spawn(self, cmd, done=None):
                def worker():
                    self.append_log("$ " + cmd)
                    proc = subprocess.Popen(["bash", "-lc", cmd], stdout=subprocess.PIPE,
                                            stderr=subprocess.STDOUT, text=True)
                    for line in proc.stdout:
                        line = line.rstrip("\n")
                        if line:
                            GLib.idle_add(self.append_log, line)
                    code = proc.wait()
                    GLib.idle_add(self.append_log, "[退出码 %d]" % code)
                    if done:
                        GLib.idle_add(done, code)
                threading.Thread(target=worker, daemon=True).start()

            def do_search(self, text):
                for child in list(self.listbox.get_children()):
                    self.listbox.remove(child)
                text = "".join(c for c in text if c.isalnum() or c in " .+-_").strip()
                out = run("apt-cache search --names-only %s | head -n 60" % (text or "."))
                for line in out.splitlines():
                    if " - " not in line:
                        continue
                    name, desc = line.split(" - ", 1)
                    row = Gtk.ListBoxRow()
                    cell = Gtk.Box(orientation=Gtk.Orientation.VERTICAL)
                    cell.set_border_width(6)
                    title = Gtk.Label(xalign=0, markup="<b>%s</b>" % name)
                    cell.pack_start(title, False, False, 0)
                    cell.pack_start(Gtk.Label(label=desc, xalign=0, wrap=True), False, False, 0)
                    cell.pkg = name
                    row.add(cell)
                    self.listbox.add(row)
                self.listbox.show_all()
                self.status.set_text("找到 %d 个结果" % len(self.listbox.get_children()))

            def selected(self):
                row = self.listbox.get_selected_row()
                return row.get_child().pkg if row else None

            def install_selected(self, _):
                pkg = self.selected()
                if not pkg:
                    self.status.set_text("请先在列表中选择一个软件包。")
                    return
                self.status.set_text("正在安装 " + pkg + " …")
                self.spawn("DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends '%s'" % pkg,
                           lambda code: self.status.set_text("安装完成" if code == 0 else "安装失败，请查看下方日志"))

            def install_deb(self, _):
                dialog = Gtk.FileChooserDialog(title="选择 .deb 文件", parent=self,
                                               action=Gtk.FileChooserAction.OPEN)
                dialog.add_buttons("取消", Gtk.ResponseType.CANCEL, "安装", Gtk.ResponseType.OK)
                filt = Gtk.FileFilter()
                filt.set_name("Debian 软件包 (*.deb)")
                filt.add_pattern("*.deb")
                dialog.add_filter(filt)
                if dialog.run() == Gtk.ResponseType.OK:
                    path = dialog.get_filename()
                    self.status.set_text("正在安装 " + os.path.basename(path) + " …")
                    self.spawn("dpkg -i '%s'; apt-get --fix-broken install -y" % path.replace("'", "'\\''"),
                               lambda code: self.status.set_text("安装完成" if code == 0 else "安装失败，请查看日志"))
                dialog.destroy()

            def repair(self, _):
                self.status.set_text("正在修复软件源…")
                self.spawn("rm -f /var/lib/dpkg/lock* /var/cache/apt/archives/lock; "
                           "dpkg --configure -a; apt-get --fix-broken install -y; apt-get update",
                           lambda code: self.status.set_text("修复完成" if code == 0 else "修复失败"))

        win = Store()
        win.connect("destroy", Gtk.main_quit)
        win.show_all()
        Gtk.main()
    """.trimIndent() + "\n"

    private fun debInstallerScript(): String = """
        #!/bin/bash
        # DroidDesk .deb installer: runs dpkg with full output visible in a terminal.
        set -u
        file="${'$'}1"
        if [ -z "${'$'}file" ] || [ ! -f "${'$'}file" ]; then
          echo "用法：droiddesk-install-deb <文件.deb>"
          exit 2
        fi
        log=/tmp/droiddesk-deb.log
        {
          echo "== 正在安装 ${'$'}file =="
          dpkg -i "${'$'}file"
          echo "== 正在补齐依赖 =="
          apt-get --fix-broken install -y
          dpkg --configure -a
        } 2>&1 | tee "${'$'}log"
        code=${'$'}{PIPESTATUS[1]}
        if [ "${'$'}code" -eq 0 ]; then
          command -v notify-send >/dev/null 2>&1 && notify-send "安装完成" "${'$'}file"
        else
          command -v notify-send >/dev/null 2>&1 && notify-send "安装失败" "请查看 ${'$'}log"
        fi
        if [ -t 0 ] && [ -t 1 ]; then
          read -r -p "按回车关闭…" _
        else
          xfce4-terminal --hold -e "cat ${'$'}log" >/dev/null 2>&1 &
        fi
        exit "${'$'}code"
    """.trimIndent() + "\n"

    private fun imeWatcherScript(): String = """
        #!/bin/bash
        # Writes 1 when IBus reports that an editable input context gained focus.
        # GTK/Qt only create a context for text widgets, so this does not fire for
        # plain window focus. Android polls this file to show the soft keyboard.
        # A FocusIn alone is still not enough: the session manager focuses the first
        # window at cold start. Only report 1 after a real touch landed on the X
        # surface (Android bumps ime-tap on every ACTION_DOWN).
        BRIDGE="${'$'}{DROIDDESK_BRIDGE:-/tmp/droiddesk-bridge}"
        TAPFILE="${'$'}BRIDGE/ime-tap"
        mkdir -p "${'$'}BRIDGE"
        printf '0\n' > "${'$'}BRIDGE/ime-focus"
        LASTTAP=""
        ARMED=0
        dbus-monitor --session "interface='org.freedesktop.IBus.Panel'" 2>/dev/null |
        while read -r line; do
          tap="${'$'}(cat "${'$'}TAPFILE" 2>/dev/null)"
          if [ -n "${'$'}tap" ] && [ "${'$'}tap" != "${'$'}LASTTAP" ]; then
            LASTTAP="${'$'}tap"
            ARMED=1
          fi
          case "${'$'}line" in
            *"member=FocusIn"*)
              if [ "${'$'}ARMED" = "1" ]; then printf '1\n' > "${'$'}BRIDGE/ime-focus"
              else printf '0\n' > "${'$'}BRIDGE/ime-focus"; fi ;;
            *"member=FocusOut"*)
              printf '0\n' > "${'$'}BRIDGE/ime-focus"
              ARMED=0 ;;
          esac
        done
    """.trimIndent() + "\n"

    private fun aptRepairScript(): String = """
        #!/bin/bash
        # One-click apt/dpkg repair used by the desktop store and the app.
        set -u
        rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock 2>/dev/null || true
        dpkg --configure -a
        apt-get --fix-broken install -y
        apt-get update
    """.trimIndent() + "\n"

    // ── Command execution inside chroot ──

    private fun getPreloadEnv(): String {
        return if (File(rootfsDir, "usr/local/lib/libdroiddesk-close-range.so").exists()) {
            "export LD_PRELOAD=/usr/local/lib/libdroiddesk-close-range.so; "
        } else {
            ""
        }
    }

    /**
     * Execute a command inside the chroot as root.
     */
    fun executeCommand(command: String, onOutput: ((String) -> Unit)? = null): String {
        if (!hasRoot()) return "Error: root access required"
        val wrapped = "${getPreloadEnv()}export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        return if (onOutput != null) {
            val code = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
                onOutput(chunk)
            }
            "Exit code: $code"
        } else {
            rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}")
        }
    }

    private fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        val wrapped = "${getPreloadEnv()}export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; $command"
        val output = rootShell.exec("chroot ${rootfsDir.absolutePath} /bin/bash -c ${shellQuote(wrapped)}") { chunk ->
            Log.d(TAG, "execChroot: ${chunk.trimEnd()}")
            onLog(chunk)
        }
        Log.d(TAG, "chroot command exit code: $output")
        return output
    }

    private fun shellQuote(input: String): String {
        // Use a single-quoted string that handles embedded single quotes safely
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        // -f='${Status}' returns something like 'install ok installed' if successfully installed
        return execChroot("dpkg-query -W -f='\${Status}' $pkg 2>/dev/null | grep -q 'install ok installed'") == 0
    }
}
