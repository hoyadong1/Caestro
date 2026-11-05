package com.drivesoundtrack.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private const val FRAME_SECONDS = 0.07
private const val PHASE_PER_FRAME = 0.075
private const val TILE_EASE_PER_FRAME = 0.23

/** Animation state that survives recomposition: tile positions ease toward the current layout. */
private class StageMotion {
    val tiles = Array(6) { Rect.Zero }
    val alpha = FloatArray(6)
    var initialized = false
    var phase = 0.0
    var animating = false
    var lastFrameNanos = 0L
    var frameSeconds = 0.0
}

/**
 * Album art for the playing score: each audible instrument layer gets a tile, and vehicle inputs
 * change how the instruments are drawn. The stage splits from one tile up to six as layers join.
 */
@Composable
fun InstrumentStage(status: PlayerStatus, accent: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val motion = remember { StageMotion() }
    val text = rememberTextMeasurer()
    var frame by remember { mutableLongStateOf(0L) }
    val count = if (status.bassSolo) 1 else Integer.bitCount(status.layerMask).coerceIn(1, 6)

    LaunchedEffect(playing, count) {
        motion.lastFrameNanos = 0L
        do {
            withFrameNanos { now ->
                val dt = if (motion.lastFrameNanos == 0L) 0.0 else (now - motion.lastFrameNanos) / 1e9
                motion.lastFrameNanos = now
                motion.frameSeconds = dt
                if (playing) motion.phase += PHASE_PER_FRAME * dt / FRAME_SECONDS
                frame = now
            }
        } while (playing || motion.animating)
    }

    Canvas(modifier) {
        frame // Redraw on every animation frame.
        val w = size.width; val h = size.height
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xff122b3b), Color(0xff09131f)), Offset.Zero, Offset(w, h)),
            cornerRadius = CornerRadius(18.dp.toPx()),
        )
        val stage = Rect(14.dp.toPx(), (if (h > 420.dp.toPx()) 115 else 93).dp.toPx(), w - 14.dp.toPx(), h - 14.dp.toPx())
        if (stage.height <= 80.dp.toPx()) return@Canvas
        val targets = layoutFor(count, stage, 8.dp.toPx())
        if (!motion.initialized) {
            motion.tiles[0] = stage; motion.alpha[0] = 1f
            for (i in 1..5) motion.tiles[i] = Rect(stage.center, Size.Zero)
            motion.initialized = true
        }
        // Ease per elapsed frame time so motion speed does not depend on the display refresh rate.
        val fraction = if (playing) (1 - (1 - TILE_EASE_PER_FRAME).pow(motion.frameSeconds / FRAME_SECONDS)).toFloat() else 1f
        var animating = false
        val scene = StageScene(this, text, status, accent, playing, motion.phase)
        for (i in 0..5) {
            val target = if (i < count) targets[i] else Rect(stage.center, Size.Zero)
            val current = motion.tiles[i]
            val next = Rect(
                current.left + (target.left - current.left) * fraction,
                current.top + (target.top - current.top) * fraction,
                current.right + (target.right - current.right) * fraction,
                current.bottom + (target.bottom - current.bottom) * fraction,
            )
            motion.tiles[i] = next
            val alphaTarget = if (i < count) 1f else 0f
            motion.alpha[i] += (alphaTarget - motion.alpha[i]) * fraction
            if (abs(target.left - next.left) > 1f || abs(target.top - next.top) > 1f ||
                abs(alphaTarget - motion.alpha[i]) > 0.02f) animating = true
            if (motion.alpha[i] > 0.03f && next.width > 15.dp.toPx() && next.height > 15.dp.toPx()) {
                clipRect(next.left, next.top, next.right, next.bottom) { scene.tile(i, next) }
            }
        }
        motion.animating = animating
    }
}

