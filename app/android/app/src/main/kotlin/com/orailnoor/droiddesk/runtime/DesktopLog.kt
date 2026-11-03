package com.orailnoor.droiddesk.runtime

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * On-disk journal for desktop-session and package-manager events.
 *
 * logcat is unusable on many devices (logd disabled, release builds not debuggable), so this
 * file is the only reliable evidence. A copy is mirrored to
 * `/sdcard/Android/data/<pkg>/files/logs/desktop.log`, which `adb pull` and any file
 * manager can read without root or `run-as`.
 *
 * Logging is opt-in (`Settings → 调试日志`, key `flutter.ddDesktopLog`, default off). While it
 * is off every call is a no-op: nothing is buffered, no thread is started and nothing is
 * written to internal or external storage. That is the power-saving default the user asked
 * for; a resident flush thread used to run even with logging disabled.
 *
 * When logging is enabled, lines are handed to a single background executor. It has no
 * timer/sleep loop — it only works while there is something to write.
 */
object DesktopLog {
    private const val FILE_NAME = "desktop.log"
    private const val MAX_BYTES = 512 * 1024L
    private const val PREFS = "FlutterSharedPreferences"
    private const val PREF_KEY = "flutter.ddDesktopLog"

    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val pending = ArrayDeque<String>()
    private val pendingLock = Object()
    private val flushQueued = AtomicBoolean(false)
    private var executor: java.util.concurrent.ExecutorService? = null
    @Volatile private var cachedExternal: File? = null
    @Volatile private var externalResolved = false
    @Volatile private var enabled = false

    /** Mirrors the Flutter setting; the settings switch writes it before the next event. */
    fun setEnabled(value: Boolean) {
        enabled = value
        if (!value) {
            synchronized(pendingLock) { pending.clear() }
            // Drop the writer too: with logging off there must be no idle flush thread.
            synchronized(this) {
                executor?.shutdown()
                executor = null
            }
            externalResolved = false
            cachedExternal = null
        }
    }

    fun isEnabled(context: Context): Boolean {
        val stored = runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_KEY, false)
        }.getOrDefault(false)
        enabled = stored
        return stored
    }

    fun file(context: Context): File =
        File(context.filesDir, "logs/$FILE_NAME").apply { parentFile?.mkdirs() }

    /** Externally readable mirror (`/sdcard/Android/data/.../files/logs/desktop.log`). */
    fun externalFile(context: Context): File? {
        if (!enabled) return null
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
        // No-op (and no allocation of the timestamp) while the user keeps logging off.
        if (!isEnabled(context)) return
        val timestamp = synchronized(stamp) { stamp.format(Date()) }
        val line = "$timestamp [$tag] ${message.trimEnd()}\n"
        synchronized(pendingLock) { pending.addLast(line) }
        scheduleFlush(context.applicationContext)
    }

    /** Writes a launch milestone with the elapsed time since process start. */
    fun event(context: Context, tag: String, message: String, startedAtMs: Long = 0L) {
        if (!enabled && !isEnabled(context)) return
        val elapsed = if (startedAtMs > 0) {
            " (+${System.currentTimeMillis() - startedAtMs}ms)"
        } else {
            ""
        }
        append(context, tag, "$message$elapsed")
    }

    fun read(context: Context): String = runCatching {
        if (!isEnabled(context)) return ""
        flushNow(context.applicationContext)
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

    /**
     * Queues exactly one flush per batch. No periodic thread: the executor works through the
     * pending lines and then idles. It is created on first use and terminated by [clear].
     */
    private fun scheduleFlush(context: Context) {
        if (!flushQueued.compareAndSet(false, true)) return
        val service = synchronized(this) {
            executor ?: Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "desktop-log-writer").apply { isDaemon = true }
            }.also { executor = it }
        }
        runCatching {
            service.execute {
                try {
                    flushNow(context)
                } catch (_: Throwable) {
                    // Logging must never break the session it is describing.
                } finally {
                    flushQueued.set(false)
                    synchronized(pendingLock) {
                        // A line arrived while flushing; queue another pass.
                        if (pending.isNotEmpty() && flushQueued.compareAndSet(false, true)) {
                            service.execute { runCatching { flushNow(context) } }
                        }
                    }
                }
            }
        }.onFailure { flushQueued.set(false) }
    }

    private fun flushNow(context: Context) {
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
