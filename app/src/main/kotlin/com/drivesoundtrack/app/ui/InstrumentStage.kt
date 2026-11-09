package com.drivesoundtrack.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.drivesoundtrack.app.audio.Instruments
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
fun InstrumentStage(status: PlayerStatus, accent: Color, playing: Boolean, modifier: Modifier = Modifier,
                    /** Leave room at the top for the title drawn over the stage. */ titleSpace: Boolean = true) {
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
        val top = if (!titleSpace) 14 else if (h > 420.dp.toPx()) 150 else 118
        val stage = Rect(14.dp.toPx(), top.dp.toPx(), w - 14.dp.toPx(), h - 14.dp.toPx())
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
        // A classical track draws the instrument chosen for each stage in its own look.
        if (s.instruments.size == 6 && !s.bassSolo) { instrumentTile(index, x, y, w, h); return }
        val title = if (index == 0 && s.bassSolo) "베이스 솔로" else STAGE_NAMES[index]
        cell(x, y, w, h, title, STAGE_HINT[index])
        when (index) {
            0 -> bass(x, y, w, h)
            1 -> drums(x, y, w, h)
            2 -> chords(x, y, w, h)
            3 -> arpeggio(x, y, w, h)
            4 -> melody(x, y, w, h)
            else -> shimmer(x, y, w, h)
        }
    }

    /** Draws text with its baseline at [baseline], matching android.graphics.Canvas.drawText. */
    private fun label(value: String, x: Float, baseline: Float, sizeDp: Float, color: Color, bold: Boolean = false) {
        val style = TextStyle(color = color, fontSize = with(d) { sizeDp.dp.toSp() },
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
        val layout = text.measure(value, style)
        d.drawText(layout, topLeft = Offset(x, baseline - layout.firstBaseline))
    }

    private fun cell(x: Float, y: Float, w: Float, h: Float, name: String, mapping: String, color: Color? = null) {
        val corner = CornerRadius(dp(12f))
        d.drawRoundRect(Color(0xff142a39), Offset(x, y), Size(w, h), corner)
        d.drawRoundRect(color?.a(170) ?: Color(0xff345264), Offset(x, y), Size(w, h), corner, style = Stroke(dp(if (color != null) 1.6f else 1f)))
        if (color != null) d.drawRoundRect(color, Offset(x + dp(12f), y + dp(10f)), Size(dp(4f), dp(20f)), CornerRadius(dp(2f)))
        val indent = if (color != null) dp(22f) else dp(12f)
        label(name, x + indent, y + dp(31f), 22f, Color.White, bold = true)
        label(mapping, x + indent, y + dp(57f), 18f, Color(0xffb9d7dd))
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

    private fun drums(x: Float, y: Float, w: Float, h: Float, tint: Color = accent) {
        val cy = y + h * 0.65f
        val speedMix = (s.kmh / 130.0).coerceIn(0.0, 1.0).toFloat()
        for (i in 0..2) {
            val center = Offset(x + w * (0.25f + i * 0.25f), cy + if (i == 1) dp(4f) else 0f)
            val radius = min(w, h) * (if (i == 1) 0.20f else 0.135f)
            val alpha = if (playing) (140 + speedMix * 65 + sin(phase * (1.5 + speedMix * 2) + i) * 30).toInt() else 145
            d.drawCircle((if (i == 1) tint else Color(0xff5f9cad)).a(alpha), radius, center)
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
        if (s.turn != 0) label(if (s.turn < 0) "◀  LEFT · 하행" else "RIGHT · 상행  ▶", x + dp(14f), y + h * 0.39f, 16f, accent, bold = true)
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

    // ------------------------------------------------------------------ one look per instrument

    /** A classical track's tile: the chosen instrument drawn in its own shape and color. */
    private fun instrumentTile(index: Int, x: Float, y: Float, w: Float, h: Float) {
        val kind = Instruments.all[s.instruments[index]]
        val look = InstrumentLooks.of(kind.id)
        val color = look.color
        cell(x, y, w, h, "${STAGE_NAMES[index]} · ${kind.label}", STAGE_HINT[index], color)
        val top = y + dp(68f)
        val bodyH = max(dp(10f), y + h - top - dp(10f))
        look(kind.id, x, top, w, bodyH)
    }

    /** Draws instrument [id] in its own shape and color inside the given box. */
    fun look(id: String, x: Float, top: Float, w: Float, bodyH: Float) {
        val look = InstrumentLooks.of(id)
        val color = look.color
        when (look.shape) {
            Shape.BOWED -> bowed(x, top, w, bodyH, color, low = id == "cello" || id == "pizz",
                bows = when (id) { "strings", "synthstrings" -> 3; "viola" -> 2; "pizz" -> 0; else -> 1 })
            Shape.KEYS -> keys(x, top, w, bodyH, color)
            Shape.ORGAN -> organ(x, top, w, bodyH, color)
            Shape.HARP -> harp(x, top, w, bodyH, color)
            Shape.PLUCKED -> plucked(x, top, w, bodyH, color)
            Shape.WIND -> wind(x, top, w, bodyH, color, id)
            Shape.BRASS -> brass(x, top, w, bodyH, color, coiled = id == "horn")
            Shape.MALLET -> mallet(x, top, w, bodyH, color)
            Shape.VOICE -> voice(x, top, w, bodyH, color)
            Shape.GLASS -> glass(x, top, w, bodyH, color)
            Shape.ORCHESTRA -> orchestraPerc(x, top, w, bodyH, color)
            Shape.LATIN -> latin(x, top, w, bodyH, color)
            Shape.TIMPANI -> timpani(x, top, w, bodyH, color)
            Shape.KIT -> drums(x, top - bodyH * 0.25f, w, bodyH * 1.2f, color)
            Shape.SYNTH -> synth(x, top, w, bodyH, color)
            Shape.REST -> {
                // A whole rest: a short block under a line.
                val cx = x + w * 0.5f; val cy = top + bodyH * 0.45f
                d.drawLine(Color(0xff5c6f7c), Offset(cx - dp(30f), cy), Offset(cx + dp(30f), cy), dp(2f))
                d.drawRect(Color(0xff9fb4c2), Offset(cx - dp(13f), cy), Size(dp(26f), dp(10f)))
            }
        }
    }

    private fun pulse(speed: Double = 1.0, offset: Double = 0.0) = if (playing) (0.5 + 0.5 * sin(phase * speed + offset)).toFloat() else 0.5f

    /** Strings with a bow sweeping across them; the strings shiver while the bow moves. */
    /** [bows]: 1 for a solo player, several for a section, 0 for pizzicato (the strings are plucked). */
    private fun bowed(x: Float, y: Float, w: Float, h: Float, c: Color, low: Boolean, bows: Int) {
        val strings = 4
        val left = x + w * 0.10f; val right = x + w * 0.90f
        for (i in 0 until strings) {
            val yy = y + h * (0.22f + i * 0.17f)
            val path = Path().apply {
                moveTo(left, yy)
                for (j in 1..30) {
                    val xx = left + (right - left) * j / 30f
                    lineTo(xx, yy + (sin(j * 1.9 + phase * 3.0 + i) * dp(if (low) 2.4f else 1.4f) * pulse(0.7, i.toDouble())).toFloat())
                }
            }
            d.drawPath(path, if (i == 1) c else c.a(150), style = Stroke(dp(if (low) 2.4f - i * 0.3f else 1.6f - i * 0.2f)))
        }
        for (b in 0 until bows) {
            val spread = if (bows == 1) 0f else (b - (bows - 1) / 2f) * 0.22f
            val bowX = x + w * (0.30f + spread + 0.30f * pulse(0.35, b * 1.7))
            val tilt = w * (if (bows == 1) 0.12f else 0.07f)
            d.drawLine(Color(0xffe8dcc4), Offset(bowX - tilt, y + h * 0.08f), Offset(bowX + tilt, y + h * 0.82f), dp(if (bows == 1) 3f else 2f))
            d.drawLine(c.a(220), Offset(bowX - tilt * 0.8f, y + h * 0.10f), Offset(bowX + tilt * 1.2f, y + h * 0.80f), dp(1.2f))
        }
        if (bows == 0) {
            // Pizzicato: a finger plucks a string, which rings in a bright arc.
            val pluck = if (playing) ((phase * 0.8).toInt()) % 4 else 1
            val yy = y + h * (0.22f + pluck * 0.17f)
            d.drawCircle(c, dp(7f) + dp(4f) * pulse(3.0), Offset(x + w * 0.62f, yy), style = Stroke(dp(2f)))
        }
    }

    /** A keyboard whose keys light up one after another. */
    private fun keys(x: Float, y: Float, w: Float, h: Float, c: Color) {
        val n = 10
        val left = x + dp(14f); val kw = (w - dp(28f)) / n
        val ky = y + h * 0.25f; val kh = h * 0.65f
        val lit = if (playing) ((phase * 0.9).toInt()) % n else -1
        for (i in 0 until n) {
            d.drawRoundRect(if (i == lit || i == (lit + 4) % n) c else Color(0xffe4ecee),
                Offset(left + i * kw + dp(1f), ky), Size(kw - dp(2f), kh), CornerRadius(dp(3f)))
        }
        for (i in 0 until n - 1) if (i % 7 != 2 && i % 7 != 6)
            d.drawRoundRect(Color(0xff0d1a24), Offset(left + (i + 0.68f) * kw, ky), Size(kw * 0.64f, kh * 0.58f), CornerRadius(dp(2f)))
    }

    /** Organ pipes that glow with the held chord. */
    private fun organ(x: Float, y: Float, w: Float, h: Float, c: Color) {
        val n = 9
        val pw = (w - dp(28f)) / n
        for (i in 0 until n) {
            val tall = h * (0.45f + 0.5f * (1f - abs(i - n / 2) / (n / 2f)))
            val glow = pulse(0.25, i * 0.6)
            d.drawRoundRect(c.a((110 + glow * 130).toInt()), Offset(x + dp(14f) + i * pw + dp(3f), y + h - tall),
                Size(pw - dp(6f), tall), CornerRadius(dp(4f)))
            d.drawRect(Color(0xff0d1a24), Offset(x + dp(14f) + i * pw + pw * 0.3f, y + h - tall * 0.25f), Size(pw * 0.4f, dp(3f)))
        }
    }

    /** A harp frame; its strings ring one by one. */
    private fun harp(x: Float, y: Float, w: Float, h: Float, c: Color) {
        val n = 11
        val left = x + w * 0.16f; val right = x + w * 0.86f
        val frame = Path().apply {
            moveTo(left, y + h * 0.95f); lineTo(left, y + h * 0.05f)
            quadraticTo(x + w * 0.55f, y - h * 0.05f, right, y + h * 0.45f)
        }
        d.drawPath(frame, Color(0xffd9b779), style = Stroke(dp(3f)))
        val ringing = if (playing) ((phase * 1.4).toInt()) % n else -1
        for (i in 0 until n) {
            val xx = left + (right - left) * (i + 0.5f) / n
            val topY = y + h * (0.06f + 0.40f * (i + 0.5f) / n)
            val wobble = if (i == ringing) dp(3f) * sin(phase * 9).toFloat() else 0f
            d.drawLine(if (i == ringing) c else c.a(140), Offset(xx, topY), Offset(xx + wobble, y + h * 0.95f), dp(if (i == ringing) 2f else 1.2f))
        }
    }

    /** Plucked strings or tines (pizzicato-like synths, kalimba): short lines that snap. */
    private fun plucked(x: Float, y: Float, w: Float, h: Float, c: Color) {
        val n = 7
        for (i in 0 until n) {
            val xx = x + w * (0.14f + i * 0.12f)
            val len = h * (0.85f - abs(i - 3) * 0.12f)
            val snap = pulse(1.6, i * 0.9)
            d.drawRoundRect(c.a((120 + snap * 135).toInt()), Offset(xx, y + h - len), Size(dp(6f), len), CornerRadius(dp(3f)))
        }
    }

    /** A woodwind: a body with tone holes and breath flowing out of its end. */
    /** Body color tells the woodwinds apart: silver flute, black clarinet, dark oboe, red-brown bassoon. */
    private fun wind(x: Float, y: Float, w: Float, h: Float, c: Color, id: String) {
        val cy = y + h * 0.42f
        val body = when (id) { "bassoon" -> Color(0xff8a5a3c); "clarinet" -> Color(0xff2b2f36); "oboe" -> Color(0xff4a3328); else -> Color(0xffcfd8dc) }
        if (id == "clarinet" || id == "oboe") {
            // A small bell at the end of the reed instruments.
            val bell = Path().apply { moveTo(x + w * 0.62f, cy - dp(6f)); lineTo(x + w * 0.68f, cy - dp(11f))
                lineTo(x + w * 0.68f, cy + dp(11f)); lineTo(x + w * 0.62f, cy + dp(6f)); close() }
            d.drawPath(bell, body)
        }
        d.drawRoundRect(body, Offset(x + w * 0.08f, cy - dp(6f)), Size(w * 0.58f, dp(12f)), CornerRadius(dp(6f)))
        for (i in 0..5) d.drawCircle(if (id == "clarinet" || id == "oboe") Color(0xffd9dde0) else Color(0xff0d1a24), dp(2.6f), Offset(x + w * (0.20f + i * 0.075f), cy))
        for (k in 0..2) {
            val path = Path()
            for (j in 0..24) {
                val t = j / 24f
                val xx = x + w * (0.66f + t * 0.30f)
                val yy = cy + (sin(t * 7 + phase * 1.4 + k * 1.3) * dp(4f + t * 10f)).toFloat() + (k - 1) * dp(9f) * t
                if (j == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            }
            d.drawPath(path, c.a(220 - k * 50), style = Stroke(dp(2f - k * 0.4f)))
        }
    }

    /** A brass bell sending out rings of sound. */
    private fun brass(x: Float, y: Float, w: Float, h: Float, c: Color, coiled: Boolean) {
        val cy = y + h * 0.45f
        val bell = Path().apply {
            moveTo(x + w * 0.10f, cy - dp(4f)); lineTo(x + w * 0.42f, cy - dp(5f))
            quadraticTo(x + w * 0.52f, cy - h * 0.10f, x + w * 0.56f, cy - h * 0.34f)
            lineTo(x + w * 0.56f, cy + h * 0.34f)
            quadraticTo(x + w * 0.52f, cy + h * 0.10f, x + w * 0.42f, cy + dp(5f))
            lineTo(x + w * 0.10f, cy + dp(4f)); close()
        }
        d.drawPath(bell, Color(0xffe0b453))
        // The horn's coiled tubing.
        if (coiled) d.drawCircle(Color(0xffe0b453), min(w, h) * 0.17f, Offset(x + w * 0.24f, cy), style = Stroke(dp(4f)))
        for (ring in 0..2) {
            val grow = ((phase * 0.12 + ring / 3.0) % 1.0).toFloat()
            val r = h * (0.22f + grow * 0.45f)
            d.drawArc(c.a((230 * (1f - grow)).toInt()), -50f, 100f, false,
                Offset(x + w * 0.56f - r * 0.6f, cy - r), Size(r * 1.2f, r * 2f), style = Stroke(dp(2f)))
        }
    }

    /** Mallet bars (marimba, xylophone, glockenspiel, music box) with a mallet bouncing along. */
    private fun mallet(x: Float, y: Float, w: Float, h: Float, c: Color) {
        val n = 8
        val bw = (w - dp(28f)) / n
        val hit = if (playing) ((phase * 1.2).toInt()) % n else -1
        for (i in 0 until n) {
            val len = h * (0.90f - i * 0.07f)
            val top = y + (h - len) / 2
            d.drawRoundRect(if (i == hit) c else c.a(120), Offset(x + dp(14f) + i * bw + dp(2f), top), Size(bw - dp(4f), len), CornerRadius(dp(3f)))
        }
        if (hit >= 0) d.drawCircle(Color.White, dp(5f), Offset(x + dp(14f) + (hit + 0.5f) * bw, y + h * 0.05f + dp(6f) * pulse(4.0)))
    }

    /** Voices: soft overlapping circles breathing together. */
    private fun voice(x: Float, y: Float, w: Float, h: Float, c: Color) {
        for (i in 0..3) {
            val r = min(w, h) * (0.20f + 0.05f * pulse(0.5, i * 1.1))
            d.drawCircle(c.a(70), r, Offset(x + w * (0.25f + i * 0.17f), y + h * 0.5f))
        }
    }

    /** Glass and synth pads: rings and orbiting sparks. */
    private fun glass(x: Float, y: Float, w: Float, h: Float, c: Color) {
        val center = Offset(x + w * 0.5f, y + h * 0.5f)
        for (ring in 1..3) d.drawCircle(c.a(210 - ring * 45), min(w, h) * (0.10f + ring * 0.10f), center, style = Stroke(dp(1.5f)))
        for (i in 0..7) {
            val angle = i * PI / 4.0 + phase * 0.18
            val radius = min(w, h) * 0.38f
            d.drawCircle(Color.White, dp(if (i % 2 == 0) 2.2f else 1.4f),
                Offset(center.x + (cos(angle) * radius).toFloat(), center.y + (sin(angle) * radius).toFloat()))
        }
    }

    /** Orchestra percussion: a bass drum seen from the front and a ringing triangle. */
    private fun orchestraPerc(x: Float, y: Float, w: Float, h: Float, c: Color) {
        val r = min(w * 0.22f, h * 0.45f)
        val center = Offset(x + w * 0.34f, y + h * 0.5f)
        d.drawCircle(c.a((90 + 120 * pulse(1.0)).toInt()), r, center)
        d.drawCircle(Color(0xffe8dcc4), r, center, style = Stroke(dp(3f)))
        d.drawCircle(Color(0xffe8dcc4).a(120), r * 0.55f, center, style = Stroke(dp(1f)))
        val tx = x + w * 0.74f; val ty = y + h * 0.20f; val ts = min(w * 0.18f, h * 0.6f)
        val tri = Path().apply { moveTo(tx, ty); lineTo(tx + ts * 0.5f, ty + ts); lineTo(tx - ts * 0.5f, ty + ts); close() }
        d.drawPath(tri, Color(0xffdfe8ea), style = Stroke(dp(2.4f)))
        d.drawCircle(c.a((255 * pulse(2.0)).toInt()), ts * 0.75f, Offset(tx, ty + ts * 0.6f), style = Stroke(dp(1f)))
    }

    /** Latin percussion: two tall conga drums and claves. */
    private fun latin(x: Float, y: Float, w: Float, h: Float, c: Color) {
        for (i in 0..1) {
            val cx = x + w * (0.30f + i * 0.25f)
            val dw = w * 0.17f; val top = y + h * (0.18f + i * 0.08f)
            d.drawRoundRect(Color(0xffa0673f), Offset(cx - dw / 2, top), Size(dw, y + h - top), CornerRadius(dp(8f)))
            d.drawOval(c.a((140 + 115 * pulse(1.5, i * 2.0)).toInt()), Offset(cx - dw / 2, top - dp(5f)), Size(dw, dp(12f)))
        }
        d.drawLine(Color(0xffd9b779), Offset(x + w * 0.72f, y + h * 0.30f), Offset(x + w * 0.90f, y + h * 0.20f), dp(5f))
        d.drawLine(Color(0xffd9b779), Offset(x + w * 0.74f, y + h * 0.48f), Offset(x + w * 0.90f, y + h * 0.36f), dp(5f))
    }

    /** Timpani: two kettle drums whose heads shiver after each stroke. */
    private fun timpani(x: Float, y: Float, w: Float, h: Float, c: Color) {
        for (i in 0..1) {
            val cx = x + w * (0.30f + i * 0.40f)
            val r = min(w * 0.18f, h * 0.42f)
            val bowl = Path().apply {
                moveTo(cx - r, y + h * 0.35f)
                quadraticTo(cx - r, y + h * 0.95f, cx, y + h * 0.95f)
                quadraticTo(cx + r, y + h * 0.95f, cx + r, y + h * 0.35f); close()
            }
            d.drawPath(bowl, Color(0xffb87333))
            d.drawOval(c.a((150 + 105 * pulse(1.0, i * 3.0)).toInt()), Offset(cx - r, y + h * 0.29f), Size(r * 2, h * 0.12f))
        }
    }

    /** Synthesisers: a glowing waveform. */
    private fun synth(x: Float, y: Float, w: Float, h: Float, c: Color) {
        for (line in 0..1) {
            val path = Path()
            for (i in 0..40) {
                val xx = x + dp(14f) + (w - dp(28f)) * i / 40f
                val saw = ((i + phase * 2).toFloat() % 8f) / 8f
                val yy = y + h * 0.5f + (if (line == 0) (saw - 0.5f) * h * 0.6f else sin(i * 0.5 + phase).toFloat() * h * 0.2f)
                if (i == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
            }
            d.drawPath(path, if (line == 0) c else c.a(110), style = Stroke(dp(if (line == 0) 2f else 1.2f)))
        }
    }
}

/** The same six names everywhere: stage tiles, the speed ladder and the band. */
internal val STAGE_NAMES = listOf("베이스", "리듬", "화음", "분산음", "선율", "고음")
private val STAGE_HINT = listOf("항상", "18 km/h부터", "38 km/h부터", "58 km/h부터", "82 km/h부터", "105 km/h부터")

internal enum class Shape { BOWED, KEYS, ORGAN, HARP, PLUCKED, WIND, BRASS, MALLET, VOICE, GLASS, ORCHESTRA, LATIN, TIMPANI, KIT, SYNTH, REST }

/** How each instrument is drawn on the stage: its shape and its own color. */
internal object InstrumentLooks {
    class Look(val shape: Shape, val color: Color)
    private val looks = mapOf(
        "cello" to Look(Shape.BOWED, Color(0xffd08a52)),
        "pizz" to Look(Shape.BOWED, Color(0xffb5763f)),
        "strings" to Look(Shape.BOWED, Color(0xffe6a86b)),
        "viola" to Look(Shape.BOWED, Color(0xffc9925e)),
        "violin" to Look(Shape.BOWED, Color(0xfff2c27e)),
        "synthstrings" to Look(Shape.BOWED, Color(0xff9aa7ff)),
        "piano" to Look(Shape.KEYS, Color(0xff8fd3ff)),
        "epiano" to Look(Shape.KEYS, Color(0xff7ee0c3)),
        "organ" to Look(Shape.ORGAN, Color(0xffd9b779)),
        "harp" to Look(Shape.HARP, Color(0xfff4dc8c)),
        "kalimba" to Look(Shape.PLUCKED, Color(0xff9fe0a4)),
        "synthpluck" to Look(Shape.PLUCKED, Color(0xff7fd6ff)),
        "flute" to Look(Shape.WIND, Color(0xffb8e6ff)),
        "clarinet" to Look(Shape.WIND, Color(0xff9ec5e8)),
        "oboe" to Look(Shape.WIND, Color(0xffa8d8b9)),
        "bassoon" to Look(Shape.WIND, Color(0xffd6a77a)),
        "horn" to Look(Shape.BRASS, Color(0xfff0c060)),
        "trumpet" to Look(Shape.BRASS, Color(0xffffd166)),
        "marimba" to Look(Shape.MALLET, Color(0xffc8875a)),
        "xylophone" to Look(Shape.MALLET, Color(0xfff0a868)),
        "glock" to Look(Shape.MALLET, Color(0xffe0f0ff)),
        "musicbox" to Look(Shape.MALLET, Color(0xfff7b7d2)),
        "choir" to Look(Shape.VOICE, Color(0xffd7b8f5)),
        "glasspad" to Look(Shape.GLASS, Color(0xffc9b8f2)),
        "orchperc" to Look(Shape.ORCHESTRA, Color(0xffe07a5f)),
        "latin" to Look(Shape.LATIN, Color(0xfff2a65a)),
        "timpani" to Look(Shape.TIMPANI, Color(0xffe8836b)),
        "softkit" to Look(Shape.KIT, Color(0xff6fb7c9)),
        "shaker" to Look(Shape.KIT, Color(0xffa3d977)),
        "electro" to Look(Shape.KIT, Color(0xff5ec8f2)),
        "synthbass" to Look(Shape.SYNTH, Color(0xff6ad1e3)),
        "synthlead" to Look(Shape.SYNTH, Color(0xffff8fb1)),
    )
    private val rest = Look(Shape.REST, Color(0xff5c6f7c))
    /** Downloaded instruments bring their own shape and color. */
    @Volatile private var downloaded: Map<String, Look> = emptyMap()
    fun of(id: String): Look = looks[id] ?: downloaded[id] ?: rest

    @Synchronized fun register(id: String, shape: String, rgb: Int) {
        if (id in looks) return
        val s = Shape.entries.firstOrNull { it.name == shape && it != Shape.REST } ?: Shape.GLASS
        downloaded = downloaded + (id to Look(s, Color(0xff000000.toInt() or rgb)))
    }
}

/** One instrument drawn on its own (no tile frame): the band screen's slots and cards. */
@Composable
fun InstrumentLook(id: String, animate: Boolean, modifier: Modifier = Modifier) {
    val text = rememberTextMeasurer()
    var phase by remember { mutableStateOf(0.0) }
    if (animate) LaunchedEffect(id) {
        var last = 0L
        while (true) withFrameNanos { now ->
            if (last != 0L) phase += PHASE_PER_FRAME * ((now - last) / 1e9) / FRAME_SECONDS
            last = now
        }
    }
    Canvas(modifier) {
        StageScene(this, text, PlayerStatus(), Color.White, animate, phase).look(id, 0f, 0f, size.width, size.height)
    }
}
