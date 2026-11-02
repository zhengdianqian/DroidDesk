package com.orailnoor.droiddesk.runtime

/**
 * Cross-component view of the desktop session that *should* be running.
 *
 * This is bookkeeping only: who started which desktop environment, and when. There is no
 * watchdog and no automatic restart — the crash/session log ([DesktopLog]) is the way to
 * diagnose a session that died, and the user decides when to start it again.
 */
object DesktopSessionState {
    @Volatile var expectedRunning: Boolean = false
        private set

    @Volatile var mode: String = "chroot"
        private set

    @Volatile var desktopEnv: String = "xfce4"
        private set

    @Volatile var lastStartAt: Long = 0L
        private set

    @Volatile var lastStopAt: Long = 0L
        private set

    fun markStarted(mode: String, desktopEnv: String) {
        this.mode = mode
        this.desktopEnv = desktopEnv
        expectedRunning = true
        lastStartAt = System.currentTimeMillis()
    }

    fun markStopped() {
        expectedRunning = false
        lastStopAt = System.currentTimeMillis()
    }
}

/**
 * Tracks whether the Android 12+ phantom-process killer is likely to reap our Linux session.
 *
 * Two switches matter:
 *  - `settings_enable_monitor_phantom_procs` (global): the developer option
 *    "Disable child process restrictions" sets this to 0.
 *  - `activity_manager/max_phantom_processes` (device_config): the actual phandle/child cap;
 *    stock values are as low as 32 on memory-constrained devices.
 *
 * Reading both usually needs shell/root privileges. With root we can relax them; without it
 * we can only explain the problem and point at the developer option.
 */
object PhantomGuard {
    private const val SETTING_KEY = "settings_enable_monitor_phantom_procs"

    fun status(context: android.content.Context): Map<String, Any> {
        val rooted = runCatching { ChrootRuntime(context).hasRoot() }.getOrDefault(false)
        val monitor = readGlobalSetting(context, rooted)
        val maxProcesses = readMaxPhantomProcesses(context, rooted)
        val numericMax = maxProcesses?.toIntOrNull()
        val monitorEnabled = when (monitor) {
            "0", "false" -> false
            "1", "true" -> true
            else -> null
        }
        val needsAttention = (numericMax != null && numericMax < 128) || monitorEnabled == true
        return mapOf(
            "rooted" to rooted,
            "monitorSetting" to (monitor ?: "unknown"),
            "monitorEnabled" to (monitorEnabled ?: false),
            "maxPhantomProcesses" to (maxProcesses ?: "unknown"),
            "needsAttention" to needsAttention,
            "sdk" to android.os.Build.VERSION.SDK_INT,
        )
    }

    /** Root only: persist the same flags the developer option flips. */
    fun relax(context: android.content.Context): Map<String, Any> {
        val shell = RootShell(context)
        if (!shell.hasRoot()) {
            return mapOf(
                "ok" to false,
                "message" to
                    "No root access. Enable developer options → “Disable child process restrictions” " +
                    "(停止限制子进程), or grant root/Shizuku and retry.",
            )
        }
        return try {
            val output = shell.exec(
                "settings put global $SETTING_KEY 0; " +
                    "device_config put activity_manager max_phantom_processes 2147483647; " +
                    "echo MONITOR=$(settings get global $SETTING_KEY); " +
                    "echo MAX=$(device_config get activity_manager max_phantom_processes)",
            )
            val ok = output.contains("MONITOR=0")
            mapOf(
                "ok" to ok,
                "message" to output.trim(),
                "monitorSetting" to (readGlobalSetting(context, true) ?: "unknown"),
                "maxPhantomProcesses" to (readMaxPhantomProcesses(context, true) ?: "unknown"),
            )
        } catch (error: Throwable) {
            mapOf("ok" to false, "message" to (error.message ?: "Failed to change phantom-process limits"))
        }
    }

    private fun readGlobalSetting(context: android.content.Context, rooted: Boolean): String? {
        if (rooted) {
            return runCatching {
                RootShell(context).exec("settings get global $SETTING_KEY").trim()
                    .takeIf { it.isNotEmpty() && !it.equals("null", true) }
            }.getOrNull()
        }
        return runCatching {
            android.provider.Settings.Global.getString(context.contentResolver, SETTING_KEY)
        }.getOrNull()
    }

    private fun readMaxPhantomProcesses(context: android.content.Context, rooted: Boolean): String? {
        val command = "device_config get activity_manager max_phantom_processes"
        return runCatching {
            if (rooted) {
                RootShell(context).exec(command).trim()
            } else {
                val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText().trim()
                process.waitFor()
                text
            }
        }.getOrNull()?.takeIf {
            it.isNotEmpty() &&
                !it.contains("Exception", true) &&
                !it.contains("Permission", true) &&
                !it.contains("Unknown", true)
        }
    }
}
