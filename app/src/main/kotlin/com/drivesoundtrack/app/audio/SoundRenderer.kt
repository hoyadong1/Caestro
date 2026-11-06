package com.drivesoundtrack.app.audio

import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Five phrase-based soundscapes with six speed-controlled instruments. Stereo 16-bit PCM. */
class SoundRenderer(private val model: SoundModel) {
    companion object {
        const val RATE = 44100
        const val CHANNELS = 2
        private const val TWO_PI = 2.0 * PI
        private const val TRANSITION_SAMPLES = RATE * 0.35
        /** Per-sample decay of a cut note's leftover value: about a 3 ms time constant. */
        private val DECLICK = kotlin.math.exp(-1.0 / (0.003 * RATE))
        /** Long debug drive. Time follows emitted PCM, including emulator slowdowns. */
        fun speedJourneyKmh(t: Double): Double = when {
            t < 5.0 -> 0.0
            t < 13.0 -> smoothSpeed(0.0, 30.0, (t - 5.0) / 8.0)
            t < 17.0 -> 30.0
            t < 25.0 -> smoothSpeed(30.0, 50.0, (t - 17.0) / 8.0)
            t < 29.0 -> 50.0
            t < 37.0 -> smoothSpeed(50.0, 70.0, (t - 29.0) / 8.0)
            t < 41.0 -> 70.0
            t < 49.0 -> smoothSpeed(70.0, 95.0, (t - 41.0) / 8.0)
            t < 53.0 -> 95.0
            t < 61.0 -> smoothSpeed(95.0, 130.0, (t - 53.0) / 8.0)
            t < 66.0 -> 130.0
            t < 94.0 -> smoothSpeed(130.0, 0.0, (t - 66.0) / 28.0)
            else -> 0.0
        }

        private fun smoothSpeed(from: Double, to: Double, progress: Double): Double {
            val eased = progress * progress * (3.0 - 2.0 * progress)
            return from + (to - from) * eased
        }
    }

    private val random = Random(1049)
    @Volatile private var requestedPreset = 0
    @Volatile private var bassSolo = false
    @Volatile private var debugSpeedJourney = false
    private var journeyFrames = 0L
    private var activePreset = 0
    private var transition = 1.0
    private var fadingOut = false
    private var beat = 0.0
    private var lastStep = -1L
    private val layerTargets = DoubleArray(6)
    private val layerMixes = DoubleArray(6)
    private var kickAge = 10.0
    private var snareAge = 10.0
    private var hatAge = 10.0
    private var bassAge = 10.0
    private var leadAge = 10.0
    private var arpAge = 10.0
    private var shimmerAge = 10.0
    private var kickPhase = 0.0
    private var bassPhase = 0.0
    // Retriggering a voice cuts its previous note mid-waveform. The cut value is carried as an
    // offset that decays over a few milliseconds, which removes the click without a second voice.
    private var bassDeclick = 0.0; private var lastBass = 0.0
    private var leadDeclick = 0.0; private var lastLead = 0.0
    private var arpDeclick = 0.0; private var lastArp = 0.0
    private var shimmerDeclick = 0.0; private var lastShimmer = 0.0
    private var driveBassPhase = 0.0
    private var driveBassHz = 55.0
    private var leadPhase = 0.0
    private var arpPhase = 0.0
    private var shimmerPhase = 0.0
    private var arpFrequency = 440.0
    private var shimmerFrequency = 880.0
    private val padPhase = DoubleArray(3)
    private val padFrequency = DoubleArray(3) { 110.0 * (it + 1) }
    private var bassFrequency = 55.0
    private var leadFrequency = 220.0
    private var turnAge = 10.0
    private var turnPhase = 0.0
    private var turnFirstHz = 440.0
    private var turnSecondHz = 330.0
    private var turnPan = 0.0
    private var previousNoise = 0.0
    private val delayLeft = DoubleArray(RATE / 4)
    private val delayRight = DoubleArray(RATE / 4)
    private var delayPosition = 0

    fun setPreset(index: Int) { requestedPreset = index.coerceIn(Soundscapes.all.indices) }
    fun setBassSolo(enabled: Boolean) { bassSolo = enabled }
    fun setDebugSpeedJourney(enabled: Boolean) {
        debugSpeedJourney = enabled
        journeyFrames = 0L
    }

