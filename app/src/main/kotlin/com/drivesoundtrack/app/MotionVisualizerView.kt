package com.drivesoundtrack.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** Decorative motion graphic. Playback and controls remain ordinary accessible Views. */
class MotionVisualizerView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var accent: Int = Soundscapes.all.first().accent
        set(value) { field = value; invalidate() }
    var energy: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }
    var playing: Boolean = false
        set(value) { field = value; invalidate() }
    var bpm: Float = 96f
        set(value) { field = value; invalidate() }

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val color = Color.rgb((accent shr 16) and 255, (accent shr 8) and 255, accent and 255)
        paint.shader = LinearGradient(0f, 0f, w, h, intArrayOf(0xff102537.toInt(), 0xff0b1523.toInt(), 0xff07101c.toInt()),
            null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, 28f, 28f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = 0x1cffffff
        val grid = 42f * resources.displayMetrics.density
        var x = grid
        while (x < w) { canvas.drawLine(x, 0f, x, h, paint); x += grid }
        var y = grid
        while (y < h) { canvas.drawLine(0f, y, w, y, paint); y += grid }

        val time = SystemClock.uptimeMillis() / 1000.0
        val pulse = if (playing) (sin(time * bpm / 60.0 * 2.0 * PI) * 0.5 + 0.5).toFloat() else 0f
        val cx = w * 0.50f
        val cy = h * 0.47f
        val radius = min(w * 0.30f, h * 0.29f)
        for (ring in 0..2) {
            val r = radius + ring * 23f * resources.displayMetrics.density
            paint.color = Color.argb(45, Color.red(color), Color.green(color), Color.blue(color))
            paint.strokeWidth = 2f * resources.displayMetrics.density
            canvas.drawCircle(cx, cy, r, paint)
        }
        paint.color = color
        paint.alpha = 215
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 7f * resources.displayMetrics.density
        val arc = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        canvas.drawArc(arc, -90f, 28f + energy * 270f + pulse * 12f, false, paint)

        paint.style = Paint.Style.FILL
        val barCount = 35
        val usableWidth = w * 0.78f
        val startX = (w - usableWidth) / 2f
        val baseline = h * 0.88f
        for (i in 0 until barCount) {
            val wave = (sin(i * 0.57 + time * if (playing) 2.1 else 0.0) * 0.5 + 0.5).toFloat()
            val form = (sin(i * 0.21 + time * if (playing) 0.8 else 0.0) * 0.5 + 0.5).toFloat()
            val barHeight = (8f + (wave * 0.55f + form * 0.45f) * (22f + energy * 57f)) * resources.displayMetrics.density
            paint.color = Color.argb((70 + energy * 105).toInt(), Color.red(color), Color.green(color), Color.blue(color))
            val barX = startX + i * usableWidth / barCount
            canvas.drawRoundRect(barX, baseline - barHeight, barX + usableWidth / barCount * 0.55f,
                baseline, 5f, 5f, paint)
        }
        if (playing) postInvalidateDelayed(42)
    }
}
