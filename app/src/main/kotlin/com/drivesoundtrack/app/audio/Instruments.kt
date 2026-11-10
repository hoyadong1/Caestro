package com.drivesoundtrack.app.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The instrument library the classical pieces are played with. Orchestral instruments are recorded
 * notes of real instruments ([SampleVoice], VSCO 2 Community Edition, public domain); the rest are
 * synthesised here: a [Voice] makes one note, a [Poly] holds a few voices, and a [Kit] or
 * [SampleKit] is a rhythm section. [Instruments] lists what each of the six stages can use.
 */
object Instruments {
    const val BASS = 0
    const val RHYTHM = 1
    const val HARMONY = 2
    const val ARP = 3
    const val MELODY = 4
    const val HIGH = 5

    /** Stage names in speed order (stage 0 always plays). */
    val stageNames = listOf("베이스", "리듬", "화음", "분산음", "선율", "고음")

    class Kind internal constructor(
        val id: String,
        val label: String,
        /** A recording of the real instrument rather than a synthesised sound. */
        val real: Boolean = false,
        internal val sustained: Boolean = false,
        internal val voices: Int = 4,
        internal val mono: Boolean = false,
        internal val minMidi: Int = 24,
        internal val maxMidi: Int = 100,
        internal val gain: Double = 1.0,
        internal val kit: Int = -1,
        internal val sampleKit: Int = -1,
        /** Downloaded from the cloud (made on the creator platform), not shipped with the app. */
        val cloud: Boolean = false,
        internal val voice: (() -> Voice)? = null,
    )

    internal fun sampled(id: String, label: String, recorded: Boolean, sustained: Boolean, voices: Int, mono: Boolean,
                         min: Int, max: Int, gain: Double, attack: Double, release: Double) =
        Kind(id, label, real = recorded, sustained = sustained, voices = voices, mono = mono, minMidi = min, maxMidi = max,
            gain = gain, cloud = true) { SampleVoice(id, attack, release) }

    private fun real(id: String, label: String, sample: String = id, sustained: Boolean, voices: Int, min: Int, max: Int,
                     gain: Double, attack: Double, release: Double, mono: Boolean = false) =
        Kind(id, label, real = true, sustained = sustained, voices = voices, mono = mono, minMidi = min, maxMidi = max,
            gain = gain) { SampleVoice(sample, attack, release) }

    /** Everything that can be played: the app's own instruments, then downloaded ones ([addCloud]). */
    @Volatile var all: List<Kind> = builtIn(); private set
    /** Stages each downloaded instrument may play on, by its index in [all]. */
    @Volatile private var cloudStages: Map<Int, List<Int>> = emptyMap()

    /** Adds a downloaded instrument for the given stages; returns its index. Indices never move. */
    @Synchronized internal fun addCloud(kind: Kind, stages: List<Int>): Int {
        val known = all.indexOfFirst { it.id == kind.id }
        if (known >= 0) return known
        all = all + kind
        cloudStages = cloudStages + ((all.size - 1) to stages.filter { it in 0..5 && it != RHYTHM })
        optionsCache = null
        return all.size - 1
    }

