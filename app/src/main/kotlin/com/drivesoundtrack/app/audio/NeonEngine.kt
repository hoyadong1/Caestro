package com.drivesoundtrack.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Neon — night electro. Band-limited saw/square synths through filters that open with speed,
 * a soft four-on-the-floor kit and a gentle side-chain breath. Deliberately sparse: long stabs
 * twice a bar, an eighth-note arpeggio and a short echo, so it stays easy to drive with.
 *
 * Layers (same speed thresholds as every soundscape):
 *   0 bass (always) · 1 drums · 2 chord stabs · 3 square arpeggio · 4 gliding lead · 5 noise riser
 */
internal class NeonEngine : ScoreEngine {
    // Output of the last [render] call. The bass is kept apart so the steering lean can leave it centred.
    override var left = 0.0; private set
    override var right = 0.0; private set
    override var bass = 0.0; private set

    private val noise = Noise(0x1F2E3D4C5BL)

    // --- Drums
    private var kickAge = 10.0
    private var kickPhase = 0.0
    private var clapAge = 10.0
    private var hatAge = 10.0
    private var hatAccent = 0.0
    private var openHatAge = 10.0
    private val clapFilter = Svf().apply { set(1300.0, 1.4) }
    private val hatFilter = Svf().apply { set(7500.0, 0.8) }
    // Wiper: a soft sixteenth-note shaker that plays at any speed while the wipers run.
    private var shakerAge = 10.0
    private val shakerFilter = Svf().apply { set(5200.0, 1.6) }

    // --- Bass: two detuned saws and a sub sine through a resonant low-pass
    private val bassOsc = arrayOf(Osc(), Osc())
    private val bassSub = Osc()
    private val bassFilter = Svf()
    private val bassAmp = Env(0.003, 0.14, 0.55, 0.03)
    private val bassFilterEnv = Env(0.002, 0.07, 0.0, 0.05)
    private var bassHz = 55.0
    private var bassGateSamples = 0

    // --- Chord stabs over a soft bed: three chord tones × two detuned saws
    private val stabOsc = Array(6) { Osc() }
    private val stabFilter = Svf()
    private val bedFilter = Svf()
    private val stabAmp = Env(0.012, 0.9, 0.25, 0.4)
    private val stabFilterEnv = Env(0.002, 0.09, 0.0, 0.05)
    private val stabHz = DoubleArray(3) { 220.0 }

    // --- Arpeggio: plucky square
    private val arpOsc = Osc()
    private val arpFilter = Svf()
    private val arpAmp = Env(0.003, 0.16, 0.0, 0.04)
    private var arpHz = 440.0
    private var arpIndex = 0

    // --- Lead: saw/square with glide and late vibrato
    private val leadOsc = Osc()
    private val leadOsc2 = Osc()
    private val leadFilter = Svf()
    private val leadAmp = Env(0.012, 0.35, 0.65, 0.12)
    private var leadTargetHz = 440.0
    private var leadHz = 440.0
    private var leadAge = 10.0
    private var leadGateSamples = 0
    private var vibratoPhase = 0.0

    // --- Riser: band-passed noise sweeping up across each two-bar phrase
    private val riserFilter = Svf()
    private var phraseBeat = 0.0

    // --- Ping-pong delay for the arpeggio and lead (dotted eighth)
    private val delayLeft = DoubleArray(SoundRenderer.RATE)
    private val delayRight = DoubleArray(SoundRenderer.RATE)
    private var delayWrite = 0

    private var stepSamples = 5000

