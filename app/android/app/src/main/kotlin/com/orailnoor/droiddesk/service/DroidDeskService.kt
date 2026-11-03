package com.orailnoor.droiddesk.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.orailnoor.droiddesk.MainActivity
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.DesktopLog
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.view.DesktopActivity
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread

/**
 * Foreground service that keeps the Linux runtime alive.
 *
 * Android aggressively kills background processes (especially Android 12+'s
 * Phantom Process Killer). This service ensures our native Termux/chroot session, desktop
 * environment, and Wayland compositor survive when the user switches apps.
 */
class DroidDeskService : Service() {

    companion object {
        const val CHANNEL_ID = "droiddesk_service"
        const val NOTIFICATION_ID = 1001
        private const val TAG = "DroidDeskService"
        private const val ACTION_STOP = "com.orailnoor.droiddesk.action.STOP_DESKTOP"
    }

    @Volatile private var stopping = false

    override fun onCreate() {
        super.onCreate()
        AndroidAppBridge.start(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopDesktop()
            return START_NOT_STICKY
        }
        val notification = buildNotification("Linux desktop is running")

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        AndroidAppBridge.stop()
        super.onDestroy()
    }

    /** Same teardown as the Flutter "stopLinux" call, triggered from the notification. */
    private fun stopDesktop() {
        // The flag used to stay true forever when the worker died midway, which made every
        // later tap on "Stop" a silent no-op until the process was recreated.
        if (stopping) {
            DesktopLog.append(this, TAG, "stopDesktop ignored: previous stop still in flight")
            return
        }
        stopping = true
        DesktopLog.append(this, TAG, "stopDesktop requested from notification")
        // A bound X11 service survives stopService; unbind the screen first.
        runCatching { DesktopActivity.teardownAndAwait(2_000) }
        thread(name = "notification-stop-linux") {
            var failure: String? = null
            var residual: List<Int> = emptyList()
            try {
                val chrootRuntime = ChrootRuntime(this)
                residual = if (chrootRuntime.hasRoot()) {
                    // forceStopSession SIGKILLs every process still bound to the rootfs and
                    // reports survivors; a null sessionProcess must not count as success.
                    chrootRuntime.forceStopSession()
                } else {
                    LinuxRuntime(this).forceStopSession()
                }
                if (chrootRuntime.hasRoot()) {
                    runCatching { LinuxRuntime(this).forceStopSession() }
                }
                stopService(Intent(this, X11ServerService::class.java))
            } catch (error: Throwable) {
                failure = error.message ?: error.javaClass.simpleName
                Log.e(TAG, "Failed to stop the Linux desktop from the notification", error)
            } finally {
                if (failure == null && residual.isNotEmpty()) {
                    failure = "仍有 ${residual.size} 个进程未退出（pid: ${residual.joinToString(",")}）"
                }
                DesktopLog.append(
                    this,
                    TAG,
                    "stopDesktop finished, failure=$failure residual=$residual",
                )
                // Keep the notification truthful: on failure the service stays alive and the
                // button stays clickable instead of pretending the desktop was stopped.
                if (failure == null) {
                    runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
                    runCatching { stopSelf() }
                } else {
                    stopping = false
                    runCatching { updateNotification("停止失败：$failure") }
                }
            }
        }
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "DroidDesk Linux Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the Linux desktop environment running"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(contentText: String): Notification {
        // Brings the existing task forward (showing the desktop if it is open); otherwise
        // MainActivity reopens the running session without restarting it.
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_DESKTOP)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DroidDeskService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("DroidDesk")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(0, "Open", openIntent)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification)
    }
}