    private fun builtIn(): List<Kind> = listOf(
        // Recorded orchestra.
        real("cello", "첼로", sustained = true, voices = 2, min = 36, max = 72, gain = 3.0, attack = 0.08, release = 0.3),
        real("pizz", "콘트라베이스", sample = "contrabass", sustained = false, voices = 3, min = 28, max = 60, gain = 2.55, attack = 0.002, release = 0.35),
        real("bassoon", "바순", sustained = true, voices = 2, min = 34, max = 72, gain = 2.42, attack = 0.05, release = 0.25),
        real("piano", "피아노", sustained = false, voices = 6, min = 29, max = 105, gain = 3.12, attack = 0.002, release = 0.45),
        real("organ", "파이프 오르간", sustained = true, voices = 5, min = 36, max = 96, gain = 2.26, attack = 0.03, release = 0.25),
        // Sections fade in over 0.08 s (was 0.3 / 0.25 s): together with the slow bow they came in behind the beat.
        real("strings", "바이올린 합주", sustained = true, voices = 5, min = 55, max = 88, gain = 2.89, attack = 0.08, release = 0.6),
        real("viola", "비올라 합주", sustained = true, voices = 4, min = 48, max = 86, gain = 2.38, attack = 0.08, release = 0.5),
        real("horn", "호른", sustained = true, voices = 4, min = 36, max = 72, gain = 2.12, attack = 0.08, release = 0.35),
        real("harp", "하프", sustained = false, voices = 6, min = 35, max = 96, gain = 1.76, attack = 0.002, release = 1.2),
        real("marimba", "마림바", sustained = false, voices = 4, min = 41, max = 96, gain = 2.2, attack = 0.002, release = 0.4),
        real("xylophone", "실로폰", sustained = false, voices = 4, min = 67, max = 108, gain = 3.4, attack = 0.002, release = 0.3),
        real("violin", "바이올린", sustained = true, voices = 1, mono = true, min = 55, max = 98, gain = 3.61, attack = 0.06, release = 0.25),
        real("flute", "플루트", sustained = true, voices = 1, mono = true, min = 60, max = 96, gain = 2.38, attack = 0.05, release = 0.25),
        real("clarinet", "클라리넷", sustained = true, voices = 1, mono = true, min = 50, max = 89, gain = 2.32, attack = 0.05, release = 0.25),
        real("oboe", "오보에", sustained = true, voices = 1, mono = true, min = 58, max = 89, gain = 2.87, attack = 0.05, release = 0.25),
        real("trumpet", "트럼펫", sustained = true, voices = 1, mono = true, min = 52, max = 84, gain = 2.75, attack = 0.04, release = 0.25),
        real("glock", "글로켄슈필", sustained = false, voices = 4, min = 79, max = 108, gain = 4.1, attack = 0.002, release = 0.6),
        Kind("orchperc", "오케스트라 타악기", real = true, gain = 0.66, sampleKit = SampleKit.ORCHESTRA),
        Kind("latin", "라틴 퍼커션", real = true, gain = 0.48, sampleKit = SampleKit.LATIN),
        // Synthesised.
        Kind("synthbass", "신스 베이스", voices = 2, maxMidi = 60, gain = 1.09) { SynthBassVoice() },
        Kind("synthstrings", "신스 스트링", sustained = true, voices = 5, minMidi = 43, gain = 0.99) { BowedVoice(4.5, 0.003, true, Adsr(0.35, 0.6, 0.85, 0.7)) },
        Kind("epiano", "전자 피아노", voices = 6, gain = 0.84) { EPianoVoice() },
        Kind("choir", "합창", sustained = true, voices = 4, minMidi = 48, maxMidi = 81, gain = 0.37) { ChoirVoice() },
        Kind("kalimba", "칼림바", voices = 4, minMidi = 60, maxMidi = 96, gain = 0.98) {
            PartialVoice(doubleArrayOf(1.0, 4.02), doubleArrayOf(1.0, 0.28), doubleArrayOf(0.31, 0.033))
        },
        Kind("synthpluck", "신스 플럭", voices = 4, gain = 1.09) { SynthPluckVoice() },
        Kind("synthlead", "신스 리드", voices = 2, minMidi = 55, gain = 0.93) { SynthLeadVoice() },
        Kind("musicbox", "뮤직박스", voices = 4, minMidi = 72, maxMidi = 103, gain = 0.97) {
            PartialVoice(doubleArrayOf(1.0, 3.0, 5.98), doubleArrayOf(1.0, 0.22, 0.07), doubleArrayOf(0.85, 0.28, 0.1))
        },
        Kind("glasspad", "유리 패드", sustained = true, voices = 3, minMidi = 64, maxMidi = 96, gain = 0.71) { GlassVoice() },
        Kind("timpani", "팀파니", gain = 0.75, kit = Kit.TIMPANI),
        Kind("softkit", "부드러운 드럼", gain = 1.07, kit = Kit.SOFT),
        Kind("electro", "일렉트로 비트", gain = 0.9, kit = Kit.ELECTRO),
        Kind("shaker", "셰이커", gain = 1.5, kit = Kit.SHAKER),
        Kind("none", "없음"),
    )

    private fun ids(vararg names: String) = names.map { indexOf(it) }

    /** What each stage may use, in display order: recorded instruments first, downloaded ones before "none". */
    val options: List<List<Int>> get() = optionsCache ?: buildOptions().also { optionsCache = it }
    @Volatile private var optionsCache: List<List<Int>>? = null