    override fun step(position: Int, bar: Long, chord: Int, tones: IntArray, scene: Soundscape, rhythmLevel: Int, bpm: Double, wiper: Boolean) {
        if (wiper) shakerAge = 0.0
        stepSamples = (60.0 / bpm / 4.0 * Dsp.RATE).toInt()
        val root = scene.rootHz * Dsp.semitones(chord.toDouble())
        if (position == 0) {
            // Stab voicing: root, third/fifth from the chord tones, an octave above the bass bed.
            stabHz[0] = root * 4.0
            stabHz[1] = root * 4.0 * Dsp.semitones(tones[1].toDouble())
            stabHz[2] = root * 4.0 * Dsp.semitones(tones[2].toDouble())
            phraseBeat = (bar % 4).toDouble() * 4.0
        }
        // Kit: four on the floor, clap on 2 and 4, offbeat open hat, closed hats fill in with speed.
        if (position % 4 == 0) { kickAge = 0.0; kickPhase = 0.0 }
        if (position == 4 || position == 12) clapAge = 0.0
        if (position % 4 == 2) openHatAge = 0.0
        if (rhythmLevel >= 2 && position % 2 == 0 && position % 4 != 2) { hatAge = 0.0; hatAccent = 0.6 }
        // Bass: quarter notes when slow, octave-pumping eighths once moving, a ghost 16th at speed.
        val bassStep = if (rhythmLevel >= 2) position % 2 == 0 else position % 4 == 0
        if (bassStep) {
            val octaveUp = rhythmLevel >= 2 && position % 4 == 2
            bassHz = root * (if (octaveUp) 2.0 else 1.0)
            bassGateSamples = (stepSamples * (if (rhythmLevel >= 2) 1.6 else 3.4)).toInt()
            bassAmp.gate(); bassFilterEnv.gate()
        }
        // Stabs on the offbeat eighths, plus the downbeat of each bar.
        if (position == 0 || position == 8) { stabAmp.gate(); stabFilterEnv.gate() }
        // Arpeggio: sixteenth notes walking the chord over two octaves.
        if (position % 2 == 0) {
            val slot = arpIndex % (tones.size * 2)
            val tone = tones[slot % tones.size] + if (slot >= tones.size) 12 else 0
            arpHz = root * 8.0 * Dsp.semitones(tone.toDouble())
            arpIndex++
            arpAmp.gate()
        }
        // Lead: the soundscape's melody, gliding between notes.
        val note = scene.melody[position]
        if (note >= 0) {
            val octave = if ((bar / 4) % 2 == 1L) 12 else 0
            leadTargetHz = root * 8.0 * Dsp.semitones((tones[note.coerceIn(tones.indices)] + octave).toDouble())
            leadGateSamples = stepSamples * 3
            leadAge = 0.0
            leadAmp.gate()
        }
    }

