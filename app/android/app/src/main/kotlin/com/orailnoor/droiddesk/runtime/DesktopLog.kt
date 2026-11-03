package com.orailnoor.droiddesk.runtime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

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
 *
 * Writes are buffered and flushed by one background thread. The previous implementation
 * opened, appended and closed both files synchronously on the caller's thread, which
 * happened on the UI thread for IME events and made every keystroke do disk I/O.
 */
object DesktopLog {
    private const val FILE_NAME = "desktop.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val FLUSH_INTERVAL_MS = 500L
    private const val MAX_PENDING = 512

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val pending = ArrayDeque<String>()
    private val pendingLock = Object()
    private var writer: Thread? = null
    @Volatile private var cachedExternal: File? = null
    @Volatile private var externalResolved = false

    fun file(context: Context): File =
        File(context.filesDir, "logs/$FILE_NAME").apply { parentFile?.mkdirs() }

    /** Externally readable mirror (`/sdcard/Android/data/.../files/logs/desktop.log`). */
    fun externalFile(context: Context): File? {
        if (externalResolved) return cachedExternal
        val resolved = runCatching {
            val dir = context.getExternalFilesDir("logs") ?: return@runCatching null
            dir.mkdirs()
            File(dir, FILE_NAME)
        }.getOrNull()
        cachedExternal = resolved
        externalResolved = true
        return resolved
    }

    fun append(context: Context, tag: String, message: String) {
        val timestamp = synchronized(stamp) { stamp.format(Date()) }
        val line = "$timestamp [$tag] ${message.trimEnd()}\n"
        synchronized(pendingLock) {
            if (pending.size >= MAX_PENDING) pending.removeFirst()
            pending.addLast(line)
        }
        ensureWriter(context.applicationContext)
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
        flush(context.applicationContext)
        val target = file(context)
        if (target.exists()) target.readText() else ""
    }.getOrDefault("")

    fun clear(context: Context) {
        runCatching {
            synchronized(pendingLock) { pending.clear() }
            file(context).delete()
            File(context.filesDir, "logs/$FILE_NAME.1").delete()
            externalFile(context)?.delete()
        }
    }

    @Synchronized
    private fun ensureWriter(context: Context) {
        if (writer?.isAlive == true) return
        writer = thread(name = "desktop-log-writer", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(FLUSH_INTERVAL_MS)
                    flush(context)
                } catch (_: InterruptedException) {
                    return@thread
                } catch (_: Throwable) {
                    // Logging must never break the session it is describing.
                }
            }
        }
    }

    private fun flush(context: Context) {
        val batch = synchronized(pendingLock) {
            if (pending.isEmpty()) return
            val copy = pending.toList()
            pending.clear()
            copy
        }
        val targets = listOfNotNull(file(context), externalFile(context))
        targets.forEach { target ->
            try {
                if (target.length() > MAX_BYTES) {
                    val backup = File(target.parentFile, "$FILE_NAME.1")
                    backup.delete()
                    target.renameTo(backup)
                }
                target.appendText(batch.joinToString(""))
            } catch (_: Throwable) {
                // Ignore: a failed flush must not crash the writer thread.
            }
        }
    }
}
