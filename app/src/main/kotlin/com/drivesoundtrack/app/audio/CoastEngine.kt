package com.drivesoundtrack.app.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Coast — sunlit cruise. A warm, bright counterpart to Pulse and Neon: C major, an electric piano
 * whose tine brightens with speed, a soft kick on one and three with a side-stick and a lazy
 * swung shaker, a kalimba-like arpeggio, a breathy flute melody and a high glass pad.
 *
 * Layers (same speed thresholds as every soundscape):
 *   0 round bass (always) · 1 soft kit · 2 electric piano · 3 kalimba · 4 flute · 5 glass pad
 */
internal class CoastEngine : ScoreEngine {
    override var left = 0.0; private set
    override var right = 0.0; private set
    override var bass = 0.0; private set

    private val noise = Noise(0x2C0A57L)
    private var time = 0.0

    // --- Soft kit
    private var kickAge = 10.0
    private var kickPhase = 0.0
    private var rimAge = 10.0
    private val rimFilter = Svf().apply { set(1900.0, 3.0) }
    private var shakerAge = 10.0
    private var shakerAccent = 0.0
    private var shakerDelay = 0 // samples until a swung offbeat shaker sounds
    private var shakerFromWiper = false // wiper shakes play at any speed; the groove's only with the kit
    private val shakerFilter = Svf().apply { set(6200.0, 1.1) }

    // --- Round bass: sine with a little second and third harmonic, gently low-passed
    private var bassPhase = 0.0
    private var bassHz = 65.41
    private val bassAmp = Env(0.008, 0.5, 0.6, 0.08)
    private val bassFilter = Svf()
    private var bassGateSamples = 0

    // --- Electric piano: four two-operator FM voices (tine attack that mellows out)
    private val pianoHz = DoubleArray(4) { 261.6 }
    private val pianoCarrier = DoubleArray(4)
    private val pianoModulator = DoubleArray(4)
    private var pianoAge = 10.0
    private val pianoAmp = Env(0.004, 1.6, 0.0, 0.5)

    // --- Kalimba arpeggio: sine with a quickly fading inharmonic partial
    private var kalimbaPhase = 0.0
    private var kalimbaHz = 523.3
    private var kalimbaAge = 10.0
    private var kalimbaIndex = 0
    private var kalimbaPan = 0.3

    // --- Flute melody: sine, a touch of second harmonic, breath noise, late vibrato
    private var flutePhase = 0.0
    private var fluteTargetHz = 523.3
    private var fluteHz = 523.3
    private var fluteAge = 10.0
    private val fluteAmp = Env(0.06, 0.6, 0.8, 0.25)
    private var fluteGateSamples = 0
    private val breathFilter = Svf().apply { set(2400.0, 0.9) }

    // --- Glass pad: three chord tones high up, swelling across each two-bar phrase
    private val padHz = DoubleArray(3) { 1046.5 }
    private val padPhase = DoubleArray(3)
    private var phraseBeat = 0.0

    // --- Small stereo room for the piano, kalimba and flute
    private val roomLeft = DoubleArray(4096)
    private val roomRight = DoubleArray(4096)
    private var roomWrite = 0

    private var stepSamples = 5000

