package com.example.gemini.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/**
 * A hand-rolled HSV picker: Material3 has no colour picker, and pulling a library in for three colours is not
 * worth it. The square picks saturation and brightness, the strip picks the hue, and the field below accepts a
 * hex code, so a brand colour can be typed instead of hunted with a thumb.
 */

/** `#RRGGBB` without the leading hash, upper case. */
fun Color.toHexString(): String = "%06X".format(toArgb() and 0xFFFFFF)

/** Parses `RRGGBB`/`#RRGGBB` into an opaque colour, or null when the text is not a colour yet. */
fun parseHexColor(raw: String): Color? {
    val cleaned = raw.trim().removePrefix("#").uppercase()
    if (cleaned.length != 6 || cleaned.any { it !in "0123456789ABCDEF" }) return null
    return Color(cleaned.toInt(16) or (0xFF shl 24))
}

@Composable
private fun SaturationValueField(
    hue: Float,
    saturation: Float,
    value: Float,
    onSaturationValueChange: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var width by remember { mutableStateOf(0f) }
    var height by remember { mutableStateOf(0f) }
    val base = remember(hue) { hsvToColor(hue, 1f, 1f) }

    fun report(x: Float, y: Float) {
        if (width <= 0f || height <= 0f) return
        onSaturationValueChange((x / width).coerceIn(0f, 1f), 1f - (y / height).coerceIn(0f, 1f))
    }

    Canvas(
        modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(RoundedCornerShape(12.dp))
            .onSizeChanged { width = it.width.toFloat(); height = it.height.toFloat() }
            .pointerInput(Unit) { detectTapGestures { report(it.x, it.y) } }
            .pointerInput(Unit) { detectDragGestures { change, _ -> report(change.position.x, change.position.y) } },
    ) {
        drawRect(brush = Brush.horizontalGradient(listOf(Color.White, base)), size = size)
        drawRect(brush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), size = size)
        val marker = Offset(saturation * size.width, (1f - value) * size.height)
        drawCircle(Color.Black.copy(alpha = 0.35f), radius = 10.dp.toPx(), center = marker)
        drawCircle(Color.White, radius = 8.dp.toPx(), center = marker)
        drawCircle(hsvToColor(hue, saturation, value), radius = 6.dp.toPx(), center = marker)
    }
}

@Composable
private fun HueStrip(hue: Float, onHueChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val spectrum = remember {
        listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { hsvToColor(it, 1f, 1f) }
    }
    var width by remember { mutableStateOf(0f) }

    Box(
        modifier
            .fillMaxWidth()
            .height(26.dp)
            .onSizeChanged { width = it.width.toFloat() }
            .pointerInput(Unit) { detectTapGestures { onHueChange((it.x / size.width) * 360f) } }
            .pointerInput(Unit) {
                detectDragGestures { change, _ -> onHueChange((change.position.x / size.width) * 360f) }
            },
    ) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(26.dp)
                .clip(RoundedCornerShape(13.dp)),
        ) {
            drawRect(brush = Brush.horizontalGradient(spectrum), size = size)
        }
        Box(
            Modifier
                .size(20.dp)
                .align(Alignment.CenterStart)
                .offset { IntOffset((hue.coerceIn(0f, 360f) / 360f * width - 10.dp.toPx()).roundToInt(), 0) },
        ) {
            Canvas(Modifier.size(20.dp)) {
                val r = size.minDimension / 2f
                drawCircle(Color.Black.copy(alpha = 0.4f), radius = r)
                drawCircle(Color.White, radius = r - 3.dp.toPx())
            }
        }
    }
}

/**
 * One colour of the theme: a saturation/brightness square, a hue strip and a hex field, so it can be picked with a
 * thumb or pasted from a brand sheet.
 */
@Composable
fun ColorPicker(
    label: String,
    color: Color,
    onColorChange: (Color) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hsv = color.toHsv()
    var hue by remember(color) { mutableStateOf(hsv.h) }
    var hexText by remember(color) { mutableStateOf(color.toHexString()) }

    Column(modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        SaturationValueField(
            hue = hue,
            saturation = hsv.s,
            value = hsv.v,
            onSaturationValueChange = { s, v ->
                val next = hsvToColor(hue, s, v)
                onColorChange(next)
                hexText = next.toHexString()
            },
        )
        Spacer(Modifier.height(10.dp))
        HueStrip(
            hue = hue,
            onHueChange = { next ->
                hue = next
                // A grey colour has no hue to show, so the strip lifts it slightly instead of doing nothing.
                val updated = hsvToColor(next, hsv.s.coerceAtLeast(0.05f), hsv.v)
                onColorChange(updated)
                hexText = updated.toHexString()
            },
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(color),
            )
            OutlinedTextField(
                value = hexText,
                onValueChange = { raw ->
                    val next = raw.filter { it.isDigit() || it in 'A'..'F' || it in 'a'..'f' }.uppercase().take(6)
                    hexText = next
                    parseHexColor(next)?.let { onColorChange(it) }
                },
                singleLine = true,
                prefix = { Text("#", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
