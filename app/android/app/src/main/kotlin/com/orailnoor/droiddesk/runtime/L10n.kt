package com.orailnoor.droiddesk.runtime

import android.content.Context

/**
 * Minimal native-side localization.
 *
 * The Flutter layer persists the selected language in shared_preferences
 * (key "appLanguage", values "zh" / "en"). Reading it here lets the runtime
 * emit the same language for install progress and log lines, without having to
 * thread the locale through every MethodChannel call.
 */
object L10n {
    private const val PREFS = "FlutterSharedPreferences"
    private const val KEY = "flutter.appLanguage"

    fun isChinese(context: Context): Boolean = language(context) != "en"

    fun language(context: Context): String = try {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "zh")
            ?.takeIf { it.isNotBlank() }
            ?: "zh"
    } catch (e: Exception) {
        "zh"
    }

    /** Pick the Chinese or English variant according to the app language. */
    fun tr(context: Context, zh: String, en: String): String =
        if (isChinese(context)) zh else en
}