    override fun step(position: Int, bar: Long, chord: Int, tones: IntArray, scene: Soundscape, rhythmLevel: Int,
                      bpm: Double, wiper: Boolean) {
        stepSamples = (60.0 / bpm / 4.0 * Dsp.RATE).toInt()
        val root = scene.rootHz * Dsp.semitones(chord.toDouble())
        if (position == 0) {
            for (i in 0 until 4) pianoHz[i] = root * 4.0 * Dsp.semitones(tones[i].toDouble())
            for (i in 0 until 3) padHz[i] = root * 16.0 * Dsp.semitones(tones[i].toDouble())
            phraseBeat = if (bar % 2 == 0L) 0.0 else 4.0
        }
        // Soft kit: kick on one and three, side-stick on two and four, swung eighth shaker.
        if (position == 0 || position == 8) { kickAge = 0.0; kickPhase = 0.0 }
        if (position == 4 || position == 12) rimAge = 0.0
        if (position % 4 == 0) { shakerAge = 0.0; shakerAccent = 0.55; shakerFromWiper = false }
        if (position % 4 == 2) { shakerDelay = (stepSamples * 0.35).toInt(); shakerAccent = 1.0; shakerFromWiper = false }
        if (wiper && position % 2 == 1) { shakerAge = 0.0; shakerAccent = 0.5; shakerFromWiper = true }
        // Bass: half notes (root, fifth) when slow; root-fifth-octave-fifth quarters once moving.
        val bassStep = if (rhythmLevel >= 1) position % 4 == 0 else position % 8 == 0
        if (bassStep) {
            val interval = when (position) { 4, 12 -> 7; 8 -> if (rhythmLevel >= 1) 12 else 7; else -> 0 }
            bassHz = root * Dsp.semitones(interval.toDouble())
            bassGateSamples = (stepSamples * (if (rhythmLevel >= 1) 3.6 else 7.4)).toInt()
            bassAmp.gate()
        }
        // Electric piano on the downbeat and, once moving, the "and" of two.
        if (position == 0 || (rhythmLevel >= 1 && position == 6)) { pianoAge = 0.0; pianoAmp.gate() }
        // Kalimba: eighth notes rising through the chord, alternating left and right.
        if (position % 2 == 0) {
            val slot = kalimbaIndex % (tones.size + 2)
            val tone = if (slot < tones.size) tones[slot] else tones[tones.size - 1 - (slot - tones.size + 1)]
            kalimbaHz = root * 8.0 * Dsp.semitones(tone.toDouble())
            kalimbaAge = 0.0; kalimbaPhase = 0.0
            kalimbaPan = if (kalimbaIndex % 2 == 0) 0.32 else -0.32
            kalimbaIndex++
        }
        // Flute: the soundscape's melody, held long and gliding gently.
        val note = scene.melody[position]
        if (note >= 0) {
            fluteTargetHz = root * 8.0 * Dsp.semitones(tones[note.coerceIn(tones.indices)].toDouble())
            fluteGateSamples = stepSamples * 6
            fluteAge = 0.0
            fluteAmp.gate()
        }
    }

