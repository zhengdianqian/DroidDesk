package com.orailnoor.droiddesk.runtime

import android.content.Context
import android.graphics.Point
import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.termux.x11.MainActivity as TermuxMainActivity
import kotlin.math.roundToInt

/**
 * Single source of truth for the X11 framebuffer size and the UI scale.
 *
 * The old code forced `displayScale = 150` in "scaled" mode: the X server got
 * framebuffer = Surface / 1.5 and the compositor stretched it back to the Surface, so every
 * pixel was resampled (that is the blur the user saw) and `startLinux` additionally clamped
 * the height to 1080. Here the default is `native`, i.e. the framebuffer is exactly the
 * Surface in physical pixels, and readability is achieved inside the desktop with an
 * integer UI scale (Xft DPI) instead of a final stretch.
 */
object DisplayProfile {
    private const val PREFS = "FlutterSharedPreferences"
    const val MODE_NATIVE = "native"
    const val MODE_1080P = "1080p"
    const val MODE_1440P = "1440p"
    const val DEFAULT_SCALE = 1.5
    val SCALES = listOf(1.0, 1.25, 1.5, 2.0)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): String {
        val value = prefs(context).getString("flutter.ddResolutionMode", MODE_NATIVE) ?: MODE_NATIVE
        return if (value in listOf(MODE_NATIVE, MODE_1080P, MODE_1440P)) value else MODE_NATIVE
    }

    fun uiScale(context: Context): Double {
        val raw = prefs(context).getString("flutter.ddUiScale", null)
        val parsed = raw?.toDoubleOrNull() ?: DEFAULT_SCALE
        return parsed.takeIf { it in SCALES } ?: DEFAULT_SCALE
    }

    fun powerMode(context: Context): String =
        (prefs(context).getString("flutter.ddPowerMode", "standard") ?: "standard")
            .takeIf { it == "saver" || it == "standard" } ?: "standard"

    fun autoIme(context: Context): Boolean =
        prefs(context).getBoolean("flutter.ddAutoIme", true)

    fun xftDpi(scale: Double): Int = (96.0 * scale).roundToInt()

    /** Real panel size in physical pixels (system bars included; the desktop is fullscreen). */
    fun physicalSize(context: Context): Point {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            if (bounds.width() > 0 && bounds.height() > 0) {
                return Point(bounds.width(), bounds.height())
            }
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return Point(metrics.widthPixels, metrics.heightPixels)
    }

    /** Framebuffer size the X server should allocate for the selected resolution preset. */
    fun effectiveSize(context: Context): Point {
        val physical = physicalSize(context)
        val portrait = physical.y >= physical.x
        val long = maxOf(physical.x, physical.y)
        val short = minOf(physical.x, physical.y)
        return when (mode(context)) {
            MODE_1080P -> if (portrait) Point(1080, 1920) else Point(1920, 1080)
            MODE_1440P -> if (portrait) Point(1440, 2560) else Point(2560, 1440)
            else -> if (physical.x > 0 && physical.y > 0) {
                Point(physical.x, physical.y)
            } else {
                if (portrait) Point(short, long) else Point(long, short)
            }
        }
    }

    /**
     * Applies the profile to the Termux:X11 preferences. Must run before LorieView is
     * measured, because the X server is created from those values.
     */
    fun applyToX11(context: Context) {
        val selected = mode(context)
        val target = effectiveSize(context)
        TermuxMainActivity.getPrefs().apply {
            displayScale.put(100)
            displayFilteringMode.put("nearest")
            adjustResolution.put(false)
            when (selected) {
                MODE_NATIVE -> {
                    displayResolutionMode.put("native")
                    // The view is already physical-pixel sized; stretching is a no-op here
                    // and keeps the desktop full-bleed.
                    displayStretch.put(true)
                }
                else -> {
                    displayResolutionMode.put("custom")
                    displayResolutionCustom.put("${target.x}x${target.y}")
                    // Letterbox instead of rescale: the framebuffer maps 1:1 to screen pixels.
                    displayStretch.put(false)
                }
            }
        }
    }

    fun info(context: Context): Map<String, Any> {
        val physical = physicalSize(context)
        val target = effectiveSize(context)
        val scale = uiScale(context)
        val density = context.resources.displayMetrics.densityDpi
        return mapOf(
            "deviceWidth" to physical.x,
            "deviceHeight" to physical.y,
            "width" to target.x,
            "height" to target.y,
            "resolutionMode" to mode(context),
            "x11Mode" to if (mode(context) == MODE_NATIVE) "native" else "custom",
            "uiScale" to scale,
            "xftDpi" to xftDpi(scale),
            "densityDpi" to density,
            "powerMode" to powerMode(context),
            "autoIme" to autoIme(context),
            "oneToOne" to (physical.x == target.x && physical.y == target.y),
        )
    }
}