    private fun buildOptions(): List<List<Int>> = baseOptions.mapIndexed { stage, base ->
        val extra = cloudStages.filterValues { stage in it }.keys.sorted()
        val none = indexOf("none")
        if (base.lastOrNull() == none) base.dropLast(1) + extra + none else base + extra
    }

    private val baseOptions: List<List<Int>> by lazy {
        listOf(
            ids("cello", "pizz", "bassoon", "piano", "organ", "horn", "synthbass"),
            ids("orchperc", "latin", "timpani", "softkit", "shaker", "electro", "none"),
            ids("strings", "viola", "horn", "organ", "piano", "epiano", "choir", "synthstrings", "none"),
            ids("harp", "piano", "marimba", "xylophone", "kalimba", "synthpluck", "none"),
            ids("violin", "flute", "clarinet", "oboe", "bassoon", "trumpet", "horn", "piano", "synthlead", "none"),
            ids("glock", "xylophone", "flute", "violin", "strings", "musicbox", "glasspad", "none"),
        )
    }

    /** Ready-made ensembles for the band screen: name and one instrument per stage. */
    val PRESETS: List<Pair<String, List<String>>> = listOf(
        "실내악" to listOf("cello", "orchperc", "viola", "harp", "violin", "flute"),
        "오케스트라" to listOf("pizz", "timpani", "strings", "harp", "horn", "glock"),
        "피아노 트리오" to listOf("cello", "shaker", "piano", "piano", "violin", "none"),
        "목관 앙상블" to listOf("bassoon", "orchperc", "horn", "marimba", "clarinet", "flute"),
        "라틴 카페" to listOf("pizz", "latin", "piano", "marimba", "trumpet", "xylophone"),
        "일렉트로닉" to listOf("synthbass", "electro", "synthstrings", "synthpluck", "synthlead", "glasspad"),
    )

    /** The band a listener starts from: a small chamber orchestra. */
    val BAND_DEFAULT = listOf("cello", "orchperc", "strings", "harp", "violin", "flute")

    fun indexOf(id: String): Int = all.indexOfFirst { it.id == id }.also { require(it >= 0) { "unknown instrument $id" } }

    internal fun build(kind: Int, stage: Int): Part? {
        val k = all[kind]
        if (k.kit >= 0) return Kit(k.kit)
        if (k.sampleKit >= 0) return SampleKit(k.sampleKit)
        val make = k.voice ?: return null
        val voices = Array(k.voices) { make().also { it.bendable = stage == MELODY } }
        val spread = when (stage) { HARMONY -> 0.32; HIGH -> 0.45; ARP -> 0.0; else -> 0.0 }
        return Poly(voices, k.mono, spread)
    }
}

/** Vehicle-driven controls every instrument reads per sample. */
internal class Controls {
    var speed = 0.0
    var accelerator = 0.0
    var brake = 0.0
    /** Steering pitch ratio; only the melody follows it. */
    var bend = 1.0
}

internal fun frac(x: Double): Double = x - floor(x)
internal fun midiHz(midi: Int): Double = 440.0 * 2.0.pow((midi - 69) / 12.0)

/** Table sine: sin(2π·phase) for phase in [0, 1). */
internal object Wave {
    private const val SIZE = 4096
    private val table = DoubleArray(SIZE + 1) { kotlin.math.sin(2.0 * PI * it / SIZE) }
    fun sin(phase: Double): Double {
        val x = phase * SIZE
        val i = x.toInt().coerceIn(0, SIZE - 1)
        return table[i] + (table[i + 1] - table[i]) * (x - i)
    }
}

/** Linear attack, exponential decay and release. [restart] starts from silence; [legato] keeps the level. */
internal class Adsr(attack: Double, decay: Double, private val sustain: Double, release: Double) {
    private val attackStep = 1.0 / (attack.coerceAtLeast(0.001) * Dsp.RATE)
    private val decayMul = Dsp.decay(decay)
    private val releaseMul = Dsp.decay(release)
    private var stage = 3 // 0 attack, 1 decay/sustain, 2 release, 3 idle
    var level = 0.0; private set
    val idle get() = stage == 3
    fun restart() { level = 0.0; stage = 0 }
    fun legato() { if (stage >= 2) stage = 0 }
    fun release() { if (stage < 2) stage = 2 }
    fun next(): Double {
        when (stage) {
            0 -> { level += attackStep; if (level >= 1.0) { level = 1.0; stage = 1 } }
            1 -> { level = sustain + (level - sustain) * decayMul; if (sustain == 0.0 && level < 1e-4) { level = 0.0; stage = 3 } }
            2 -> { level *= releaseMul; if (level < 1e-4) { level = 0.0; stage = 3 } }
        }
        return level
    }
}

