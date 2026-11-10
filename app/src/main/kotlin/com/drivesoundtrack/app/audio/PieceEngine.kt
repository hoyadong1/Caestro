package com.drivesoundtrack.app.audio

/**
 * Plays a [Piece] with the instrument the listener picked for each of the six stages.
 * Stage 0 (bass) always plays; the renderer fades stages 1..5 in with speed through `mixes`.
 * Changing an instrument takes effect on the next step; the old one fades out over ~0.2 s.
 */
internal class PieceEngine(private val piece: Piece) : ScoreEngine {
    override var left = 0.0; private set
    override var right = 0.0; private set
    override var bass = 0.0; private set

    private val controls = Controls()
    private val kinds = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }
    private val parts = arrayOfNulls<Part>(6)
    private val retiring = arrayOfNulls<Part>(6)
    private val retiringGain = DoubleArray(6)
    private val retiringLevel = DoubleArray(6)
    @Volatile private var pending: IntArray? = null
    private var arpSide = 1.0
    private var stepSamples = 5000
    private val tonicHz = midiHz(36 + piece.tonic)

    private val presenceA = Array(6) { Svf().apply { set(260.0, 0.7) } }
    private val presenceB = Array(6) { Svf().apply { set(260.0, 0.7) } }

    private val roomLeft = DoubleArray(4096)
    private val roomRight = DoubleArray(4096)
    private var roomWrite = 0

    init { for (stage in 0..5) parts[stage] = Instruments.build(kinds[stage], stage) }

    /** Instrument index (into [Instruments.all]) per stage. Applied on the audio thread at the next step. */
    fun setOrchestration(ids: IntArray) { pending = ids.copyOf() }

    override fun reset() {
        for (stage in 0..5) { parts[stage] = Instruments.build(kinds[stage], stage); retiring[stage] = null }
        java.util.Arrays.fill(roomLeft, 0.0); java.util.Arrays.fill(roomRight, 0.0)
    }

    override fun step(position: Int, bar: Long, chord: Int, tones: IntArray, scene: Soundscape, rhythmLevel: Int,
                      bpm: Double, wiper: Boolean) {
        pending?.let { ids ->
            pending = null
            for (stage in 0..5) if (ids[stage] != kinds[stage]) {
                retiring[stage] = parts[stage]
                retiringLevel[stage] = STAGE_GAIN[stage] * Instruments.all[kinds[stage]].gain
                retiringGain[stage] = 1.0
                kinds[stage] = ids[stage]
                parts[stage] = Instruments.build(ids[stage], stage)
            }
        }
        stepSamples = (60.0 / bpm / 4.0 * Dsp.RATE).toInt()
        val spb = piece.stepsPerBar
        now = bar * spb + position
        val s = (now % piece.lengthSteps).toInt()
        val span = piece.chordAt(s)
        val accent = when { position == 0 -> 1.0; position % 4 == 0 -> 0.86; else -> 0.74 }

        piece.bassAt(s)?.let { play(Instruments.BASS, it.midi, it.length, 0.95) }
        (parts[Instruments.RHYTHM] as? Kit)?.step(position, spb, bar, rhythmLevel, wiper, tonicHz, stepSamples)
        (parts[Instruments.RHYTHM] as? SampleKit)?.step(position, spb, bar, rhythmLevel, wiper, tonicHz)

        chordClasses = 0
        for (iv in span.intervals) chordClasses = chordClasses or (1 shl Math.floorMod(span.root + iv, 12))
        for (m in span.harmony) chordClasses = chordClasses or (1 shl Math.floorMod(m, 12))
        for (m in span.arp) chordClasses = chordClasses or (1 shl Math.floorMod(m, 12))
        // The melody note held from here (one starting now, else one still sounding), for the clash rule below.
        val tune = piece.melodyAt(s)?.let { if (it.length >= 4) it.midi else null }
            ?: if (now < melodyUntil && melodyUntil - now >= 4) melodyMidi else null

        if (Instruments.all[kinds[Instruments.HARMONY]].sustained) {
            if (span.start == s) for (m in span.harmony) if (!clashes(m, tune)) play(Instruments.HARMONY, m, span.length, 0.8)
        } else if (piece.comp[position]) {
            var until = 1
            while (position + until < spb && !piece.comp[position + until]) until++
            for (m in span.harmony) if (!clashes(m, tune)) play(Instruments.HARMONY, m, until, accent * 0.85)
        }

        val index = piece.arp[position]
        if (index >= 0) {
            arpSide = -arpSide
            play(Instruments.ARP, span.arp[index % span.arp.size], 3, accent * 0.9, arpSide * 0.32)
        }

        piece.melodyAt(s)?.let { play(Instruments.MELODY, it.midi, it.length, 0.9) }

        val high = Instruments.all[kinds[Instruments.HIGH]]
        when (piece.high) {
            HighLine.BELLS -> if (high.sustained) {
                if (span.start == s) for (m in span.harmony.takeLast(2)) if (!clashes(m, tune)) play(Instruments.HIGH, m + 12, span.length, 0.7)
            } else if (s % (spb / 2) == 0 && (span.start == s || span.length > spb / 2)) {
                // The chord's top note, or the highest chord tone that does not rub against the melody.
                val bell = if (!clashes(span.top, tune)) span.top else span.harmony.sortedDescending().firstOrNull { !clashes(it, tune) }
                if (bell != null) play(Instruments.HIGH, bell + 12, spb / 2, 0.8)
            }
            HighLine.CANON -> piece.melodyAt(Math.floorMod(s - piece.canonBars * spb, piece.lengthSteps))
                ?.let { play(Instruments.HIGH, it.midi, it.length, 0.75) }
            HighLine.OCTAVE -> piece.melodyAt(s)?.let { play(Instruments.HIGH, it.midi + 12, it.length, 0.75) }
        }
    }

    /** Tests only: told of every note as played (stage, MIDI note after fitting the instrument's range, steps). */
    internal var onNote: ((stage: Int, midi: Int, steps: Int) -> Unit)? = null

    // The bass note now sounding: chord and arpeggio notes never go under it.
    private var now = 0L
    private var bassMidi = 0
    private var bassUntil = -1L
    // The melody note now sounding.
    private var melodyMidi = 0
    private var melodyUntil = -1L

    /** Pitch classes of the chord now playing, as written (its voicing included). */
    private var chordClasses = 0

    /**
     * A chord or bell note a semitone (or major seventh / minor ninth) from the melody note held for a
     * beat or more, when that melody note is not itself in the chord: left out for that chord. The
     * arrangement keeps simple chords under the tune, and where the tune holds a note foreign to them
     * (Satie's C# over G major 7) the two would rub for a bar. Written sevenths (G with F#) stay.
     */
    private fun clashes(midi: Int, tune: Int?): Boolean {
        if (tune == null || chordClasses and (1 shl Math.floorMod(tune, 12)) != 0) return false
        val ic = Math.floorMod(midi - tune, 12)
        return ic == 1 || ic == 11
    }

    private fun play(stage: Int, midi: Int, steps: Int, velocity: Double, pan: Double? = null) {
        val poly = parts[stage] as? Poly ?: return
        val kind = Instruments.all[kinds[stage]]
        var m = midi
        while (m < kind.minMidi) m += 12
        while (m > kind.maxMidi) m -= 12
        if (stage == Instruments.BASS) { bassMidi = m; bassUntil = now + steps }
        else if (stage == Instruments.MELODY) { melodyMidi = m; melodyUntil = now + steps }
        else if ((stage == Instruments.HARMONY || stage == Instruments.ARP) && now < bassUntil) {
            // A bass instrument that cannot reach a written low note plays it an octave up (Bach's closing G1
            // on a cello); the written chord under it would then sound below the bass, muddy and inverted.
            while (m < bassMidi && m + 12 <= kind.maxMidi) m += 12
        }
        onNote?.invoke(stage, m, steps)
        poly.play(midiHz(m), (steps * stepSamples * 0.95).toInt().coerceAtLeast(1), velocity, pan)
    }

    override fun render(speed: Double, accelerator: Double, brake: Double, mixes: DoubleArray, steeringPitch: Double, bpm: Double) {
        val c = controls
        c.speed = speed; c.accelerator = accelerator; c.brake = brake; c.bend = steeringPitch
        val soften = 1.0 - brake * 0.45
        var l = 0.0
        var r = 0.0
        var low = 0.0
        var send = 0.0
        for (stage in 0..5) {
            val mix = if (stage == 0) 1.0 else mixes[stage]
            val part = parts[stage]
            // A silent stage is not rendered; its voices simply wait until the stage fades back in.
            if (part != null && mix > 1e-4) {
                part.render(c)
                val kind = Instruments.all[kinds[stage]]
                var gain = STAGE_GAIN[stage] * kind.gain * mix
                if (stage == Instruments.HARMONY || stage == Instruments.HIGH) gain *= soften
                var pl = part.left; var pr = part.right
                if (kind.presence > 0.0 && stage == Instruments.BASS) {
                    // Only in the bass register, where the lift is needed (an organ playing chords was
                    // lifted 5 dB over its neighbours). Two 12 dB/octave high-passes at 260 Hz leave the fundamental alone.
                    presenceA[stage].process((pl + pr) * 0.5)
                    presenceB[stage].process(presenceA[stage].high)
                    val lift = presenceB[stage].high * kind.presence
                    pl += lift; pr += lift
                }
                if (stage == 0) low += (pl + pr) * 0.5 * gain
                else {
                    l += pl * gain; r += pr * gain
                    if (stage >= Instruments.HARMONY) send += (pl + pr) * 0.5 * gain * SEND[stage]
                }
            }
            val old = retiring[stage]
            if (old != null) {
                old.render(c)
                val gain = retiringLevel[stage] * retiringGain[stage] * mix
                if (stage == 0) low += (old.left + old.right) * 0.5 * gain else { l += old.left * gain; r += old.right * gain }
                retiringGain[stage] *= FADE
                if (retiringGain[stage] < 1e-3) retiring[stage] = null
            }
        }
        // Small stereo room: two short reflections, crossed between the sides.
        val outLeft = roomLeft[(roomWrite - 1013 + roomLeft.size) % roomLeft.size]
        val outRight = roomRight[(roomWrite - 1367 + roomRight.size) % roomRight.size]
        roomLeft[roomWrite] = send + outRight * 0.32
        roomRight[roomWrite] = send + outLeft * 0.32
        roomWrite = (roomWrite + 1) % roomLeft.size
        left = l + outLeft * 0.35
        right = r + outRight * 0.35
        bass = low * (1.0 - brake * 0.15)
    }

    private companion object {
        /** Base level per stage before each instrument's own trim. */
        // The high stage sat under the band on the emulator ("barely heard"): +3 dB, still the softest stage.
        val STAGE_GAIN = doubleArrayOf(0.085, 1.0, 0.036, 0.054, 0.066, 0.036)
        val SEND = doubleArrayOf(0.0, 0.0, 0.5, 0.7, 0.6, 0.6)
        val FADE = Dsp.decay(0.06)
    }
}
