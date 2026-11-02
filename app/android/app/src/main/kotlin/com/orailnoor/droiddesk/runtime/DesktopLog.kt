package com.orailnoor.droiddesk.runtime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-disk journal for desktop-session and package-manager events.
 *
 * Android's logcat ring buffer rotates quickly and is unreadable on a stock device, so the
 * failure report a user can actually send is the one written here. The file is capped at
 * 512 KiB; the previous generation is kept as `desktop.log.1`.
 */
object DesktopLog {
    private const val FILE_NAME = "desktop.log"
    private const val MAX_BYTES = 512 * 1024L
    private val lock = Any()
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    fun file(context: Context): File =
        File(context.filesDir, "logs/$FILE_NAME").apply { parentFile?.mkdirs() }

    fun append(context: Context, tag: String, message: String) {
        synchronized(lock) {
            try {
                val target = file(context)
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

    fun read(context: Context): String = runCatching {
        val target = file(context)
        if (target.exists()) target.readText() else ""
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching {
            file(context).delete()
            File(context.filesDir, "logs/$FILE_NAME.1").delete()
        }
    }
}