    override fun render(speed: Double, accelerator: Double, brake: Double, mixes: DoubleArray, steeringPitch: Double, bpm: Double) {
        val frame = 1.0 / Dsp.RATE
        time += frame
        val soften = 1.0 - brake * 0.45 // braking mellows the piano and pad

        // Kit
        val kick = if (kickAge < 0.7) {
            kickPhase += 2.0 * PI * (52.0 + 40.0 * exp(-kickAge * 28.0)) * frame
            sin(kickPhase) * exp(-kickAge * 6.0) * 0.20
        } else 0.0
        rimFilter.process(noise.next())
        val rim = if (rimAge < 0.12) (rimFilter.band * 0.8 + sin(2.0 * PI * 820.0 * rimAge) * 0.35) *
            exp(-rimAge * 55.0) * 0.075 else 0.0
        if (shakerDelay > 0 && --shakerDelay == 0) shakerAge = 0.0
        shakerFilter.process(noise.next())
        val shaker = if (shakerAge < 0.15) shakerFilter.band * (1.0 - exp(-shakerAge * 400.0)) *
            exp(-shakerAge * 28.0) * shakerAccent * 0.11 * (if (shakerFromWiper) 1.0 else mixes[1]) else 0.0
        val kit = (kick + rim) * mixes[1]

        // Bass
        if (bassGateSamples > 0 && --bassGateSamples == 0) bassAmp.release()
        bassPhase += 2.0 * PI * bassHz * frame
        val rawBass = sin(bassPhase) + sin(bassPhase * 2.0) * (0.22 + accelerator * 0.12) + sin(bassPhase * 3.0) * 0.06
        bassFilter.set(260.0 + 520.0 * speed + 300.0 * accelerator, 0.7)
        bass = bassFilter.process(rawBass) * bassAmp.next() * 0.085 * (1.0 - brake * 0.15)

        // Electric piano: modulation depth (the "tine") fades after each strike and grows with speed.
        val pianoEnv = pianoAmp.next()
        val index = ((0.35 + 1.2 * speed) * exp(-pianoAge * 6.0) + 0.12) * soften
        val tremolo = sin(2.0 * PI * 4.2 * time) * 0.12
        var piano = 0.0
        for (i in 0 until 4) {
            pianoModulator[i] += 2.0 * PI * pianoHz[i] * frame
            pianoCarrier[i] += 2.0 * PI * pianoHz[i] * frame
            piano += sin(pianoCarrier[i] + index * sin(pianoModulator[i]))
        }
        piano *= pianoEnv * 0.030 * mixes[2]
        pianoAge += frame
        val pianoLeft = piano * (1.0 + tremolo)
        val pianoRight = piano * (1.0 - tremolo)

        // Kalimba
        kalimbaPhase += 2.0 * PI * kalimbaHz * frame
        val kalimba = if (kalimbaAge < 0.9) (sin(kalimbaPhase) + sin(kalimbaPhase * 4.02) * 0.28 * exp(-kalimbaAge * 30.0)) *
            (1.0 - exp(-kalimbaAge * 900.0)) * exp(-kalimbaAge * 3.2) * ((0.9 - kalimbaAge) / 0.06).coerceIn(0.0, 1.0) *
            0.042 * mixes[3] else 0.0
        kalimbaAge += frame

        // Flute
        if (fluteGateSamples > 0 && --fluteGateSamples == 0) fluteAmp.release()
        fluteHz += (fluteTargetHz - fluteHz) * (1.0 - Dsp.decay(0.03))
        val vibrato = 1.0 + sin(2.0 * PI * 5.0 * time) * 0.005 * ((fluteAge - 0.3) / 0.4).coerceIn(0.0, 1.0)
        flutePhase += 2.0 * PI * fluteHz * vibrato * steeringPitch * frame
        breathFilter.process(noise.next())
        val fluteEnv = fluteAmp.next()
        val flute = (sin(flutePhase) + sin(flutePhase * 2.0) * 0.12 + breathFilter.band * 0.18) * fluteEnv * 0.055 * mixes[4]
        fluteAge += frame

        // Glass pad: swells over each two-bar phrase
        phraseBeat += bpm / 60.0 * frame
        val swell = 0.55 + 0.45 * sin(PI * (phraseBeat / 8.0).coerceIn(0.0, 1.0))
        var pad = 0.0
        for (i in 0 until 3) { padPhase[i] += 2.0 * PI * padHz[i] * frame; pad += sin(padPhase[i]) }
        pad *= swell * 0.011 * soften * mixes[5]

        // Small room: two short reflections, crossed between the sides
        val readA = (roomWrite - 1013 + roomLeft.size) % roomLeft.size
        val readB = (roomWrite - 1367 + roomLeft.size) % roomLeft.size
        val roomOutLeft = roomLeft[readA]
        val roomOutRight = roomRight[readB]
        val send = piano * 0.5 + kalimba * 0.7 + flute * 0.6
        roomLeft[roomWrite] = send + roomOutRight * 0.32
        roomRight[roomWrite] = send + roomOutLeft * 0.32
        roomWrite = (roomWrite + 1) % roomLeft.size

        left = kit + shaker * 0.85 + pianoLeft + kalimba * (1.0 + kalimbaPan) + flute + pad * 0.9 + roomOutLeft * 0.35
        right = kit + shaker * 1.0 + pianoRight + kalimba * (1.0 - kalimbaPan) + flute + pad * 1.0 + roomOutRight * 0.35

        kickAge += frame; rimAge += frame; shakerAge += frame
    }
}
