package com.drivesoundtrack.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.View
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Parked/demo album art: vehicle inputs are represented as instruments, not a car. */
class InstrumentStageView(context: Context) : View(context) {
    var accent: Int = 0xff66c9ff.toInt()
    var playing = false
    var bassSolo = false
    var layerMask = 1
    var accelerator = 0.0
    var speed = 0.0
    var brake = 0.0
    var steering = 0.0
    var turn = 0
    var wiper = false
    var pedalLive = false
    var brakeLive = false
    var steeringLive = false
    var wiperLive = false
    var vehicleLive = false

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tileRects = Array(6) { RectF() }
    private val tileAlpha = FloatArray(6)
    private var tilesInitialized = false
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

        val stage = RectF(dp(14f), dp(if (h > dp(420f)) 115f else 93f), w - dp(14f), h - dp(14f))
        if (stage.height() <= dp(80f)) return
        val count = if (bassSolo) 1 else Integer.bitCount(layerMask).coerceIn(1, 6)
        val targets = layoutFor(count, stage)
        if (!tilesInitialized) {
            tileRects[0].set(stage)
            tileAlpha[0] = 1f
            for (i in 1..5) tileRects[i].set(stage.centerX(), stage.centerY(), stage.centerX(), stage.centerY())
            tilesInitialized = true
        }
        var animating = false
        for (i in 0..5) {
            val target = if (i < count) targets[i] else RectF(stage.centerX(), stage.centerY(), stage.centerX(), stage.centerY())
            val current = tileRects[i]
            val fraction = if (playing) 0.23f else 1f
            current.set(
                current.left + (target.left - current.left) * fraction,
                current.top + (target.top - current.top) * fraction,
                current.right + (target.right - current.right) * fraction,
                current.bottom + (target.bottom - current.bottom) * fraction,
            )
            val alphaTarget = if (i < count) 1f else 0f
            tileAlpha[i] += (alphaTarget - tileAlpha[i]) * fraction
            if (kotlin.math.abs(target.left - current.left) > 1f ||
                kotlin.math.abs(target.top - current.top) > 1f ||
                kotlin.math.abs(alphaTarget - tileAlpha[i]) > 0.02f) animating = true
            if (tileAlpha[i] > 0.03f && current.width() > dp(15f) && current.height() > dp(15f)) {
                val save = canvas.save()
                canvas.clipRect(current)
                drawTile(canvas, i, current)
                canvas.restoreToCount(save)
            }
        }
        if (playing || animating) postInvalidateDelayed(70)
    }

    private fun layoutFor(count: Int, bounds: RectF): List<RectF> {
        val gap = dp(8f)
        val x = bounds.left; val y = bounds.top; val w = bounds.width(); val h = bounds.height()
        fun tile(left: Float, top: Float, width: Float, height: Float) =
            RectF(left, top, left + width, top + height)
        return when (count) {
            1 -> listOf(tile(x, y, w, h))
            2 -> { val cw = (w - gap) / 2; listOf(tile(x, y, cw, h), tile(x + cw + gap, y, cw, h)) }
            3 -> { val cw = (w - gap) / 2; val rh = (h - gap) / 2
                listOf(tile(x, y, cw, h), tile(x + cw + gap, y, cw, rh), tile(x + cw + gap, y + rh + gap, cw, rh)) }
            4 -> { val cw = (w - gap) / 2; val rh = (h - gap) / 2
                listOf(tile(x, y, cw, rh), tile(x + cw + gap, y, cw, rh),
                    tile(x, y + rh + gap, cw, rh), tile(x + cw + gap, y + rh + gap, cw, rh)) }
            5 -> { val cw = (w - 2 * gap) / 3; val rh = (h - gap) / 2
                listOf(tile(x, y, cw, h), tile(x + cw + gap, y, cw, rh),
                    tile(x + 2 * (cw + gap), y, cw, rh), tile(x + cw + gap, y + rh + gap, cw, rh),
                    tile(x + 2 * (cw + gap), y + rh + gap, cw, rh)) }
            else -> { val cw = (w - 2 * gap) / 3; val rh = (h - gap) / 2
                (0..5).map { i -> tile(x + (i % 3) * (cw + gap), y + (i / 3) * (rh + gap), cw, rh) } }
        }
    }

    private fun drawTile(c: Canvas, index: Int, area: RectF) {
        val x = area.left; val y = area.top; val w = area.width(); val h = area.height()
        when (index) {
            0 -> { cell(c, x, y, w, h, if (bassSolo) "BASS SOLO / 베이스" else "BASS / 베이스", "속도 → 저음 음색"); bass(c, x, y, w, h) }
            1 -> { cell(c, x, y, w, h, "DRUMS / 드럼", "18 km/h · 박동"); drums(c, x, y, w, h) }
            2 -> { cell(c, x, y, w, h, "CHORDS / 화음", "38 km/h · 화성"); chords(c, x, y, w, h) }
            3 -> { cell(c, x, y, w, h, "ARP / 아르페지오", "58 km/h · 분산 음"); arpeggio(c, x, y, w, h) }
            4 -> { cell(c, x, y, w, h, "MELODY / 선율", "82 km/h · 조향"); melody(c, x, y, w, h) }
            else -> { cell(c, x, y, w, h, "SHIMMER / 고역", "105 km/h · 반짝임"); shimmer(c, x, y, w, h) }
        }
    }

    private fun cell(c: Canvas, x: Float, y: Float, w: Float, h: Float,
                     name: String, mapping: String, detail: String? = null) {
        p.style = Paint.Style.FILL; p.color = 0xff142a39.toInt()
        c.drawRoundRect(x, y, x + w, y + h, dp(12f), dp(12f), p)
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(1f); p.color = 0xff345264.toInt()
        c.drawRoundRect(x, y, x + w, y + h, dp(12f), dp(12f), p)
        p.style = Paint.Style.FILL; p.color = Color.WHITE; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textSize = dp(15f); c.drawText(name, x + dp(12f), y + dp(24f), p)
        p.color = 0xffb9d7dd.toInt(); p.textSize = dp(12f)
        c.drawText(mapping, x + dp(12f), y + dp(44f), p)
        if (detail != null) {
            p.color = 0xff8fb1bf.toInt(); p.textSize = dp(11f)
            c.drawText(detail, x + dp(12f), y + dp(61f), p)
        }
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
        val speedMix = (speed / 130.0).coerceIn(0.0, 1.0).toFloat()
        for (i in 0..2) {
            val cx = x + w * (0.25f + i * 0.25f)
            p.style = Paint.Style.FILL; p.color = if (i == 1) accent else 0xff5f9cad.toInt()
            p.alpha = if (playing) (140 + speedMix * 65 + sin(phase * (1.5 + speedMix * 2) + i) * 30)
                .toInt().coerceIn(0, 255) else 145
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
        if (turn != 0) {
            p.style = Paint.Style.FILL; p.color = accent; p.typeface = android.graphics.Typeface.DEFAULT_BOLD
            p.textSize = dp(14f)
            val cue = if (turn < 0) "◀  LEFT · 하행" else "RIGHT · 상행  ▶"
            c.drawText(cue, x + dp(14f), y + h * 0.39f, p)
        }
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

    private fun arpeggio(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val points = intArrayOf(2, 4, 3, 6, 4, 7, 5, 8)
        val path = Path()
        for (i in points.indices) {
            val px = x + w * (0.10f + i * 0.115f)
            val py = y + h * (0.82f - points[i] * 0.045f)
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        p.style = Paint.Style.STROKE; p.strokeWidth = dp(2f); p.color = accent
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (i in points.indices) {
            val px = x + w * (0.10f + i * 0.115f)
            val py = y + h * (0.82f - points[i] * 0.045f)
            p.color = if (playing && (phase.toInt() / 3) % points.size == i) Color.WHITE else accent
            c.drawCircle(px, py, dp(3.5f), p)
        }
    }

    private fun shimmer(c: Canvas, x: Float, y: Float, w: Float, h: Float) {
        val cx = x + w * 0.5f; val cy = y + h * 0.64f
        p.style = Paint.Style.STROKE; p.color = accent; p.strokeWidth = dp(1.5f)
        for (ring in 1..3) {
            p.alpha = 210 - ring * 45
            val r = min(w, h) * (0.10f + ring * 0.075f)
            c.drawCircle(cx, cy, r, p)
        }
        p.alpha = 255; p.style = Paint.Style.FILL; p.color = Color.WHITE
        for (i in 0..7) {
            val angle = i * PI / 4.0 + phase * 0.18
            val radius = min(w, h) * 0.30f
            c.drawCircle(cx + (kotlin.math.cos(angle) * radius).toFloat(),
                cy + (sin(angle) * radius).toFloat(), dp(if (i % 2 == 0) 2.2f else 1.4f), p)
        }
    }
}
