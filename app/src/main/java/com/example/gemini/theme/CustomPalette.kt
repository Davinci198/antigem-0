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

/** A palette built from the two colours the user picked: the schemes plus the scalars the app paints with. */
data class CustomPalette(
    val accent: Color,
    /** The second colour: gradient ends, the second accent, anything that must not be the accent itself. */
    val accentSoft: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val outline: Color,
    val muted: Color,
    val darkColors: ColorScheme,
    val lightColors: ColorScheme,
)

/**
 * The dark scheme walks the background toward white (toward black when the user picked a light background), the
 * light scheme walks it the other way, and the accent drives primary/secondary with contrast guaranteed on the
 * matching `on*` colour.
 *
 * [secondAccent] is optional: when it is null the second colour is the accent rotated 30°, which keeps a theme
 * coherent without asking anything; when the user picked one, that exact colour is used instead.
 */
fun customPalette(accent: Color, background: Color, secondAccent: Color? = null): CustomPalette {
    val lightBackground = background.relativeLuminance() > 0.32f
    val onDark = if (lightBackground) Color(0xFF12100F) else Color(0xFFF2F4F3)
    val liftDirection = if (lightBackground) Color.Black else Color.White

    fun lift(amount: Float): Color = background.mix(liftDirection, amount)
    fun surfaceOf(): Color = lift(if (lightBackground) 0.06f else 0.04f)
    fun variantOf(): Color = lift(if (lightBackground) 0.10f else 0.08f)
    fun mutedOf(): Color = lift(if (lightBackground) 0.30f else 0.55f)

    val darkAccent = accent.darkenedFor(onDark)
    val darkSecondary = (secondAccent ?: darkAccent.rotateHue(30f)).darkenedFor(onDark)
    val lightAccent = accent.darkenedFor(Color.White)
    val lightSecondary = (secondAccent ?: lightAccent.rotateHue(30f)).darkenedFor(Color.White)

    return CustomPalette(
        accent = darkAccent,
        accentSoft = secondAccent?.darkenedFor(onDark) ?: darkAccent.rotateHue(-30f),
        background = background,
        surface = surfaceOf(),
        surfaceVariant = variantOf(),
        outline = lift(0.16f),
        muted = mutedOf(),
        darkColors = darkColorScheme(
            primary = darkAccent,
            onPrimary = darkAccent.bestForeground(),
            primaryContainer = darkAccent.mix(background, 0.78f),
            onPrimaryContainer = darkAccent.mix(onDark, 0.55f),
            secondary = darkSecondary,
            onSecondary = darkSecondary.bestForeground(),
            secondaryContainer = darkSecondary.mix(background, 0.80f),
            onSecondaryContainer = darkSecondary.mix(onDark, 0.55f),
            background = background,
            onBackground = onDark,
            surface = surfaceOf(),
            onSurface = onDark,
            surfaceVariant = variantOf(),
            onSurfaceVariant = mutedOf(),
            outline = lift(0.16f),
            outlineVariant = lift(0.12f),
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
            surface = Color.White,
            onSurface = background.mix(Color.Black, 0.80f),
            surfaceVariant = background.mix(Color.White, 0.85f),
            onSurfaceVariant = background.mix(Color.Black, 0.45f),
            outline = background.mix(Color.White, 0.55f),
            outlineVariant = background.mix(Color.White, 0.75f),
            surfaceTint = Color.Transparent,
        ),
    )
}

/** Starting points offered as swatches, so nobody has to drag a slider to get a usable theme. */
data class ColorPreset(val label: String, val accent: Color, val background: Color)

val colorPresets: List<ColorPreset> = listOf(
    ColorPreset("Amber", Color(0xFFD97706), Color(0xFF181816)),
    ColorPreset("Violet", Color(0xFF7C5CFC), Color(0xFF0F0F10)),
    ColorPreset("Teal", Color(0xFF14B8A6), Color(0xFF0D1414)),
    ColorPreset("Rose", Color(0xFFF43F5E), Color(0xFF16100F)),
    ColorPreset("Green", Color(0xFF22C55E), Color(0xFF0E1311)),
    ColorPreset("Blue", Color(0xFF3B82F6), Color(0xFF0F1116)),
    ColorPreset("Paper", Color(0xFF7C3AED), Color(0xFFFAF7F2)),
)