    fun render(pcm: ShortArray) {
        require(pcm.size % CHANNELS == 0)
        if (debugSpeedJourney) {
            model.setSpeed(speedJourneyKmh(journeyFrames.toDouble() / RATE))
            journeyFrames += pcm.size / CHANNELS
        }
        model.tick()
        val bpm = model.bpm()
        val energy = model.intensity()
        val momentum = model.momentum()
        val accelerator = model.accelerator()
        val acceleration = model.acceleration()
        val speedLevel = model.speedLevel()
        val rhythmLevel = model.rhythmLevel()
        val layerMask = model.layerMask()
        val brake = model.brake()
        val steering = model.steering()
        val turn = model.turn()
        val wiper = model.wiper()
        val driveMode = model.driveMode()
        val frameTime = 1.0 / RATE
        val steeringPitch = 2.0.pow(steering * 0.35 / 12.0)
        var output = 0

        repeat(pcm.size / CHANNELS) {
            if (requestedPreset != activePreset && !fadingOut) fadingOut = true
            if (fadingOut) {
                transition = max(0.0, transition - 1.0 / TRANSITION_SAMPLES)
                if (transition == 0.0) {
                    activePreset = requestedPreset
                    lastStep = -1L
                    fadingOut = false
                }
            } else if (transition < 1.0) {
                transition = min(1.0, transition + 1.0 / TRANSITION_SAMPLES)
            }
            val scene = Soundscapes.all[activePreset]
            beat += bpm / (60.0 * RATE)
            val step = floor(beat * 4.0).toLong()
            if (step != lastStep) {
                if (step % 16L == 0L) {
                    for (layer in 1..5) layerTargets[layer] = if (layerMask and (1 shl layer) != 0) 1.0 else 0.0
                    model.setAudibleLayerMask(layerMask)
                }
                triggerStep(scene, step, energy, steering, rhythmLevel, turn)
                lastStep = step
            }
            val layerStep = 1.0 / (RATE * 1.15)
            for (layer in 1..5) layerMixes[layer] +=
                (layerTargets[layer] - layerMixes[layer]).coerceIn(-layerStep, layerStep)

            val kick = if (kickAge < 0.8) {
                val frequency = 49.0 + 95.0 * exp(-kickAge * 24.0)
                kickPhase += TWO_PI * frequency * frameTime
                sin(kickPhase) * exp(-kickAge * 13.5) * (0.23 + speedLevel * 0.06)
            } else 0.0
            val noise = random.nextDouble() * 2.0 - 1.0
            val snare = if (snareAge < 0.4) noise * exp(-snareAge * 24.0) * 0.105 else 0.0
            val hatNoise = noise - previousNoise * 0.75
            previousNoise = noise
            val hat = if (hatAge < 0.2) hatNoise * exp(-hatAge * 78.0) *
                (0.012 + speedLevel * 0.035 + energy * 0.012) else 0.0

            bassPhase += TWO_PI * bassFrequency * frameTime
            val bassWave = when (activePreset) {
                1 -> sin(bassPhase) * 0.92 + sin(bassPhase * 3.0) * (0.05 + accelerator * 0.07)
                2 -> sin(bassPhase) * 0.70 + sin(bassPhase * 2.0) * (0.10 + accelerator * 0.13)
                3 -> sin(bassPhase) * 0.78 + sin(bassPhase * 2.0) * (0.16 + accelerator * 0.18) + sin(bassPhase * 3.0) * 0.09
                // Root, octave and double octave are the same musical note.
                // Move the weight toward the octaves as speed rises; no chromatic pitch sweep.
                4 -> sin(bassPhase) * (0.76 - speedLevel * 0.53) +
                    sin(bassPhase * 2.0) * (0.12 + speedLevel * 0.57) +
                    sin(bassPhase * 4.0) * (0.03 + speedLevel * 0.14)
                // Speed changes the existing note's harmonic color continuously.
                else -> sin(bassPhase) * (0.90 - speedLevel * 0.20) +
                    sin(bassPhase * 2.0) * (0.08 + speedLevel * 0.35 + accelerator * 0.10) +
                    sin(bassPhase * 3.0) * speedLevel * 0.05
            }
            val bassDecay = if (activePreset == 4) 3.8 - speedLevel * 0.55 + accelerator * 0.7
                else 3.4 - speedLevel * 0.8 + accelerator * 1.3
            val bassDrive = if (activePreset == 4) 0.75 + accelerator * 0.22 + speedLevel * 0.13
                else 0.68 + accelerator * 0.32 + speedLevel * 0.45
            val bass = (if (bassAge < 1.4) bassWave * onset(bassAge) * exp(-bassAge * bassDecay) *
                scene.bassGain * bassDrive * release(bassAge, 1.4) else 0.0) + bassDeclick
            bassDeclick *= DECLICK
            lastBass = bass
            driveBassPhase += TWO_PI * driveBassHz * frameTime
            val driveBass = if (activePreset == 4) scene.bassGain * 0.22 *
                (sin(driveBassPhase) * 0.94 + sin(driveBassPhase * 2.0) * 0.06)
                else 0.0
            val bassStem = (bass + driveBass) * (1.0 - brake * 0.18)

            for (voice in padPhase.indices) padPhase[voice] += TWO_PI * padFrequency[voice] * frameTime
            val padEnvelope = (0.84 + energy * 0.16) * scene.padGain * (1.0 - 0.20 * exp(-kickAge * 12.0)) *
                (1.0 - brake * 0.58) * (1.0 - max(0.0, -acceleration) * 0.10)
            val padLeft = (sin(padPhase[0]) * 0.50 + sin(padPhase[1]) * 0.32 + sin(padPhase[2]) * 0.18) * padEnvelope
            val padRight = (sin(padPhase[0] + 0.22) * 0.40 + sin(padPhase[1] + 0.58) * 0.36 +
                sin(padPhase[2] + 1.05) * 0.24) * padEnvelope

            leadPhase += TWO_PI * leadFrequency * steeringPitch * frameTime
            val leadWave = when (activePreset) {
                1 -> sin(leadPhase) * 0.85 + sin(leadPhase * 2.01) * 0.12
                2 -> sin(leadPhase) * (0.92 + sin(beat * TWO_PI / 8.0) * 0.06)
                3 -> sin(leadPhase) * 0.76 + sin(leadPhase * 2.0) * 0.20 + sin(leadPhase * 3.0) * 0.08
                4 -> sin(leadPhase) * 0.82 + sin(leadPhase * 2.0) * 0.14 + sin(leadPhase * 4.0) * 0.04
                else -> sin(leadPhase) + sin(leadPhase * 2.0) * scene.brightness * 0.28
            }
            val lead = (if (leadAge < 1.0) (leadWave + sin(leadPhase * 2.0) * abs(steering) * 0.08) *
                onset(leadAge) * exp(-leadAge * 5.0) * scene.leadGain *
                (0.55 + energy * 0.45) * release(leadAge, 1.0) else 0.0) + leadDeclick
            leadDeclick *= DECLICK
            lastLead = lead
            arpPhase += TWO_PI * arpFrequency * frameTime
            val arp = (if (arpAge < 0.55) (sin(arpPhase) * 0.88 + sin(arpPhase * 2.0) * 0.12) *
                onset(arpAge) * exp(-arpAge * 7.0) * 0.042 * release(arpAge, 0.55) else 0.0) + arpDeclick
            arpDeclick *= DECLICK
            lastArp = arp
            shimmerPhase += TWO_PI * shimmerFrequency * frameTime
            val shimmer = (if (shimmerAge < 1.1) (sin(shimmerPhase) * 0.72 +
                sin(shimmerPhase * 2.0) * 0.28) * onset(shimmerAge) * exp(-shimmerAge * 3.2) * 0.024 *
                release(shimmerAge, 1.1) else 0.0) + shimmerDeclick
            shimmerDeclick *= DECLICK
            lastShimmer = shimmer
            val turnVoice = if (turnAge < 0.52) {
                val second = turnAge >= 0.18
                val noteAge = if (second) turnAge - 0.18 else turnAge
                turnPhase += TWO_PI * (if (second) turnSecondHz else turnFirstHz) * frameTime
                sin(turnPhase) * (1.0 - exp(-noteAge * 150.0)) * exp(-noteAge * 11.0) *
                    (if (second) 0.10 else 0.14)
            } else 0.0
            val echoLeft = delayLeft[delayPosition]
            val echoRight = delayRight[delayPosition]
            delayLeft[delayPosition] = lead * layerMixes[4] * 0.28 + echoRight * 0.26
            delayRight[delayPosition] = lead * layerMixes[4] * 0.18 + echoLeft * 0.26
            delayPosition = (delayPosition + 1) % delayLeft.size

            val rhythm = (kick + snare + hat * (1.0 + if (wiper) 0.35 else 0.0)) *
                (0.70 + energy * 0.30) * (1.0 - brake * 0.28)
            val surge = if (momentum > 0.18) hatNoise * momentum * 0.008 else 0.0
            val coast = if (acceleration < -0.05) hatNoise * -acceleration * 0.006 else 0.0
            val texture = if (wiper && hatAge < 0.12) hatNoise * exp(-hatAge * 36.0) * 0.025 else 0.0
            val steeringResponse = max(layerMixes[4], abs(steering) * 0.52)
            val leadLeft = lead * (0.66 - steering * 0.35) * steeringResponse
            val leadRight = lead * (0.66 + steering * 0.35) * steeringResponse
            val fullLeft = rhythm * layerMixes[1] + bassStem + padLeft * layerMixes[2] +
                arp * layerMixes[3] * 0.78 + leadLeft + echoLeft +
                shimmer * layerMixes[5] * 0.70 + surge + coast + texture +
                turnVoice * (0.60 - turnPan * 0.35)
            val fullRight = rhythm * layerMixes[1] + bassStem + padRight * layerMixes[2] +
                arp * layerMixes[3] + leadRight + echoRight +
                shimmer * layerMixes[5] - surge - coast - texture +
                turnVoice * (0.60 + turnPan * 0.35)
            val left = tanh((if (bassSolo) bassStem else fullLeft) * 1.75) * 0.78 * transition
            val right = tanh((if (bassSolo) bassStem else fullRight) * 1.75) * 0.78 * transition
            pcm[output++] = (left * 32767.0).toInt().toShort()
            pcm[output++] = (right * 32767.0).toInt().toShort()

            kickAge += frameTime
            snareAge += frameTime
            hatAge += frameTime
            bassAge += frameTime
            leadAge += frameTime
            turnAge += frameTime
            arpAge += frameTime
            shimmerAge += frameTime
        }
    }

