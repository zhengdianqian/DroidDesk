package com.orailnoor.droiddesk.runtime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk journal for desktop-session and package-manager events.
 *
 * logcat is unusable on many devices (logd disabled, release builds not debuggable), so this
 * file is the only reliable evidence. A copy is mirrored to
 * `/sdcard/Android/data/<pkg>/files/logs/desktop.log`, which `adb pull` and any file
 * manager can read without root or `run-as`.
 *
 * Every line carries a timestamp plus an event tag so a launch can be replayed:
 * `SESSION_START`, `MOUNT`, `X_START`, `DE_START`, `READY`, `SESSION_FAIL`, `IMEBRIDGE`,
 * `DISPLAY`.
 */
object DesktopLog {
    private const val FILE_NAME = "desktop.log"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun file(context: Context): File =
        File(context.filesDir, "logs/$FILE_NAME").apply { parentFile?.mkdirs() }

    /** Externally readable mirror (`/sdcard/Android/data/.../files/logs/desktop.log`). */
    fun externalFile(context: Context): File? = runCatching {
        val dir = context.getExternalFilesDir("logs") ?: return@runCatching null
        dir.mkdirs()
        File(dir, FILE_NAME)
    }.getOrNull()

    fun append(context: Context, tag: String, message: String) {
        synchronized(lock) {
            val targets = listOfNotNull(file(context), externalFile(context))
            targets.forEach { target ->
                try {
                    if (target.length() > MAX_BYTES) {
                        val backup = File(target.parentFile, "$FILE_NAME.1")
                        backup.delete()
                        target.renameTo(backup)
                    }
                    target.appendText("${stamp.format(Date())} [$tag] ${message.trimEnd()}\n")
                } catch (_: Throwable) {
                    // Logging must never break the session it is describing.
                }
            }
        }
    }

    /** Writes a launch milestone with the elapsed time since process start. */
    fun event(context: Context, tag: String, message: String, startedAtMs: Long = 0L) {
        val elapsed = if (startedAtMs > 0) {
            " (+${System.currentTimeMillis() - startedAtMs}ms)"
        } else {
            ""
        }
        append(context, tag, "$message$elapsed")
    }

    fun read(context: Context): String = runCatching {
        val target = file(context)
        if (target.exists()) target.readText() else ""
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching {
            file(context).delete()
            File(context.filesDir, "logs/$FILE_NAME.1").delete()
            externalFile(context)?.delete()
        }
    }
}
