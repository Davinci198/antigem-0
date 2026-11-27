package com.example.gemini.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The palette is the only place where a user-picked colour becomes 30 theme colours, so its rules are pinned
 * here instead of being judged by eye: contrast that keeps text readable, surfaces that move in the right
 * direction, and hue maths that survives a round trip.
 */
class CustomPaletteTest {

    private val dark = Color(0xFF181816)
    private val violet = Color(0xFF7C5CFC)

    @Test
    fun `the accent keeps WCAG AA against the surfaces it is drawn on`() {
        val palette = customPalette(violet, dark)
        val scheme = palette.darkColors
        assertTrue(
            "primary on background reads",
            contrastRatio(scheme.primary, scheme.background) >= 4.5f
        )
        assertTrue(
            "onPrimary reads on primary",
            contrastRatio(scheme.onPrimary, scheme.primary) >= 4.5f
        )
        assertTrue(
            "body text reads on surface",
            contrastRatio(scheme.onSurface, scheme.surface) >= 4.5f
        )
    }

    @Test
    fun `a light background still produces readable dark text`() {
        val palette = customPalette(Color(0xFF7C3AED), Color(0xFFFAF7F2))
        assertTrue(
            "onSurface on a light background",
            contrastRatio(palette.darkColors.onSurface, palette.darkColors.background) >= 4.5f
        )
    }

    @Test
    fun `surfaces walk away from the background`() {
        val palette = customPalette(violet, dark)
        assertTrue("surface is lighter than the background", palette.surface.relativeLuminance() > palette.background.relativeLuminance())
        assertTrue("variant is lighter than the surface", palette.surfaceVariant.relativeLuminance() > palette.surface.relativeLuminance())
        assertTrue("muted text is the brightest step", palette.muted.relativeLuminance() > palette.surfaceVariant.relativeLuminance())
    }

    @Test
    fun `hsv round trip keeps the colour`() {
        val original = Color(0xFF14B8A6)
        val hsv = original.toHsv()
        val back = hsvToColor(hsv.h, hsv.s, hsv.v)
        assertEquals(original.red, back.red, 0.01f)
        assertEquals(original.green, back.green, 0.01f)
        assertEquals(original.blue, back.blue, 0.01f)
    }

    @Test
    fun `hue rotation keeps saturation and value`() {
        val rotated = violet.rotateHue(120f)
        val before = violet.toHsv()
        val after = rotated.toHsv()
        assertEquals(before.s, after.s, 0.02f)
        assertEquals(before.v, after.v, 0.02f)
        assertTrue("the hue actually moved", kotlin.math.abs(after.h - before.h) > 60f)
    }

    @Test
    fun `bestForeground picks the readable side`() {
        assertEquals(Color.Black, Color(0xFFFFFFFF).bestForeground())
        assertEquals(Color.White, Color(0xFF101010).bestForeground())
    }

    @Test
    fun `every preset has an accent that reads on its background`() {
        colorPresets.forEach { preset ->
            val scheme = customPalette(preset.accent, preset.background).darkColors
            assertTrue(
                "${preset.label}: accent reads on its background",
                contrastRatio(scheme.primary, scheme.background) >= 4.5f
            )
        }
    }
}
