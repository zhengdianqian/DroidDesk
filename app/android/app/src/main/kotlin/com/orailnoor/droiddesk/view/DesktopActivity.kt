package com.orailnoor.droiddesk.view

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.SurfaceHolder
import android.view.ViewGroup
import android.view.View
import android.view.MotionEvent
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.EditText
import android.text.InputType
import android.widget.LinearLayout
import android.util.Log
import android.widget.Toast
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.view.Gravity
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.os.SystemClock
import com.orailnoor.droiddesk.R
import com.orailnoor.droiddesk.service.DroidDeskService
import com.termux.x11.MainActivity as TermuxMainActivity
import com.termux.x11.LorieView
import com.orailnoor.droiddesk.runtime.LinuxRuntime
import com.orailnoor.droiddesk.runtime.ChrootRuntime
import com.orailnoor.droiddesk.runtime.ClipboardSync
import com.orailnoor.droiddesk.runtime.XfceMobileProfile
import com.orailnoor.droiddesk.runtime.DisplayProfile
import com.orailnoor.droiddesk.runtime.DesktopLog
import com.orailnoor.droiddesk.runtime.PhantomGuard
import com.orailnoor.droiddesk.x11.X11ServiceClient
import com.orailnoor.droiddesk.x11.X11InputController
import com.orailnoor.droiddesk.runtime.AndroidAppSync
import java.io.File
import java.io.BufferedReader
import java.io.FileReader
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class DesktopActivity : Activity() {
    private var lorieView: LorieView? = null
    private var connectionRequested = false
    private var isSetupDone = false
    private var shouldStartSession = false
    private var sessionMode = "termux"
    private var desktopEnv = "xfce4"
    private lateinit var linuxRuntime: LinuxRuntime
    private lateinit var chrootRuntime: ChrootRuntime
    private var clipboardSync: ClipboardSync? = null
    private lateinit var placeholder: FrameLayout
    private var x11ServiceClient: X11ServiceClient? = null
    private var inputController: X11InputController? = null
    private var inputModeButton: Button? = null
    private var controlOverlay: LinearLayout? = null
    private var collapsedControl: View? = null
    private val collapsedFadeHandler = Handler(Looper.getMainLooper())
    private val fadeCollapsedControl = Runnable {
        collapsedControl?.animate()?.alpha(COLLAPSED_IDLE_ALPHA)?.setDuration(400)?.start()
    }
    private var extraKeysBar: ExtraKeysBar? = null
    private var logoAnimator: FloatingLogoAnimator? = null
    private var surfaceCallback: SurfaceHolder.Callback? = null
    private var intentListenerThread: Thread? = null
    private var imePollThread: Thread? = null
    @Volatile private var imePolling = false
    private var lastImeFocus: String? = null
    private var loadingOverlay: FrameLayout? = null
    private var loadingStatus: TextView? = null
    private var loadingEstimate: TextView? = null
    private var loadingCancel: Button? = null
    private var loadingFailure: TextView? = null
    private var loadingFailureScroll: ScrollView? = null
    private var loadingFailureActions: LinearLayout? = null
    private var desktopRevealed = false
    @Volatile private var sessionPhase = "准备中"
    @Volatile private var sessionStartInFlight = false
    private val activityStartedAt = System.currentTimeMillis()
    @Volatile private var sessionAborted = false
    @Volatile private var x11Connected = false
    private val loadingMessageHandler = Handler(Looper.getMainLooper())
    private var loadingMessageIndex = 0
    private var loadingStartedAt = 0L
    private var estimatedLoadingSeconds = 30
    private var imeAnchor: EditText? = null
    private val loadingMessages = listOf(
        "Waking up your portable Linux workspace",
        "Connecting Android to your Linux desktop",
        "Starting the desktop engine and services",
        "Loading your apps, icons, and shortcuts",
        "Polishing your panels, wallpaper, and workspace",
        "Almost ready for your next big idea",
    )
    private val loadingMessageTicker = object : Runnable {
        override fun run() {
            // Kept only for the "attach to a live session" path; session starts drive the
            // real phase text so a hang can never masquerade as an endless spinner.
            val status = loadingStatus ?: return
            if (sessionStartInFlight) return
            val ticker = this
            status.animate().alpha(0f).setDuration(180).withEndAction {
                loadingMessageIndex = (loadingMessageIndex + 1) % loadingMessages.size
                status.text = loadingMessages[loadingMessageIndex]
                status.animate().alpha(1f).setDuration(260).withEndAction {
                    if (!desktopRevealed) {
                        loadingMessageHandler.postDelayed(ticker, 5_000)
                    }
                }.start()
            }.start()
        }
    }
    private val loadingEstimateTicker = object : Runnable {
        override fun run() {
            val estimate = loadingEstimate ?: return
            val elapsedSeconds = ((android.os.SystemClock.elapsedRealtime() - loadingStartedAt) / 1_000).toInt()
            val remaining = (estimatedLoadingSeconds - elapsedSeconds).coerceAtLeast(0)
            val tail = if (remaining > 0) {
                "预计还需 $remaining 秒"
            } else {
                "仍在处理，请稍候"
            }
            estimate.text = "${sessionPhase} · 已用时 ${elapsedSeconds} 秒 · $tail"
            estimate.contentDescription = estimate.text
            if (!desktopRevealed) loadingMessageHandler.postDelayed(this, 1_000)
        }
    }

    companion object {
        private const val TAG = "DesktopActivity"
        private const val COLLAPSED_IDLE_ALPHA = 0.4f
        private const val COLLAPSED_FADE_DELAY_MS = 3_000L
        private var current: java.lang.ref.WeakReference<DesktopActivity>? = null

        fun isOpen(): Boolean = current?.get()?.isFinishing == false

        /** Closes the desktop screen, e.g. when the session is stopped from the notification. */
        fun finishIfOpen() {
            current?.get()?.let { activity -> activity.runOnUiThread { activity.finish() } }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = java.lang.ref.WeakReference(this)
        linuxRuntime = LinuxRuntime(this)
        chrootRuntime = ChrootRuntime(this)
        shouldStartSession = intent.getBooleanExtra("startSession", false)
        sessionMode = intent.getStringExtra("mode") ?: if (chrootRuntime.hasRoot()) "chroot" else "termux"
        desktopEnv = intent.getStringExtra("de") ?: "xfce4"
        estimatedLoadingSeconds = if (shouldStartSession) 30 else 10

        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        placeholder = FrameLayout(this)
        placeholder.setBackgroundColor(Color.BLACK)
        setContentView(placeholder)
        showLoadingOverlay()
        // Some Android/LineageOS builds throw from PhoneWindow.getInsetsController
        // until a decor view has been created by setContentView().
        enableImmersiveMode()

        Log.i(TAG, "DesktopActivity created mode=$sessionMode startSession=$shouldStartSession")
        if (shouldStartSession) {
            // The session start is only requested after the X server connects. If that never
            // happens the page used to spin forever, so fail loudly instead of pretending.
            loadingMessageHandler.postDelayed({
                if (shouldStartSession && !connectionRequested) {
                    showSessionFailure("X 服务器未能在 20 秒内启动（可能有残留进程占用或 socket 未释放）")
                }
            }, 20_000)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
        if (hasFocus) startClipboardSync() else clipboardSync?.stop()
        if (hasFocus && !isSetupDone) {
            isSetupDone = true
            Log.i(TAG, "Window focused — setting up LorieView")
            setupLorieView()
        }
    }

    override fun onResume() {
        super.onResume()
        enableImmersiveMode()
        restartSessionIfStopped()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / multi-window resize re-shows the system bars on several OEM ROMs.
        enableImmersiveMode()
        placeholder.post {
            val w = placeholder.width
            val h = placeholder.height
            Log.i(TAG, "Window size after configuration change: ${w}x$h")
            DesktopLog.append(this, TAG, "window resized to ${w}x$h after configuration change")
            lorieView?.let { view ->
                Log.i(TAG, "LorieView size ${view.width}x${view.height}, target ${DisplayProfile.effectiveSize(this)}")
            }
        }
    }

    private fun isSessionRunning(): Boolean =
        if (sessionMode == "chroot") chrootRuntime.isRunning() else linuxRuntime.isRunning()

    /**
     * The session can be stopped while this screen stays in the back stack (notification Stop,
     * in-app Stop). Coming back to it would only show the bare X server, so start Linux again.
     */
    private fun restartSessionIfStopped() {
        if (!desktopRevealed || isSessionRunning()) return
        Log.i(TAG, "Desktop session is no longer running; starting it again")
        desktopRevealed = false
        sessionAborted = false
        sessionPhase = "准备中"
        controlOverlay?.visibility = View.INVISIBLE
        collapsedControl?.visibility = View.INVISIBLE
        loadingFailureScroll?.visibility = View.GONE
        loadingFailureActions?.visibility = View.GONE
        loadingCancel?.visibility = View.VISIBLE
        loadingOverlay?.visibility = View.VISIBLE
        loadingOverlay?.alpha = 1f
        logoAnimator?.start()
        estimatedLoadingSeconds = 30
        loadingStartedAt = android.os.SystemClock.elapsedRealtime()
        loadingMessageHandler.postDelayed(loadingMessageTicker, 5_000)
        loadingMessageHandler.postDelayed(loadingEstimateTicker, 1_000)
        // Stop also ended the keep-alive service.
        startForegroundService(Intent(this, DroidDeskService::class.java))
        shouldStartSession = true
        if (x11Connected) {
            startDesktopSessionIfRequested()
        } else {
            // The X server was stopped together with the session; wait for the surface
            // callback to reconnect it instead of launching a guest with no display.
            connectionRequested = false
            lorieView?.let { view ->
                view.post {
                    synchronized(this@DesktopActivity) {
                        if (!connectionRequested) {
                            connectionRequested = true
                            connectToX11Service()
                        }
                    }
                }
            }
        }
    }

    private fun startClipboardSync() {
        if (clipboardSync == null) {
            clipboardSync = if (sessionMode == "chroot") {
                ClipboardSync(this, chrootRuntime::readLinuxClipboard, chrootRuntime::writeLinuxClipboard)
            } else {
                ClipboardSync(this, linuxRuntime::readLinuxClipboard, linuxRuntime::writeLinuxClipboard)
            }
        }
        clipboardSync?.start()
    }

    override fun onPause() {
        clipboardSync?.stop()
        super.onPause()
    }

    /**
     * True immersive fullscreen: both the status bar and the navigation bar are hidden and
     * only peek back in on a deliberate swipe.
     *
     * The previous implementation relied on the insets controller alone; several OEM ROMs
     * (HyperOS included) still painted the status bar at non-default display scales, so the
     * legacy FLAG_FULLSCREEN is now applied on every API level as well. It is re-applied
     * whenever the window regains focus, the configuration changes, the layout changes, or
     * the system bars become visible again.
     */
    @Suppress("DEPRECATION")
    private fun enableImmersiveMode() {
        if (isFinishing || isDestroyed) return
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.decorView.windowInsetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        }

        // Some ROMs re-show the bars right after the first layout pass; hide once more on the
        // next frame (no polling, a single retry keeps the power-saver promise).
        window.decorView.post {
            if (isFinishing || isDestroyed) return@post
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.decorView.windowInsetsController?.hide(
                    WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars(),
                )
            } else {
                window.decorView.systemUiVisibility = window.decorView.systemUiVisibility
            }
        }
    }

    private fun setupLorieView() {
        Log.i(TAG, "Setting up LorieView")
        X11InputController.configureDisplayScale(this)
        TermuxMainActivity.getInstance().initLorieView(this)
        lorieView = TermuxMainActivity.getInstance().lorieView

        // Keep Android overlay controls above the X11 SurfaceView.
        lorieView!!.setZOrderOnTop(false)
        placeholder.setBackgroundColor(Color.BLACK)

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        // TermuxMainActivity retains its LorieView singleton across activity
        // recreation. Detach it from the previous activity's container before
        // attaching it here, otherwise Android throws "child already has a parent".
        (lorieView!!.parent as? ViewGroup)?.removeView(lorieView)
        placeholder.addView(lorieView, params)

        // Invisible 1x1 EditText: the Android IME anchor. Only this view is focusable while
        // typing, so the system keyboard (including Chinese IMEs) targets it and committed
        // text is injected into the X session instead of being replayed key by key.
        if (imeAnchor == null) {
            imeAnchor = GuestImeAnchor(this) { lorieView }.also { anchor ->
                placeholder.addView(anchor, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
                anchor.isFocusableInTouchMode = true
                // Must stay VISIBLE (alpha 0, one pixel): INVISIBLE views cannot hold focus.
                anchor.visibility = View.VISIBLE
                anchor.alpha = 0f
            }
        }
        loadingOverlay?.bringToFront()
        Log.i(TAG, "LorieView added to placeholder")

        // Start X server only after the Surface is actually created/changed.
        surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceCreated")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                Log.i(TAG, "LorieView surfaceChanged ${width}x${height}")
                DesktopLog.event(
                    this@DesktopActivity,
                    "DISPLAY",
                    "surface=${width}x${height} target=${DisplayProfile.effectiveSize(this@DesktopActivity)}",
                    activityStartedAt,
                )
                synchronized(this@DesktopActivity) {
                    if (!connectionRequested) {
                        connectionRequested = true
                        connectToX11Service()
                    }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.i(TAG, "LorieView surfaceDestroyed")
            }
        }.also { lorieView!!.holder.addCallback(it) }
    }

    private fun connectToX11Service() {
        x11ServiceClient = X11ServiceClient(
            context = this,
            onConnected = { serverPid, connectionFd, logcatFd ->
                try {
                    if (!LorieView.connected() || X11ServiceClient.connectedServerPid != serverPid) {
                        LorieView.connect(connectionFd.detachFd())
                        logcatFd?.let { LorieView.startLogcat(it.detachFd()) }
                        X11ServiceClient.connectedServerPid = serverPid
                        Log.i(TAG, "LorieView connected to X11 service pid=$serverPid")
                        DesktopLog.event(this@DesktopActivity, "X_START", "connected pid=$serverPid", activityStartedAt)
                    } else {
                        connectionFd.close()
                        logcatFd?.close()
                        Log.i(TAG, "LorieView reused live X11 service pid=$serverPid")
                    }

                    attachDesktopInput()
                    x11Connected = true
                } catch (error: Throwable) {
                    connectionFd.close()
                    logcatFd?.close()
                    showX11Error("Failed to attach LorieView to the X11 service", error)
                }
            },
            onError = ::showX11Error,
        ).also { it.connect() }
    }

    private fun attachDesktopInput() {
        if (inputController == null) {
            inputController = X11InputController(lorieView!!, onThreeFingerTap = ::toggleKeyboard)
        }
        addDesktopControls()
        lorieView?.requestFocus()
        startAutoImePolling()
        if (shouldStartSession) {
            startDesktopSessionIfRequested()
        } else {
            waitForDesktopAndReveal()
        }
    }

    /**
     * The guest's IBus watcher writes 1 only when an editable widget gains focus (GTK/Qt
     * create an input context for text entries alone), so the soft keyboard never pops up
     * from a plain window click. Opt-out via Settings → 自动弹出输入法.
     */
    private fun startAutoImePolling() {
        if (!DisplayProfile.autoIme(this) || imePollThread != null) return
        imePolling = true
        imePollThread = Thread({
            val focusFile = File(filesDir, "guest-bridge/ime-focus")
            while (imePolling && !Thread.currentThread().isInterrupted) {
                val value = runCatching {
                    if (focusFile.exists()) focusFile.readText().trim() else null
                }.getOrNull()
                if (value != null && value != lastImeFocus && (value == "0" || value == "1")) {
                    lastImeFocus = value
                    DesktopLog.event(this, "IMEBRIDGE", "editable focus=$value", activityStartedAt)
                    runOnUiThread {
                        if (value == "1") showKeyboard() else hideKeyboard()
                    }
                }
                try {
                    Thread.sleep(if (DisplayProfile.powerMode(this@DesktopActivity) == "saver") 2000L else 1200L)
                } catch (interrupted: InterruptedException) {
                    break
                }
            }
        }, "DroidDeskAutoIme").apply { isDaemon = true }
        imePollThread?.start()
    }

    private fun addDesktopControls() {
        if (controlOverlay != null) return
        val density = resources.displayMetrics.density

        // Frosted, translucent controls: one rounded pill when open, an AssistiveTouch-style
        // square when collapsed.
        fun frosted(radius: Float) = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.argb(200, 22, 26, 34))
            setStroke((1 * density).toInt(), Color.argb(40, 255, 255, 255))
        }
        fun pressRipple() = RippleDrawable(
            ColorStateList.valueOf(Color.argb(60, 255, 255, 255)),
            null,
            GradientDrawable().apply { cornerRadius = 20 * density; setColor(Color.WHITE) },
        )
        fun iconButton(icon: Int, label: String) = ImageButton(this).apply {
            setImageResource(icon)
            contentDescription = label
            background = pressRipple()
            scaleType = android.widget.ImageView.ScaleType.CENTER
        }

        val buttonHeight = (44 * density).toInt()
        val keyboardButton = iconButton(R.drawable.ic_dd_keyboard, "Show keyboard").apply {
            setOnClickListener { showKeyboard() }
        }
        inputModeButton = Button(this).apply {
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            textSize = 13f
            setTextColor(Color.WHITE)
            background = pressRipple()
            stateListAnimator = null
            compoundDrawablePadding = (6 * density).toInt()
            setPadding((12 * density).toInt(), 0, (14 * density).toInt(), 0)
            showInputMode(this)
            setOnClickListener {
                inputController?.nextMode()
                showInputMode(this)
                Toast.makeText(this@DesktopActivity, "Input mode: $text", Toast.LENGTH_SHORT).show()
            }
        }
        val hideButton = iconButton(R.drawable.ic_dd_close, "Hide desktop controls").apply {
            setOnClickListener { setControlsCollapsed(true) }
        }

        controlOverlay = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = frosted(26 * density)
            elevation = 10 * density
            val pad = (4 * density).toInt()
            setPadding(pad, pad, pad, pad)
            addView(keyboardButton, LinearLayout.LayoutParams(buttonHeight, buttonHeight))
            addView(inputModeButton, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, buttonHeight,
            ))
            addView(hideButton, LinearLayout.LayoutParams(buttonHeight, buttonHeight))
            // Start collapsed so the bar never covers windows until the user asks for it.
            visibility = View.INVISIBLE
        }

        // AssistiveTouch-style handle: fades out when idle and lights up while touched.
        collapsedControl = View(this).apply {
            contentDescription = "Show desktop controls"
            background = assistiveTouchDrawable(density)
            elevation = 10 * density
            alpha = COLLAPSED_IDLE_ALPHA
            if (!desktopRevealed) visibility = View.INVISIBLE
        }

        // Sit just above XFCE's bottom dock, whose height follows the in-desktop UI scale.
        val dockHeight = XfceMobileProfile.dockHeightPx(DisplayProfile.uiScale(this))
        val overlayParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = (8 * density).toInt()
            bottomMargin = dockHeight + (8 * density).toInt()
        }
        val collapsedSize = (52 * density).toInt()
        val collapsedParams = FrameLayout.LayoutParams(
            collapsedSize,
            collapsedSize,
            Gravity.BOTTOM or Gravity.END,
        ).apply {
            rightMargin = overlayParams.rightMargin
            bottomMargin = overlayParams.bottomMargin
        }

        placeholder.addView(controlOverlay, overlayParams)
        placeholder.addView(collapsedControl, collapsedParams)
        collapsedControl?.setOnTouchListener(assistiveTouchListener(collapsedControl!!))
        collapsedControl?.bringToFront()
        addExtraKeysBar()
    }

    /** Esc/Tab/Ctrl/Alt/arrow row that rides on top of the soft keyboard while it is open. */
    private fun addExtraKeysBar() {
        val bar = ExtraKeysBar(this) { lorieView }.apply { visibility = View.GONE }
        extraKeysBar = bar
        placeholder.addView(bar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM,
        ))
        // Typing on the soft keyboard releases one-shot Ctrl/Alt (see LorieView's InputConnection).
        TermuxMainActivity.getInstance().apply {
            mExtraKeys = object : TermuxMainActivity.DummyExtraKeys() {
                override fun unsetSpecialKeys() {
                    runOnUiThread { extraKeysBar?.onKeyTyped() }
                }
            }
            useTermuxEKBarBehaviour = true
        }
        placeholder.setOnApplyWindowInsetsListener { view, insets ->
            updateExtraKeysBar(insets)
            view.onApplyWindowInsets(insets)
        }
        placeholder.requestApplyInsets()
    }

    @Suppress("DEPRECATION")
    private fun updateExtraKeysBar(insets: WindowInsets) {
        val bar = extraKeysBar ?: return
        val imeBottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (insets.isVisible(WindowInsets.Type.ime())) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
        } else {
            insets.systemWindowInsetBottom
        }
        if (imeBottom > 0) {
            (bar.layoutParams as FrameLayout.LayoutParams).bottomMargin = imeBottom
            bar.visibility = View.VISIBLE
            bar.requestLayout()
            bar.bringToFront()
        } else if (bar.visibility == View.VISIBLE) {
            bar.releaseAll()
            bar.visibility = View.GONE
        }

        // A swipe (or an OEM quirk) can bring the bars back; hide them again without polling.
        val barsVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insets.isVisible(WindowInsets.Type.statusBars()) ||
                insets.isVisible(WindowInsets.Type.navigationBars())
        } else {
            (insets.systemWindowInsetTop > 0)
        }
        if (barsVisible) {
            extraKeysBar?.post { enableImmersiveMode() }
        }
    }

    private fun dragListener(target: View, onTap: (() -> Unit)? = null): View.OnTouchListener {
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0f
        var startY = 0f
        var dragged = false
        val threshold = resources.displayMetrics.density * 6

        return View.OnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = target.x
                    startY = target.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (kotlin.math.abs(dx) > threshold || kotlin.math.abs(dy) > threshold) {
                        dragged = true
                    }
                    target.x = (startX + dx).coerceIn(0f, (placeholder.width - target.width).coerceAtLeast(0).toFloat())
                    target.y = (startY + dy).coerceIn(0f, (placeholder.height - target.height).coerceAtLeast(0).toFloat())
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) onTap?.invoke()
                    true
                }
                else -> false
            }
        }
    }

    /** iOS AssistiveTouch look: dark translucent rounded square with nested white circles. */
    private fun assistiveTouchDrawable(density: Float): LayerDrawable {
        fun circle(alpha: Int) = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(alpha, 255, 255, 255))
        }
        val square = GradientDrawable().apply {
            cornerRadius = 14 * density
            setColor(Color.argb(170, 30, 30, 30))
        }
        return LayerDrawable(arrayOf(square, circle(45), circle(90), circle(240))).apply {
            setLayerInset(1, (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt())
            setLayerInset(2, (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt(), (12 * density).toInt())
            setLayerInset(3, (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt(), (16 * density).toInt())
        }
    }

    /** Mouse icon for trackpad mode, finger icon for the touch modes, plus the mode name. */
    private fun showInputMode(button: Button) {
        val label = inputController?.modeLabel() ?: "Trackpad"
        val icon = if (label == "Trackpad") R.drawable.ic_dd_mouse else R.drawable.ic_dd_touch
        button.text = label
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    }

    /** Lights the ☰ handle up while touched, snaps it to the nearest side after a drag, then fades it. */
    private fun assistiveTouchListener(handle: View): View.OnTouchListener {
        val drag = dragListener(handle) { setControlsCollapsed(false) }
        var downX = 0f
        return View.OnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = handle.x
                    collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
                    handle.animate().cancel()
                    handle.alpha = 1f
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (handle.x != downX) {
                        val margin = 8 * resources.displayMetrics.density
                        val maxX = (placeholder.width - handle.width).toFloat()
                        val edgeX = if (handle.x + handle.width / 2f < placeholder.width / 2f) margin else maxX - margin
                        handle.animate().x(edgeX.coerceIn(0f, maxX.coerceAtLeast(0f))).setDuration(180).start()
                    }
                    scheduleCollapsedFade()
                }
            }
            drag.onTouch(view, event)
        }
    }

    private fun scheduleCollapsedFade() {
        collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        collapsedFadeHandler.postDelayed(fadeCollapsedControl, COLLAPSED_FADE_DELAY_MS)
    }

    private fun setControlsCollapsed(collapsed: Boolean) {
        val from = if (collapsed) controlOverlay else collapsedControl
        val to = if (collapsed) collapsedControl else controlOverlay
        to?.x = from?.x ?: 0f
        to?.y = from?.y ?: 0f
        from?.visibility = View.INVISIBLE
        to?.visibility = View.VISIBLE
        to?.bringToFront()
        // The expanded bar is wider than the ☰ handle; once laid out, keep it fully on screen.
        to?.post { keepOnScreen(to) }
        if (collapsed) {
            collapsedControl?.alpha = 1f
            scheduleCollapsedFade()
        } else {
            collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        }
    }

    private fun keepOnScreen(view: View) {
        val maxX = (placeholder.width - view.width).coerceAtLeast(0).toFloat()
        val maxY = (placeholder.height - view.height).coerceAtLeast(0).toFloat()
        view.x = view.x.coerceIn(0f, maxX)
        view.y = view.y.coerceIn(0f, maxY)
    }

    /** Three-finger tap: open the keyboard, or close it when it is already showing. */
    private fun toggleKeyboard() {
        val imeVisible = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.decorView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            extraKeysBar?.visibility == View.VISIBLE
        }
        if (imeVisible) hideKeyboard() else showKeyboard()
    }

    private fun hideKeyboard() {
        val anchor = imeAnchor
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        inputMethod.hideSoftInputFromWindow((anchor ?: lorieView)?.windowToken, 0)
        if (anchor != null) {
            anchor.clearFocus()
            anchor.text?.clear()
        }
        extraKeysBar?.releaseAll()
        extraKeysBar?.visibility = View.GONE
        lorieView?.requestFocus()
    }

    /**
     * Pops the real Android input method (not an in-app keyboard window) by focusing the
     * hidden 1x1 anchor. The IME's commits are forwarded to X11 as text, so Chinese input
     * only hits the desktop once, on commit.
     */
    private fun showKeyboard() {
        val anchor = imeAnchor ?: lorieView ?: return
        val inputMethod = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        anchor.requestFocus()
        inputMethod.restartInput(anchor)
        anchor.post {
            if (isFinishing || isDestroyed) return@post
            inputMethod.showSoftInput(anchor, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun startDesktopSessionIfRequested() {
        if (!shouldStartSession || sessionAborted) return
        shouldStartSession = false
        if (sessionStartInFlight) return
        sessionStartInFlight = true
        loadingStartedAt = android.os.SystemClock.elapsedRealtime()
        startSessionThread()
    }

    private fun startSessionThread() {
        val timeoutMs = 420_000L
        Thread({
            DesktopLog.append(this, TAG, "session start (mode=$sessionMode de=$desktopEnv)")
            val size = DisplayProfile.effectiveSize(this)
            DesktopLog.event(
                this,
                "SESSION_START",
                "mode=$sessionMode de=$desktopEnv target=${size.x}x${size.y}",
                activityStartedAt,
            )
            try {
                // A chroot start needs ~40-60 s on a cold image; every phase is bounded so a
                // hang can never leave the user staring at an endless spinner.
                runPhase("清理上次会话残留", 20_000) {
                    if (sessionMode == "chroot") chrootRuntime.cleanupStaleSession()
                    else linuxRuntime.cleanupStaleSession()
                }
                runPhase("检查运行环境", timeoutMs) {
                    if (sessionMode == "chroot") {
                        chrootRuntime.startSession(desktopEnv) { phase -> setPhase(phase) }
                    } else {
                        linuxRuntime.startSession(desktopEnv, "x11", onPhase = { phase -> setPhase(phase) })
                    }
                }
                if (sessionMode == "chroot") {
                    // Best effort: Android app integration must never block the desktop.
                    runPhase("接入 Android 应用", 30_000) {
                        runCatching {
                            val rootfsPath = chrootRuntime.getRootfsPath()
                            chrootRuntime.executeCommand(
                                "mkdir -p /tmp && mkfifo /tmp/android_intent_pipe && chmod 666 /tmp/android_intent_pipe",
                            ) {}
                            AndroidAppSync.syncApps(this@DesktopActivity, rootfsPath)
                            startIntentListener(rootfsPath)
                        }
                    }
                }
                setPhase("等待桌面绘制完成")
                val ready = runPhase("等待桌面就绪", 60_000) {
                    if (sessionMode == "chroot") {
                        chrootRuntime.waitForDesktopReady(desktopEnv, 45_000)
                    } else {
                        linuxRuntime.waitForDesktopReady(desktopEnv, 45_000)
                    }
                }
                if (!ready) {
                    throw IllegalStateException("桌面进程已启动，但 45 秒内没有出现（xfce4-session 可能已崩溃）")
                }
                DesktopLog.event(this, "DE_START", "desktop processes detected", activityStartedAt)
                revealDesktop(true)
            } catch (timeout: PhaseTimeout) {
                abortSession("阶段「${timeout.phase}」超时（${timeout.timeoutMs / 1000} 秒）")
            } catch (error: Throwable) {
                Log.e(TAG, "Desktop session failed", error)
                abortSession("${sessionPhase}失败：${error.message ?: error.javaClass.simpleName}")
            } finally {
                sessionStartInFlight = false
            }
        }, "LinuxDesktopSession").apply { isDaemon = true }.start()
    }

    private class PhaseTimeout(val phase: String, val timeoutMs: Long) : RuntimeException("$phase timeout")

    private fun <T> runPhase(phase: String, timeoutMs: Long, block: () -> T): T {
        setPhase(phase)
        val executor = Executors.newSingleThreadExecutor()
        val future = executor.submit<T> { block() }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (timeout: TimeoutException) {
            future.cancel(true)
            throw PhaseTimeout(phase, timeoutMs)
        } catch (failure: ExecutionException) {
            throw (failure.cause ?: failure)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun setPhase(phase: String) {
        sessionPhase = phase
        Log.i(TAG, "Session phase: $phase")
        DesktopLog.event(this, "PHASE", phase, activityStartedAt)
        runOnUiThread {
            if (desktopRevealed || isFinishing) return@runOnUiThread
            loadingStatus?.text = phase
            loadingStatus?.alpha = 1f
        }
    }

    private fun abortSession(reason: String) {
        Log.e(TAG, "Session aborted: $reason")
        DesktopLog.event(this, "SESSION_FAIL", reason, activityStartedAt)
        runCatching {
            if (sessionMode == "chroot") chrootRuntime.stopSession() else linuxRuntime.stopSession()
        }
        showSessionFailure(reason)
    }

    private fun showSessionFailure(reason: String) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            loadingMessageHandler.removeCallbacksAndMessages(null)
            logoAnimator?.stop()
            loadingStatus?.text = "启动失败"
            loadingEstimate?.text = reason
            loadingEstimate?.setTextColor(Color.rgb(255, 138, 128))
            loadingFailure?.apply {
                text = "可复制错误详情：\n$reason"
            }
            loadingFailureScroll?.visibility = View.VISIBLE
            loadingFailureActions?.visibility = View.VISIBLE
            loadingCancel?.visibility = View.GONE
            loadingOverlay?.visibility = View.VISIBLE
            loadingOverlay?.alpha = 1f
            loadingOverlay?.bringToFront()
        }
    }

    private fun cancelSession() {
        sessionAborted = true
        sessionStartInFlight = false
        DesktopLog.append(this, TAG, "session cancelled by user")
        Thread({
            runCatching {
                if (sessionMode == "chroot") chrootRuntime.stopSession() else linuxRuntime.stopSession()
            }
        }, "CancelSession").start()
        finish()
    }

    /** Full retry: tear the guest down and rebuild the activity (and therefore the session). */
    private fun retrySession() {
        sessionAborted = false
        loadingMessageHandler.removeCallbacksAndMessages(null)
        Thread({
            runCatching {
                if (sessionMode == "chroot") chrootRuntime.stopSession() else linuxRuntime.stopSession()
            }
            runOnUiThread { if (!isFinishing) recreate() }
        }, "RetrySession").start()
    }

    private fun waitForDesktopAndReveal() {
        Thread({
            val ready = if (sessionMode == "chroot") {
                chrootRuntime.waitForDesktopReady(desktopEnv)
            } else {
                linuxRuntime.waitForDesktopReady(desktopEnv)
            }
            revealDesktop(ready)
        }, "LinuxDesktopReady").start()
    }

    /** Follows the theme picked in the app (Flutter stores it as flutter.theme_mode). */
    private fun isLightTheme(): Boolean {
        val mode = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            .getString("flutter.theme_mode", "dark")
        return when (mode) {
            "light" -> true
            "system" -> (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) !=
                android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> false
        }
    }

    private fun showLoadingOverlay() {
        val density = resources.displayMetrics.density
        val light = isLightTheme()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }

        runCatching {
            assets.open("flutter_assets/assets/icons/logo.png").use { stream ->
                ImageView(this).apply {
                    setImageBitmap(BitmapFactory.decodeStream(stream))
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    logoAnimator = FloatingLogoAnimator(this).also { it.start() }
                    content.addView(
                        this,
                        LinearLayout.LayoutParams((112 * density).toInt(), (112 * density).toInt()).apply {
                            bottomMargin = (26 * density).toInt()
                        },
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Loading logo unavailable", it) }

        content.addView(TextView(this).apply {
            text = "DroidDesk"
            textSize = 28f
            setTextColor(if (light) Color.rgb(28, 28, 30) else Color.WHITE)
            gravity = Gravity.CENTER
        })
        content.addView(LoadingSpinnerView(
            this,
            arcColor = Color.rgb(10, 132, 255),
            trackColor = if (light) Color.argb(40, 10, 132, 255) else Color.argb(50, 255, 255, 255),
        ), LinearLayout.LayoutParams(
            (44 * density).toInt(),
            (44 * density).toInt(),
        ).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = (22 * density).toInt()
        })
        loadingStatus = TextView(this).apply {
            text = loadingMessages.first()
            textSize = 14f
            setTextColor(if (light) Color.rgb(58, 58, 60) else Color.rgb(190, 198, 215))
            gravity = Gravity.CENTER
        }.also { status ->
            content.addView(status, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (14 * density).toInt() })
        }
        loadingEstimate = TextView(this).apply {
            text = "正在启动…"
            textSize = 12f
            setTextColor(if (light) Color.rgb(110, 110, 115) else Color.rgb(130, 143, 164))
            gravity = Gravity.CENTER
            contentDescription = text
        }.also { estimate ->
            content.addView(estimate, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (8 * density).toInt() })
        }

        // Selectable, copyable failure details — never hide the real apt/XFCE error.
        loadingFailure = TextView(this).apply {
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(if (light) Color.rgb(180, 40, 40) else Color.rgb(255, 138, 128))
            setTextIsSelectable(true)
            visibility = View.GONE
        }
        val failureScroller = ScrollView(this).apply {
            isFillViewport = false
            visibility = View.GONE
            addView(loadingFailure, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        loadingFailureScroll = failureScroller
        content.addView(failureScroller, LinearLayout.LayoutParams(
            (300 * density).toInt(),
            (150 * density).toInt(),
        ).apply { topMargin = (12 * density).toInt() })

        fun actionButton(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            textSize = 13f
            isAllCaps = false
            minWidth = 0
            minHeight = 0
            setTextColor(if (light) Color.WHITE else Color.rgb(20, 24, 32))
            background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(if (light) Color.rgb(10, 132, 255) else Color.rgb(235, 240, 250))
            }
            setPadding((18 * density).toInt(), (8 * density).toInt(), (18 * density).toInt(), (8 * density).toInt())
            setOnClickListener { onClick() }
        }

        loadingFailureActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            addView(actionButton("重试") { retrySession() }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { rightMargin = (12 * density).toInt() })
            addView(actionButton("返回") { finish() }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        content.addView(loadingFailureActions, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = (14 * density).toInt() })

        loadingCancel = actionButton("取消启动") { cancelSession() }.also { cancel ->
            cancel.visibility = if (shouldStartSession) View.VISIBLE else View.GONE
            cancel.background = GradientDrawable().apply {
                cornerRadius = 18 * density
                setColor(if (light) Color.argb(25, 0, 0, 0) else Color.argb(40, 255, 255, 255))
            }
            cancel.setTextColor(if (light) Color.rgb(60, 60, 64) else Color.rgb(200, 208, 222))
            content.addView(cancel, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = (18 * density).toInt() })
        }

        loadingOverlay = FrameLayout(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                if (light) {
                    intArrayOf(Color.rgb(242, 242, 247), Color.rgb(237, 237, 242))
                } else {
                    intArrayOf(Color.rgb(12, 18, 32), Color.rgb(4, 7, 14))
                },
            )
            addView(content, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ))
        }.also { overlay ->
            placeholder.addView(overlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }
        loadingStartedAt = android.os.SystemClock.elapsedRealtime()
        loadingMessageHandler.postDelayed(loadingMessageTicker, 5_000)
        loadingMessageHandler.postDelayed(loadingEstimateTicker, 1_000)
    }

    private fun revealDesktop(ready: Boolean) {
        runOnUiThread {
            if (desktopRevealed || isFinishing || isDestroyed) return@runOnUiThread
            desktopRevealed = true
            loadingMessageHandler.removeCallbacks(loadingMessageTicker)
            loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
            loadingCancel?.visibility = View.GONE
            loadingFailureScroll?.visibility = View.GONE
            loadingFailureActions?.visibility = View.GONE
            if (!ready) Log.w(TAG, "Revealing desktop after readiness timeout")
            Log.i(TAG, "Desktop revealed ready=$ready")
            val view = lorieView
            DesktopLog.event(
                this@DesktopActivity,
                "READY",
                "ready=$ready surface=${view?.width}x${view?.height} " +
                    "target=${DisplayProfile.effectiveSize(this@DesktopActivity)}",
                activityStartedAt,
            )
            DesktopLog.append(this, TAG, "desktop revealed ready=$ready")
            enableImmersiveMode()
            loadingOverlay?.animate()
                ?.alpha(0f)
                ?.setDuration(350)
                ?.withEndAction {
                    loadingOverlay?.visibility = View.GONE
                    logoAnimator?.stop()
                    collapsedControl?.visibility = View.VISIBLE
                    collapsedControl?.bringToFront()
                    lorieView?.requestFocus()
                }
                ?.start()
        }
    }

    private fun showX11Error(message: String, error: Throwable?) {
        Log.e(TAG, message, error)
        DesktopLog.append(this, TAG, "X11 error: $message ${error?.message ?: ""}")
        Toast.makeText(this, "X11 Error: $message", Toast.LENGTH_LONG).show()
        if (sessionStartInFlight || shouldStartSession) {
            showSessionFailure("X 服务器启动失败：$message${error?.message?.let { "（$it）" } ?: ""}")
        }
    }

    private fun startIntentListener(rootfsPath: String) {
        if (intentListenerThread != null) return
        intentListenerThread = Thread({
            val pipePath = "$rootfsPath/tmp/android_intent_pipe"
            while (!Thread.currentThread().isInterrupted) {
                try {
                    val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "tail -f $pipePath"))
                    BufferedReader(java.io.InputStreamReader(process.inputStream)).use { reader ->
                        while (!Thread.currentThread().isInterrupted) {
                            val packageName = reader.readLine() ?: break
                            if (packageName.isNotBlank()) {
                                launchAndroidApp(packageName.trim())
                            }
                        }
                    }
                    process.destroy()
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Thread.sleep(1000)
                }
            }
        }, "AndroidIntentListener")
        intentListenerThread?.start()
    }

    private fun launchAndroidApp(packageName: String) {
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                Log.i(TAG, "Launching Android App: ${packageName}")
                startActivity(launchIntent)
            } else {
                Log.w(TAG, "No launch intent found for package: $packageName")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app $packageName", e)
        }
    }

    override fun onDestroy() {
        collapsedFadeHandler.removeCallbacks(fadeCollapsedControl)
        imePolling = false
        imePollThread?.interrupt()
        imePollThread = null
        clipboardSync?.stop()
        clipboardSync = null
        extraKeysBar?.releaseAll()
        extraKeysBar = null
        TermuxMainActivity.getInstance().apply {
            mExtraKeys = null
            useTermuxEKBarBehaviour = false
        }
        loadingMessageHandler.removeCallbacks(loadingMessageTicker)
        loadingMessageHandler.removeCallbacks(loadingEstimateTicker)
        surfaceCallback?.let { callback -> lorieView?.holder?.removeCallback(callback) }
        surfaceCallback = null
        inputController?.dispose()
        inputController = null
        x11ServiceClient?.disconnect()
        x11ServiceClient = null
        intentListenerThread?.interrupt()
        intentListenerThread = null
        if (current?.get() === this) current = null
        super.onDestroy()
    }
}