    /**
     * One stereo sample. [mixes] are the layer fades 1..5 (index 0 unused, bass is always on).
     * [speed] 0..1, [accelerator] 0..1, [brake] 0..1.
     */
    override fun render(speed: Double, accelerator: Double, brake: Double, mixes: DoubleArray, steeringPitch: Double, bpm: Double) {
        // Parked, the bass filter barely opens on each note: a soft, round pulse. It sharpens with speed.
        val pluck = 0.2 + 0.8 * speed.pow(1.5)
        val frame = 1.0 / Dsp.RATE
        val closing = 1.0 - brake * 0.55 // braking pulls every filter down

        // Kick and side-chain
        val kick = if (kickAge < 0.6) {
            val hz = 46.0 + 120.0 * exp(-kickAge * 38.0)
            kickPhase += 2.0 * PI * hz * frame
            val body = sin(kickPhase) * exp(-kickAge * 7.5)
            val click = if (kickAge < 0.004) noise.next() * (1.0 - kickAge / 0.004) * 0.35 else 0.0
            (body + click * 0.5) * 0.22
        } else 0.0
        val pump = 1.0 - 0.25 * exp(-kickAge * 6.0) * mixes[1]

        // Clap: three quick bursts then a short tail, band-passed
        val clapEnv = if (clapAge < 0.25) {
            val burst = clapAge % 0.011
            val bursts = if (clapAge < 0.033) exp(-burst * 300.0) else 0.0
            bursts * 0.8 + exp(-clapAge * 18.0) * 0.6
        } else 0.0
        val clapNoise = noise.next()
        clapFilter.process(clapNoise)
        val clap = clapFilter.band * clapEnv * 0.32

        // Hats from high-passed noise
        val hatNoise = noise.next()
        hatFilter.process(hatNoise)
        val hats = hatFilter.high * ((if (hatAge < 0.08) exp(-hatAge * 55.0) * hatAccent * 0.085 else 0.0) +
            (if (openHatAge < 0.35) exp(-openHatAge * 11.0) * 0.060 else 0.0))

        // Bass
        if (bassGateSamples > 0 && --bassGateSamples == 0) { bassAmp.release(); bassFilterEnv.release() }
        val rawBass = bassOsc[0].saw(bassHz * 0.9977) + bassOsc[1].saw(bassHz * 1.0023) + bassSub.sine(bassHz * 0.5) * 0.55
        bassFilter.set((150.0 + 1500.0 * speed.pow(1.2) + 650.0 * accelerator + 650.0 * pluck * bassFilterEnv.next()) * closing, 1.1)
        val drive = 1.4 + accelerator * 2.6
        val bassOut = tanh(bassFilter.process(rawBass) * drive) / tanh(drive) * bassAmp.next() * 0.075 *
            (1.0 - 0.12 * exp(-kickAge * 6.0) * mixes[1])

        // Stabs and bed
        var chordRaw = 0.0
        for (i in 0 until 6) chordRaw += stabOsc[i].saw(stabHz[i / 2] * (if (i % 2 == 0) 0.9954 else 1.0046))
        stabFilter.set((480.0 + 2600.0 * speed + 2200.0 * stabFilterEnv.next()) * closing, 0.9)
        bedFilter.set(700.0 * closing, 0.7)
        val stab = stabFilter.process(chordRaw) * stabAmp.next() * 0.060
        val bed = bedFilter.process(chordRaw) * 0.014
        val chords = (stab + bed) * pump * mixes[2]

        // Arpeggio
        arpFilter.set((1700.0 + 3600.0 * speed) * closing, 1.3)
        val arp = arpFilter.process(arpOsc.square(arpHz)) * arpAmp.next() * 0.075 * pump * mixes[3]

        // Lead
        if (leadGateSamples > 0 && --leadGateSamples == 0) leadAmp.release()
        leadHz += (leadTargetHz - leadHz) * (1.0 - Dsp.decay(0.045))
        vibratoPhase += 2.0 * PI * 5.4 * frame
        val vibrato = 1.0 + sin(vibratoPhase) * 0.004 * ((leadAge - 0.25) / 0.3).coerceIn(0.0, 1.0)
        val leadPitch = leadHz * vibrato * steeringPitch
        leadFilter.set((1300.0 + 2800.0 * speed) * closing, 1.0)
        val leadRaw = leadOsc.saw(leadPitch) * 0.7 + leadOsc2.square(leadPitch * 1.003) * 0.3
        val lead = leadFilter.process(leadRaw) * leadAmp.next() * 0.080 * mixes[4]
        leadAge += frame

        // Riser: two-bar sweep that swells and resets softly at the phrase line
        phraseBeat += bpm / 60.0 * frame
        val phrase = (phraseBeat / 16.0).coerceIn(0.0, 1.0)
        riserFilter.set((700.0 + 6500.0 * phrase * phrase) * closing, 2.2)
        riserFilter.process(noise.next())
        val riserGain = sin(PI * phrase).pow(0.7) * (0.35 + 0.65 * phrase)
        val riser = riserFilter.band * riserGain * 0.055 * mixes[5]

        // Ping-pong delay (dotted eighth) for arp and lead
        val delaySamples = (0.75 * 60.0 / bpm * Dsp.RATE).toInt().coerceIn(1, delayLeft.size - 1)
        val read = (delayWrite - delaySamples + delayLeft.size) % delayLeft.size
        val echoLeft = delayLeft[read]
        val echoRight = delayRight[read]
        delayLeft[delayWrite] = (arp * 0.6 + lead * 0.4) * 0.35 + echoRight * 0.24
        delayRight[delayWrite] = echoLeft * 0.24
        delayWrite = (delayWrite + 1) % delayLeft.size

        shakerFilter.process(noise.next())
        val shaker = if (shakerAge < 0.12) shakerFilter.band * exp(-shakerAge * 32.0) * 0.12 else 0.0
        shakerAge += frame
        val drums = (kick + clap) * mixes[1] + shaker
        val hatMix = hats * mixes[1]
        bass = bassOut
        // Light stereo spread (hats and arp slightly apart), then trimmed to the other soundscapes' level.
        left = (drums + hatMix * 0.85 + chords * 0.95 + arp * 1.0 + lead * 0.8 + riser * 0.85 + echoLeft * 0.6) * 0.86
        right = (drums + hatMix * 1.0 + chords * 0.95 + arp * 0.8 + lead * 0.8 + riser * 0.95 + echoRight * 0.6) * 0.86

        kickAge += frame; clapAge += frame; hatAge += frame; openHatAge += frame
        if (abs(left) > 4.0 || left.isNaN()) left = 0.0 // never pass a runaway filter to the output
        if (abs(right) > 4.0 || right.isNaN()) right = 0.0
    }
}
