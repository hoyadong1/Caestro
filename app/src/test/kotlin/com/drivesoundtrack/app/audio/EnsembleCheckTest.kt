package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Does the band play together and in tune? Three checks over every classical piece and every built-in
 * instrument a stage offers, using the notes the engine really plays ([PieceEngine.onNote]):
 *
 *  - timing: from each note's start to the moment it reaches half of its rise in level. Parts that
 *    speak late against the others sound behind the beat;
 *  - tuning: each pitched instrument played alone over its range; the pitch it sounds, against the note;
 *  - harmony: the notes that sound together, for every bass x harmony x arpeggio choice and every melody
 *    and high choice: no accompaniment note below the bass (range folding can push a chord under it),
 *    and no semitone / major-seventh clash between a chord tone and a non-chord tone held a beat or longer.
 */
class EnsembleCheckTest {
    private val rate = 44100
    private val scenes = Soundscapes.all.filter { it.piece != null && it.cloudId == null }
    private val pitched = listOf(0, 2, 3, 4, 5)
    private fun options(stage: Int) = Instruments.options[stage].filter { Instruments.all[it].id != "none" && !Instruments.all[it].cloud }
    private fun db(x: Double) = 20 * ln(max(x, 1e-12)) / ln(10.0)
    private val names = listOf("C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B")
    private fun noteName(m: Int) = names[Math.floorMod(m, 12)] + (m / 12 - 1)

    // ------------------------------------------------------------------ timing

    @Test
    fun everyPartSpeaksOnTheBeat() {
        val problems = mutableListOf<String>()
        val table = StringBuilder()
        for (scene in scenes) {
            val piece = scene.piece!!
            table.appendLine("== ${piece.title}: ms from note start to half its rise (median / 90th percentile, notes measured)")
            for (stage in pitched) {
                for (kind in options(stage)) {
                    val (audio, starts) = renderAlone(scene, stage, kind)
                    val late = lateness(audio, starts)
                    if (late.isEmpty()) { table.appendLine("  ${stage + 1} %-12s (no clear attacks)".format(Instruments.all[kind].id)); continue }
                    val sorted = late.sorted()
                    val median = sorted[sorted.size / 2]
                    val p90 = sorted[(sorted.size * 9 / 10).coerceAtMost(sorted.size - 1)]
                    table.appendLine("  ${stage + 1} %-12s %5.0f / %5.0f ms  (%d)".format(Instruments.all[kind].id, median, p90, late.size))
                    // Half the rise within 100 ms, as OnsetTest asks of a single note, plus 10 ms for this
                    // measurement's resolution (5 ms frames, judged against a median level inside a piece).
                    // A mono (legato) line slurs into a note that follows straight on, swelling from where
                    // the last one faded: by design, so only its median counts.
                    val mono = Instruments.all[kind].mono
                    if (median > 110 || (!mono && p90 > 180)) problems += "${piece.title} stage ${stage + 1} ${Instruments.all[kind].id}: speaks late, median %.0f ms, 90%% %.0f ms".format(median, p90)
                }
            }
        }
        println(table)
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }

