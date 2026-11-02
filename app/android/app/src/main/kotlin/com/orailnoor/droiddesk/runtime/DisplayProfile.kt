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
 * Resolution is user selectable and defaults to a *scaled* (power saving) profile: the X
 * framebuffer is `physical * frameScale` (0.75x by default) and the compositor enlarges it
 * with nearest-neighbour filtering, so nothing is bilinearly blurred. "1:1 物理像素" maps the
 * framebuffer to the panel one-to-one instead.
 */
object DisplayProfile {
    private const val PREFS = "FlutterSharedPreferences"
    const val MODE_DEVICE = "device"          // follow device, scaled by frameScale
    const val MODE_ONE_TO_ONE = "oneToOne"    // framebuffer == physical pixels
    const val MODE_1080P = "1080p"
    const val MODE_1440P = "1440p"

    const val DEFAULT_FRAME_SCALE = 0.75
    val FRAME_SCALES = listOf(0.5, 0.75, 1.0)

    const val DEFAULT_SCALE = 1.5
    val SCALES = listOf(1.0, 1.25, 1.5, 2.0)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun mode(context: Context): String {
        val value = prefs(context).getString("flutter.ddResolutionMode", MODE_DEVICE) ?: MODE_DEVICE
        return if (value in listOf(MODE_DEVICE, MODE_ONE_TO_ONE, MODE_1080P, MODE_1440P)) {
            value
        } else {
            MODE_DEVICE
        }
    }

    fun frameScale(context: Context): Double {
        val raw = prefs(context).getString("flutter.ddFrameScale", null)
        val parsed = raw?.toDoubleOrNull() ?: DEFAULT_FRAME_SCALE
        return parsed.takeIf { value -> FRAME_SCALES.any { kotlin.math.abs(it - value) < 0.001 } }
            ?: DEFAULT_FRAME_SCALE
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

    /**
     * Scales the panel down to the requested short side while keeping its aspect ratio, and
     * never exceeds the panel on the other axis.
     */
    private fun fitToWidth(physical: Point, width: Int): Point {
        if (physical.x <= 0 || physical.y <= 0) return Point(width, width)
        val portrait = physical.y >= physical.x
        val shortSide = minOf(physical.x, physical.y)
        val w = minOf(width, shortSide)
        val h = (w.toDouble() * (if (portrait) physical.y else physical.x) / shortSide)
            .roundToInt()
            .let { if (it % 2 == 0) it else it + 1 }
            .coerceAtMost(maxOf(physical.x, physical.y))
        return if (portrait) Point(w, h) else Point(h, w)
    }

    /** Framebuffer size the X server should allocate for the selected resolution profile. */
    fun effectiveSize(context: Context): Point {
        val physical = physicalSize(context)
        val portrait = physical.y >= physical.x
        val long = maxOf(physical.x, physical.y)
        val short = minOf(physical.x, physical.y)
        return when (mode(context)) {
            // Fixed-width presets keep the panel aspect ratio. Hardcoding 1080x1920 or
            // 1440x2560 stretched the desktop on 16:10-ish tablets and, at 1440p, asked the
            // X server for a framebuffer larger than the panel, which made libXlorie fail to
            // start (the launch then ended on the loading screen).
            MODE_1080P -> fitToWidth(physical, 1080)
            MODE_1440P -> fitToWidth(physical, 1440)
            MODE_ONE_TO_ONE -> if (physical.x > 0 && physical.y > 0) {
                Point(physical.x, physical.y)
            } else {
                if (portrait) Point(short, long) else Point(long, short)
            }
            else -> {
                val scale = frameScale(context)
                val w = (physical.x * scale).roundToInt().let { if (it % 2 == 0) it else it + 1 }
                val h = (physical.y * scale).roundToInt().let { if (it % 2 == 0) it else it + 1 }
                if (w > 0 && h > 0) Point(w, h) else Point(short, long)
            }
        }
    }

    /**
     * Applies the profile to the Termux:X11 preferences. Must run before LorieView is
     * measured, because the X server is created from those values.
     */
    fun applyToX11(context: Context) {
        val target = effectiveSize(context)
        val physical = physicalSize(context)
        val exact = target.x == physical.x && target.y == physical.y
        TermuxMainActivity.getPrefs().apply {
            displayScale.put(100)
            displayFilteringMode.put("nearest")
            adjustResolution.put(false)
            if (exact) {
                displayResolutionMode.put("native")
                displayStretch.put(true)
            } else {
                // Custom framebuffer, nearest-neighbour upscale to the Surface (no bilinear blur).
                displayResolutionMode.put("custom")
                displayResolutionCustom.put("${target.x}x${target.y}")
                displayStretch.put(true)
            }
        }
    }

    fun info(context: Context): Map<String, Any> {
        val physical = physicalSize(context)
        val target = effectiveSize(context)
        val scale = uiScale(context)
        val frame = frameScale(context)
        val density = context.resources.displayMetrics.densityDpi
        val exact = physical.x == target.x && physical.y == target.y
        return mapOf(
            "deviceWidth" to physical.x,
            "deviceHeight" to physical.y,
            "width" to target.x,
            "height" to target.y,
            "resolutionMode" to mode(context),
            "frameScale" to frame,
            "x11Mode" to if (exact) "native" else "custom",
            "uiScale" to scale,
            "xftDpi" to xftDpi(scale),
            "densityDpi" to density,
            "powerMode" to powerMode(context),
            "autoIme" to autoIme(context),
            "oneToOne" to exact,
        )
    }
}
