package com.example.gemini.theme

import androidx.compose.ui.graphics.Color

// Claude-inspired warm color palette
val ClaudeTerracotta = Color(0xFFD97706)
val ClaudeTerracottaDark = Color(0xFFB45309)
val ClaudeCream = Color(0xFFF9F9FB)
val ClaudeWarmSurface = Color(0xFFF9F9FB)
val ClaudeUserBubbleLight = Color(0xFFE8EAED)
val ClaudeThinkingLight = Color(0xFFF1F3F5)

val ClaudeDarkBg = Color(0xFF181816)
val ClaudeDarkSurface = Color(0xFF232320)
val ClaudeUserBubbleDark = Color(0xFF32312D)
val ClaudeThinkingDark = Color(0xFF282724)

val CodeBlockBgDark = Color(0xFF1E1E1C)
val TextPrimaryLight = Color(0xFF1C1917)
val TextSecondaryLight = Color(0xFF78716C)
val TextPrimaryDark = Color(0xFFF5F5F4)
val TextSecondaryDark = Color(0xFFA8A29E)

val QuotaGreen = Color(0xFF16A34A)
val QuotaAmber = Color(0xFFD97706)
val QuotaRed = Color(0xFFDC2626)

val GeminiBlue = Color(0xFF3B82F6)
val GeminiPurple = Color(0xFF8B5CF6)

/**
 * Colors asked for by *role* instead of by brand. This fork has no dynamic theme manager yet (upstream's ThemeManager,
 * which can repaint the whole app, is a separate import), so each role is the fixed value of upstream's default theme,
 * shared with the brand constant that already carries the same color here. A screen that means "this needs attention"
 * should use [warning] rather than a hex, so it follows the theme when one lands.
 */
object AppColors {
    /** Needs attention, is not an error. */
    val warning: Color get() = QuotaAmber

    /** The second accent, after the agent's own. */
    val accentAlt: Color get() = GeminiPurple

    /** Cool accent: things you can operate on (voice, device tools). */
    val accentTeal: Color get() = Color(0xFF00ACC1)

    /** Warm accent: creative or experimental features. */
    val accentPink: Color get() = Color(0xFFEC4899)
}
