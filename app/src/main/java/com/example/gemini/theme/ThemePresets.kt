package com.example.gemini.theme

import androidx.compose.ui.graphics.Color

/**
 * The themes that come with the app: twenty-six of them, eight light and eighteen dark.
 *
 * Each one is only three colours — an accent, a background and a second accent — because everything else
 * (surfaces, borders, muted text, containers, the two Material schemes) is derived from those in
 * [customPalette]. That keeps a theme coherent and makes adding one a single line.
 *
 * The names and the palettes follow the ones the upstream app ships, so a user coming from there finds the
 * same list; the colours are the same hexes, trimmed to the three that survive in a derived palette.
 */
data class ColorPreset(
    val label: String,
    val accent: Color,
    val background: Color,
    val second: Color? = null,
    val dark: Boolean = true,
)

/** Dark themes first: the app opens on [DEFAULT_PRESET_LABEL]. */
val colorPresets: List<ColorPreset> = listOf(
    ColorPreset("Amber Night", Color(0xFFD97706), Color(0xFF181816), Color(0xFFB45309)),
    ColorPreset("Midnight Aurora", Color(0xFFA78BFA), Color(0xFF0A0B14), Color(0xFF7C6AE6)),
    ColorPreset("Discord", Color(0xFF5865F2), Color(0xFF121214), Color(0xFF4752C4)),
    ColorPreset("Obsidian", Color(0xFF8B9CFF), Color(0xFF0D0E12), Color(0xFF6E7FF3)),
    ColorPreset("Graphite", Color(0xFFF0A04B), Color(0xFF121212), Color(0xFFD9822B)),
    ColorPreset("Midnight OLED", Color(0xFF22D3EE), Color(0xFF000000), Color(0xFF0891B2)),
    ColorPreset("High Contrast", Color(0xFFFFD60A), Color(0xFF000000), Color(0xFF00E5FF)),
    ColorPreset("Mocha", Color(0xFFCBA6F7), Color(0xFF1E1E2E), Color(0xFFF5C2E7)),
    ColorPreset("Neon City", Color(0xFF7AA2F7), Color(0xFF1A1B26), Color(0xFFBB9AF7)),
    ColorPreset("Aurora", Color(0xFF2DD4BF), Color(0xFF0B1118), Color(0xFF34D399)),
    ColorPreset("Nord", Color(0xFF88C0D0), Color(0xFF2E3440), Color(0xFF81A1C1)),
    ColorPreset("Dracula", Color(0xFFBD93F9), Color(0xFF282A36), Color(0xFFFF79C6)),
    ColorPreset("Velvet", Color(0xFFEBBCBA), Color(0xFF191724), Color(0xFFC4A7E7)),
    ColorPreset("Ocean", Color(0xFF38BDF8), Color(0xFF0B1426), Color(0xFF6366F1)),
    ColorPreset("Ember", Color(0xFFFE8019), Color(0xFF1D2021), Color(0xFFFABD2F)),
    ColorPreset("Forest", Color(0xFF4ADE80), Color(0xFF0F1A14), Color(0xFF2DD4BF)),
    ColorPreset("Sunset", Color(0xFFFB923C), Color(0xFF1C1020), Color(0xFFF472B6)),
    ColorPreset("Solarized Dark", Color(0xFF268BD2), Color(0xFF002B36), Color(0xFF2AA198)),
    ColorPreset("Porcelain", Color(0xFFD97706), Color(0xFFF9F9FB), Color(0xFFB45309), dark = false),
    ColorPreset("Lavender", Color(0xFF7C3AED), Color(0xFFF8F6FD), Color(0xFFA855F7), dark = false),
    ColorPreset("Sky", Color(0xFF2563EB), Color(0xFFF5F8FC), Color(0xFF0EA5E9), dark = false),
    ColorPreset("Sakura", Color(0xFFDB2777), Color(0xFFFFF7F9), Color(0xFFC026D3), dark = false),
    ColorPreset("Mint", Color(0xFF059669), Color(0xFFF4FBF8), Color(0xFF0D9488), dark = false),
    ColorPreset("Linen", Color(0xFFC2410C), Color(0xFFFBF8F3), Color(0xFFEA580C), dark = false),
    ColorPreset("Paper", Color(0xFF9A3412), Color(0xFFF7F1E3), Color(0xFFB45309), dark = false),
    ColorPreset("Solarized Light", Color(0xFF268BD2), Color(0xFFFDF6E3), Color(0xFF2AA198), dark = false),
)

val darkPresets: List<ColorPreset> = colorPresets.filter { it.dark }
val lightPresets: List<ColorPreset> = colorPresets.filterNot { it.dark }

/** The theme the app ships with, and the one "back to the app palette" returns to. */
val defaultPreset: ColorPreset = colorPresets.first { it.label == "Amber Night" }

/** By label, any case, or null when nothing matches. */
fun presetNamed(label: String): ColorPreset? =
    colorPresets.firstOrNull { it.label.equals(label.trim(), ignoreCase = true) }
