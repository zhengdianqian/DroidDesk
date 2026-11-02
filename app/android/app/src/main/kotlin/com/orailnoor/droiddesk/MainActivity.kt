package com.orailnoor.droiddesk

import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import android.content.Intent
import android.app.role.RoleManager
import android.os.Bundle
import android.os.Build
import android.os.PowerManager
import android.content.Context
import android.net.Uri
import android.provider.Settings
import com.orailnoor.droiddesk.service.DroidDeskService
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.RootShell
import com.orailnoor.droiddesk.runtime.AndroidAppBridge
import com.orailnoor.droiddesk.runtime.AppUpdater
import com.orailnoor.droiddesk.runtime.DesktopIntegration
import com.orailnoor.droiddesk.runtime.DisplayProfile
import com.orailnoor.droiddesk.runtime.DesktopLog
import com.orailnoor.droiddesk.runtime.DesktopSessionState
import com.orailnoor.droiddesk.runtime.PhantomGuard
import com.orailnoor.droiddesk.view.AndroidSurfaceViewFactory
import com.orailnoor.droiddesk.x11.X11ServerService
import kotlin.concurrent.thread
import android.util.Log
import android.widget.Toast

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.droiddesk/core"
        private const val TAG = "MainActivity"
        /** Sent by the "Open" notification action: show the desktop, resuming a running session. */
        const val ACTION_OPEN_DESKTOP = "com.orailnoor.droiddesk.action.OPEN_DESKTOP"
        private const val DEB_PICK_REQUEST = 4021
        private val packageOperationRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /** Icon lookups may download from the network; cap concurrency so a long list doesn't time out. */
    private val linuxIconExecutor = java.util.concurrent.Executors.newFixedThreadPool(4)

    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private lateinit var desktopIntegration: DesktopIntegration
    /** Result of the .deb file picker, answered in onActivityResult. */
    private var pendingDebResult: MethodChannel.Result? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val logBuffer = StringBuffer()
    private var lastLogFlushTime = 0L
    private val logFlushRunnable = Runnable { flushLogBuffer() }

    private fun flushLogBuffer() {
        val text = synchronized(logBuffer) {
            val t = logBuffer.toString()
            logBuffer.setLength(0)
            t
        }
        if (text.isNotEmpty()) {
            flutterEngine?.dartExecutor?.binaryMessenger?.let { messenger ->
                MethodChannel(messenger, CHANNEL).invokeMethod("onTerminalOutput", mapOf("text" to text))
            }
        }
    }

    private fun appendTerminalOutput(chunk: String) {
        synchronized(logBuffer) {
            logBuffer.append(chunk)
            val now = System.currentTimeMillis()
            if (now - lastLogFlushTime > 150) {
                lastLogFlushTime = now
                mainHandler.post(logFlushRunnable)
            } else {
                mainHandler.removeCallbacks(logFlushRunnable)
                mainHandler.postDelayed(logFlushRunnable, 150)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        }
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        desktopIntegration = DesktopIntegration(this)

        if (intent.getBooleanExtra("autoSetup", false)) {
            runAutoChrootSetup()
        }
        handleHomeLaunch(intent)
        maybeAutoStartDesktop(intent)
    }

    /**
     * Copies the picked .deb into app storage and hands it to the guest installer. The system
     * picker grants a temporary read URI, which the chroot cannot open directly.
     */
    @Deprecated("FlutterActivity still delivers the document picker through onActivityResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != DEB_PICK_REQUEST) return
        val pending = pendingDebResult
        pendingDebResult = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) {
            pending?.success(mapOf("ok" to false, "cancelled" to true, "message" to "已取消选择"))
            return
        }
        val displayName = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
            }
        }.getOrNull() ?: "droiddesk-install.deb"
        installThread("install-deb") {
            val messenger = flutterEngine?.dartExecutor?.binaryMessenger
            val progress: (Double, String) -> Unit = { value, status ->
                if (messenger != null) runOnUiThread {
                    MethodChannel(messenger, CHANNEL).invokeMethod(
                        "onPackageOperationProgress",
                        mapOf("progress" to value, "status" to status),
                    )
                }
            }
            val log: (String) -> Unit = { chunk ->
                if (messenger != null) runOnUiThread {
                    MethodChannel(messenger, CHANNEL).invokeMethod(
                        "onPackageOperationLog", mapOf("text" to chunk),
                    )
                }
            }
            val outcome = try {
                val target = java.io.File(cacheDir, "droiddesk-install.deb").apply {
                    parentFile?.mkdirs()
                }
                contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use(input::copyTo)
                } ?: throw java.io.IOException("无法读取所选文件")
                if (!chrootRuntime.hasRoot()) {
                    mapOf("ok" to false, "message" to "deb 安装需要 root（chroot 运行时）")
                } else {
                    val ok = chrootRuntime.installDeb(target, progress, log)
                    mapOf("ok" to ok, "name" to displayName)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "deb install failed", error)
                mapOf("ok" to false, "message" to (error.message ?: "deb 安装失败"))
            }
            runOnUiThread { pending?.success(outcome) }
        }
    }

    /** Optional "Auto-Start Linux Desktop" setting: open the chroot desktop whenever the app starts. */
    private fun maybeAutoStartDesktop(launchIntent: Intent) {
        // Home presses and the notification's Open action already launch the desktop.
        val isHomeLaunch = launchIntent.action == Intent.ACTION_MAIN && launchIntent.hasCategory(Intent.CATEGORY_HOME)
        if (isHomeLaunch || launchIntent.action == ACTION_OPEN_DESKTOP) return
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val autoStart = prefs.getBoolean("flutter.autoStartDesktop", false)
        if (autoStart && chrootRuntime.hasRoot() && chrootRuntime.isRootfsReady()) {
            startForegroundService()
            val desktopIntent = Intent(this, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                putExtra("startSession", !chrootRuntime.isRunning())
                putExtra("mode", "chroot")
                putExtra("de", "xfce4")
            }
            startActivity(desktopIntent)
        }
    }

    override fun onResume() {
        super.onResume()
        restoreSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            restoreSystemBars()
            window.decorView.post { restoreSystemBars() }
        }
    }

    @Suppress("DEPRECATION")
    /**
     * Undo the desktop's immersive mode while keeping the app edge-to-edge: Flutter draws behind
     * the transparent bars and pads with SafeArea. Fitting system windows here would leave the
     * bare (black) window showing through the status bar.
     */
    private fun restoreSystemBars() {
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.decorView.systemUiVisibility =
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.show(
                android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars()
            )
        }
        // Resetting the flags above clears the icon colour; re-apply the app theme's choice.
        lightSystemBars?.let(::updateStatusBarIcons)
    }

    /** Last status-bar icon mode requested by Flutter; null until the theme reports it. */
    private var lightSystemBars: Boolean? = null

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleHomeLaunch(intent)
    }

    private fun handleHomeLaunch(homeIntent: Intent) {
        val isHomeLaunch = homeIntent.action == Intent.ACTION_MAIN && homeIntent.hasCategory(Intent.CATEGORY_HOME)
        if (!isHomeLaunch && homeIntent.action != ACTION_OPEN_DESKTOP) return
        if (homeIntent.action == ACTION_OPEN_DESKTOP && com.orailnoor.droiddesk.view.DesktopActivity.isOpen()) {
            // Reuse the existing desktop screen; a second one would fight over the shared LorieView.
            startActivity(
                Intent(this, com.orailnoor.droiddesk.view.DesktopActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            if (!isTaskRoot) finish()
            return
        }

        thread(name = "home-launch") {
            val rooted = chrootRuntime.hasRoot()
            val desktopEnv = if (rooted) "xfce4" else linuxRuntime.getInstalledDE()
            val ready = if (rooted) {
                chrootRuntime.isRootfsReady() && chrootRuntime.isDesktopInstalled()
            } else {
                linuxRuntime.isBootstrapped() && desktopEnv.isNotEmpty()
            }
            if (!ready) {
                Log.i(TAG, "Home launch opened setup because Linux is not ready")
                return@thread
            }

            startForegroundService()
            val sessionRunning = if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning()
            runOnUiThread {
                startActivity(Intent(this, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                    putExtra("startSession", !sessionRunning)
                    putExtra("mode", if (rooted) "chroot" else "termux")
                    putExtra("de", desktopEnv)
                })
            }
        }
    }

    /**
     * Hidden developer/auto-tester path: download, extract, install, and launch
     * the chroot desktop without any Flutter UI interaction.
     */
    private fun runAutoChrootSetup() {
        thread(name = "auto-chroot-setup") {
            try {
                Log.i(TAG, "Auto-setup: checking root...")
                if (!chrootRuntime.hasRoot()) {
                    runOnUiThread {
                        android.widget.Toast.makeText(this, "Auto-setup requires root", android.widget.Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }

                startForegroundService()

                if (!chrootRuntime.isRootfsReady()) {
                    Log.i(TAG, "Auto-setup: downloading rootfs...")
                    val dlLatch = java.util.concurrent.CountDownLatch(1)
                    var dlOk = false
                    chrootRuntime.downloadRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            dlOk = progress >= 1.0
                            dlLatch.countDown()
                        }
                    }
                    dlLatch.await()
                    if (!dlOk) throw RuntimeException("Rootfs download failed")

                    Log.i(TAG, "Auto-setup: extracting rootfs...")
                    val exLatch = java.util.concurrent.CountDownLatch(1)
                    var exOk = false
                    chrootRuntime.extractRootfs { progress, _ ->
                        if (progress >= 1.0 || progress < 0) {
                            exOk = progress >= 1.0
                            exLatch.countDown()
                        }
                    }
                    exLatch.await()
                    if (!exOk) throw RuntimeException("Rootfs extraction failed")
                }

                if (!chrootRuntime.isDesktopInstalled()) {
                    Log.i(TAG, "Auto-setup: installing desktop environment...")
                    val inLatch = java.util.concurrent.CountDownLatch(1)
                    var inOk = false
                    chrootRuntime.installDesktopEnvironment(
                        desktopEnv = "xfce4",
                        onProgress = { progress, _ ->
                            if (progress >= 1.0 || progress < 0) {
                                inOk = progress >= 1.0
                                inLatch.countDown()
                            }
                        },
                        onLog = {}
                    )
                    inLatch.await()
                    if (!inOk) throw RuntimeException("Desktop installation failed")
                }

                Log.i(TAG, "Auto-setup: launching desktop...")
                runOnUiThread {
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                        putExtra("startSession", !chrootRuntime.isRunning())
                        putExtra("mode", "chroot")
                        putExtra("de", "xfce4")
                    }
                    startActivity(intent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Auto-setup failed", e)
                runOnUiThread {
                    android.widget.Toast.makeText(this, "Auto-setup failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        flutterEngine
            .platformViewsController
            .registry
            .registerViewFactory("droiddesk-surface", AndroidSurfaceViewFactory())

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {

                // ── Runtime Status ──
                "getRuntimeStatus" -> {
                    val rooted = chrootRuntime.hasRoot()
                    result.success(mapOf(
                        "isBootstrapped" to if (rooted) chrootRuntime.isRootfsReady() else linuxRuntime.isBootstrapped(),
                        "isRunning" to if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning(),
                        "hasRoot" to rooted,
                        "distro" to if (rooted) "ubuntu-chroot" else "termux-native",
                        "installedDE" to if (rooted) {
                            if (chrootRuntime.isDesktopInstalled()) "xfce4" else ""
                        } else {
                            linuxRuntime.getInstalledDE()
                        },
                        "rootfsPath" to if (rooted) chrootRuntime.getRootfsPath() else "",
                        "rootfsSizeMB" to if (rooted) chrootRuntime.getRootfsSizeMB() else 0L
                    ))
                }

                // ── Device Info ──
                "getDeviceInfo" -> {
                    result.success(mapOf(
                        "model" to Build.MODEL,
                        "brand" to Build.BRAND,
                        "androidVersion" to Build.VERSION.RELEASE,
                        "sdkVersion" to Build.VERSION.SDK_INT,
                        "cpuAbi" to Build.SUPPORTED_ABIS.firstOrNull(),
                        "gpuVendor" to getGpuVendor(),
                        "graphicsMode" to if (chrootRuntime.hasRoot()) {
                            "Software (llvmpipe)"
                        } else {
                            linuxRuntime.getGraphicsMode()
                        },
                        "totalRamMB" to getTotalRam(),
                        "availableStorageMB" to getAvailableStorage()
                    ))
                }

                // ── Root checks ──
                "checkRoot" -> {
                    thread {
                        val ok = chrootRuntime.hasRoot()
                        runOnUiThread { result.success(ok) }
                    }
                }

                "resetRootCache" -> {
                    RootShell(this).resetCache()
                    result.success(true)
                }

                // ── Chroot rootfs management (rooted) ──
                "downloadRootfs" -> {
                    installThread("download-rootfs") {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.downloadRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onDownloadProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "extractRootfs" -> {
                    installThread("extract-rootfs") {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.extractRootfs { progress, status ->
                                runOnUiThread {
                                    flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                        MethodChannel(messenger, CHANNEL).invokeMethod(
                                            "onExtractProgress",
                                            mapOf("progress" to progress, "status" to status)
                                        )
                                    }
                                }
                                if (progress >= 1.0 || progress < 0) {
                                    success = progress >= 1.0
                                    latch.countDown()
                                }
                            }
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                "installDesktopEnvironment" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    installThread("install-desktop-chroot") {
                        try {
                            val latch = java.util.concurrent.CountDownLatch(1)
                            var success = false
                            chrootRuntime.installDesktopEnvironment(
                                desktopEnv,
                                { progress, status ->
                                    runOnUiThread {
                                        flutterEngine.dartExecutor.binaryMessenger.let { messenger ->
                                            MethodChannel(messenger, CHANNEL).invokeMethod(
                                                "onInstallProgress",
                                                mapOf("progress" to progress, "status" to status)
                                            )
                                        }
                                    }
                                    if (progress >= 1.0 || progress < 0) {
                                        success = progress >= 1.0
                                        latch.countDown()
                                    }
                                },
                                { logChunk ->
                                    appendTerminalOutput(logChunk)
                                }
                            )
                            latch.await()
                            runOnUiThread { result.success(success) }
                        } catch (e: Exception) {
                            runOnUiThread { result.success(false) }
                        }
                    }
                }

                // ── Native Termux desktop install (non-root fallback) ──
                "installDesktopNative" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    installThread("install-desktop-native") {
                        linuxRuntime.setInstallLogSink { chunk ->
                            appendTerminalOutput(chunk)
                        }
                        try {
                            val ok = linuxRuntime.installDesktopEnvironmentNative(
                                desktopEnv,
                            ) { progress, status ->
                                runOnUiThread {
                                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                                        "onInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                                }
                            }
                            runOnUiThread { result.success(ok) }
                        } finally {
                            linuxRuntime.setInstallLogSink(null)
                        }
                    }
                }

                "getOptionalApps" -> {
                    val status = if (chrootRuntime.hasRoot()) {
                        chrootRuntime.getOptionalAppsStatus()
                    } else {
                        linuxRuntime.getOptionalAppsStatus()
                    }
                    result.success(status)
                }

                "installOptionalApp" -> {
                    val appId = call.argument<String>("appId") ?: ""
                    installThread("install-optional-app") {
                        val logSink: (String) -> Unit = { chunk ->
                            appendTerminalOutput(chunk)
                        }
                        val progressSink: (Double, String) -> Unit = { progress, status ->
                            runOnUiThread {
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod(
                                        "onOptionalInstallProgress",
                                        mapOf("progress" to progress, "status" to status),
                                    )
                            }
                        }

                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.installOptionalApp(appId, progressSink, logSink)
                        } else {
                            linuxRuntime.setInstallLogSink(logSink)
                            try {
                                linuxRuntime.installOptionalApp(appId, progressSink)
                            } finally {
                                linuxRuntime.setInstallLogSink(null)
                            }
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                // ── Native package store ──
                "searchNativePackages" -> {
                    val query = call.argument<String>("query") ?: ""
                    val limit = call.argument<Int>("limit") ?: 60
                    thread(name = "package-search") {
                        val packages = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.searchPackages(query, limit)
                        } else {
                            linuxRuntime.searchNativePackages(query, limit)
                        }
                        runOnUiThread { result.success(packages) }
                    }
                }

                "getInstalledNativePackages" -> {
                    thread(name = "installed-packages") {
                        val packages = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.installedPackages()
                        } else {
                            linuxRuntime.getInstalledNativePackages()
                        }
                        runOnUiThread { result.success(packages) }
                    }
                }

                "installNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = false)
                }

                "getUpgradablePackages" -> {
                    val refresh = call.argument<Boolean>("refresh") ?: false
                    thread(name = "upgradable-packages") {
                        val packages = runCatching {
                            if (chrootRuntime.hasRoot()) chrootRuntime.upgradablePackages(refresh)
                            else linuxRuntime.getUpgradablePackages(refresh)
                        }.getOrDefault(emptyList())
                        runOnUiThread { result.success(packages) }
                    }
                }

                "upgradeAllPackages" -> {
                    val rooted = chrootRuntime.hasRoot()
                    runPackageOperation(
                        flutterEngine, result, "updates", remove = false,
                    ) { progress ->
                        if (rooted) {
                            chrootRuntime.upgradeAll(progress) { chunk ->
                                MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
                                    .invokeMethod("onPackageOperationLog", mapOf("text" to chunk))
                            }
                        } else {
                            linuxRuntime.upgradeAllPackages(progress)
                        }
                    }
                }

                "getStorageInfo" -> {
                    thread(name = "storage-info") {
                        val info = if (chrootRuntime.hasRoot()) {
                            val stat = android.os.StatFs(filesDir.absolutePath)
                            mapOf<String, Any>(
                                "deviceFree" to stat.availableBytes,
                                "deviceTotal" to stat.totalBytes,
                                "system" to 0L,
                                "home" to 0L,
                                "cache" to 0L,
                                "available" to true,
                            )
                        } else {
                            runCatching { linuxRuntime.getStorageInfo() }.getOrDefault(emptyMap())
                        }
                        runOnUiThread { result.success(info) }
                    }
                }

                "cleanPackageCache" -> {
                    thread(name = "clean-package-cache") {
                        val freed = if (chrootRuntime.hasRoot()) {
                            runCatching {
                                chrootRuntime.executeCommand("apt-get clean; rm -rf /var/lib/apt/lists/*")
                                0L
                            }.getOrDefault(-1L)
                        } else {
                            runCatching { linuxRuntime.cleanPackageCache() }.getOrDefault(-1L)
                        }
                        runOnUiThread { result.success(freed) }
                    }
                }

                "removeNativePackage" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    runPackageOperation(flutterEngine, result, packageName, remove = true)
                }

                "cancelNativePackageOperation" -> {
                    result.success(linuxRuntime.cancelPackageOperation())
                }

                // ── Android + Linux desktop integration ──
                "searchDesktopItems" -> {
                    val query = call.argument<String>("query") ?: ""
                    thread(name = "desktop-search") {
                        val items = desktopIntegration.search(query, chrootRuntime.hasRoot())
                        runOnUiThread { result.success(items) }
                    }
                }

                "launchDesktopItem" -> {
                    val kind = call.argument<String>("kind") ?: ""
                    val id = call.argument<String>("id") ?: ""
                    when (kind) {
                        "android" -> result.success(AndroidAppBridge.launchAndroidPackage(this, id))
                        "setting" -> {
                            AndroidAppBridge.launchSystemAction(this, id)
                            result.success(true)
                        }
                        "linux", "folder" -> {
                            thread(name = "launch-desktop-item") {
                                val rooted = chrootRuntime.hasRoot()
                                val running = if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning()
                                if (!running) {
                                    runOnUiThread { openDesktop(startSession = true) }
                                    if (rooted) chrootRuntime.waitForDesktopReady("xfce4")
                                    else linuxRuntime.waitForDesktopReady(linuxRuntime.getInstalledDE())
                                }
                                val safeId = id.takeIf { it.matches(Regex("[A-Za-z0-9_.+-]+")) }
                                val command = if (kind == "linux" && safeId != null) {
                                    "DISPLAY=:0 gtk-launch '$safeId' >/dev/null 2>&1 &"
                                } else {
                                    val folder = when (id) {
                                        "Downloads", "Documents", "Pictures" -> id
                                        else -> ""
                                    }
                                    "DISPLAY=:0 thunar \"${if (chrootRuntime.hasRoot()) "/root" else filesDir.absolutePath + "/home"}${if (folder.isEmpty()) "" else "/$folder"}\" >/dev/null 2>&1 &"
                                }
                                if (rooted) chrootRuntime.executeCommand(command)
                                else linuxRuntime.executeCommand(command)
                                runOnUiThread {
                                    if (running) openDesktop(startSession = false)
                                    result.success(true)
                                }
                            }
                        }
                        else -> result.success(false)
                    }
                }

                // ── In-app updates ──
                "getAppVersion" -> result.success(AppUpdater.currentVersion(this))
                "canInstallUpdates" -> result.success(AppUpdater.canInstall(this))
                "openInstallPermission" -> {
                    runCatching { AppUpdater.openInstallPermissionSettings(this) }
                    result.success(true)
                }
                "cancelUpdateDownload" -> {
                    AppUpdater.cancel()
                    result.success(true)
                }
                "cleanupUpdates" -> {
                    thread(name = "update-cleanup") { runCatching { AppUpdater.cleanup(this) } }
                    result.success(true)
                }
                "downloadUpdate" -> {
                    val url = call.argument<String>("url").orEmpty()
                    val size = (call.argument<Number>("size") ?: 0).toLong()
                    val tag = call.argument<String>("tag") ?: "latest"
                    installThread("update-download") {
                        val messenger = flutterEngine.dartExecutor.binaryMessenger
                        val outcome: Map<String, Any?> = try {
                            val apk = AppUpdater.download(this, url, size, tag) { done, total ->
                                runOnUiThread {
                                    MethodChannel(messenger, CHANNEL).invokeMethod(
                                        "onUpdateProgress",
                                        mapOf("downloaded" to done, "total" to total),
                                    )
                                }
                            }
                            mapOf("ok" to true, "path" to apk.absolutePath)
                        } catch (error: AppUpdater.UpdateException) {
                            mapOf("ok" to false, "code" to error.code, "message" to error.message)
                        } catch (error: Throwable) {
                            Log.e(TAG, "Update download failed", error)
                            mapOf("ok" to false, "code" to "unknown", "message" to error.message)
                        }
                        runOnUiThread { result.success(outcome) }
                    }
                }
                "installUpdate" -> {
                    val path = call.argument<String>("path").orEmpty()
                    val ok = runCatching { AppUpdater.install(this, java.io.File(path)) }
                        .onFailure { Log.e(TAG, "Could not open the installer", it) }
                        .isSuccess
                    result.success(ok)
                }

                "getAndroidApps" -> result.success(AndroidAppBridge.listApps(this))
                "getLinuxAppIcon" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    val iconNames = call.argument<List<String>>("iconNames").orEmpty()
                    linuxIconExecutor.execute {
                        // The native runtime lives in filesDir/usr; a chroot rootfs is root-owned,
                        // so rooted devices rely on the Papirus fallback.
                        val prefix = java.io.File(filesDir, "usr").takeIf { it.isDirectory }
                        val icon = runCatching {
                            com.orailnoor.droiddesk.runtime.LinuxAppIcons.find(
                                this@MainActivity, prefix, packageName, iconNames,
                            )
                        }.getOrNull()
                        runOnUiThread { result.success(icon) }
                    }
                }
                "getAppIcon" -> {
                    val packageName = call.argument<String>("package") ?: ""
                    thread(name = "app-icon") {
                        val png = runCatching {
                            val size = 96
                            val bitmap = android.graphics.Bitmap.createBitmap(
                                size, size, android.graphics.Bitmap.Config.ARGB_8888,
                            )
                            packageManager.getApplicationIcon(packageName).apply {
                                setBounds(0, 0, size, size)
                                draw(android.graphics.Canvas(bitmap))
                            }
                            java.io.ByteArrayOutputStream().use { out ->
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                                out.toByteArray()
                            }
                        }.getOrNull()
                        runOnUiThread { result.success(png) }
                    }
                }
                "getDockPackages" -> result.success(AndroidAppBridge.getDockPackages(this))
                "saveDockPackages" -> {
                    val packages = call.argument<List<String>>("packages").orEmpty()
                    AndroidAppBridge.setDockPackages(this, packages)
                    thread(name = "sync-desktop-dock") {
                        val synced = runCatching {
                            syncAndroidDesktopIntegration()
                            true
                        }.onFailure { Log.e(TAG, "Could not refresh desktop dock", it) }
                            .getOrDefault(false)
                        runOnUiThread { result.success(synced) }
                    }
                }

                "listDesktopSnapshots" -> result.success(desktopIntegration.listSnapshots())
                "createDesktopSnapshot" -> thread(name = "create-desktop-snapshot") {
                    runCatching { desktopIntegration.createSnapshot(chrootRuntime.hasRoot()) }
                        .onSuccess { value -> runOnUiThread { result.success(value) } }
                        .onFailure { error -> runOnUiThread { result.error("snapshot", error.message, null) } }
                }
                "restoreDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    thread(name = "restore-desktop-snapshot") {
                        runCatching {
                            val rooted = chrootRuntime.hasRoot()
                            if (chrootRuntime.isRunning()) chrootRuntime.stopSession()
                            if (linuxRuntime.isRunning()) linuxRuntime.stopSession()
                            val savedPackages = desktopIntegration.restoreSnapshot(name, rooted)
                            val missingPackages = desktopIntegration.missingPackages(savedPackages, rooted)
                            if (missingPackages.isNotEmpty()) {
                                val command = "DEBIAN_FRONTEND=noninteractive apt-get " +
                                    "-o Dpkg::Options::=--force-confdef " +
                                    "-o Dpkg::Options::=--force-confold --no-upgrade install -y " +
                                    missingPackages.joinToString(" ")
                                if (rooted) {
                                    chrootRuntime.ensureMounts()
                                    try {
                                        chrootRuntime.executeCommand(command)
                                    } finally {
                                        chrootRuntime.unmountAll()
                                    }
                                } else {
                                    linuxRuntime.executeCommand(command)
                                }
                            }
                            true
                        }.onSuccess { value -> runOnUiThread { result.success(value) } }
                            .onFailure { error -> runOnUiThread { result.error("restore", error.message, null) } }
                    }
                }
                "deleteDesktopSnapshot" -> {
                    val name = call.argument<String>("name") ?: ""
                    result.success(runCatching { desktopIntegration.deleteSnapshot(name) }.getOrDefault(false))
                }
                "openAndroidControl" -> {
                    AndroidAppBridge.launchSystemAction(this, call.argument<String>("action") ?: "settings")
                    result.success(true)
                }

                // ── Start Linux session ──
                "startLinux" -> {
                    val desktopEnv = call.argument<String>("de") ?: "xfce4"
                    val mode = call.argument<String>("mode") ?: "x11"
                    // Sizes are advisory: the X server uses DisplayProfile (physical pixels
                    // by default) before LorieView is measured. Never downscale here.
                    val display = com.orailnoor.droiddesk.runtime.DisplayProfile.effectiveSize(this)
                    val width = call.argument<Int>("width") ?: display.x
                    val height = call.argument<Int>("height") ?: display.y

                    startForegroundService()

                    if (chrootRuntime.hasRoot()) {
                        // Rooted fast path: chroot + LorieView
                        thread {
                            if (!chrootRuntime.isRootfsReady()) {
                                Log.w(TAG, "Chroot rootfs not ready; cannot start session")
                                runOnUiThread { result.success(false) }
                                return@thread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", !chrootRuntime.isRunning())
                                    putExtra("mode", "chroot")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    } else if (chrootRuntime.isRootfsReady()) {
                        // A ready Ubuntu image plus a denied/late root grant used to fall
                        // through to the native runtime, which left a mix of stale desktop
                        // processes behind and made every second launch fail. Fail loudly
                        // with an actionable message instead of silently switching runtimes.
                        Log.e(TAG, "Ubuntu rootfs exists but root is not available")
                        runOnUiThread {
                            Toast.makeText(
                                this@MainActivity,
                                "已安装的 Ubuntu 需要 root 权限：请在 KernelSU/Magisk 中允许 DroidDesk 获取 root，然后重试",
                                Toast.LENGTH_LONG,
                            ).show()
                            result.success(false)
                        }
                    } else {
                        // Non-root fallback: native Termux path
                        installThread("start-linux-native") {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            val installed = linuxRuntime.getInstalledDE()
                            val ready = installed == desktopEnv ||
                                linuxRuntime.installDesktopEnvironmentNative(desktopEnv)
                            if (!ready) {
                                Log.e(TAG, "Native Termux desktop setup failed; session was not launched")
                                runOnUiThread {
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Native Linux setup failed. Check the setup log.",
                                        Toast.LENGTH_LONG,
                                    ).show()
                                    result.success(false)
                                }
                                return@installThread
                            }
                            runOnUiThread {
                                val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                                    putExtra("startSession", !linuxRuntime.isRunning())
                                    putExtra("mode", "termux")
                                    putExtra("de", desktopEnv)
                                }
                                startActivity(intent)
                                result.success(true)
                            }
                        }
                    }
                }

                "launchDesktopActivity" -> {
                    // Same deterministic choice as startLinux: never flip runtimes silently.
                    val rooted = chrootRuntime.hasRoot()
                    val rootedSession = rooted || chrootRuntime.isRootfsReady()
                    if (rootedSession && !rooted) {
                        Toast.makeText(
                            this,
                            "已安装的 Ubuntu 需要 root 权限，请在 KernelSU/Magisk 中授权后重试",
                            Toast.LENGTH_LONG,
                        ).show()
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    // Flutter's "running" state can be stale (e.g. after Stop from the notification).
                    val running = if (rooted) chrootRuntime.isRunning() else linuxRuntime.isRunning()
                    if (!running) startForegroundService()
                    val intent = Intent(this@MainActivity, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
                        putExtra("startSession", !running)
                        putExtra("mode", if (rooted) "chroot" else "termux")
                        putExtra("de", if (rooted) "xfce4" else linuxRuntime.getInstalledDE())
                    }
                    startActivity(intent)
                    result.success(true)
                }

                "stopLinux" -> {
                    // A desktop screen left in the back stack would reopen onto a dead session.
                    com.orailnoor.droiddesk.view.DesktopActivity.finishIfOpen()
                    thread(name = "stop-linux-session") {
                        if (chrootRuntime.hasRoot() || chrootRuntime.isRunning()) {
                            chrootRuntime.stopSession()
                        }
                        linuxRuntime.stopSession()
                        stopService(Intent(this@MainActivity, X11ServerService::class.java))
                        stopForegroundService()
                        runOnUiThread { result.success(true) }
                    }
                }

                // ── Command execution ──
                "executeCommand" -> {
                    val command = call.argument<String>("command") ?: ""
                    Thread {
                        val output = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.executeCommand(command) { chunk ->
                                appendTerminalOutput(chunk)
                            }
                        } else {
                            linuxRuntime.executeCommand(command) { chunk ->
                                appendTerminalOutput(chunk)
                            }
                        }
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            result.success(output)
                        }
                    }.start()
                }

                "interruptCommand" -> {
                    linuxRuntime.interruptCommand()
                    result.success(true)
                }

                // ── System ──
                "requestBatteryOptimization" -> {
                    requestIgnoreBatteryOptimization()
                    result.success(true)
                }

                "isBatteryOptimized" -> {
                    result.success(isBatteryOptimized())
                }

                "requestDefaultLauncher" -> {
                    requestDefaultLauncher()
                    result.success(true)
                }

                "unsetDefaultLauncher" -> {
                    thread(name = "unset-home-role") {
                        val removed = unsetDefaultLauncher()
                        runOnUiThread {
                            if (removed) openHomeChooser() else openHomeSettings()
                            result.success(removed)
                        }
                    }
                }

                "isDefaultLauncher" -> {
                    result.success(isDefaultLauncher())
                }

                "updateStatusBarTheme" -> {
                    val isLight = call.argument<Boolean>("isLightMode") ?: false
                    runOnUiThread {
                        updateStatusBarIcons(isLight)
                    }
                    result.success(true)
                }

                "setupBootstrap" -> {
                    if (chrootRuntime.hasRoot()) {
                        // Nothing to bootstrap for chroot; rootfs handles it
                        result.success(true)
                    } else {
                        installThread("setup-bootstrap") {
                            linuxRuntime.extractBootstrapIfNeeded(applicationContext)
                            linuxRuntime.setupBootstrap()
                            runOnUiThread { result.success(true) }
                        }
                    }
                }

                // ── Display profile: physical-pixel 1:1 framebuffer + in-desktop UI scale ──
                "getDisplayInfo" -> result.success(DisplayProfile.info(this))

                // ── Android 12+ phantom-process killer ──
                "getPhantomStatus" -> thread(name = "phantom-status") {
                    val status = PhantomGuard.status(this)
                    runOnUiThread { result.success(status) }
                }

                "relaxPhantomLimit" -> thread(name = "phantom-relax") {
                    val outcome = PhantomGuard.relax(this)
                    runOnUiThread { result.success(outcome) }
                }

                // ── Desktop crash journal ──
                "getDesktopLog" -> thread(name = "desktop-log") {
                    val text = DesktopLog.read(this)
                    runOnUiThread { result.success(text) }
                }

                "clearDesktopLog" -> {
                    DesktopLog.clear(this)
                    result.success(true)
                }

                "getSessionState" -> result.success(
                    mapOf(
                        "expectedRunning" to DesktopSessionState.expectedRunning,
                        "mode" to DesktopSessionState.mode,
                    ),
                )

                // ── APT maintenance (works in both chroot and native runtimes) ──
                "repairPackageManager" -> {
                    val messenger = flutterEngine.dartExecutor.binaryMessenger
                    installThread("apt-repair") {
                        val rooted = chrootRuntime.hasRoot()
                        val progress: (Double, String) -> Unit = { value, status ->
                            runOnUiThread {
                                MethodChannel(messenger, CHANNEL).invokeMethod(
                                    "onPackageOperationProgress",
                                    mapOf("progress" to value, "status" to status),
                                )
                            }
                        }
                        val log: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(messenger, CHANNEL).invokeMethod(
                                    "onPackageOperationLog", mapOf("text" to chunk),
                                )
                            }
                        }
                        val ok = if (rooted) {
                            chrootRuntime.repairPackageManager(progress, log)
                        } else {
                            progress(0.05, "正在修复软件包管理器…")
                            val output = linuxRuntime.executeCommand(
                                "dpkg --configure -a; apt-get --fix-broken install -y; apt-get update", log,
                            )
                            progress(1.0, "修复命令执行完毕")
                            !output.contains("E: ")
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                "diagnosePackageManager" -> {
                    val messenger = flutterEngine.dartExecutor.binaryMessenger
                    installThread("apt-diagnose") {
                        val log: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(messenger, CHANNEL).invokeMethod(
                                    "onPackageOperationLog", mapOf("text" to chunk),
                                )
                            }
                        }
                        if (chrootRuntime.hasRoot() && chrootRuntime.isRootfsReady()) {
                            chrootRuntime.diagnosePackageManager(log)
                        } else {
                            log("当前为 Termux 运行模式（无 root），不需要 Ubuntu 源诊断\n")
                        }
                        runOnUiThread { result.success(true) }
                    }
                }

                "applyBestMirror" -> {
                    val messenger = flutterEngine.dartExecutor.binaryMessenger
                    installThread("apt-mirror") {
                        val log: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(messenger, CHANNEL).invokeMethod(
                                    "onPackageOperationLog", mapOf("text" to chunk),
                                )
                            }
                        }
                        val ok = if (chrootRuntime.hasRoot()) {
                            chrootRuntime.applyBestMirror(log)
                        } else {
                            // The native runtime uses Termux repositories, not Ubuntu ports.
                            false
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                "applyChineseEnvironment" -> {
                    val messenger = flutterEngine.dartExecutor.binaryMessenger
                    installThread("zh-migration") {
                        val log: (String) -> Unit = { chunk ->
                            runOnUiThread {
                                MethodChannel(messenger, CHANNEL).invokeMethod(
                                    "onPackageOperationLog", mapOf("text" to chunk),
                                )
                            }
                        }
                        val ok = when {
                            chrootRuntime.hasRoot() && chrootRuntime.isRootfsReady() ->
                                chrootRuntime.applyChineseEnvironment(log)
                            linuxRuntime.isBootstrapped() -> {
                                linuxRuntime.applyChineseEnvironment(log)
                                true
                            }
                            else -> false
                        }
                        runOnUiThread { result.success(ok) }
                    }
                }

                // Resolution / scale changes only take effect once the X server is rebuilt,
                // otherwise the old framebuffer keeps being stretched into the new surface.
                "restartDesktopForDisplay" -> {
                    val before = DisplayProfile.effectiveSize(this)
                    thread(name = "display-restart") {
                        if (chrootRuntime.isRunning()) chrootRuntime.stopSession()
                        if (linuxRuntime.isRunning()) linuxRuntime.stopSession()
                        stopService(Intent(this@MainActivity, X11ServerService::class.java))
                        val after = DisplayProfile.effectiveSize(this)
                        DesktopLog.append(
                            this@MainActivity,
                            TAG,
                            "display change restart: ${before.x}x${before.y} -> ${after.x}x${after.y}",
                        )
                        runOnUiThread {
                            result.success(
                                mapOf(
                                    "before" to "${before.x}x${before.y}",
                                    "after" to "${after.x}x${after.y}",
                                ),
                            )
                        }
                    }
                }

                "pickDebPackage" -> {
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(
                            Intent.EXTRA_MIME_TYPES,
                            arrayOf("application/vnd.debian.binary-package", "application/octet-stream"),
                        )
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    pendingDebResult = result
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, DEB_PICK_REQUEST)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun updateStatusBarIcons(isLightMode: Boolean) {
        lightSystemBars = isLightMode
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val lightBars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (isLightMode) lightBars else 0,
                lightBars,
            )
        } else {
            @Suppress("DEPRECATION")
            var flags = window.decorView.systemUiVisibility
            flags = if (isLightMode) {
                flags or android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            } else {
                flags and android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            }
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = flags
        }
    }

    private fun syncAndroidDesktopIntegration() {
        val rooted = chrootRuntime.hasRoot()
        if (rooted) {
            val rootfs = java.io.File(filesDir, "rootfs")
            AndroidAppBridge.syncLaunchers(
                context = this,
                homeDir = java.io.File(rootfs, "root"),
                python = java.io.File(rootfs, "usr/bin/python3"),
                sessionRoot = rootfs,
            )
            if (chrootRuntime.isRunning()) {
                chrootRuntime.executeCommand(
                    "export DBUS_SESSION_BUS_ADDRESS=unix:path=/tmp/dbus-session; " +
                        AndroidAppBridge.xfceDockCommand(this) +
                        " DISPLAY=:0 xfce4-panel -r >/dev/null 2>&1 || true",
                )
            }
        } else {
            AndroidAppBridge.syncLaunchers(
                context = this,
                homeDir = java.io.File(filesDir, "home"),
                python = java.io.File(filesDir, "usr/bin/python3"),
            )
            if (linuxRuntime.isRunning()) {
                linuxRuntime.executeCommand(
                    AndroidAppBridge.xfceDockCommand(this) +
                        " DISPLAY=:0 xfce4-panel -r >/dev/null 2>&1 || true",
                )
            }
        }
    }

    private fun openDesktop(startSession: Boolean) {
        val rooted = chrootRuntime.hasRoot()
        startForegroundService()
        startActivity(Intent(this, com.orailnoor.droiddesk.view.DesktopActivity::class.java).apply {
            putExtra("startSession", startSession)
            putExtra("mode", if (rooted) "chroot" else "termux")
            putExtra("de", if (rooted) "xfce4" else linuxRuntime.getInstalledDE())
        })
    }

    private fun runPackageOperation(
        flutterEngine: FlutterEngine,
        result: MethodChannel.Result,
        packageName: String,
        remove: Boolean,
        operation: ((progress: (Double, String) -> Unit) -> Boolean)? = null,
    ) {
        if (!packageOperationRunning.compareAndSet(false, true)) {
            MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                "onPackageOperationProgress",
                mapOf(
                    "progress" to -1.0,
                    "status" to "Previous package operation is still stopping. Try again.",
                ),
            )
            result.success(false)
            return
        }
        installThread(if (remove) "package-remove" else "package-install") {
            val rooted = chrootRuntime.hasRoot()
            if (!rooted) linuxRuntime.beginPackageOperation()
            val logSink: (String) -> Unit = { chunk ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationLog",
                        mapOf("text" to chunk),
                    )
                }
            }
            if (!rooted) linuxRuntime.setInstallLogSink(logSink)
            val progress: (Double, String) -> Unit = { value, status ->
                runOnUiThread {
                    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL).invokeMethod(
                        "onPackageOperationProgress",
                        mapOf("progress" to value, "status" to status),
                    )
                }
            }
            try {
                startForegroundService()
                // Rooted devices install inside the Ubuntu chroot; the native runtime owns
                // the app-private Termux tree. Branching here is what previously made the
                // store fail on rooted devices: it always targeted linuxRuntime.
                val ok = when {
                    operation != null -> operation(progress)
                    rooted && remove -> chrootRuntime.removePackage(packageName, progress, logSink)
                    rooted -> chrootRuntime.installPackage(packageName, progress, logSink)
                    remove -> linuxRuntime.removeStorePackage(packageName, progress)
                    else -> linuxRuntime.installStorePackage(packageName, progress)
                }
                runOnUiThread { result.success(ok) }
            } catch (error: Throwable) {
                Log.e(TAG, "Package operation failed for $packageName", error)
                DesktopLog.append(this, "package", "operation $packageName failed: ${error.message}")
                progress(-1.0, error.message ?: "Package operation failed")
                runOnUiThread { result.success(false) }
            } finally {
                if (!rooted) {
                    linuxRuntime.setInstallLogSink(null)
                    linuxRuntime.finishPackageOperation()
                }
                packageOperationRunning.set(false)
            }
        }
    }

    // ── Install wake lock ──

    private val installOperations = java.util.concurrent.atomic.AtomicInteger(0)
    private val installWakeLock by lazy {
        (getSystemService(POWER_SERVICE) as android.os.PowerManager)
            .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "DroidDesk::Install")
            .apply { setReferenceCounted(false) }
    }

    /**
     * Runs a long setup/install step on a background thread while keeping the CPU awake and the
     * screen on, so downloads and package installs are not paused when the phone would sleep.
     */
    private fun installThread(name: String, block: () -> Unit) = thread(name = name) {
        if (installOperations.getAndIncrement() == 0) {
            installWakeLock.acquire(2 * 60 * 60 * 1000L)
            runOnUiThread { window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        try {
            block()
        } finally {
            if (installOperations.decrementAndGet() == 0) {
                if (installWakeLock.isHeld) installWakeLock.release()
                runOnUiThread { window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
            }
        }
    }

    // ── Foreground Service ──

    private fun startForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopForegroundService() {
        val intent = Intent(this, DroidDeskService::class.java)
        stopService(intent)
    }

    // ── Battery Optimization ──

    private fun isBatteryOptimized(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun requestIgnoreBatteryOptimization() {
        if (isBatteryOptimized()) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        }
    }

    private fun isDefaultLauncher(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
                roleManager.isRoleHeld(RoleManager.ROLE_HOME)
        } else {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            packageManager.resolveActivity(
                homeIntent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName == packageName
        }
    }

    private fun requestDefaultLauncher() {
        if (isDefaultLauncher()) {
            Toast.makeText(this, "DroidDesk is already the default launcher", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
                startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
                return
            }
        }
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    private fun unsetDefaultLauncher(): Boolean {
        if (!isDefaultLauncher()) return true

        val rootShell = RootShell(this)
        if (rootShell.hasRoot()) {
            runCatching {
                val userId = android.os.Process.myUid() / 100000
                rootShell.exec(
                    "cmd role remove-role-holder --user $userId " +
                        "android.app.role.HOME $packageName",
                )
                repeat(10) {
                    if (!isDefaultLauncher()) return true
                    Thread.sleep(100)
                }
            }.onFailure { Log.w(TAG, "Could not remove the Home role with root", it) }
        }

        // Older Android versions may still store Home as a preferred activity
        // rather than a RoleManager holder. Apps may clear their own preference.
        runCatching { packageManager.clearPackagePreferredActivities(packageName) }
        return !isDefaultLauncher()
    }

    private fun openHomeChooser() {
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        startActivity(Intent.createChooser(homeIntent, "Choose your Home app"))
    }

    private fun openHomeSettings() {
        Toast.makeText(
            this,
            "Android requires you to select another default Home app",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
    }

    // ── Hardware Detection ──

    private fun getGpuVendor(): String {
        return try {
            val prop = Runtime.getRuntime().exec(arrayOf("getprop", "ro.hardware.egl"))
            val result = prop.inputStream.bufferedReader().readText().trim()
            prop.waitFor()
            if (result.isNotEmpty()) result else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
    }

    private fun getTotalRam(): Long {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024)
    }

    private fun getAvailableStorage(): Long {
        val stat = android.os.StatFs(filesDir.absolutePath)
        return stat.availableBytes / (1024 * 1024)
    }
}
