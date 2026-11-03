package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Firefox tuning for software rendering inside proot/chroot.
 *
 * Video in Firefox flickered because the browser tried the accelerated paths that do not
 * exist here: WebRender GPU path, hardware video decode and X11-EGL, all layered on top of
 * llvmpipe, plus xfwm4's compositor repainting the whole window every frame. The fix is
 * threefold and each part is independent:
 *
 *  1. xfwm4 compositing stays off (see [XfceMobileProfile.windowManagerConfig]).
 *  2. Mesa runs llvmpipe with vblank disabled (`vblank_mode=0`, set by the profile.d files).
 *  3. Firefox gets these prefs through its supported autoconfig hook, so they apply to every
 *     profile without touching the user's own prefs.js:
 *      `<install>/defaults/pref/droiddesk-autoconfig.js` + `<install>/droiddesk.cfg`.
 *
 * `pref()` (not `lockPref()`) keeps everything user-overridable: someone who wants the GPU
 * path back can set the pref in about:config and win.
 *
 * The tuning is written to every detected Firefox/ESR install directory and is idempotent:
 * the files are only rewritten when their content actually differs.
 */
object FirefoxTuning {
    private const val TAG = "FirefoxTuning"
    private const val AUTOCONFIG_NAME = "droiddesk-autoconfig.js"
    private const val CONFIG_NAME = "droiddesk.cfg"

    private val AUTOCONFIG_JS = """
        // DroidDesk autoconfig — managed file, safe to delete
        pref("general.config.filename", "$CONFIG_NAME");
        pref("general.config.obscure_value", 0);
    """.trimIndent() + "\n"

    /** Software-rendering prefs. Values verified against the documented pref names. */
    private val CONFIG_PREFS = listOf(
        // WebRender stays in software mode; forcing the GPU path on llvmpipe produces
        // frame flashes and dropped tiles instead of a picture.
        "gfx.webrender.software" to "true",
        "gfx.webrender.all" to "false",
        "gfx.canvas.accelerated" to "false",
        // Legacy acceleration switches are still consulted by some builds.
        "layers.acceleration.disabled" to "true",
        "layers.acceleration.force-enabled" to "false",
        // Video: decode on the CPU and present through the software path only. The hardware
        // decoder fails in proot and its fallback is what caused the flashing.
        "media.hardware-video-decoding.enabled" to "false",
        "media.hardware-video-decoding.force-enabled" to "false",
        "media.ffmpeg.vaapi.enabled" to "false",
        "gfx.x11-egl.force-enabled" to "false",
        // proot cannot support Firefox's content sandbox; disabling it prevents silent
        // renderer restarts that also showed up as flicker.
        "security.sandbox.content.level" to "0",
        // Ubuntu-ish desktop integration, cheap on CPU.
        "widget.non-native-theme.enabled" to "true",
        "browser.shell.checkDefaultBrowser" to "false",
        // No UI animations: they are extra full-window repaints in software rendering.
        "toolkit.cosmeticAnimations.enabled" to "false",
        "browser.fullscreen.animate" to "false",
    )

    private fun configText(powerMode: String): String = buildString {
        append("# DroidDesk Firefox tuning — managed file, safe to delete\n")
        CONFIG_PREFS.forEach { (key, value) -> append("pref(\"$key\", $value);\n") }
        // In power saver mode cap the compositor at 30 fps; llvmpipe spends most of its CPU
        // budget painting video frames, and the panel/window chrome does not need 60.
        if (powerMode == "saver") append("pref(\"layout.frame_rate\", 30);\n")
    }

    /**
     * Direct write into an app-owned tree (the Termux-style prefix). Returns the directories
     * that were tuned, or an empty list when Firefox is not installed yet.
     */
    fun installNative(context: Context, prefixDir: File, powerMode: String): List<String> {
        val dirs = candidateFirefoxDirs(prefixDir)
        if (dirs.isEmpty()) return emptyList()
        val tuned = mutableListOf<String>()
        dirs.forEach { dir ->
            val ok = runCatching { writeInto(dir, powerMode) }.getOrElse { error ->
                Log.w(TAG, "cannot tune $dir: ${error.message}")
                false
            }
            if (ok) tuned += dir.absolutePath
        }
        if (tuned.isNotEmpty()) {
            DesktopLog.append(context, TAG, "Firefox software-rendering profile applied to $tuned")
        }
        return tuned
    }

    /**
     * Rootfs variant. `/usr/lib/firefox*` is root-owned after apt, so the files are written by
     * su. The script is base64-encoded to avoid any quoting/escaping surprises.
     */
    fun installInChroot(context: Context, rootfsDir: File, powerMode: String): Boolean {
        val script = chrootScript(rootfsDir.absolutePath, powerMode)
        return runCatching {
            RootShell(context).exec(
                "echo ${shellQuote(android.util.Base64.encodeToString(script.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP))} | base64 -d | sh",
            )
            true
        }.getOrElse { error ->
            Log.w(TAG, "chroot Firefox tuning failed: ${error.message}")
            false
        }
    }

    private fun writeInto(dir: File, powerMode: String): Boolean {
        val prefDir = File(dir, "defaults/pref").apply { mkdirs() }
        val autoFile = File(prefDir, AUTOCONFIG_NAME)
        val cfgFile = File(dir, CONFIG_NAME)
        val cfg = configText(powerMode)
        var changed = false
        if (!autoFile.isFile || autoFile.readText() != AUTOCONFIG_JS) {
            autoFile.writeText(AUTOCONFIG_JS)
            changed = true
        }
        if (!cfgFile.isFile || cfgFile.readText() != cfg) {
            cfgFile.writeText(cfg)
            changed = true
        }
        return changed || dir.isDirectory
    }

    /** Firefox, Firefox ESR and distro package layouts, in the order they are tried. */
    private fun candidateFirefoxDirs(prefix: File): List<File> {
        val lib = File(prefix, "lib")
        val direct = listOf("firefox", "firefox-esr", "firefox-bin", "mozilla").map { File(lib, it) }
        val globbed = runCatching {
            lib.listFiles { file -> file.isDirectory && file.name.startsWith("firefox") }
                ?.toList()
                .orEmpty()
        }.getOrDefault(emptyList())
        return (direct + globbed).distinct().filter { dir ->
            dir.isDirectory && (File(dir, "libxul.so").isFile || dir.listFiles { f -> f.name == "firefox" || f.name == "firefox-bin" }?.isNotEmpty() == true)
        }
    }

    private fun chrootScript(rootfsPath: String, powerMode: String): String {
        val auto = AUTOCONFIG_JS.trimIndent()
        val cfg = configText(powerMode).trimIndent()
        return """
            root=${shellQuote(rootfsPath)}
            for dir in "${'$'}root"/usr/lib/firefox "${'$'}root"/usr/lib/firefox-esr "${'$'}root"/opt/firefox*; do
              [ -d "${'$'}dir" ] || continue
              [ -e "${'$'}dir/libxul.so" ] || [ -e "${'$'}dir/firefox" ] || [ -e "${'$'}dir/firefox-bin" ] || continue
              mkdir -p "${'$'}dir/defaults/pref"
              cat > "${'$'}dir/defaults/pref/$AUTOCONFIG_NAME" <<'DD_AUTO'
$auto
DD_AUTO
              cat > "${'$'}dir/$CONFIG_NAME" <<'DD_CFG'
$cfg
DD_CFG
              chmod 0644 "${'$'}dir/defaults/pref/$AUTOCONFIG_NAME" "${'$'}dir/$CONFIG_NAME"
            done
        """.trimIndent()
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
