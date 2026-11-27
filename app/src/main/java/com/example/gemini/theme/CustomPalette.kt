package com.example.gemini.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/**
 * Two colours from the user, a whole theme out of them.
 *
 * Everything here is plain Kotlin over [Color]'s float components, so the rules are covered by JVM unit tests
 * instead of by looking at screenshots: the accent is darkened until it clears WCAG AA against the text colour
 * it sits on, and every surface is the user's background walked a fixed step towards white or black.
 */
data class Hsv(val h: Float, val s: Float, val v: Float)

fun Float.clamp01(): Float = coerceIn(0f, 1f)

fun Color.toHsv(): Hsv {
    val maxC = maxOf(red, green, blue)
    val minC = minOf(red, green, blue)
    val d = maxC - minC
    val h = when {
        d == 0f -> 0f
        maxC == red -> 60f * (((green - blue) / d) % 6f)
        maxC == green -> 60f * (((blue - red) / d) + 2f)
        else -> 60f * (((red - green) / d) + 4f)
    }
    val s = if (maxC == 0f) 0f else d / maxC
    return Hsv((h + 360f) % 360f, s, maxC)
}

fun hsvToColor(h: Float, s: Float, v: Float): Color {
    val hue = h.mod(360f)
    val sat = s.clamp01()
    val value = v.clamp01()
    val c = value * sat
    val x = c * (1f - abs(hue / 60f % 2f - 1f))
    val m = value - c
    val (r, g, b) = when {
        hue < 60f -> Triple(c, x, 0f)
        hue < 120f -> Triple(x, c, 0f)
        hue < 180f -> Triple(0f, c, x)
        hue < 240f -> Triple(0f, x, c)
        hue < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(red = r + m, green = g + m, blue = b + m, alpha = 1f)
}

fun Color.rotateHue(degrees: Float): Color {
    val hsv = toHsv()
    if (hsv.s <= 0.001f) return this
    return hsvToColor((hsv.h + degrees + 360f) % 360f, hsv.s, hsv.v)
}

fun Color.mix(other: Color, t: Float): Color {
    val u = t.clamp01()
    return Color(
        red = red + (other.red - red) * u,
        green = green + (other.green - green) * u,
        blue = blue + (other.blue - blue) * u,
        alpha = alpha + (other.alpha - alpha) * u,
    )
}

fun Color.relativeLuminance(): Float {
    fun channel(c: Float): Float =
        if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    return 0.2126f * channel(red) + 0.7152f * channel(green) + 0.0722f * channel(blue)
}

fun contrastRatio(a: Color, b: Color): Float {
    val la = a.relativeLuminance()
    val lb = b.relativeLuminance()
    return (max(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/** White or black, whichever reads better on this colour. */
fun Color.bestForeground(): Color =
    if (contrastRatio(this, Color.White) >= contrastRatio(this, Color.Black)) Color.White else Color.Black

/** Darkens this colour until it clears [min] contrast against [against]. */
fun Color.darkenedFor(against: Color, min: Float = 4.5f): Color {
    var candidate = this
    var guard = 0
    while (contrastRatio(candidate, against) < min && guard < 40) {
        candidate = candidate.mix(Color.Black, 0.06f)
        guard++
    }
    return candidate
}

/** A palette built from the colours the user picked: the schemes plus the scalars the app paints with. */
data class CustomPalette(
    val accent: Color,
    /** The second colour: gradient ends, the second accent, anything that must not be the accent itself. */
    val accentSoft: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val muted: Color,
    /** Code blocks and the terminal-ish surfaces. */
    val codeBg: Color,
    val darkColors: ColorScheme,
    val lightColors: ColorScheme,
)

/**
 * The colours a user (or a model through theme_mcp) can set on their own, over the derived palette.
 *
 * Keys are stable: they are also what `theme_tokens` exposes, so a choice saved by one build keeps working in the
 * next one. Anything not present is derived as usual.
 */
object ThemeToken {
    const val ACCENT = "accent"
    const val SECOND = "second"
    const val BACKGROUND = "background"
    const val SURFACE = "surface"
    const val SURFACE_VARIANT = "surface_variant"
    const val OUTLINE = "outline"
    const val MUTED = "muted"
    const val CODE_BG = "code_bg"

    /** Every overridable token, in the order the editor shows them. */
    val keys = listOf(ACCENT, SECOND, BACKGROUND, SURFACE, SURFACE_VARIANT, OUTLINE, MUTED, CODE_BG)

    /** What each token paints, for the editor and for `theme_tokens`. */
    fun about(key: String): String = when (key) {
        ACCENT -> "Buttons, selected tabs, links, the send button"
        SECOND -> "Gradient ends and the second accent"
        BACKGROUND -> "The screen behind everything"
        SURFACE -> "Cards, sheets, the composer, top bars"
        SURFACE_VARIANT -> "Chips, inputs, the user's chat bubble"
        OUTLINE -> "Borders, dividers, input outlines"
        MUTED -> "Secondary text: captions, hints, timestamps"
        CODE_BG -> "Code blocks and monospace surfaces"
        else -> ""
    }
}

/**
 * The dark scheme walks the background toward white (toward black when the user picked a light background), the
 * light scheme walks it the other way, and the accent drives primary/secondary with contrast guaranteed on the
 * matching `on*` colour.
 *
 * [secondAccent] is optional: when it is null the second colour is the accent rotated 30°, which keeps a theme
 * coherent without asking anything; when the user picked one, that exact colour is used instead.
 *
 * [overrides] can replace any single token ([ThemeToken]) after the derivation, so a user who wants this exact
 * border colour gets it without having to bend the two colour inputs to reach it by accident.
 */
fun customPalette(
    accent: Color,
    background: Color,
    secondAccent: Color? = null,
    overrides: Map<String, Color> = emptyMap(),
): CustomPalette {
    val lightBackground = background.relativeLuminance() > 0.32f
    val onDark = if (lightBackground) Color(0xFF12100F) else Color(0xFFF2F4F3)
    val liftDirection = if (lightBackground) Color.Black else Color.White

    fun lift(amount: Float): Color = background.mix(liftDirection, amount)

    val darkAccent = (overrides[ThemeToken.ACCENT] ?: accent).darkenedFor(onDark)
    val secondSource = overrides[ThemeToken.SECOND] ?: secondAccent
    val darkSecondary = (secondSource ?: darkAccent.rotateHue(30f)).darkenedFor(onDark)
    val lightAccent = (overrides[ThemeToken.ACCENT] ?: accent).darkenedFor(Color.White)
    val lightSecondary = (secondSource ?: lightAccent.rotateHue(30f)).darkenedFor(Color.White)

    val surface = overrides[ThemeToken.SURFACE] ?: lift(if (lightBackground) 0.06f else 0.04f)
    val surfaceVariant = overrides[ThemeToken.SURFACE_VARIANT] ?: lift(if (lightBackground) 0.10f else 0.08f)
    val outline = overrides[ThemeToken.OUTLINE] ?: lift(0.16f)
    // On a light background 30% toward black is still too pale for secondary text (2.1:1); 45% clears 3:1.
    val muted = overrides[ThemeToken.MUTED] ?: lift(if (lightBackground) 0.45f else 0.55f)
    val codeBg = overrides[ThemeToken.CODE_BG] ?: surface.mix(Color.Black, 0.15f)

    return CustomPalette(
        accent = darkAccent,
        accentSoft = darkSecondary,
        background = overrides[ThemeToken.BACKGROUND] ?: background,
        surface = surface,
        surfaceVariant = surfaceVariant,
        outline = outline,
        muted = muted,
        codeBg = codeBg,
        darkColors = darkColorScheme(
            primary = darkAccent,
            onPrimary = darkAccent.bestForeground(),
            primaryContainer = darkAccent.mix(background, 0.78f),
            onPrimaryContainer = darkAccent.mix(onDark, 0.55f),
            secondary = darkSecondary,
            onSecondary = darkSecondary.bestForeground(),
            secondaryContainer = darkSecondary.mix(background, 0.80f),
            onSecondaryContainer = darkSecondary.mix(onDark, 0.55f),
            background = overrides[ThemeToken.BACKGROUND] ?: background,
            onBackground = onDark,
            surface = surface,
            onSurface = onDark,
            surfaceVariant = surfaceVariant,
            onSurfaceVariant = muted,
            outline = outline,
            outlineVariant = outline.mix(background, 0.35f),
            surfaceTint = Color.Transparent,
        ),
        lightColors = lightColorScheme(
            primary = lightAccent,
            onPrimary = Color.White,
            primaryContainer = lightAccent.mix(Color.White, 0.84f),
            onPrimaryContainer = lightAccent.mix(Color.Black, 0.55f),
            secondary = lightSecondary,
            onSecondary = Color.White,
            secondaryContainer = lightSecondary.mix(Color.White, 0.86f),
            onSecondaryContainer = lightSecondary.mix(Color.Black, 0.55f),
            background = background.mix(Color.White, 0.92f),
            onBackground = background.mix(Color.Black, 0.80f),
            surface = surface.mix(Color.White, 0.06f),
            onSurface = background.mix(Color.Black, 0.80f),
            surfaceVariant = surfaceVariant.mix(Color.White, 0.55f),
            onSurfaceVariant = muted.mix(Color.Black, 0.35f),
            outline = outline.mix(Color.White, 0.30f),
            outlineVariant = outline.mix(Color.White, 0.60f),
            surfaceTint = Color.Transparent,
        ),
    )
}