    private fun triggerStep(scene: Soundscape, absoluteStep: Long, energy: Double,
                            steering: Double, rhythmLevel: Int, turn: Int) {
        val position = (absoluteStep % 16).toInt()
        val bar = absoluteStep / 16
        val chordIndex = (bar % scene.chords.size).toInt()
        val chord = scene.chords[chordIndex]
        val tones = scene.chordTones?.get(chordIndex) ?: scene.scale
        if (position == 0) {
            val root = scene.rootHz * 2.0 * ratio(chord)
            driveBassHz = scene.rootHz * ratio(chord)
            padFrequency[0] = root
            padFrequency[1] = root * ratio(tones[if (scene.chordTones == null) 2 else 1])
            padFrequency[2] = root * ratio(tones[if (scene.chordTones == null) 4 else 2])
        }
        if ((scene.kick.hasStep(position) && (rhythmLevel >= 1 || position == 0 || position == 8)) ||
            (activePreset == 4 && rhythmLevel == 2 && position == 14)) {
            kickAge = 0.0
            kickPhase = 0.0
        }
        if (scene.snare.hasStep(position) && energy >= 0.12) snareAge = 0.0
        if ((scene.hat.hasStep(position) && energy >= 0.15) ||
            (rhythmLevel >= 1 && position % 4 == 0) ||
            (rhythmLevel >= 2 && position % 4 == 3)) hatAge = 0.0
        if (scene.bass.hasStep(position)) {
            val degree = if (position % 8 == 0) 0 else if (position % 4 == 0) 2 else 1
            bassFrequency = scene.rootHz * ratio(chord + tones[degree])
            bassDeclick = lastBass
            bassAge = 0.0
            bassPhase = 0.0
        }
        if (position == 2 || position == 6 || position == 10 || position == 14) {
            val degree = tones[(position / 4 + (bar % 2).toInt()) % tones.size]
            arpFrequency = scene.rootHz * 4.0 * ratio(chord + degree)
            arpDeclick = lastArp
            arpAge = 0.0
            arpPhase = 0.0
        }
        if (position == 0 || position == 8) {
            shimmerFrequency = scene.rootHz * 8.0 * ratio(chord + tones[if (position == 0) 2 else 4])
            shimmerDeclick = lastShimmer
            shimmerAge = 0.0
            shimmerPhase = 0.0
        }
        val note = scene.melody[position]
        if (note >= 0 && energy >= (if (activePreset == 4) 0.16 else 0.24) &&
            (bar % 4 != 3L || position % 4 == 0)) {
            val octave = if ((bar / 4) % 2 == 1L) 12 else 0
            val directed = (note + if (steering > 0.16) 1 else if (steering < -0.16) -1 else 0)
                .coerceIn(tones.indices)
            leadFrequency = scene.rootHz * 4.0 * ratio(chord + tones[directed] + octave)
            leadDeclick = lastLead
            leadAge = 0.0
            leadPhase = 0.0
        }
        // A held indicator keeps a gentle two-note answer on the musical half-bar.
        if (turn != 0 && (position == 0 || position == 8)) {
            val low = scene.rootHz * 4.0 * ratio(chord + tones[1])
            val high = scene.rootHz * 4.0 * ratio(chord + tones[3])
            turnFirstHz = if (turn < 0) high else low
            turnSecondHz = if (turn < 0) low else high
            turnPan = turn.toDouble()
            turnAge = 0.0
            turnPhase = 0.0
        }
        if (bar % 8 == 7L && position == 14 && energy >= 0.48) snareAge = 0.0
    }

    /** ~3 ms fade-in so a note never starts at full level. */
    private fun onset(age: Double) = 1.0 - exp(-age * 900.0)
    /** 60 ms fade before a voice's hard length limit. */
    private fun release(age: Double, limit: Double) = ((limit - age) / 0.06).coerceIn(0.0, 1.0)

    private fun Int.hasStep(position: Int) = (this and (1 shl position)) != 0
    private fun ratio(semitones: Int) = 2.0.pow(semitones / 12.0)
}