private fun layoutFor(count: Int, bounds: Rect, gap: Float): List<Rect> {
    val x = bounds.left; val y = bounds.top; val w = bounds.width; val h = bounds.height
    fun tile(left: Float, top: Float, width: Float, height: Float) = Rect(left, top, left + width, top + height)
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

private class StageScene(
    private val d: DrawScope,
    private val text: TextMeasurer,
    private val s: PlayerStatus,
    private val accent: Color,
    private val playing: Boolean,
    private val phase: Double,
) {
    private fun dp(v: Float) = with(d) { v.dp.toPx() }
    private fun Color.a(alpha: Int) = copy(alpha = alpha.coerceIn(0, 255) / 255f)

    fun tile(index: Int, r: Rect) {
        val x = r.left; val y = r.top; val w = r.width; val h = r.height
        when (index) {
            0 -> { cell(x, y, w, h, if (s.bassSolo) "BASS SOLO / 베이스" else "BASS / 베이스", "속도 → 저음 음색"); bass(x, y, w, h) }
            1 -> { cell(x, y, w, h, "DRUMS / 드럼", "18 km/h · 박동"); drums(x, y, w, h) }
            2 -> { cell(x, y, w, h, "CHORDS / 화음", "38 km/h · 화성"); chords(x, y, w, h) }
            3 -> { cell(x, y, w, h, "ARP / 아르페지오", "58 km/h · 분산 음"); arpeggio(x, y, w, h) }
            4 -> { cell(x, y, w, h, "MELODY / 선율", "82 km/h · 조향"); melody(x, y, w, h) }
            else -> { cell(x, y, w, h, "SHIMMER / 고역", "105 km/h · 반짝임"); shimmer(x, y, w, h) }
        }
    }

    /** Draws text with its baseline at [baseline], matching android.graphics.Canvas.drawText. */
    private fun label(value: String, x: Float, baseline: Float, sizeDp: Float, color: Color, bold: Boolean = false) {
        val style = TextStyle(color = color, fontSize = with(d) { sizeDp.dp.toSp() },
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        val layout = text.measure(value, style)
        d.drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
    }

    private fun cell(x: Float, y: Float, w: Float, h: Float, name: String, mapping: String) {
        val corner = CornerRadius(dp(12f))
        d.drawRoundRect(Color(0xff142a39), Offset(x, y), Size(w, h), corner)
        d.drawRoundRect(Color(0xff345264), Offset(x, y), Size(w, h), corner, style = Stroke(dp(1f)))
        label(name, x + dp(12f), y + dp(24f), 15f, Color.White, bold = true)
        label(mapping, x + dp(12f), y + dp(44f), 12f, Color(0xffb9d7dd), bold = true)
    }

    private fun bass(x: Float, y: Float, w: Float, h: Float) {
        val amp = s.accelerator.coerceIn(0.0, 1.0).toFloat()
        d.drawOval(accent.a(36), Offset(x + w * 0.06f, y + h * 0.40f), Size(w * 0.85f, h * 0.43f))
        for (i in 0..3) {
            val yy = y + h * (0.48f + i * 0.095f)
            val path = Path().apply {
                moveTo(x + dp(14f), yy)
                for (j in 1..24) {
                    val xx = x + dp(14f) + (w - dp(28f)) * j / 24f
                    lineTo(xx, yy + (sin(j * 0.62 + phase + i) * dp(1.5f + amp * 3.0f)).toFloat())
                }
            }
            d.drawPath(path, if (i == 1) accent else Color(0xff7196a6), style = Stroke(dp(if (i == 1) 2.1f else 1.1f)))
        }
        for (i in 0..3) d.drawCircle(Color(0xffdceeed), dp(3.5f), Offset(x + w * 0.88f, y + h * (0.48f + i * 0.095f)))
        for (i in 1..5) {
            val fretX = x + w * (0.17f + i * 0.115f)
            d.drawRoundRect(Color(0xff648a99).a(120), Offset(fretX, y + h * 0.45f), Size(dp(1.2f), h * 0.33f), CornerRadius(dp(1f)))
        }
    }

    private fun drums(x: Float, y: Float, w: Float, h: Float) {
        val cy = y + h * 0.65f
        val speedMix = (s.kmh / 130.0).coerceIn(0.0, 1.0).toFloat()
        for (i in 0..2) {
            val center = Offset(x + w * (0.25f + i * 0.25f), cy + if (i == 1) dp(4f) else 0f)
            val radius = min(w, h) * (if (i == 1) 0.20f else 0.135f)
            val alpha = if (playing) (140 + speedMix * 65 + sin(phase * (1.5 + speedMix * 2) + i) * 30).toInt() else 145
            d.drawCircle((if (i == 1) accent else Color(0xff5f9cad)).a(alpha), radius, center)
            d.drawCircle(Color(0xffd4f8fa), radius, center, style = Stroke(dp(1f)))
        }
        val cymbal = Stroke(dp(1.3f))
        d.drawOval(Color(0xff9ed8da), Offset(x + w * 0.12f, y + h * 0.39f), Size(w * 0.30f, h * 0.06f), style = cymbal)
        d.drawOval(Color(0xff9ed8da), Offset(x + w * 0.58f, y + h * 0.39f), Size(w * 0.30f, h * 0.06f), style = cymbal)
    }

    private fun chords(x: Float, y: Float, w: Float, h: Float) {
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
        d.drawPath(ribbon, accent.a((190 - s.brake * 90).toInt()), style = Stroke(dp(1.5f)))
        val keyAlpha = (220 - s.brake.coerceIn(0.0, 1.0) * 100).toInt()
        for (i in 0 until count) {
            val xx = x + dp(14f) + i * (keyW + gap)
            d.drawRoundRect((if (i % 3 == 0) accent else Color(0xffd8e8e9)).a(keyAlpha),
                Offset(xx, keyY), Size(keyW, keyH), CornerRadius(dp(2f)))
        }
    }

    private fun melody(x: Float, y: Float, w: Float, h: Float) {
        if (s.turn != 0) label(if (s.turn < 0) "◀  LEFT · 하행" else "RIGHT · 상행  ▶", x + dp(14f), y + h * 0.39f, 14f, accent, bold = true)
        for (line in 0..2) {
            val path = Path()
            val baseY = y + h * (0.51f + line * 0.12f)
            for (i in 0..36) {
                val xx = x + dp(14f) + (w - dp(28f)) * i / 36f
                val yy = (baseY + sin(i * 0.35 + phase * 0.5 + line * 0.7) * dp(7f + line * 1.5f) - s.steering.toFloat() * dp(11f)).toFloat()
                if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            }
            d.drawPath(path, if (line == 1) accent else Color(0xff81a6b5), style = Stroke(dp(if (line == 1) 2.3f else 1.2f)))
        }
    }

    private fun arpeggio(x: Float, y: Float, w: Float, h: Float) {
        val points = intArrayOf(2, 4, 3, 6, 4, 7, 5, 8)
        fun at(i: Int) = Offset(x + w * (0.10f + i * 0.115f), y + h * (0.82f - points[i] * 0.045f))
        val path = Path()
        for (i in points.indices) at(i).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }
        d.drawPath(path, accent, style = Stroke(dp(2f)))
        for (i in points.indices) {
            val lit = playing && (phase.toInt() / 3) % points.size == i
            d.drawCircle(if (lit) Color.White else accent, dp(3.5f), at(i))
        }
    }

    private fun shimmer(x: Float, y: Float, w: Float, h: Float) {
        val center = Offset(x + w * 0.5f, y + h * 0.64f)
        for (ring in 1..3) d.drawCircle(accent.a(210 - ring * 45), min(w, h) * (0.10f + ring * 0.075f), center, style = Stroke(dp(1.5f)))
        for (i in 0..7) {
            val angle = i * PI / 4.0 + phase * 0.18
            val radius = min(w, h) * 0.30f
            d.drawCircle(Color.White, dp(if (i % 2 == 0) 2.2f else 1.4f),
                Offset(center.x + (cos(angle) * radius).toFloat(), center.y + (sin(angle) * radius).toFloat()))
        }
    }
}
