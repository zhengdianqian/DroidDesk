package com.orailnoor.droiddesk.x11

import android.view.MotionEvent
import android.view.View
import com.termux.x11.LorieView
import com.termux.x11.MainActivity
import com.termux.x11.input.InputEventSender
import com.termux.x11.input.TouchInputHandler

/** Connects LorieView to the gesture/input implementation imported from Termux:X11. */
class X11InputController(
    private val lorieView: LorieView,
    onThreeFingerTap: () -> Unit = {},
    onTapUp: () -> Unit = {},
) {
    private val inputHandler = TouchInputHandler(
        MainActivity.getInstance(),
        InputEventSender(lorieView),
    )

    /** Input mode the user picked last time; survives app restarts. */
    private val savedModes = lorieView.context.getSharedPreferences("desktop_input", android.content.Context.MODE_PRIVATE)

    var mode: Int = savedModes.getInt(KEY_MODE, TouchInputHandler.InputMode.TRACKPAD)
        private set

    /** Down position/time used to tell a real tap from a pan, swipe or long press. */
    private var downAtMs = 0L
    private var downX = 0f
    private var downY = 0f

    init {
        setMode(mode)
        MainActivity.getInstance().setKeyHandler(inputHandler::sendKeyEvent)
        TouchInputHandler.setThreeFingerTapListener { onThreeFingerTap() }
        lorieView.setCallback { width, height, transform ->
            inputHandler.handleInputTransformChanged(width, height, transform)
        }
        val tapSlop = 8 * lorieView.context.resources.displayMetrics.density
        val tapTimeoutMs = 300L
        lorieView.setOnTouchListener { view, event ->
            // Observe only: input handling still belongs to TouchInputHandler. The IME
            // bridge learns from this that a human tapped the desktop, which keeps an
            // auto-focused window at cold start from opening the keyboard. Only a short,
            // motionless ACTION_UP counts: a scroll or a long press is not a tap on a
            // text widget, and acting on ACTION_DOWN popped the keyboard while scrolling.
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downAtMs = android.os.SystemClock.uptimeMillis()
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_UP -> {
                    val held = android.os.SystemClock.uptimeMillis() - downAtMs
                    val moved = kotlin.math.hypot(
                        (event.x - downX).toDouble(),
                        (event.y - downY).toDouble(),
                    )
                    if (held < tapTimeoutMs && moved < tapSlop) onTapUp()
                }
            }
            inputHandler.handleTouchEvent(lorieView, view, event)
        }
        lorieView.setOnGenericMotionListener(::handleMotionEvent)
    }

    fun nextMode(): Int {
        val next = when (mode) {
            TouchInputHandler.InputMode.TRACKPAD -> TouchInputHandler.InputMode.SIMULATED_TOUCH
            TouchInputHandler.InputMode.SIMULATED_TOUCH -> TouchInputHandler.InputMode.TOUCH
            else -> TouchInputHandler.InputMode.TRACKPAD
        }
        setMode(next)
        return next
    }

    fun modeLabel(): String = when (mode) {
        TouchInputHandler.InputMode.SIMULATED_TOUCH -> "Touchscreen"
        TouchInputHandler.InputMode.TOUCH -> "Direct touch"
        else -> "Trackpad"
    }

    fun dispose() {
        MainActivity.getInstance().setKeyHandler(null)
        TouchInputHandler.setThreeFingerTapListener(null)
        lorieView.setOnTouchListener(null)
        lorieView.setOnGenericMotionListener(null)
        lorieView.setCallback(null)
    }

    private fun setMode(newMode: Int) {
        mode = newMode
        savedModes.edit().putInt(KEY_MODE, newMode).apply()
        val prefs = MainActivity.getPrefs()
        prefs.touchMode.put(newMode.toString())
        inputHandler.reloadPreferences(prefs)
    }

    private fun handleMotionEvent(view: View, event: MotionEvent): Boolean =
        inputHandler.handleTouchEvent(lorieView, view, event)

    companion object {
        private const val KEY_MODE = "mode"

        /**
         * Must run before LorieView is measured: the X server takes its framebuffer size
         * from these preferences. Defaults are physical-pixel 1:1 with nearest filtering;
         * UI scale is applied inside the desktop through Xft DPI, not by stretching pixels.
         */
        fun configureDisplayScale(context: android.content.Context) {
            com.orailnoor.droiddesk.runtime.DisplayProfile.applyToX11(context)
            MainActivity.getPrefs().apply {
                scaleTouchpad.put(true)
            }
        }
    }
}
