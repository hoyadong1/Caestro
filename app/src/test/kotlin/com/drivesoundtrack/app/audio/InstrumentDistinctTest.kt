package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every instrument a stage offers must sound like itself: played alone on its stage through a piece,
 * no two choices may share a timbre (spectrum and articulation), none may be much quieter than its
 * neighbours (a swap would go unnoticed), and a swap during playback must change the sound at once.
 */
class InstrumentDistinctTest {
    private val rate = 44100

    /** One stage of [piece] played alone with [kind] for [seconds], mono. */
    private fun render(piece: Piece, stage: Int, kind: Int, seconds: Double, swapTo: Int? = null): DoubleArray {
        val engine = PieceEngine(piece)
        val ids = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }.also { it[stage] = kind }
        engine.setOrchestration(ids)
        val mixes = DoubleArray(6).also { it[stage] = 1.0 }
        val scene = Soundscapes.all.first { it.piece === piece }
        val bpm = scene.baseBpm
        val stepSamples = (60.0 / bpm / 4.0 * rate).toInt()
        val total = (seconds * rate).toInt()
        val out = DoubleArray(total)
        var i = 0
        var step = 0
        while (i < total) {
            if (swapTo != null && i >= total / 2 && ids[stage] != swapTo) { ids[stage] = swapTo; engine.setOrchestration(ids) }
            engine.step(step % piece.stepsPerBar, (step / piece.stepsPerBar).toLong(), 0, intArrayOf(0, 4, 7, 12, 16), scene, 2, bpm, false)
            repeat(stepSamples) {
                if (i < total) {
                    engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm)
                    out[i++] = if (stage == 0) engine.bass else (engine.left + engine.right) * 0.5
                }
            }
            step++
        }
        return out
    }

    private class Print(val bands: DoubleArray, val articulation: Double, val rms: Double)

    /** Average log spectrum in 24 bands (60 Hz..12 kHz), normalised, plus how choppy the level is. */
    private fun print(x: DoubleArray): Print {
        val n = 2048
        val edges = DoubleArray(25) { 60.0 * Math.pow(12000.0 / 60.0, it / 24.0) }
        val bands = DoubleArray(24)
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val levels = mutableListOf<Double>()
        var start = 0
        while (start + n <= x.size) {
            val re = DoubleArray(n) { x[start + it] * window[it] }
            val im = DoubleArray(n)
            fft(re, im)
            for (k in 1 until n / 2) {
                val f = k.toDouble() * rate / n
                val b = edges.indexOfLast { it <= f }
                if (b in 0..23) bands[b] += re[k] * re[k] + im[k] * im[k]
            }
            var s = 0.0
            for (k in 0 until n) s += x[start + k] * x[start + k]
            levels += sqrt(s / n)
            start += n / 2
        }
        val sum = bands.sum().coerceAtLeast(1e-30)
        val logBands = DoubleArray(24) { log10(bands[it] / sum + 1e-6) }
        val mean = levels.average().coerceAtLeast(1e-12)
        val articulation = levels.zipWithNext { a, b -> abs(b - a) }.average() / mean
        return Print(logBands, articulation, sqrt(x.sumOf { it * it } / x.size))
    }

    private fun distance(a: Print, b: Print): Double {
        var s = 0.0
        for (i in a.bands.indices) s += (a.bands[i] - b.bands[i]).let { it * it }
        return sqrt(s / a.bands.size) + abs(a.articulation - b.articulation)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            for (i in 0 until n step len) for (k in 0 until len / 2) {
                val wr = cos(ang * k); val wi = sin(ang * k)
                val ur = re[i + k]; val ui = im[i + k]
                val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                re[i + k] = ur + vr; im[i + k] = ui + vi
                re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
            }
            len = len shl 1
        }
    }

    private fun db(x: Double) = 20 * ln(max(x, 1e-9)) / ln(10.0)

    @Test
    fun everyChoiceSoundsDifferentAndSwapsAtOnce() {
        val pieces = listOf(Pieces.canon, Pieces.odeToJoy)
        val problems = mutableListOf<String>()
        for (stage in listOf(0, 2, 3, 4, 5)) {
            val options = Instruments.options[stage].filter { Instruments.all[it].id != "none" }
            val prints = options.associateWith { kind -> pieces.map { print(render(it, stage, kind, 8.0)) } }
            val loudest = prints.values.maxOf { p -> p.maxOf { it.rms } }
            println("stage $stage (${Instruments.stageNames[stage]}):")
            for (a in options) {
                val near = options.filter { it != a }.minBy { b -> pieces.indices.sumOf { distance(prints[a]!![it], prints[b]!![it]) } / pieces.size }
                val d = pieces.indices.sumOf { distance(prints[a]!![it], prints[near]!![it]) } / pieces.size
                val level = db(prints[a]!!.maxOf { it.rms } / loudest)
                println("  %-12s nearest %-12s distance %.3f  level %5.1f dB  articulation %.2f".format(
                    Instruments.all[a].id, Instruments.all[near].id, d, level, prints[a]!![0].articulation))
                if (d < 0.12) problems += "stage $stage: ${Instruments.all[a].id} sounds like ${Instruments.all[near].id} ($d)"
                if (level < -12) problems += "stage $stage: ${Instruments.all[a].id} is ${"%.1f".format(level)} dB under the loudest choice"
            }
            // A swap while playing changes the sound within a beat: after it the stage plays what the new
            // instrument plays from the start (same notes, same moment), not what the old one plays.
            for (b in options.drop(1)) {
                val a = options[0]
                val swapped = render(Pieces.canon, stage, a, 8.0, swapTo = b)
                val pureA = render(Pieces.canon, stage, a, 8.0)
                val pureB = render(Pieces.canon, stage, b, 8.0)
                // Compared by timbre over the same stretch (vibrato and breath make sample-exact runs differ).
                val from = swapped.size / 2 + rate // one second after the swap
                val after = print(swapped.copyOfRange(from, swapped.size))
                fun gap(x: DoubleArray) = distance(after, print(x.copyOfRange(from, x.size)))
                val toB = gap(pureB); val toA = gap(pureA)
                if (toB > 0.3 || toB * 2 >= toA) problems += "stage $stage: swapping ${Instruments.all[a].id} → ${Instruments.all[b].id}: " +
                    "after the swap the output differs %.2f from the new instrument, %.2f from the old".format(toB, toA)
                if (b == options[1]) println("  swap ${Instruments.all[a].id} → ${Instruments.all[b].id}: after the swap the output differs %.2f from the new instrument, %.2f from the old".format(toB, toA))
            }
        }
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }
}
