package com.example.gemini.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import com.example.gemini.theme.ThemePrefs
import com.example.gemini.theme.bestForeground
import com.example.gemini.theme.colorPresets
import com.example.gemini.theme.customPalette
import com.example.gemini.theme.hsvToColor
import com.example.gemini.theme.toHsv

/**
 * Pick an accent and a background; everything else in the palette is derived from those two.
 *
 * The heavy lifting (contrast, surfaces, the two Material schemes) lives in `theme/CustomPalette.kt`, which is
 * plain Kotlin and unit-tested, so this screen only moves numbers around and shows the result.
 */
@Composable
fun ThemeColorsScreen() {
    var enabled by remember { mutableStateOf(ThemePrefs.enabled) }
    var accent by remember { mutableStateOf(ThemePrefs.accent) }
    var background by remember { mutableStateOf(ThemePrefs.background) }

    val accentHsv = remember(accent) { accent.toHsv() }
    val backgroundHsv = remember(background) { background.toHsv() }
    val preview = remember(accent, background) { customPalette(accent, background) }

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
                    "The built-in palette is warm amber. Turn this on and the accent you pick drives every screen: " +
                        "buttons, headers, links, the drawer.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = { on ->
                    enabled = on
                    if (on) ThemePrefs.setColors(accent, background) else ThemePrefs.reset()
                }
            )
        }

        Text("Presets", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            colorPresets.take(6).forEach { preset ->
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
                            .clickable {
                                accent = preset.accent
                                background = preset.background
                                enabled = true
                                ThemePrefs.setColors(preset.accent, preset.background)
                            }
                    )
                    Text(preset.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        Text("Accent", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = accentHsv.h,
            onValueChange = {
                accent = hsvToColor(it, accentHsv.s.coerceAtLeast(0.55f), accentHsv.v)
                enabled = true
                ThemePrefs.setColors(accent, background)
            },
            valueRange = 0f..360f
        )
        Slider(
            value = accentHsv.v,
            onValueChange = {
                accent = hsvToColor(accentHsv.h, accentHsv.s, it.coerceIn(0.35f, 1f))
                enabled = true
                ThemePrefs.setColors(accent, background)
            },
            valueRange = 0.2f..1f
        )

        Text("Background", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Slider(
            value = backgroundHsv.h,
            onValueChange = {
                background = hsvToColor(it, 0.08f, backgroundHsv.v.coerceIn(0.03f, 0.20f))
                enabled = true
                ThemePrefs.setColors(accent, background)
            },
            valueRange = 0f..360f
        )
        Slider(
            value = backgroundHsv.v,
            onValueChange = {
                // A background that is too bright makes every other colour unreadable, so the range is narrow.
                background = hsvToColor(backgroundHsv.h, 0.08f, it.coerceIn(0.03f, 0.20f))
                enabled = true
                ThemePrefs.setColors(accent, background)
            },
            valueRange = 0.03f..0.20f
        )

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
                "Contrast %.1f:1 on the background".format(
                    com.example.gemini.theme.contrastRatio(preview.accent, preview.background)
                ),
                color = preview.darkColors.onSurfaceVariant,
                fontSize = 12.sp
            )
            Box(
                modifier = Modifier
                    .background(preview.accent, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            ) {
                Text("Send", color = preview.accent.bestForeground(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .background(
                        Brush.horizontalGradient(listOf(preview.accent, preview.accentSoft)),
                        RoundedCornerShape(10.dp)
                    )
            )
        }

        OutlinedButton(
            onClick = {
                enabled = false
                accent = ThemePrefs.defaultAccent
                background = ThemePrefs.defaultBackground
                ThemePrefs.reset()
            }
        ) { Text("Back to the app palette") }

        Spacer(Modifier.height(8.dp))
    }
}
