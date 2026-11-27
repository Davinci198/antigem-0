package com.example.gemini.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The two colours the user chose, and whether they are used instead of the built-in palette.
 *
 * Kept in its own preferences file and mirrored in a [StateFlow] because the theme reads it from every
 * composable: a change has to repaint the whole app without a restart.
 */
object ThemePrefs {

    private const val FILE = "appearance"
    private const val KEY_ENABLED = "custom_colors"
    private const val KEY_ACCENT = "accent"
    private const val KEY_BACKGROUND = "background"

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

    fun setColors(accent: Color, background: Color) {
        store()?.edit()
            ?.putInt(KEY_ACCENT, accent.toArgb())
            ?.putInt(KEY_BACKGROUND, background.toArgb())
            ?.putBoolean(KEY_ENABLED, true)
            ?.apply()
        bump()
    }

    /** Back to the app's own palette. */
    fun reset() {
        store()?.edit()
            ?.remove(KEY_ACCENT)
            ?.remove(KEY_BACKGROUND)
            ?.putBoolean(KEY_ENABLED, false)
            ?.apply()
        bump()
    }

    /** The palette currently in force; null while the built-in theme should be used. */
    fun palette(): CustomPalette? =
        if (enabled) customPalette(accent, background) else null

    private fun bump() {
        version.value = System.currentTimeMillis()
    }
}