/** One note of an instrument. */
internal abstract class Voice(private val env: Adsr) {
    protected var hz = 440.0
    private var target = 440.0
    private var velocity = 1.0
    private var gate = 0
    protected var age = 0.0
    var bendable = false
    var last = 0.0; private set
    val idle get() = env.idle

    fun start(frequency: Double, gateSamples: Int, velocity: Double, legato: Boolean) {
        this.velocity = velocity
        gate = gateSamples
        target = frequency
        if (legato && !env.idle) { env.legato(); legatoTo(frequency) }
        else { hz = frequency; age = 0.0; env.restart(); reset() }
    }

    protected open fun reset() {}
    /** A tied-on note in a mono line ([hz] glides there); a sampled voice may move to a closer recording. */
    protected open fun legatoTo(frequency: Double) {}
    protected abstract fun wave(c: Controls): Double
    protected fun pitch(c: Controls) = if (bendable) hz * c.bend else hz

    fun next(c: Controls): Double {
        if (env.idle) { last = 0.0; return 0.0 }
        if (gate > 0 && --gate == 0) env.release()
        if (hz != target) hz += (target - hz) * GLIDE
        val value = wave(c) * env.next() * velocity
        age += FRAME
        last = value
        return value
    }

    companion object {
        const val FRAME = 1.0 / SoundRenderer.RATE
        private val GLIDE = 1.0 - Dsp.decay(0.035)
    }
}

/** Something a stage plays through: stereo out after [render]. */
internal interface Part {
    val left: Double
    val right: Double
    fun render(c: Controls)
}

/**
 * A few voices of one instrument. A stolen voice restarts from silence and its cut value fades
 * out over ~3 ms, so voice stealing cannot click. Mono instruments glide from note to note.
 */
internal class Poly(private val voices: Array<Voice>, private val mono: Boolean, spread: Double) : Part {
    private val pans = DoubleArray(voices.size) { if (voices.size == 1) 0.0 else spread * (2.0 * it / (voices.size - 1) - 1.0) }
    private var cursor = 0
    private var declick = 0.0
    override var left = 0.0; private set
    override var right = 0.0; private set

    fun play(hz: Double, gateSamples: Int, velocity: Double, pan: Double? = null) {
        if (mono) { voices[0].start(hz, gateSamples, velocity, legato = true); return }
        var pick = -1
        for (k in voices.indices) {
            val i = (cursor + k) % voices.size
            if (voices[i].idle) { pick = i; break }
        }
        if (pick < 0) { pick = cursor; declick += voices[pick].last }
        cursor = (pick + 1) % voices.size
        if (pan != null) pans[pick] = pan
        voices[pick].start(hz, gateSamples, velocity, legato = false)
    }

    override fun render(c: Controls) {
        var l = declick
        var r = declick
        declick *= DECLICK
        for (i in voices.indices) {
            val v = voices[i].next(c)
            if (v != 0.0) { l += v * (1.0 - pans[i]); r += v * (1.0 + pans[i]) }
        }
        left = l; right = r
    }

    private companion object { val DECLICK = Dsp.decay(0.003) }
}

// ---------------------------------------------------------------- voices

internal class SynthBassVoice : Voice(Adsr(0.005, 0.8, 0.4, 0.1)) {
    private var p = 0.0
    override fun reset() { p = 0.0 }
    override fun wave(c: Controls): Double {
        p = frac(p + hz / Dsp.RATE)
        return Wave.sin(p) * (0.85 - c.speed * 0.2) + Wave.sin(frac(p * 2)) * (0.12 + c.speed * 0.3 + c.accelerator * 0.12) +
            Wave.sin(frac(p * 3)) * c.speed * 0.06
    }
}

