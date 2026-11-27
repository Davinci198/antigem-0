package com.example.gemini.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's colours, as computed values instead of fixed ones.
 *
 * When the user picks an accent and a background in Settings, [ThemePrefs.palette] answers with the generated
 * palette and every one of these follows it; without a choice they keep the warm amber palette the app shipped
 * with. Reading [ThemePrefs.version] here is what makes a colour change repaint the screens that use these values
 * directly instead of through MaterialTheme.
 */
private val DefaultAccent = Color(0xFFD97706)
private val DefaultAccentDark = Color(0xFFB45309)
private val DefaultCream = Color(0xFFF9F9FB)
private val DefaultUserBubbleLight = Color(0xFFE8EAED)
private val DefaultThinkingLight = Color(0xFFF1F3F5)
private val DefaultDarkBg = Color(0xFF181816)
private val DefaultDarkSurface = Color(0xFF232320)
private val DefaultUserBubbleDark = Color(0xFF32312D)
private val DefaultThinkingDark = Color(0xFF282724)
private val DefaultCodeBlockBgDark = Color(0xFF1E1E1C)
private val DefaultBlue = Color(0xFF3B82F6)
private val DefaultPurple = Color(0xFF8B5CF6)

private fun paletteOrNull(): CustomPalette? {
    // Reading the version registers a snapshot dependency, so every screen that uses a colour below is redrawn
    // when the user changes the theme.
    @Suppress("UNUSED_EXPRESSION")
    ThemePrefs.version.value
    return ThemePrefs.palette()
}

val ClaudeTerracotta: Color get() = paletteOrNull()?.accent ?: DefaultAccent
val ClaudeTerracottaDark: Color get() = paletteOrNull()?.accent?.mix(Color.Black, 0.18f) ?: DefaultAccentDark
val ClaudeCream: Color get() = paletteOrNull()?.lightColors?.background ?: DefaultCream
val ClaudeWarmSurface: Color get() = paletteOrNull()?.lightColors?.surface ?: DefaultCream
val ClaudeUserBubbleLight: Color get() = paletteOrNull()?.lightColors?.surfaceVariant ?: DefaultUserBubbleLight
val ClaudeThinkingLight: Color get() = paletteOrNull()?.lightColors?.surface ?: DefaultThinkingLight

val ClaudeDarkBg: Color get() = paletteOrNull()?.background ?: DefaultDarkBg
val ClaudeDarkSurface: Color get() = paletteOrNull()?.surface ?: DefaultDarkSurface
val ClaudeUserBubbleDark: Color get() = paletteOrNull()?.surfaceVariant ?: DefaultUserBubbleDark
val ClaudeThinkingDark: Color get() = paletteOrNull()?.surface?.mix(Color.White, 0.03f) ?: DefaultThinkingDark

val CodeBlockBgDark: Color get() = paletteOrNull()?.codeBg ?: DefaultCodeBlockBgDark
val TextPrimaryLight: Color = Color(0xFF1C1917)
val TextSecondaryLight: Color = Color(0xFF78716C)
val TextPrimaryDark: Color get() = paletteOrNull()?.darkColors?.onSurface ?: Color(0xFFF5F5F4)
val TextSecondaryDark: Color get() = paletteOrNull()?.muted ?: Color(0xFFA8A29E)

/** States keep their meaning whatever the accent is: green is a pass, amber needs attention, red failed. */
val QuotaGreen: Color = Color(0xFF16A34A)
val QuotaAmber: Color = Color(0xFFD97706)
val QuotaRed: Color = Color(0xFFDC2626)

val GeminiBlue: Color get() = paletteOrNull()?.accent?.rotateHue(60f) ?: DefaultBlue

/** The second colour of the theme: gradient ends and anything painted next to the accent. */
val GeminiPurple: Color get() = paletteOrNull()?.accentSoft ?: DefaultPurple

/**
 * Colors asked for by *role* instead of by brand. A screen that means "this needs attention" should use
 * [warning] rather than a hex, so it follows the theme.
 */
object AppColors {
    /** Needs attention, is not an error. */
    val warning: Color get() = QuotaAmber

    /** The second accent, after the agent's own. */
    val accentAlt: Color get() = GeminiPurple

    /** Cool accent: things you can operate on (voice, device tools). */
    val accentTeal: Color get() = paletteOrNull()?.accentSoft ?: Color(0xFF00ACC1)

    /** Warm accent: creative or experimental features. */
    val accentPink: Color get() = paletteOrNull()?.accent?.rotateHue(150f) ?: Color(0xFFEC4899)

    /** A confirmed, healthy state. */
    val success: Color get() = QuotaGreen
}
