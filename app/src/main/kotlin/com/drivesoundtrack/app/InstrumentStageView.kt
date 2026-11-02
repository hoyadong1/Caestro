package com.drivesoundtrack.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Parked/demo album art: vehicle inputs are represented as instruments, not a car. */
class InstrumentStageView(context: Context) : View(context) {
    var accent: Int = 0xff66e3d5.toInt()
    var playing = false
    var accelerator = 0.0
    var brake = 0.0
    var steering = 0.0
    var wiper = false
    var pedalLive = false
    var brakeLive = false
    var steeringLive = false
    var wiperLive = false
    var vehicleLive = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0.0
    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader = LinearGradient(0f, 0f, w, h, 0xff122b3b.toInt(), 0xff09131f.toInt(), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(0f, 0f, w, h, dp(18f), dp(18f), p)
        p.shader = null
        if (playing) phase += 0.075

        val top = dp(if (h > dp(420f)) 115f else 93f)
        val cellW = (w - dp(42f)) / 2f
        val cellH = (h - top - dp(26f)) / 2f
        val left = dp(14f)
        val right = left + cellW + dp(14f)
        val row2 = top + cellH + dp(8f)
        if (cellH <= dp(35f)) return

        cell(canvas, left, top, cellW, cellH, "BASS", if (pedalLive) "PEDAL" else "SPEED Δ")
        bass(canvas, left, top, cellW, cellH)
        cell(canvas, right, top, cellW, cellH, "DRUMS", if (vehicleLive) "VEHICLE" else "DEMO")
        drums(canvas, right, top, cellW, cellH)
        cell(canvas, left, row2, cellW, cellH, "CHORDS", if (brakeLive) "BRAKE" else "NEUTRAL")
        chords(canvas, left, row2, cellW, cellH)
        cell(canvas, right, row2, cellW, cellH, "MELODY", if (steeringLive) "STEERING" else "NEUTRAL")
        melody(canvas, right, row2, cellW, cellH)
        if (wiper) {
            p.color = 0xffb6d7eb.toInt(); p.textSize = dp(9f); p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            canvas.drawText("TEXTURE  /  WIPER", right + dp(8f), row2 + cellH - dp(7f), p)
        }
        if (playing) postInvalidateDelayed(70)
    }