/** Bowed string: saw (or two detuned saws for a section) through a low-pass that opens with speed. */
internal class BowedVoice(
    private val brightness: Double,
    private val vibratoDepth: Double,
    private val ensemble: Boolean,
    env: Adsr,
) : Voice(env) {
    private val a = Osc()
    private val b = Osc()
    private val filter = Svf()
    private var vibrato = 0.0
    override fun reset() { a.phase = 0.0; b.phase = 0.37; vibrato = 0.0 }
    override fun wave(c: Controls): Double {
        vibrato = frac(vibrato + 5.4 / Dsp.RATE)
        val depth = vibratoDepth * ((age - 0.2) / 0.5).coerceIn(0.0, 1.0)
        val f = pitch(c) * (1.0 + Wave.sin(vibrato) * depth)
        var s = a.saw(f)
        if (ensemble) s = (s + b.saw(f * 1.0045)) * 0.6
        filter.set(f * brightness * (1.0 + 0.5 * c.speed) * (1.0 - 0.35 * c.brake) + 250.0, 0.75)
        return filter.process(s)
    }
}

/** Clarinet-like: square wave (odd harmonics) through a gentle low-pass. */
internal class ReedVoice : Voice(Adsr(0.035, 0.3, 0.85, 0.12)) {
    private val osc = Osc()
    private val filter = Svf()
    private var vibrato = 0.0
    override fun reset() { osc.phase = 0.0 }
    override fun wave(c: Controls): Double {
        vibrato = frac(vibrato + 4.8 / Dsp.RATE)
        val f = pitch(c) * (1.0 + Wave.sin(vibrato) * 0.003 * ((age - 0.3) / 0.4).coerceIn(0.0, 1.0))
        filter.set(f * (3.0 + 1.5 * c.speed) + 300.0, 0.7)
        return filter.process(osc.square(f))
    }
}

internal class FluteVoice : Voice(Adsr(0.06, 0.6, 0.8, 0.22)) {
    private var p = 0.0
    private var vibrato = 0.0
    private val noise = Noise(0x3F1L)
    private val breath = Svf().apply { set(2400.0, 0.9) }
    override fun wave(c: Controls): Double {
        vibrato = frac(vibrato + 5.0 / Dsp.RATE)
        val f = pitch(c) * (1.0 + Wave.sin(vibrato) * 0.005 * ((age - 0.3) / 0.4).coerceIn(0.0, 1.0))
        p = frac(p + f / Dsp.RATE)
        breath.process(noise.next())
        return Wave.sin(p) + Wave.sin(frac(p * 2)) * 0.12 + breath.band * 0.18
    }
}

/** "Ah" voices: two detuned saws through two vowel formants. */
internal class ChoirVoice : Voice(Adsr(0.45, 0.8, 0.9, 0.9)) {
    private val a = Osc()
    private val b = Osc()
    private val f1 = Svf().apply { set(700.0, 5.0) }
    private val f2 = Svf().apply { set(1150.0, 6.0) }
    private var vibrato = 0.0
    override fun reset() { a.phase = 0.0; b.phase = 0.5 }
    override fun wave(c: Controls): Double {
        vibrato = frac(vibrato + 4.6 / Dsp.RATE)
        val f = pitch(c) * (1.0 + Wave.sin(vibrato) * 0.004)
        val s = a.saw(f) + b.saw(f * 1.006)
        f1.process(s); f2.process(s)
        return f1.band * 0.9 + f2.band * 0.6
    }
}

internal class OrganVoice : Voice(Adsr(0.012, 0.3, 0.9, 0.09)) {
    private var p = 0.0
    private var tremolo = 0.0
    override fun reset() { p = 0.0 }
    override fun wave(c: Controls): Double {
        p = frac(p + pitch(c) / Dsp.RATE)
        tremolo = frac(tremolo + 5.8 / Dsp.RATE)
        val body = Wave.sin(p) + Wave.sin(frac(p * 2)) * 0.6 + Wave.sin(frac(p * 3)) * 0.4 +
            Wave.sin(frac(p * 4)) * (0.2 + 0.15 * c.speed) + Wave.sin(frac(p * 6)) * 0.1 * c.speed
        return body * (1.0 + Wave.sin(tremolo) * 0.06)
    }
}

