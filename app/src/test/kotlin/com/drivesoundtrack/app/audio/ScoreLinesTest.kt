package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.sqrt

/**
 * A score's own drum line and its bar-by-bar arpeggio and accompaniment patterns (0.14): every rhythm section
 * plays the written hits and nothing else, two lines sound different, the patterns change where the score says,
 * and a score without them plays as before.
 */
class ScoreLinesTest {
    private val rate = 44100
    private val bpm = 90.0

    private fun piece(drums: String? = null, arpLine: String? = null, compLine: String? = null) = Piece(
        "Lines", "test", 4, 0, Score.chords("C:16 | F:16 | G:16 | C:16", 16), Score.notes("C5:16 | A4:16 | B4:16 | C5:16", 16),
        BassLine.HALVES, emptyList(), Score.pattern("0 . 1 . 2 . 1 . 0 . 1 . 2 . 1 ."), Score.hits("x . . . x . . . x . . . x . . ."),
        HighLine.BELLS, 0, listOf("cello", "softkit", "piano", "harp", "violin", "glock"),
        drumLine = drums?.let { Score.drums(it, 16) }, arpLine = arpLine?.let { Score.patternLine(it, 16) },
        compLine = compLine?.let { Score.hitsLine(it, 16) },
    )

    /** [stage] of [p] alone with instrument [kind], mono, for [bars] bars at drum level [level]. */
    private fun render(p: Piece, stage: Int, kind: Int, bars: Int, level: Int = 2, notes: MutableList<IntArray>? = null): DoubleArray {
        val engine = PieceEngine(p)
        engine.setOrchestration(IntArray(6) { Instruments.indexOf(p.defaults[it]) }.also { it[stage] = kind })
        if (notes != null) engine.onNote = { st, midi, steps -> notes += intArrayOf(st, midi, steps) }
        val mixes = DoubleArray(6).also { it[stage] = 1.0 }
        val scene = classical(p, "", 0, bpm)
        val stepSamples = (60.0 / bpm / 4.0 * rate).toInt()
        val out = DoubleArray(bars * 16 * stepSamples)
        var i = 0
        for (step in 0 until bars * 16) {
            if (notes != null) notes += intArrayOf(-1, step, 0) // a step marker
            engine.step(step % 16, (step / 16).toLong(), 0, intArrayOf(0, 4, 7, 12, 16), scene, level, bpm, false)
            repeat(stepSamples) { engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm); out[i++] = (engine.left + engine.right) * 0.5 }
        }
        return out
    }

    private fun rms(x: DoubleArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / (to - from))
    }

    private val kits = listOf("orchperc", "latin", "timpani", "softkit", "shaker", "electro")

    @Test
    fun eachRhythmSectionPlaysTheWrittenHitsOnly() {
        val stepSamples = (60.0 / bpm / 4.0 * rate).toInt()
        for (id in kits) {
            val kind = Instruments.indexOf(id)
            val silent = render(piece(drums = ". . . . . . . . . . . . . . . ."), 1, kind, 2)
            val own = render(piece(), 1, kind, 2)
            check(rms(silent) < rms(own) * 0.02) { "$id: an empty drum line still plays (${rms(silent)} vs ${rms(own)})" }
            // One low hit at the start of each bar: the sound sits in the bar's first beat.
            val ones = render(piece(drums = "K . . . . . . . . . . . . . . ."), 1, kind, 4)
            var first = 0.0; var rest = 0.0
            for (bar in 0 until 4) {
                val b = bar * 16 * stepSamples
                first += rms(ones, b, b + 4 * stepSamples)
                rest += rms(ones, b + 8 * stepSamples, b + 16 * stepSamples)
            }
            check(first > 0.0 && rest < first * 0.25) { "$id: a hit on one is not where it was written (first beat $first, second half $rest)" }
            println("$id: own groove rms ${"%.4f".format(rms(own))}, one hit per bar first/rest ${"%.4f".format(first)}/${"%.4f".format(rest)}")
        }
    }

    @Test
    fun twoDrumLinesSoundDifferent() {
        for (id in kits) {
            val kind = Instruments.indexOf(id)
            val a = render(piece(drums = "K . . . S . . . K . . . S . . ."), 1, kind, 2)
            val b = render(piece(drums = "H . H . H . H . H . H . H . H A"), 1, kind, 2)
            // Compare loudness beat by beat: the two grooves must differ in where the sound is.
            val beat = a.size / 8
            val ea = DoubleArray(8) { rms(a, it * beat, (it + 1) * beat) }
            val eb = DoubleArray(8) { rms(b, it * beat, (it + 1) * beat) }
            val na = ea.sum(); val nb = eb.sum()
            val diff = (0 until 8).sumOf { kotlin.math.abs(ea[it] / na - eb[it] / nb) } + kotlin.math.abs(na - nb) / (na + nb)
            check(diff > 0.05) { "$id: two different drum lines sound alike ($diff)" }
        }
    }

    @Test
    fun patternsChangeBarByBar() {
        // Arpeggio: bar 1 plays every beat, bar 2 only once; the line tiles, so bars 3-4 repeat 1-2.
        val notes = mutableListOf<IntArray>()
        render(piece(arpLine = "0 . . . 1 . . . 2 . . . 1 . . . | 0 . . . . . . . . . . . . . . ."), 3, Instruments.indexOf("harp"), 4, notes = notes)
        val perBar = IntArray(4)
        var step = 0
        for (n in notes) if (n[0] == -1) step = n[1] else if (n[0] == Instruments.ARP) perBar[step / 16]++
        check(perBar.toList() == listOf(4, 1, 4, 1)) { "arpeggio notes per bar ${perBar.toList()}" }
        // Accompaniment: a struck instrument hits where each bar says.
        val hits = mutableListOf<IntArray>()
        render(piece(compLine = "x . . . . . . . . . . . . . . . | x . . . x . . . x . . . x . . ."), 2, Instruments.indexOf("piano"), 2, notes = hits)
        val strikes = IntArray(2)
        var at = 0
        var last = -1
        for (n in hits) if (n[0] == -1) at = n[1] else if (n[0] == Instruments.HARMONY && at != last) { strikes[at / 16]++; last = at }
        check(strikes.toList() == listOf(1, 4)) { "accompaniment strikes per bar ${strikes.toList()}" }
    }

    @Test
    fun scoresWithoutLinesPlayAsBefore() {
        val p = piece()
        for (s in 0 until p.lengthSteps) {
            check(p.drumsAt(s) == null)
            check(p.arpAt(s) == p.arp[s % 16] && p.compAt(s) == p.comp[s % 16])
        }
    }

    @Test
    fun badLinesAreRejected() {
        fun fails(block: () -> Unit) = try { block(); false } catch (_: Exception) { true }
        check(fails { Score.drums("K . . . | H", 16) }) { "half a bar" }
        check(fails { Score.drums("K . X . . . . . . . . . . . . .", 16) }) { "unknown letter" }
        check(fails { piece(drums = "K . . . . . . . . . . . . . . . | . . . . . . . . . . . . . . . . | H . . . . . . . . . . . . . . .") }) { "3 bars do not tile 4" }
        check(Score.drums("KH . SA .", 4).toList() == listOf(Score.DRUM_LOW or Score.DRUM_HIGH, 0, Score.DRUM_BACK or Score.DRUM_ACCENT, 0))
    }
}
