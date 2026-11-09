package com.drivesoundtrack.app.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.tan

/** Small synthesis toolkit shared by the soundscapes. Everything runs per sample at [SoundRenderer.RATE]. */
internal object Dsp {
    const val RATE = SoundRenderer.RATE.toDouble()

    /** Band-limited step correction (PolyBLEP) so saw/square edges do not alias into harsh noise. */
    fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
        t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
        else -> 0.0
    }

    /** Per-sample multiplier for an exponential decay with the given time constant in seconds. */
    fun decay(seconds: Double): Double = exp(-1.0 / (seconds * RATE))

    fun semitones(n: Double): Double = Math.pow(2.0, n / 12.0)
}

/** Band-limited oscillator: saw, square, or a mix. Phase 0..1. */
internal class Osc {
    var phase = 0.0
    fun saw(hz: Double): Double {
        val dt = min(0.49, hz / Dsp.RATE)
        val value = 2.0 * phase - 1.0 - Dsp.polyBlep(phase, dt)
        advance(dt)
        return value
    }
    fun square(hz: Double): Double {
        val dt = min(0.49, hz / Dsp.RATE)
        var value = if (phase < 0.5) 1.0 else -1.0
        value += Dsp.polyBlep(phase, dt)
        value -= Dsp.polyBlep((phase + 0.5) % 1.0, dt)
        advance(dt)
        return value
    }
    fun sine(hz: Double): Double {
        val value = kotlin.math.sin(2.0 * PI * phase)
        advance(hz / Dsp.RATE)
        return value
    }
    private fun advance(dt: Double) { phase += dt; if (phase >= 1.0) phase -= 1.0 }
}

/**
 * State-variable filter (topology-preserving form): stable while the cutoff moves every sample,
 * which the speed- and envelope-driven filters rely on.
 */
internal class Svf {
    private var ic1 = 0.0
    private var ic2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0; private var a3 = 0.0; private var k = 1.0
    private var lastCutoff = -1.0; private var lastQ = -1.0
    var low = 0.0; private set
    var band = 0.0; private set
    var high = 0.0; private set

    fun set(cutoffHz: Double, q: Double) {
        val cutoff = cutoffHz.coerceIn(20.0, Dsp.RATE * 0.45)
        if (cutoff == lastCutoff && q == lastQ) return
        lastCutoff = cutoff; lastQ = q
        val g = tan(PI * cutoff / Dsp.RATE)
        k = 1.0 / q
        a1 = 1.0 / (1.0 + g * (g + k)); a2 = g * a1; a3 = g * a2
    }

    fun process(x: Double): Double {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2.0 * v1 - ic1
        ic2 = 2.0 * v2 - ic2
        low = v2; band = v1; high = x - k * v1 - v2
        return low
    }
}

/** Fast white noise (xorshift), -1..1. */
internal class Noise(seed: Long = 0x5DEECE66DL) {
    private var state = seed
    fun next(): Double {
        state = state xor (state shl 13); state = state xor (state ushr 7); state = state xor (state shl 17)
        return (state ushr 11).toDouble() / (1L shl 53).toDouble() * 2.0 - 1.0
    }
}

/** Attack/decay/release envelope whose level never jumps, so retriggers cannot click. */
internal class Env(attack: Double, decay: Double, private val sustain: Double, release: Double) {
    private val attackStep = 1.0 - Dsp.decay(attack)
    private val releaseStep = 1.0 - Dsp.decay(release)
    private val decayStep = 1.0 - Dsp.decay(decay)
    private var stage = 3 // 0 attack, 1 decay, 2 release, 3 idle
    var level = 0.0; private set
    fun gate() { stage = 0 }
    fun release() { if (stage < 2) stage = 2 }
    fun next(): Double {
        when (stage) {
            0 -> { level += (1.08 - level) * attackStep; if (level >= 1.0) { level = 1.0; stage = 1 } }
            1 -> level += (sustain - level) * decayStep
            2 -> { level -= level * releaseStep; if (level < 1e-5) { level = 0.0; stage = 3 } }
        }
        return level
    }
}

/**
 * A soundscape with its own instruments. [SoundRenderer] calls [step] on every sixteenth note and
 * [render] once per sample, then mixes [left]/[right] and the centred [bass] like its shared voices.
 * [mixes] holds the layer fades 1..5 (index 0 unused; the bass always plays).
 */
internal interface ScoreEngine {
    val left: Double
    val right: Double
    val bass: Double
    fun step(position: Int, bar: Long, chord: Int, tones: IntArray, scene: Soundscape, rhythmLevel: Int, bpm: Double, wiper: Boolean)
    fun render(speed: Double, accelerator: Double, brake: Double, mixes: DoubleArray, steeringPitch: Double, bpm: Double)
}