/** Two-operator FM electric piano: the "tine" fades after each strike and grows with speed. */
internal class EPianoVoice : Voice(Adsr(0.003, 1.6, 0.0, 0.35)) {
    private var carrier = 0.0
    private var modulator = 0.0
    private var tine = 1.0
    private val tineMul = Dsp.decay(1.0 / 6.0)
    override fun reset() { carrier = 0.0; modulator = 0.0; tine = 1.0 }
    override fun wave(c: Controls): Double {
        val f = pitch(c)
        carrier = frac(carrier + f / Dsp.RATE)
        modulator = frac(modulator + f / Dsp.RATE)
        tine *= tineMul
        val index = ((0.35 + 1.1 * c.speed) * tine + 0.12) * (1.0 - 0.45 * c.brake)
        return Wave.sin(frac(carrier + index * Wave.sin(modulator) / (2.0 * PI)))
    }
}

/** Piano: slightly stretched partials, higher ones dying faster, lower notes ringing longer. */
internal class PianoVoice : Voice(Adsr(0.002, 8.0, 0.0, 0.22)) {
    private val phase = DoubleArray(6)
    private val amp = DoubleArray(6)
    private val mul = DoubleArray(6)
    private val ratio = DoubleArray(6)
    override fun reset() {
        for (k in 0 until 6) {
            val n = k + 1.0
            ratio[k] = n * sqrt(1.0 + 0.00035 * n * n)
            amp[k] = if (hz * ratio[k] > 16000.0) 0.0 else (1.0 / n).pow(1.15) * (if (k >= 2) 1.3 else 1.0)
            mul[k] = Dsp.decay((2.6 / (0.6 + 0.55 * n) * (262.0 / hz).pow(0.35)).coerceIn(0.15, 6.0))
            phase[k] = 0.0
        }
    }
    override fun wave(c: Controls): Double {
        var s = 0.0
        val f = pitch(c)
        for (k in 0 until 6) if (amp[k] > 1e-4) {
            phase[k] = frac(phase[k] + f * ratio[k] / Dsp.RATE)
            s += Wave.sin(phase[k]) * amp[k]
            amp[k] *= mul[k]
        }
        return s * 0.62
    }
}

/** Struck/bell instruments: a few partials, each with its own decay. */
internal class PartialVoice(
    private val ratios: DoubleArray,
    private val levels: DoubleArray,
    decays: DoubleArray,
) : Voice(Adsr(0.0015, 30.0, 0.0, 0.3)) {
    private val phase = DoubleArray(ratios.size)
    private val amp = DoubleArray(ratios.size)
    private val mul = DoubleArray(ratios.size) { Dsp.decay(decays[it]) }
    override fun reset() {
        for (k in ratios.indices) { phase[k] = 0.0; amp[k] = if (hz * ratios[k] > 17000.0) 0.0 else levels[k] }
    }
    override fun wave(c: Controls): Double {
        var s = 0.0
        val f = pitch(c)
        for (k in ratios.indices) if (amp[k] > 1e-4) {
            phase[k] = frac(phase[k] + f * ratios[k] / Dsp.RATE)
            s += Wave.sin(phase[k]) * amp[k]
            amp[k] *= mul[k]
        }
        return s
    }
}

internal class GlassVoice : Voice(Adsr(0.7, 1.0, 0.9, 1.4)) {
    private var a = 0.0
    private var b = 0.0
    override fun wave(c: Controls): Double {
        val f = pitch(c)
        a = frac(a + f / Dsp.RATE)
        b = frac(b + f * 2.003 / Dsp.RATE)
        return Wave.sin(a) + Wave.sin(b) * 0.15
    }
}

internal class SynthPluckVoice : Voice(Adsr(0.002, 0.35, 0.0, 0.1)) {
    private val osc = Osc()
    private val filter = Svf()
    private var sweep = 1.0
    private val sweepMul = Dsp.decay(0.05)
    override fun reset() { osc.phase = 0.0; sweep = 1.0 }
    override fun wave(c: Controls): Double {
        val f = pitch(c)
        sweep *= sweepMul
        filter.set(f * (1.2 + 8.0 * sweep) * (0.7 + 0.6 * c.speed), 1.1)
        return filter.process(osc.saw(f))
    }
}

