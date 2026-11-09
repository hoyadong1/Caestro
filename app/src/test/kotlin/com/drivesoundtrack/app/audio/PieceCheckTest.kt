package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class PieceCheckTest {
    private val pieces = Soundscapes.all.mapNotNull { it.piece }

    @Test
    fun scoresAreConsistent() {
        check(pieces.size == 5)
        for (piece in pieces) {
            val spb = piece.stepsPerBar
            check(piece.lengthSteps % spb == 0)
            // Chords cover the piece without gaps.
            var position = 0
            for (chord in piece.chords) { check(chord.start == position) { "${piece.title} chord gap at $position" }; position += chord.length }
            check(position == piece.lengthSteps)
            // Melody notes on strong beats should mostly be chord tones: catches mistyped notes.
            val strong = piece.melody.filter { it.start % 4 == 0 }
            val inChord = strong.count { note ->
                val chord = piece.chordAt(note.start)
                chord.intervals.any { (chord.root + it) % 12 == note.midi % 12 }
            }
            check(inChord >= strong.size * 0.6) { "${piece.title}: only $inChord of ${strong.size} strong-beat notes fit the chords" }
            check(piece.melody.all { it.midi in 55..96 }) { "${piece.title} melody out of range" }
            // Default instruments exist and are allowed on their stage.
            piece.defaults.forEachIndexed { stage, id ->
                check(Instruments.indexOf(id) in Instruments.options[stage]) { "${piece.title}: $id not allowed on stage $stage" }
            }
            println("${piece.title}: ${piece.lengthBars} bars, $inChord/${strong.size} strong-beat chord tones")
        }
    }

    @Test
    fun everyInstrumentSoundsOnItsStage() {
        val piece = Pieces.odeToJoy
        for (stage in 0..5) for (kind in Instruments.options[stage]) {
            val rms = stageRms(piece, stage, kind)
            val label = Instruments.all[kind].label
            println("stage $stage %-8s rms %.4f".format(label, rms))
            if (Instruments.all[kind].id == "none") check(rms == 0.0) else check(rms > 0.004) { "$label silent on stage $stage" }
        }
    }

    @Test
    fun albumMovesToTheNextTrack() {
        val model = SoundModel()
        val renderer = SoundRenderer(model)
        renderer.trackSeconds = 1.0
        val classics = Albums.classics
        renderer.setPlaylist(classics)
        renderer.setPreset(classics[0])
        val buffer = ShortArray(2048)
        val seen = linkedSetOf<Int>()
        repeat(44100 * 40 / 1024) { renderer.render(buffer); seen += renderer.track() }
        check(seen.take(2) == listOf(classics[0], classics[1])) { "album did not advance: $seen" }
        println("album order: ${seen.map { Soundscapes.all[it].name }}")
    }

    @Test
    fun loudnessMatchesTheOriginals() {
        fun loudness(preset: Int, kmh: Double): Pair<Double, Int> {
            val model = SoundModel().apply { setPreset(preset); setSpeed(kmh) }
            val renderer = SoundRenderer(model).apply { setPreset(preset) }
            val buffer = ShortArray(2048)
            var sum = 0.0; var count = 0; var peak = 0
            repeat(44100 * 14 / 1024) { block ->
                renderer.render(buffer)
                if (block > 44100 * 4 / 1024) for (s in buffer) { sum += s.toDouble() * s; count++; peak = maxOf(peak, abs(s.toInt())) }
            }
            return sqrt(sum / count) to peak
        }
        for (kmh in listOf(0.0, 30.0, 60.0, 130.0)) {
            val coast = loudness(Soundscapes.all.indexOfFirst { it.name == "Coast" }, kmh).first
            for (preset in Albums.classics) {
                val (rms, peak) = loudness(preset, kmh)
                println("%3.0f km/h %-14s rms %5.0f (Coast %5.0f) peak %5d".format(kmh, Soundscapes.all[preset].name, rms, coast, peak))
                check(peak < 30000) { "${Soundscapes.all[preset].name} clips at $kmh km/h" }
                check(rms > coast * 0.5 && rms < coast * 2.0) { "${Soundscapes.all[preset].name} level off at $kmh km/h: $rms vs $coast" }
            }
        }
    }

    private fun stageRms(piece: Piece, stage: Int, kind: Int): Double {
        val engine = PieceEngine(piece)
        val ids = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }.also { it[stage] = kind }
        engine.setOrchestration(ids)
        val mixes = DoubleArray(6).also { it[stage] = 1.0 }
        val scene = Soundscapes.all.first { it.piece === piece }
        val bpm = scene.baseBpm
        val stepSamples = (60.0 / bpm / 4.0 * 44100).toInt()
        var sum = 0.0; var count = 0
        for (step in 0 until piece.lengthSteps) {
            engine.step(step % piece.stepsPerBar, (step / piece.stepsPerBar).toLong(), 0, intArrayOf(0, 4, 7, 12, 16), scene, 2, bpm, false)
            repeat(stepSamples) {
                engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm)
                val v = if (stage == 0) engine.bass else (engine.left + engine.right) * 0.5
                sum += v * v; count++
            }
        }
        return sqrt(sum / count)
    }
}
