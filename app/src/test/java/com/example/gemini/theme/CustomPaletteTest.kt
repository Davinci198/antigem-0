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
    fun `a second colour is used as given, not derived`() {
        val teal = Color(0xFF00BFA5)
        val derived = customPalette(violet, dark)
        val chosen = customPalette(violet, dark, teal)
        val tealHue = teal.toHsv().h
        val chosenHue = chosen.accentSoft.toHsv().h
        val derivedHue = derived.accentSoft.toHsv().h
        assertTrue("the picked colour wins", kotlin.math.abs(chosenHue - tealHue) < 8f)
        assertTrue("and it is not the derived one", kotlin.math.abs(chosenHue - derivedHue) > 20f)
        assertTrue("the secondary scheme colour follows it", chosen.darkColors.secondary.toHsv().h.let { kotlin.math.abs(it - tealHue) < 12f })
    }

    @Test
    fun `bestForeground picks the readable side`() {
        assertEquals(Color.Black, Color(0xFFFFFFFF).bestForeground())
        assertEquals(Color.White, Color(0xFF101010).bestForeground())
    }

    @Test
    fun `every preset has button text that reads on its accent`() {
        // The accent is used as a surface (buttons, chips), so what has to clear WCAG AA is the text on top of it,
        // not the accent against the background (which is a link colour and may be lighter on purpose).
        colorPresets.forEach { preset ->
            val palette = customPalette(preset.accent, preset.background, preset.second)
            assertTrue(
                "${preset.label}: text on the accent",
                contrastRatio(palette.accent.bestForeground(), palette.accent) >= 4.5f
            )
            assertTrue(
                "${preset.label}: body text on the background",
                contrastRatio(palette.darkColors.onSurface, palette.background) >= 4.5f
            )
            assertTrue(
                "${preset.label}: secondary text on the background",
                contrastRatio(palette.muted, palette.background) >= 3f
            )
        }
    }

    @Test
    fun `a token override wins over the derived colour and leaves the rest alone`() {
        val accent = Color(0xFFD97706)
        val background = Color(0xFF181816)
        val border = Color(0xFF334455)
        val plain = customPalette(accent, background)
        val edited = customPalette(accent, background, overrides = mapOf(ThemeToken.OUTLINE to border))
        assertEquals(border, edited.outline)
        assertEquals(plain.surface, edited.surface)
        assertTrue("the derived border was different", plain.outline != border)
    }

    @Test
    fun `code blocks get their own surface, derived from the card unless set`() {
        val plain = customPalette(Color(0xFFD97706), Color(0xFF181816))
        assertTrue("derived code background is darker than the card", plain.codeBg.relativeLuminance() < plain.surface.relativeLuminance())
        val chosen = Color(0xFF101010)
        val edited = customPalette(Color(0xFFD97706), Color(0xFF181816), overrides = mapOf(ThemeToken.CODE_BG to chosen))
        assertEquals(chosen, edited.codeBg)
    }

    @Test
    fun `the shipped list is twenty-six themes, eight of them light`() {
        assertEquals(26, colorPresets.size)
        assertEquals(8, lightPresets.size)
        assertEquals(18, darkPresets.size)
        assertEquals(lightPresets.size + darkPresets.size, colorPresets.size)
        assertEquals("Amber Night", defaultPreset.label)
        assertTrue("labels are unique", colorPresets.map { it.label }.toSet().size == colorPresets.size)
    }

    @Test
    fun `a preset can be found by name, in any case`() {
        assertEquals("Nord", presetNamed("nord")?.label)
        assertEquals("Solarized Light", presetNamed("  solarized light ")?.label)
        assertEquals(null, presetNamed("No Such Theme"))
    }
}