    private fun cell(c: Canvas, x: Float, y: Float, w: Float, h: Float, name: String, signal: String) {
        p.style = Paint.Style.FILL; p.color = 0xff142a39.toInt()
        c.drawRoundRect(x, y, x + w, y + h, dp(12f), dp(12f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = 0xff345264.toInt()
        c.drawRoundRect(x, y, x + w, y + h, dp(12f), dp(12f), p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textSize = dp(12f); c.drawText(name, x + dp(12f), y + dp(20f), p)
        p.color = 0xff9fb8c5.toInt(); p.textSize = dp(9f)
        c.drawText(signal, x + dp(12f), y + dp(34f), p)
    }

    private fun bass(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val amp = accelerator.coerceIn(0.0, 1.0).toFloat()
        p.style = Paint.Style.FILL; p.color = accent; p.alpha = 36
        c.drawOval(x + w * 0.06f, y + h * 0.40f, x + w * 0.91f, y + h * 0.83f, p)
        p.alpha = 255
        for (i in 0..3) {
            val yy = y + h * (0.48f + i * 0.095f)
            val path = Path().apply {
                moveTo(x + dp(14f), yy)
                for (j in 1..24) {
                    val xx = x + dp(14f) + (w - dp(28f)) * j / 24f
                    val wave = sin(j * 0.62 + phase + i) * dp(1.5f + amp * 3.0f)
                    lineTo(xx, yy + wave.toFloat())
                }
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(if (i == 1) 2.1f else 1.1f)
            p.color = if (i == 1) accent else 0xff7196a6.toInt(); c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
        p.color = 0xffdceeed.toInt()
        for (i in 0..3) c.drawCircle(x + w * 0.88f, y + h * (0.48f + i * 0.095f), dp(3.5f), p)
        p.color = 0xff648a99.toInt(); p.alpha = 120
        for (i in 1..5) {
            val fretX = x + w * (0.17f + i * 0.115f)
            c.drawRoundRect(fretX, y + h * 0.45f, fretX + dp(1.2f), y + h * 0.78f, dp(1f), dp(1f), p)
        }
        p.alpha = 255
    }

    private fun drums(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val cy = y + h * 0.65f
        for (i in 0..2) {
            val cx = x + w * (0.25f + i * 0.25f)
            p.style = Paint.Style.FILL; p.color = if (i == 1) accent else 0xff5f9cad.toInt()
            p.alpha = if (playing) 170 + (sin(phase * 2 + i) * 35).toInt() else 145
            c.drawCircle(cx, cy + if (i == 1) dp(4f) else 0f, min(w, h) * (if (i == 1) 0.20f else 0.135f), p)
            p.alpha = 255; p.style = Paint.Style.STROKE; p.color = 0xffd4f8fa.toInt(); p.strokeWidth = dp(1f)
            c.drawCircle(cx, cy + if (i == 1) dp(4f) else 0f, min(w, h) * (if (i == 1) 0.20f else 0.135f), p)
        }
        p.style = Paint.Style.STROKE; p.color = 0xff9ed8da.toInt(); p.strokeWidth = dp(1.3f)
        c.drawOval(x + w * 0.12f, y + h * 0.39f, x + w * 0.42f, y + h * 0.45f, p)
        c.drawOval(x + w * 0.58f, y + h * 0.39f, x + w * 0.88f, y + h * 0.45f, p)
        p.style = Paint.Style.FILL
    }

    private fun chords(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val count = 9
        val gap = dp(2f)
        val keyW = (w - dp(28f) - gap * (count - 1)) / count
        val keyY = y + h * 0.54f
        val keyH = max(dp(16f), h * 0.30f)
        val ribbon = Path().apply {
            moveTo(x + dp(14f), y + h * 0.44f)
            for (i in 1..30) {
                val xx = x + dp(14f) + (w - dp(28f)) * i / 30f
                lineTo(xx, y + h * 0.44f + (sin(i * 0.45 + phase * 0.3) * dp(5f)).toFloat())
            }
        }
        p.style = Paint.Style.STROKE; p.color = accent; p.alpha = (190 - brake * 90).toInt(); p.strokeWidth = dp(1.5f)
        c.drawPath(ribbon, p); p.style = Paint.Style.FILL; p.alpha = 255
        for (i in 0 until count) {
            p.color = if (i % 3 == 0) accent else 0xffd8e8e9.toInt()
            p.alpha = (220 - brake.coerceIn(0.0, 1.0) * 100).toInt()
            val xx = x + dp(14f) + i * (keyW + gap)
            c.drawRoundRect(xx, keyY, xx + keyW, keyY + keyH, dp(2f), dp(2f), p)
        }
        p.alpha = 255
    }

    private fun melody(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        for (line in 0..2) {
            val path = Path()
            val baseY = y + h * (0.51f + line * 0.12f)
            for (i in 0..36) {
                val xx = x + dp(14f) + (w - dp(28f)) * i / 36f
                val yy = baseY + sin(i * 0.35 + phase * 0.5 + line * 0.7) * dp(7f + line * 1.5f) - steering.toFloat() * dp(11f)
                if (i == 0) path.moveTo(xx, yy.toFloat()) else path.lineTo(xx, yy.toFloat())
            }
            p.style = Paint.Style.STROKE; p.strokeWidth = dp(if (line == 1) 2.3f else 1.2f)
            p.color = if (line == 1) accent else 0xff81a6b5.toInt()
            c.drawPath(path, p)
        }
        p.style = Paint.Style.FILL
    }
}
