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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.gemini.theme.ColorPicker
import com.example.gemini.theme.ThemePrefs
import com.example.gemini.theme.bestForeground
import com.example.gemini.theme.colorPresets
import com.example.gemini.theme.contrastRatio
import com.example.gemini.theme.customPalette
import com.example.gemini.theme.mix
import com.example.gemini.theme.rotateHue

/**
 * Pick the accent, the background and (optionally) a second colour; everything else in the palette is derived
 * from them.
 *
 * The heavy lifting lives in `theme/CustomPalette.kt`, which is plain Kotlin and unit-tested, so this screen only
 * holds three colours and shows what they produce.
 */
@Composable
fun ThemeColorsScreen() {
    var enabled by remember { mutableStateOf(ThemePrefs.enabled) }
    var accent by remember { mutableStateOf(ThemePrefs.accent) }
    var background by remember { mutableStateOf(ThemePrefs.background) }
    var second by remember { mutableStateOf(ThemePrefs.secondAccent) }

    val preview = remember(accent, background, second) { customPalette(accent, background, second) }

    fun apply() {
        enabled = true
        ThemePrefs.setColors(accent, background, second)
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
                    "The built-in palette is warm amber. Turn this on and these colours drive every screen: " +
                        "buttons, headers, links, the drawer.",
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

        Text("Presets", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            colorPresets.forEach { preset ->
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
                                apply()
                            }
                    )
                    Text(preset.label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                }
            }
        }

        ColorPicker(
            label = "Accent",
            color = accent,
            onColorChange = { accent = it; apply() }
        )

        ColorPicker(
            label = "Background",
            color = background,
            onColorChange = { background = it; apply() }
        )

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
                    second = if (match) null else background.rotateHue(150f).mix(accent, 0.35f)
                    apply()
                }
            )
        }
        if (second != null) {
            ColorPicker(
                label = "Second colour",
                color = second ?: accent,
                onColorChange = { second = it; apply() }
            )
        }

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
                color = preview.darkColors.onSurfaceVariant,
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
                        .background(preview.accentSoft, RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                ) {
                    Text("Stop", color = preview.accentSoft.bestForeground(), fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                }
            }
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
                accent = ThemePrefs.defaultAccent
                background = ThemePrefs.defaultBackground
                second = null
                ThemePrefs.reset()
            }
        ) { Text("Back to the app palette") }

        Spacer(Modifier.height(8.dp))
    }
}
