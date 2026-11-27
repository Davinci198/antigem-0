package com.example.gemini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.ColorPicker
import com.example.gemini.theme.ColorPreset
import com.example.gemini.theme.CustomPalette
import com.example.gemini.theme.ThemePrefs
import com.example.gemini.theme.ThemeToken
import com.example.gemini.theme.bestForeground
import com.example.gemini.theme.contrastRatio
import com.example.gemini.theme.darkPresets
import com.example.gemini.theme.defaultPreset
import com.example.gemini.theme.lightPresets

/**
 * The theme editor: pick one of the shipped themes, then bend it.
 *
 * Three colours (accent, background, second accent) are enough for the whole app because `theme/CustomPalette.kt`
 * derives every surface, border and container from them; the tokens below are the escape hatch for the few things
 * a palette cannot guess, and each one is stored separately so it survives a preset change.
 */
@Composable
fun ThemeColorsScreen() {
    var enabled by remember { mutableStateOf(ThemePrefs.enabled) }
    var accent by remember { mutableStateOf(ThemePrefs.accent) }
    var background by remember { mutableStateOf(ThemePrefs.background) }
    var second by remember { mutableStateOf(ThemePrefs.secondAccent) }
    var tokens by remember { mutableStateOf(ThemePrefs.tokens()) }
    var openToken by remember { mutableStateOf<String?>(null) }

    val preview = ThemePrefs.previewPalette(accent, background, second, tokens)

    fun apply(next: Map<String, Color> = tokens) {
        enabled = true
        tokens = next
        ThemePrefs.setTheme(accent, background, second, next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use my colours", fontSize = 14.5.sp, fontWeight = FontWeight.Bold)
                Text(
                    "The app ships with Amber Night. Pick another theme below, or set the three colours yourself — " +
                        "every surface, border and container follows them.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    if (on) apply() else ThemePrefs.reset()
                }
            )
        }

        PresetRow(
            title = "Themes · dark",
            presets = darkPresets,
            accent = accent,
            background = background,
            onPick = { preset ->
                accent = preset.accent
                background = preset.background
                second = preset.second
                tokens = emptyMap()
                apply(emptyMap())
            }
        )

        PresetRow(
            title = "Themes · light",
            presets = lightPresets,
            accent = accent,
            background = background,
            onPick = { preset ->
                accent = preset.accent
                background = preset.background
                second = preset.second
                tokens = emptyMap()
                apply(emptyMap())
            }
        )

        ColorPicker(label = "Accent", color = accent, onColorChange = { accent = it; apply() })
        ColorPicker(label = "Background", color = background, onColorChange = { background = it; apply() })

        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Second colour", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Gradient ends and the second accent. Turn this off to give them a colour of their own.",
                    fontSize = 11.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Switch(
                checked = second == null,
                onCheckedChange = { match ->
                    second = if (match) null else background.mix(accent, 0.35f)
                    apply()
                }
            )
        }
        if (second != null) {
            ColorPicker(label = "Second colour", color = second ?: accent, onColorChange = { second = it; apply() })
        }

        Text("Fine tuning", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Set one surface by hand when the derived colour is not what you want. Each one is stored on its own, " +
                "so changing the theme later keeps it until you reset it.",
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        listOf(
            ThemeToken.SURFACE,
            ThemeToken.SURFACE_VARIANT,
            ThemeToken.OUTLINE,
            ThemeToken.MUTED,
            ThemeToken.CODE_BG
        ).forEach { key ->
            val current = tokens[key]
            val shown = current ?: resolved(preview, key)
            TokenRow(
                key = key,
                colour = shown,
                overridden = current != null,
                expanded = openToken == key,
                onToggle = { openToken = if (openToken == key) null else key },
                onPick = { picked -> apply(tokens + (key to picked)) },
                onClear = { apply(tokens - key) }
            )
        }

        ContrastNotes(preview)

        Text("Preview", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(preview.background, RoundedCornerShape(14.dp))
                .border(1.dp, preview.outline, RoundedCornerShape(14.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("DeepSeek Harness", color = preview.darkColors.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                "Body text reads at %.1f:1 · button text at %.1f:1".format(
                    contrastRatio(preview.darkColors.onSurface, preview.background),
                    contrastRatio(preview.accent.bestForeground(), preview.accent)
                ),
                color = preview.muted,
                fontSize = 12.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .background(preview.accent, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text("Send", color = preview.accent.bestForeground(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
                Box(
                    modifier = Modifier
                        .background(preview.surfaceVariant, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text("Stop", color = preview.darkColors.onSurfaceVariant, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(10.dp)
                    .background(preview.codeBg, RoundedCornerShape(6.dp))
            )
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .background(
                        Brush.horizontalGradient(listOf(preview.accent, preview.accentSoft)),
                        RoundedCornerShape(10.dp)
                    )
            )
        }

        OutlinedButton(
            onClick = {
                enabled = false
                accent = defaultPreset.accent
                background = defaultPreset.background
                second = defaultPreset.second
                tokens = emptyMap()
                ThemePrefs.reset()
            }
        ) { Text("Back to the app palette") }

        Spacer(Modifier.height(8.dp))
    }
}

/** The colour a token would have without an override, so the editor can show it next to the swatch. */
private fun resolved(palette: CustomPalette, key: String): Color = when (key) {
    ThemeToken.SURFACE -> palette.surface
    ThemeToken.SURFACE_VARIANT -> palette.surfaceVariant
    ThemeToken.OUTLINE -> palette.outline
    ThemeToken.MUTED -> palette.muted
    ThemeToken.CODE_BG -> palette.codeBg
    ThemeToken.ACCENT -> palette.accent
    ThemeToken.SECOND -> palette.accentSoft
    ThemeToken.BACKGROUND -> palette.background
    else -> palette.background
}

@Composable
private fun PresetRow(
    title: String,
    presets: List<ColorPreset>,
    accent: Color,
    background: Color,
    onPick: (ColorPreset) -> Unit,
) {
    Text(title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        presets.forEach { preset ->
            val selected = preset.accent == accent && preset.background == background
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .background(preset.accent, CircleShape)
                        .border(
                            width = if (selected) 3.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.onSurface else preset.accent.copy(alpha = 0.4f),
                            shape = CircleShape
                        )
                        .clickable { onPick(preset) }
                )
                Text(preset.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun TokenRow(
    key: String,
    colour: Color,
    overridden: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPick: (Color) -> Unit,
    onClear: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { onToggle() }) {
            Box(
                Modifier
                    .size(30.dp)
                    .background(colour, RoundedCornerShape(8.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    key.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    ThemeToken.about(key),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (overridden) {
                TextButton(onClick = onClear) { Text("Reset", fontSize = 12.sp) }
            }
        }
        if (expanded) {
            ColorPicker(label = "", color = colour, onColorChange = onPick)
        }
    }
}

/** Says plainly when a hand-set colour makes text unreadable, instead of letting the user find out by squinting. */
@Composable
private fun ContrastNotes(palette: CustomPalette) {
    val body = contrastRatio(palette.darkColors.onSurface, palette.background)
    val muted = contrastRatio(palette.muted, palette.background)
    val onCard = contrastRatio(palette.darkColors.onSurface, palette.surface)
    val problems = buildList {
        if (body < 4.5f) add("Body text on the background is %.1f:1 — needs 4.5:1".format(body))
        if (muted < 3.0f) add("Secondary text is %.1f:1 — needs 3:1".format(muted))
        if (onCard < 4.5f) add("Body text on cards is %.1f:1 — needs 4.5:1".format(onCard))
    }
    if (problems.isEmpty()) {
        Text(
            "Readability is fine: body %.1f:1, secondary %.1f:1.".format(body, muted),
            fontSize = 11.5.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            problems.forEach { line ->
                Text(line, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