internal class SynthLeadVoice : Voice(Adsr(0.005, 0.5, 0.45, 0.2)) {
    private var p = 0.0
    override fun reset() { p = 0.0 }
    override fun wave(c: Controls): Double {
        p = frac(p + pitch(c) / Dsp.RATE)
        return Wave.sin(p) + Wave.sin(frac(p * 2)) * (0.18 + 0.2 * c.speed)
    }
}

/**
 * Plucked string (Karplus-Strong) for harp and pizzicato. The excitation is a smooth pluck shape
 * plus a little low-passed noise ([brightness]), so the attack does not crack.
 */
internal class PluckVoice(private val ringSeconds: Double, private val brightness: Double) : Voice(Adsr(0.002, 30.0, 1.0, 0.25)) {
    private val buffer = DoubleArray(4096)
    private var write = 0
    private var delay = 100.0
    private var feedback = 0.99
    private val noise = Noise(0x9E37L)

    override fun reset() {
        delay = (Dsp.RATE / hz - 0.5).coerceIn(2.0, 4000.0)
        feedback = 10.0.pow(-3.0 / (ringSeconds * hz))
        val n = delay.toInt() + 2
        var smooth = 0.0
        write = 0
        java.util.Arrays.fill(buffer, 0.0)
        for (i in 0 until n) {
            val x = i.toDouble() / n
            val shape = if (x < 0.25) x / 0.25 else (1.0 - x) / 0.75
            smooth += (noise.next() - smooth) * 0.35
            buffer[(4096 - n + i) and 4095] = (shape * (1.0 - brightness) + smooth * brightness * 1.6) * 0.8
        }
    }

    private fun read(back: Double): Double {
        val pos = write - back
        val i = floor(pos).toInt()
        val f = pos - i
        val a = buffer[i and 4095]
        val b = buffer[(i + 1) and 4095]
        return a + (b - a) * f
    }

    override fun wave(c: Controls): Double {
        val y = feedback * 0.5 * (read(delay) + read(delay + 1.0))
        buffer[write] = y
        write = (write + 1) and 4095
        return y
    }
}

// ---------------------------------------------------------------- rhythm

/** Rhythm sections that follow the bar length (4/4 or 3/4) and the drum level from speed. */
internal class Kit(private val style: Int) : Part {
    companion object { const val TIMPANI = 0; const val SOFT = 1; const val ELECTRO = 2; const val SHAKER = 3 }

    override var left = 0.0; private set
    override var right = 0.0; private set
    private val noise = Noise(0x5A17L + style)
    private var kickAge = 10.0; private var kickPhase = 0.0; private var kickLevel = 1.0
    private var timpAge = 10.0; private var timpPhase = 0.0; private var timpHz = 73.4; private var timpLevel = 1.0
    private var timpLast = 0.0; private var timpDeclick = 0.0
    private var snareAge = 10.0; private var snareLevel = 1.0
    private var rimAge = 10.0
    private var hatAge = 10.0; private var hatLevel = 1.0
    private var shakerAge = 10.0; private var shakerLevel = 1.0; private var shakerDelay = 0
    private val snareFilter = Svf().apply { set(1800.0, 0.9) }
    private val rimFilter = Svf().apply { set(1900.0, 3.0) }
    private val hatFilter = Svf().apply { set(7500.0, 0.9) }
    private val shakerFilter = Svf().apply { set(6200.0, 1.1) }
    private val thumpFilter = Svf().apply { set(180.0, 0.7) }

    fun step(inBar: Int, stepsPerBar: Int, bar: Long, level: Int, wiper: Boolean, tonicHz: Double, stepSamples: Int) {
        val beats = stepsPerBar / 4
        val onBeat = inBar % 4 == 0
        val beat = inBar / 4
        val backbeat = onBeat && (if (beats == 3) beat > 0 else beat % 2 == 1)
        when (style) {
            TIMPANI -> {
                if (inBar == 0) timp(tonicHz, 1.0)
                else if (beats == 4 && inBar == 8 && level >= 1) timp(if (tonicHz > 90.0) tonicHz * 0.75 else tonicHz * 1.5, 0.7)
                if (level >= 2 && bar % 4 == 3L && inBar >= stepsPerBar - 4) timp(tonicHz, 0.3 + 0.12 * (inBar - stepsPerBar + 4))
            }
            SOFT -> {
                if (inBar == 0 || (beats == 4 && inBar == 8)) kick(0.9)
                if (backbeat) rimAge = 0.0
                shakerGroove(inBar, stepSamples)
            }
            ELECTRO -> {
                if (inBar == 0 || (onBeat && level >= 1 && beats == 4) || (beats == 4 && inBar == 8)) kick(1.0)
                if (onBeat && (if (beats == 3) beat == 2 else beat % 2 == 1)) { snareAge = 0.0; snareLevel = 0.8 }
                if (inBar % 4 == 2) { hatAge = 0.0; hatLevel = 1.0 }
                else if (level >= 2 && inBar % 2 == 1) { hatAge = 0.0; hatLevel = 0.45 }
            }
            SHAKER -> {
                if (inBar == 0) kick(0.6)
                shakerGroove(inBar, stepSamples)
                if (level >= 1 && backbeat) { hatAge = 0.0; hatLevel = 0.8 }
            }
        }
        if (wiper && inBar % 2 == 1) { shakerAge = 0.0; shakerLevel = 0.5 }
    }