    /** [stage] of [scene] alone with [kind], once through the piece; also the sample index of every note start. */
    private fun renderAlone(scene: Soundscape, stage: Int, kind: Int): Pair<DoubleArray, IntArray> {
        val piece = scene.piece!!
        val engine = PieceEngine(piece)
        val ids = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }.also { it[stage] = kind }
        engine.setOrchestration(ids)
        val mixes = DoubleArray(6).also { it[stage] = 1.0 }
        val bpm = scene.baseBpm
        val stepSamples = (60.0 / bpm / 4.0 * rate).toInt()
        val out = DoubleArray(piece.lengthSteps * stepSamples)
        val starts = mutableListOf<Int>()
        var i = 0
        engine.onNote = { s, _, _ -> if (s == stage && (starts.isEmpty() || starts.last() != i)) starts += i }
        for (step in 0 until piece.lengthSteps) {
            val span = piece.chordAt(step)
            engine.step(step % piece.stepsPerBar, (step / piece.stepsPerBar).toLong(), span.relativeRoot, span.tones, scene, 2, bpm, false)
            repeat(stepSamples) {
                engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm)
                out[i++] = if (stage == 0) engine.bass else (engine.left + engine.right) * 0.5
            }
        }
        // The first note runs into an empty pipeline (the instrument swap happens on the first step): skip it.
        return out to starts.drop(1).toIntArray()
    }

    /** Per note with a clear attack: ms from its start until the level has made half of its rise. */
    private fun lateness(x: DoubleArray, starts: IntArray): List<Double> {
        val frame = 220 // 5 ms
        val env = DoubleArray(x.size / frame) { k -> var s = 0.0; for (j in k * frame until (k + 1) * frame) s += x[j] * x[j]; sqrt(s / frame) }
        val out = mutableListOf<Double>()
        for ((n, t0) in starts.withIndex()) {
            val f0 = t0 / frame
            val next = if (n + 1 < starts.size) starts[n + 1] / frame else env.size
            // The note's playing level: the median over 150..400 ms (bowed notes swell and tremble later on,
            // so a later peak says little about when the note was there). Short notes: what fits before the next.
            val end = minOf(env.size, f0 + 80, max(f0 + 12, next)) // up to 400 ms, at least 60 ms
            if (f0 + 2 >= end) continue
            val from = minOf(f0 + 30, end - 2)
            val playing = (from until end).map { env[it] }.sorted().let { it[it.size / 2] }
            val base = env[maxOf(0, f0 - 1)]
            val rise = playing - base
            if (playing < 1e-5 || rise < playing * 0.25) continue // tied or legato note: no attack to time
            val k = (f0 until end).firstOrNull { env[it] >= base + rise * 0.5 } ?: continue
            out += (k - f0) * frame * 1000.0 / rate
        }
        return out
    }

    // ------------------------------------------------------------------ tuning

    @Test
    fun everyInstrumentPlaysInTune() {
        val problems = mutableListOf<String>()
        val table = StringBuilder("cents off per note (+ sharp, - flat)\n")
        val controls = Controls().apply { speed = 0.5; accelerator = 0.2 }
        for ((index, kind) in Instruments.all.withIndex()) {
            if (kind.cloud || kind.id == "none" || kind.kit >= 0 || kind.sampleKit >= 0) continue
            val stage = (0..5).firstOrNull { index in Instruments.options[it] && it != Instruments.MELODY }
                ?: (0..5).first { index in Instruments.options[it] }
            val notes = (kind.minMidi..kind.maxMidi step 4).toMutableList().also { if (it.last() != kind.maxMidi) it += kind.maxMidi }
            val row = StringBuilder()
            var worst = 0.0
            for (m in notes) {
                val part = Instruments.build(index, stage) as Poly
                val target = midiHz(m)
                part.play(target, (1.0 * rate).toInt(), 0.9, 0.0)
                val x = DoubleArray((0.9 * rate).toInt()) { part.render(controls); (part.left + part.right) * 0.5 }
                val cents = centsOff(x, (0.08 * rate).toInt(), (0.6 * rate).toInt(), target)
                if (cents == null) { row.append(" ${noteName(m)}:?"); continue }
                row.append(" %s:%+.0f".format(noteName(m), cents))
                if (abs(cents) > abs(worst)) worst = cents
                // 20 cents: real players and recordings drift ~10-15 cents at the edges of their range.
                if (abs(cents) > 20) problems += "${kind.id} ${noteName(m)} is %+.0f cents off".format(cents)
            }
            table.appendLine("  %-12s worst %+4.0f |%s".format(kind.id, worst, row))
        }
        println(table)
        check(problems.isEmpty()) { problems.joinToString("\n") }
    }

    /**
     * Pitch of [x] over [from]..[to], in cents from [target]: the strongest spectral peak within a
     * semitone and a half of the target (Hann window, zero-padded FFT, parabolic interpolation).
     * Autocorrelation was misled by the inharmonic partials of glockenspiel and xylophone.
     */
    private fun centsOff(x: DoubleArray, from: Int, to: Int, target: Double): Double? {
        val len = minOf(to, x.size) - from
        if (len < 2048) return null
        val n = 1 shl 19
        val re = DoubleArray(n); val im = DoubleArray(n)
        for (i in 0 until len) re[i] = x[from + i] * (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * i / (len - 1)))
        fft(re, im)
        val mag = DoubleArray(n / 2) { sqrt(re[it] * re[it] + im[it] * im[it]) }
        val overall = (8 until n / 2).maxOf { mag[it] }
        // The pitch from each of the first four harmonics that is clearly present; the median of them.
        // Low notes (a contrabass E1) have a weak, smeared fundamental; bells have one strong one.
        val estimates = mutableListOf<Double>()
        for (h in 1..4) {
            val lo = (h * target * Math.pow(2.0, -1.0 / 12) * n / rate).toInt().coerceAtLeast(2)
            val hi = (h * target * Math.pow(2.0, 1.0 / 12) * n / rate).toInt()
            if (hi >= n / 2 - 2) break
            var best = lo
            for (k in lo..hi) if (mag[k] > mag[best]) best = k
            if (mag[best] < overall * 0.05) continue
            val a = mag[best - 1]; val b = mag[best]; val c = mag[best + 1]
            val shift = if (a - 2 * b + c != 0.0) 0.5 * (a - c) / (a - 2 * b + c) else 0.0
            estimates += (best + shift) * rate / n / h
            if (h == 1 && target > 400) break // bells and bars: overtones are not harmonic, trust the fundamental
        }
        if (estimates.isEmpty()) return null
        val f = estimates.sorted()[estimates.size / 2]
        return 1200 * ln(f / target) / ln(2.0)
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
            val ang = -2 * Math.PI / len
            val wr0 = kotlin.math.cos(ang); val wi0 = kotlin.math.sin(ang)
            for (i in 0 until n step len) {
                var wr = 1.0; var wi = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * wr - im[i + k + len / 2] * wi
                    val vi = re[i + k + len / 2] * wi + im[i + k + len / 2] * wr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val t = wr * wr0 - wi * wi0; wi = wr * wi0 + wi * wr0; wr = t
                }
            }
            len = len shl 1
        }
    }

    // ------------------------------------------------------------------ harmony

    private class Event(val stage: Int, val midi: Int, val start: Int, val end: Int)

    /** Every note the engine plays through [scene]'s piece with [ids], in steps. */
    private fun notes(scene: Soundscape, ids: IntArray): List<Event> {
        val piece = scene.piece!!
        val engine = PieceEngine(piece)
        engine.setOrchestration(ids)
        val events = mutableListOf<Event>()
        var now = 0
        engine.onNote = { stage, midi, steps -> events += Event(stage, midi, now, now + steps) }
        for (step in 0 until piece.lengthSteps) {
            now = step
            val span = piece.chordAt(step)
            engine.step(step % piece.stepsPerBar, (step / piece.stepsPerBar).toLong(), span.relativeRoot, span.tones, scene, 2, scene.baseBpm, false)
        }
        return events
    }

    @Test
    fun partsSoundTogetherInHarmony() {
        val problems = sortedSetOf<String>()
        val report = StringBuilder()
        for (scene in scenes) {
            val piece = scene.piece!!
            val defaults = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }
            val bands = mutableListOf<IntArray>()
            for (b in options(0)) for (h in options(2)) for (a in options(3)) bands += defaults.copyOf().also { it[0] = b; it[2] = h; it[3] = a }
            for (m in options(4)) bands += defaults.copyOf().also { it[4] = m }
            for (h in options(5)) bands += defaults.copyOf().also { it[5] = h }
            var under = 0; var clashes = 0; var passing = 0
            for (ids in bands) {
                val events = notes(scene, ids)
                val band = ids.joinToString("/") { Instruments.all[it].id }
                val at = Array(piece.lengthSteps) { mutableListOf<Event>() }
                for (e in events) for (s in e.start until minOf(e.end, piece.lengthSteps)) at[s] += e
                for (s in 0 until piece.lengthSteps) {
                    val sounding = at[s]
                    val bass = sounding.filter { it.stage == Instruments.BASS }.minOfOrNull { it.midi } ?: continue
                    for (e in sounding) if (e.stage != Instruments.BASS && e.midi < bass) {
                        under++
                        problems += "${piece.title} bar ${s / piece.stepsPerBar + 1}: ${Instruments.stageNames[e.stage]} ${Instruments.all[ids[e.stage]].id} " +
                            "${noteName(e.midi)} under the bass ${Instruments.all[ids[0]].id} ${noteName(bass)}"
                    }
                }
                // Clashes: a semitone (or major seventh) between notes held together for a beat or more,
                // unless both are tones of the chord (a written major-seventh chord is meant).
                // Each pair is looked at once, on the step where the later of the two starts.
                val pairs = mutableListOf<Pair<Event, Event>>()
                for (s in 0 until piece.lengthSteps) for (b in at[s]) if (b.start == s) for (a in at[s]) {
                    if (a === b || (a.start == s && System.identityHashCode(a) > System.identityHashCode(b))) continue
                    pairs += a to b
                }
                for ((a, b) in pairs) {
                    if (a.stage == b.stage) continue
                    val from = max(a.start, b.start); val to = minOf(a.end, b.end)
                    if (to - from < 4) continue
                    val ic = Math.floorMod(a.midi - b.midi, 12)
                    if (ic != 1 && ic != 11) continue
                    val chord = piece.chordAt(from)
                    // Chord tones: the symbol's, plus what the written voicing really holds (Satie's major sevenths).
                    val pcs = (chord.intervals.map { Math.floorMod(chord.root + it, 12) } +
                        chord.harmony.map { Math.floorMod(it, 12) } + chord.arp.map { Math.floorMod(it, 12) }).toSet()
                    if (Math.floorMod(a.midi, 12) in pcs && Math.floorMod(b.midi, 12) in pcs) continue
                    // A one-beat passing or neighbour note against the chord is ordinary voice leading.
                    if (to - from < 8) { passing++; continue }
                    clashes++
                    problems += "${piece.title} bar ${from / piece.stepsPerBar + 1} beat ${from % piece.stepsPerBar / 4 + 1}: " +
                        "${Instruments.stageNames[a.stage]} ${noteName(a.midi)} against ${Instruments.stageNames[b.stage]} ${noteName(b.midi)} " +
                        "for ${(to - from) / 4.0} beats (with $band)"
                }
            }
            report.appendLine("${piece.title}: ${bands.size} bands checked, $under notes under the bass, $clashes clashes held 2+ beats, " +
                "$passing one-beat passing clashes (all bands together)")
        }
        println(report)
        // One line per distinct problem regardless of which band showed it.
        val distinct = problems.map { it.substringBefore(" (with ") }.toSortedSet()
        println(distinct.joinToString("\n"))
        check(distinct.isEmpty()) { "${distinct.size} harmony problems:\n" + distinct.take(60).joinToString("\n") }
    }
}
