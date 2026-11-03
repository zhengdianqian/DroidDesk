package com.orailnoor.droiddesk.runtime

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/** Synchronizes plain text while DroidDesk's desktop activity is foreground. */
class ClipboardSync(
    context: Context,
    private val readLinux: () -> String?,
    private val writeLinux: (String) -> Boolean,
) {
    companion object {
        private const val TAG = "ClipboardSync"
        @Volatile private var rememberedAndroid: String? = null
        @Volatile private var rememberedLinux: String? = null
    }

    private val appContext = context.applicationContext
    private val clipboard = appContext.getSystemService(ClipboardManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val running = AtomicBoolean(false)
    private val busy = AtomicBoolean(false)
    private var lastAndroid: String? = rememberedAndroid
    private var lastLinux: String? = rememberedLinux

    private val tick = object : Runnable {
        override fun run() {
            if (!running.get()) return
            if (busy.compareAndSet(false, true)) synchronizeOnce()
            // Android -> Linux is fully event driven (see `listener`); this timer only
            // has to pick up Linux -> Android copies. 4s instead of 1.6s halves the
            // number of xclip processes spawned while the desktop is idle.
            handler.postDelayed(this, 4000)
        }
    }

    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!running.get() || !busy.compareAndSet(false, true)) return@OnPrimaryClipChangedListener
        val androidText = readAndroidClip()
        thread(name = "clipboard-push", isDaemon = true) {
            try {
                if (lastAndroid == null) {
                    rememberAndroid(androidText)
                } else if (androidText != lastAndroid) {
                    if (writeLinux(androidText)) rememberLinux(androidText)
                    rememberAndroid(androidText)
                }
            } catch (error: Throwable) {
                Log.d(TAG, "Clipboard push unavailable: ${error.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        clipboard.addPrimaryClipChangedListener(listener)
        handler.post(tick)
    }

    fun stop() {
        running.set(false)
        handler.removeCallbacks(tick)
        clipboard.removePrimaryClipChangedListener(listener)
    }

    private fun readAndroidClip(): String =
        clipboard.primaryClip?.getItemAt(0)?.coerceToText(appContext)?.toString().orEmpty()

    private fun synchronizeOnce() {
        val androidText = readAndroidClip()
        thread(name = "clipboard-sync", isDaemon = true) {
            try {
                val linuxText = readLinux() ?: return@thread
                when {
                    lastAndroid == null && lastLinux == null -> {
                        rememberAndroid(androidText)
                        rememberLinux(linuxText)
                        if (androidText.isNotEmpty() && androidText != linuxText && writeLinux(androidText)) {
                            rememberLinux(androidText)
                        }
                    }
                    androidText != lastAndroid -> {
                        if (writeLinux(androidText)) rememberLinux(androidText)
                        rememberAndroid(androidText)
                    }
                    linuxText != lastLinux -> {
                        rememberLinux(linuxText)
                        rememberAndroid(linuxText)
                        handler.post {
                            if (running.get()) {
                                clipboard.setPrimaryClip(ClipData.newPlainText("DroidDesk", linuxText))
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                Log.d(TAG, "Clipboard sync unavailable: ${error.message}")
            } finally {
                busy.set(false)
            }
        }
    }

    private fun rememberAndroid(value: String) {
        lastAndroid = value
        rememberedAndroid = value
    }

    private fun rememberLinux(value: String) {
        lastLinux = value
        rememberedLinux = value
    }
}
