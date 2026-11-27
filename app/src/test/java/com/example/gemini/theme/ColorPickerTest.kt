package com.example.gemini.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The hex field and the three pickers are the only way into the palette, so the parsing gets its own tests. */
class ColorPickerTest {

    @Test
    fun `six hex digits parse to an opaque colour`() {
        val colour = parseHexColor("7C5CFC")
        assertEquals(Color(0xFF7C5CFC), colour)
        assertEquals(1f, colour!!.alpha)
    }

    @Test
    fun `a leading hash and lower case are accepted`() {
        assertEquals(Color(0xFF00BFA5), parseHexColor("#00bfa5"))
        assertEquals(Color(0xFF00BFA5), parseHexColor("  #00BFA5  "))
    }

    @Test
    fun `anything incomplete is rejected instead of guessed`() {
        assertNull(parseHexColor(""))
        assertNull(parseHexColor("#12345"))
        assertNull(parseHexColor("1234567"))
        assertNull(parseHexColor("GGGGGG"))
    }

    @Test
    fun `a parsed colour reads back as the same hex`() {
        listOf("D97706", "181816", "FFFFFF", "000000", "00BFA5").forEach { hex ->
            assertEquals(hex, parseHexColor(hex)!!.toHexString())
        }
    }

    @Test
    fun `the accent and the second colour stay distinct in the palette`() {
        val accent = parseHexColor("D97706")!!
        val second = parseHexColor("00BFA5")!!
        val background = parseHexColor("181816")!!
        val palette = customPalette(accent, background, second)
        val hueOf = { c: Color -> c.toHsv().h }
        // The picked second colour is teal; the accent is amber: they must not collapse into each other.
        assertEquals(true, kotlin.math.abs(hueOf(palette.accentSoft) - hueOf(second)) < 8f)
        assertEquals(true, kotlin.math.abs(hueOf(palette.accent) - hueOf(accent)) > 40f)
    }
}
