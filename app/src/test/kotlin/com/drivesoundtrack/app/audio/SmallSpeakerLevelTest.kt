package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Every built-in instrument a stage offers must really play, in every classical piece, all the way
 * through, and stay audible on small speakers (laptop, emulator, door speakers that roll off under
 * ~200 Hz). A part whose sound lives only below that range reads as loud on a full-band meter yet
 * disappears in the car.
 *
 * Each choice is played alone on its stage through the whole piece and checked for:
 *  - sound at all;
 *  - coverage: wherever the piece's default instrument for that stage sounds (in 4-bar windows),
 *    the choice sounds too;
 *  - level after a steep 200 Hz high-pass, against the loudest choice of the stage.
 */
class SmallSpeakerLevelTest {
    private val rate = 44100

    private class Take(val audio: DoubleArray, val windowSamples: Int)

    /** One stage of [scene]'s piece played alone with [kind], once through the whole piece, mono. */
    private fun render(scene: Soundscape, stage: Int, kind: Int): Take {
        val piece = scene.piece!!
        val engine = PieceEngine(piece)
        val ids = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }.also { it[stage] = kind }
        engine.setOrchestration(ids)
        val mixes = DoubleArray(6).also { it[stage] = 1.0 }
        val bpm = scene.baseBpm
        val stepSamples = (60.0 / bpm / 4.0 * rate).toInt()
        val out = DoubleArray(piece.lengthSteps * stepSamples)
        var i = 0
        for (step in 0 until piece.lengthSteps) {
            val span = piece.chordAt(step)
            engine.step(step % piece.stepsPerBar, (step / piece.stepsPerBar).toLong(), span.relativeRoot, span.tones, scene, 2, bpm, false)
            repeat(stepSamples) {
                engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm)
                out[i++] = if (stage == 0) engine.bass else (engine.left + engine.right) * 0.5
            }
        }
        return Take(out, stepSamples * piece.stepsPerBar * 4)
    }

    /** Two cascaded 2nd-order Butterworth high-passes at [hz]: a 24 dB/octave small-speaker roll-off. */
    private fun highPass(x: DoubleArray, hz: Double): DoubleArray {
        var y = x
        repeat(2) {
            val w = 2 * PI * hz / rate
            val alpha = sin(w) / (2 * sqrt(0.5))
            val a0 = 1 + alpha
            val b0 = (1 + cos(w)) / 2 / a0; val b1 = -(1 + cos(w)) / a0; val b2 = b0
            val a1 = -2 * cos(w) / a0; val a2 = (1 - alpha) / a0
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            val src = y
            y = DoubleArray(src.size) { i ->
                val v = src[i]
                val o = b0 * v + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                x2 = x1; x1 = v; y2 = y1; y1 = o
                o
            }
        }
        return y
    }

    private fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / max(1, to - from))
    }
    private fun db(x: Double) = 20 * ln(max(x, 1e-9)) / ln(10.0)
    private fun playingLevel(x: DoubleArray, barSamples: Int): Double {
        val w = rate / 10
        val perBar = (0 until x.size - barSamples + 1 step barSamples).map { bar ->
            (bar until bar + barSamples - w step w / 2).maxOf { rms(x, it, it + w) }
        }.sorted()
        return perBar[perBar.size / 2]
    }
    private fun windows(t: Take, x: DoubleArray) = (0 until x.size step t.windowSamples).map { rms(x, it, minOf(x.size, it + t.windowSamples)) }

    @Test
    fun everyChoicePlaysThroughEveryPieceAndCarriesOnSmallSpeakers() {
        val scenes = Soundscapes.all.filter { it.piece != null && it.cloudId == null }
        val problems = mutableListOf<String>()
        val table = StringBuilder()
        for (scene in scenes) {
            val piece = scene.piece!!
            table.appendLine("== ${piece.title} (${piece.lengthBars} bars)")
            for (stage in 0..5) {
                val options = Instruments.options[stage].filter { Instruments.all[it].id != "none" && !Instruments.all[it].cloud }
                val takes = options.associateWith { render(scene, stage, it) }
                val small = takes.mapValues { (_, t) -> highPass(t.audio, 200.0) }
                val full = takes.mapValues { (_, t) -> rms(t.audio) }
                // How loud the part is when it plays: each bar's loudest 100 ms, median over the bars. A
                // whole-piece average counts the silence after each pizzicato or drum hit against it, next to
                // held bows; slow 3/4 bars (Gymnopédie) hold a hit or two in 2.6 s.
                val barSamples = takes.values.first().windowSamples / 4
                val smallRms = small.mapValues { (_, x) -> playingLevel(x, barSamples) }
                val loudest = smallRms.values.max()
                // Where the stage plays: windows in which the default instrument clearly sounds.
                val defaultKind = Instruments.indexOf(piece.defaults[stage])
                val reference = takes[defaultKind] ?: takes.values.first()
                val refWindows = windows(reference, reference.audio)
                val refPeak = refWindows.max()
                val active = refWindows.indices.filter { refWindows[it] > refPeak * 0.05 }
                table.appendLine("  stage $stage ${Instruments.stageNames[stage]} (default ${piece.defaults[stage]}, plays in ${active.size}/${refWindows.size} windows)")
                for (kind in options) {
                    val id = Instruments.all[kind].id
                    val t = takes[kind]!!
                    val s = db(smallRms[kind]!! / loudest)
                    val w = windows(t, t.audio)
                    val wPeak = w.max()
                    val missing = active.filter { w[it] < max(wPeak * 0.03, 1e-5) }
                    table.appendLine("    %-12s full %6.1f dBFS  small speaker %6.1f dB  missing windows %s".format(
                        id, db(full[kind]!!), s, if (missing.isEmpty()) "-" else missing.toString()))
                    if (full[kind]!! < 1e-4) problems += "${piece.title} stage $stage: $id is silent"
                    else if (missing.isNotEmpty()) problems += "${piece.title} stage $stage: $id is silent in 4-bar windows $missing where ${piece.defaults[stage]} plays"
                    if (s < -9) problems += "${piece.title} stage $stage: $id is ${"%.1f".format(s)} dB under the loudest choice on a small speaker"
                }
            }
        }
        println(table)
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }
}
