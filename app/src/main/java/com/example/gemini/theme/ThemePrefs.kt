package com.example.gemini.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The colours the user chose, and whether they are used instead of the built-in palette.
 *
 * Kept in its own preferences file and mirrored in Compose snapshot state because the theme is read from every
 * composable: a change has to repaint the whole app without a restart.
 *
 * Three inputs (accent, background, second accent) drive the derivation in [customPalette]; [tokens] then lets any
 * single one of the eight [ThemeToken] colours be replaced outright, which is what the token editor and
 * `theme_mcp` write.
 */
object ThemePrefs {

    private const val FILE = "appearance"
    private const val KEY_ENABLED = "custom_colors"
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"
    private const val KEY_SECOND = "second_accent"
    private const val KEY_TOKENS = "token_"

    /** The app's own palette: warm amber on a near-black background. */
    val defaultAccent = Color(0xFFD97706)
    val defaultBackground = Color(0xFF181816)

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * Compose snapshot state: reading it inside a composable makes that composable repaint when the colours change.
     * It is what lets the plain colour vals in Color.kt follow the theme.
     */
    val version = mutableStateOf(0L)

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun store(): SharedPreferences? = prefs

    var enabled: Boolean
        get() = store()?.getBoolean(KEY_ENABLED, false) ?: false
        set(value) {
            store()?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
            bump()
        }

    val accent: Color
        get() = Color(store()?.getInt(KEY_ACCENT, defaultAccent.toArgb()) ?: defaultAccent.toArgb())

    val background: Color
        get() = Color(store()?.getInt(KEY_BACKGROUND, defaultBackground.toArgb()) ?: defaultBackground.toArgb())

    /** The second colour the user set, or null when it should follow the accent (rotated by 30°). */
    val secondAccent: Color?
        get() = store()?.let { p ->
            val stored = p.getInt(KEY_SECOND, Int.MIN_VALUE)
            if (stored == Int.MIN_VALUE) null else Color(stored)
        }

    /** Token → colour, only for the tokens that were set by hand. */
    fun tokens(): Map<String, Color> {
        val p = store() ?: return emptyMap()
        return ThemeToken.keys.mapNotNull { key ->
            val stored = p.getInt(KEY_TOKENS + key, Int.MIN_VALUE)
            if (stored == Int.MIN_VALUE) null else key to Color(stored)
        }.toMap()
    }

    fun token(key: String): Color? = store()?.let { p ->
        val stored = p.getInt(KEY_TOKENS + key, Int.MIN_VALUE)
        if (stored == Int.MIN_VALUE) null else Color(stored)
    }

    /** Sets one token outright; a null colour drops the override and lets it be derived again. */
    fun setToken(key: String, color: Color?) {
        val edit = store()?.edit()
        if (color == null) edit?.remove(KEY_TOKENS + key) else edit?.putInt(KEY_TOKENS + key, color.toArgb())
        edit?.putBoolean(KEY_ENABLED, true)?.apply()
        bump()
    }

    fun setColors(accent: Color, background: Color, secondAccent: Color? = null) {
        val edit = store()?.edit()
            ?.putInt(KEY_ACCENT, accent.toArgb())
            ?.putInt(KEY_BACKGROUND, background.toArgb())
            ?.putBoolean(KEY_ENABLED, true)
        if (secondAccent == null) edit?.remove(KEY_SECOND) else edit?.putInt(KEY_SECOND, secondAccent.toArgb())
        edit?.apply()
        bump()
    }

    /** Everything at once, as `theme_mcp` (and the token editor) needs it. */
    fun setTheme(accent: Color, background: Color, secondAccent: Color?, tokens: Map<String, Color>) {
        val edit = store()?.edit()
            ?.putInt(KEY_ACCENT, accent.toArgb())
            ?.putInt(KEY_BACKGROUND, background.toArgb())
            ?.putBoolean(KEY_ENABLED, true)
        if (secondAccent == null) edit?.remove(KEY_SECOND) else edit?.putInt(KEY_SECOND, secondAccent.toArgb())
        ThemeToken.keys.forEach { key ->
            val color = tokens[key]
            if (color == null) edit?.remove(KEY_TOKENS + key) else edit?.putInt(KEY_TOKENS + key, color.toArgb())
        }
        edit?.apply()
        bump()
    }

    /** One of the shipped themes, applied in full. */
    fun applyPreset(preset: ColorPreset) {
        setTheme(preset.accent, preset.background, preset.second, emptyMap())
    }

    /** Back to the app's own palette. */
    fun reset() {
        val edit = store()?.edit()
            ?.remove(KEY_ACCENT)
            ?.remove(KEY_BACKGROUND)
            ?.remove(KEY_SECOND)
            ?.putBoolean(KEY_ENABLED, false)
        ThemeToken.keys.forEach { edit?.remove(KEY_TOKENS + it) }
        edit?.apply()
        bump()
    }

    /** The palette currently in force; null while the built-in theme should be used. */
    fun palette(): CustomPalette? =
        if (enabled) customPalette(accent, background, secondAccent, tokens()) else null

    /** The palette these colours would produce, whether or not they are in force — what the editor previews. */
    fun previewPalette(
        accent: Color,
        background: Color,
        secondAccent: Color?,
        tokens: Map<String, Color>,
    ): CustomPalette = customPalette(accent, background, secondAccent, tokens)

    private fun bump() {
        version.value = System.currentTimeMillis()
    }
}
