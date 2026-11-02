package com.drivesoundtrack.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Compact album art for each original sound world. */
class SoundscapeGlyphView(context: Context, private val index: Int, private val accent: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val cx = w * 0.54f; val cy = h * 0.5f
        val radius = min(w * 0.36f, h * 0.34f)
        for (ring in 3 downTo 0) {
            p.style = Paint.Style.STROKE
            p.strokeWidth = min(w, h) * (if (ring == 0) 0.011f else 0.005f)
            p.color = accent; p.alpha = if (ring == 0) 210 else 38 + (3 - ring) * 24
            canvas.drawCircle(cx, cy, radius * (0.56f + ring * 0.18f), p)
        }
        val path = Path()
        for (i in 0..100) {
            val angle = i / 100.0 * PI * 2.0
            val modulation = sin(angle * (index + 3) + index) * 0.10 + cos(angle * 3.0) * 0.035
            val r = radius * (0.73 + modulation)
            val x = cx + (cos(angle) * r).toFloat()
            val y = cy + (sin(angle) * r).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        p.style = Paint.Style.STROKE; p.strokeWidth = min(w, h) * 0.016f
        p.alpha = 240; canvas.drawPath(path, p)
        p.style = Paint.Style.FILL; p.alpha = 255
    }
}