    private fun shakerGroove(inBar: Int, stepSamples: Int) {
        if (inBar % 4 == 0) { shakerAge = 0.0; shakerLevel = 0.55 }
        if (inBar % 4 == 2) { shakerDelay = (stepSamples * 0.35).toInt().coerceAtLeast(1); shakerLevel = 1.0 }
    }

    private fun kick(level: Double) { kickAge = 0.0; kickPhase = 0.0; kickLevel = level }
    private fun timp(hz: Double, level: Double) {
        timpDeclick += timpLast
        timpAge = 0.0; timpPhase = 0.0; timpHz = hz; timpLevel = level
    }

    override fun render(c: Controls) {
        val frame = Voice.FRAME
        val n = noise.next()
        var mono = 0.0
        if (kickAge < 0.7) {
            val punch = if (style == ELECTRO) 49.0 + 80.0 * exp(-kickAge * 24.0) else 52.0 + 40.0 * exp(-kickAge * 28.0)
            kickPhase += 2.0 * PI * punch * frame
            mono += sin(kickPhase) * exp(-kickAge * (if (style == ELECTRO) 10.0 else 6.0)) * 0.20 * kickLevel
        }
        var timp = 0.0
        // The thump filter keeps running between strokes so a new stroke never starts from a stale value.
        thumpFilter.process(n)
        if (timpAge < 2.0) {
            timpPhase = frac(timpPhase + timpHz * (1.0 + 0.04 * exp(-timpAge * 20.0)) * frame)
            timp = (Wave.sin(timpPhase) + Wave.sin(frac(timpPhase * 1.505)) * 0.35 + Wave.sin(frac(timpPhase * 1.99)) * 0.12) *
                exp(-timpAge * 2.2) * (1.0 - exp(-timpAge * 700.0)) * 0.17 * timpLevel +
                thumpFilter.low * exp(-timpAge * 35.0) * (1.0 - exp(-timpAge * 700.0)) * 0.5 * timpLevel
        }
        timpLast = timp
        mono += timp + timpDeclick
        timpDeclick *= 0.993
        snareFilter.process(n)
        if (snareAge < 0.35) mono += (snareFilter.band * 1.2 + sin(2.0 * PI * 190.0 * snareAge) * 0.4) *
            exp(-snareAge * 22.0) * 0.07 * snareLevel
        rimFilter.process(n)
        if (rimAge < 0.12) mono += (rimFilter.band * 0.8 + sin(2.0 * PI * 820.0 * rimAge) * 0.35) * exp(-rimAge * 55.0) * 0.07
        hatFilter.process(n)
        val hat = if (hatAge < 0.2) hatFilter.high * exp(-hatAge * (if (style == SHAKER) 30.0 else 70.0)) * 0.028 * hatLevel else 0.0
        if (shakerDelay > 0 && --shakerDelay == 0) shakerAge = 0.0
        shakerFilter.process(n)
        val shaker = if (shakerAge < 0.15) shakerFilter.band * (1.0 - exp(-shakerAge * 400.0)) * exp(-shakerAge * 28.0) *
            shakerLevel * 0.10 else 0.0
        left = mono + hat * 0.8 + shaker * 0.85
        right = mono + hat * 1.0 + shaker * 1.0
        kickAge += frame; timpAge += frame; snareAge += frame; rimAge += frame; hatAge += frame; shakerAge += frame
    }
}