/**
 * Invisible 1x1 text field that exists only to give the Android input method a focus target.
 *
 * Native Termux:X11 views report themselves as text editors too, but focusing them while the
 * desktop controls are on screen produced grey/duplicate commits. Keeping a dedicated anchor
 * lets us show the *system* keyboard (IME) on demand and inject only committed text into the
 * X session — composing/preview text stays inside the IME, so Chinese characters do not get
 * typed twice.
 */
private class GuestImeAnchor(
    context: android.content.Context,
    private val target: () -> LorieView?,
) : EditText(context) {
    init {
        alpha = 0f
        background = null
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
        inputType = InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : InputConnectionWrapper(base, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value == "\n" || value == "\r\n") {
                    forwardKey(KeyEvent.KEYCODE_ENTER)
                } else if (value.isNotEmpty()) {
                    target()?.sendTextEvent(value.toByteArray(Charsets.UTF_8))
                }
                // Keep the anchor itself empty; it is not a visible editor.
                super.commitText("", 1)
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // Composing (pinyin preview) is rendered by the IME alone.
                return true
            }

            override fun finishComposingText(): Boolean = true

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtMost(16)) { forwardKey(KeyEvent.KEYCODE_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                if (event != null && event.action == KeyEvent.ACTION_DOWN) {
                    forwardKey(event.keyCode)
                }
                return true
            }
        }
    }

    private fun forwardKey(keyCode: Int) {
        val view = target() ?: return
        view.sendKeyEvent(0, keyCode, true)
        view.sendKeyEvent(0, keyCode, false)
    }
}
