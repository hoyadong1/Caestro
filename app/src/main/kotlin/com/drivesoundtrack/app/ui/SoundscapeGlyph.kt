package com.drivesoundtrack.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Compact album art for each original sound world. */
@Composable
fun SoundscapeGlyph(index: Int, accent: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val center = Offset(w * 0.54f, h * 0.5f)
        val radius = min(w * 0.36f, h * 0.34f)
        for (ring in 3 downTo 0) {
            drawCircle(
                accent.copy(alpha = (if (ring == 0) 210 else 38 + (3 - ring) * 24) / 255f),
                radius * (0.56f + ring * 0.18f), center,
                style = Stroke(min(w, h) * (if (ring == 0) 0.011f else 0.005f)),
            )
        }
        val path = Path()
        for (i in 0..100) {
            val angle = i / 100.0 * PI * 2.0
            val modulation = sin(angle * (index + 3) + index) * 0.10 + cos(angle * 3.0) * 0.035
            val r = radius * (0.73 + modulation)
            val x = center.x + (cos(angle) * r).toFloat()
            val y = center.y + (sin(angle) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        drawPath(path, accent.copy(alpha = 240 / 255f), style = Stroke(min(w, h) * 0.016f))
    }
}
