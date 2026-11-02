package com.drivesoundtrack.app

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tanh

/** Five original, phrase-based soundscapes with vehicle-controlled layers. Stereo 16-bit PCM. */
class SoundRenderer(private val model: SoundModel) {
    companion object {
        const val RATE = 44100
        const val CHANNELS = 2
        private const val TWO_PI = 2.0 * PI
        private const val TRANSITION_SAMPLES = RATE * 0.35
    }

    private val random = Random(1049)
    @Volatile private var requestedPreset = 0
    private var activePreset = 0
    private var transition = 1.0
    private var fadingOut = false
    private var beat = 0.0
    private var lastStep = -1L
    private var kickAge = 10.0
    private var snareAge = 10.0
    private var hatAge = 10.0
    private var bassAge = 10.0
    private var leadAge = 10.0
    private var kickPhase = 0.0
    private var bassPhase = 0.0
    private var leadPhase = 0.0
    private val padPhase = DoubleArray(3)
    private val padFrequency = DoubleArray(3) { 110.0 * (it + 1) }
    private var bassFrequency = 55.0
    private var leadFrequency = 220.0
    private var lastTurn = 0
    private var pendingTurn = 0
    private var previousNoise = 0.0
    private val delayLeft = DoubleArray(RATE / 4)
    private val delayRight = DoubleArray(RATE / 4)
    private var delayPosition = 0

    fun setPreset(index: Int) { requestedPreset = index.coerceIn(Soundscapes.all.indices) }

    fun render(pcm: ShortArray) {
        require(pcm.size % CHANNELS == 0)
        model.tick()
        val bpm = model.bpm()
        val energy = model.intensity()
        val momentum = model.momentum()
        val accelerator = model.accelerator()
        val brake = model.brake()
        val steering = model.steering()
        val turn = model.turn()
        val wiper = model.wiper()
        val driveMode = model.driveMode()
        val frameTime = 1.0 / RATE
        var output = 0

        if (turn != lastTurn) {
            if (turn != 0) pendingTurn = turn
            lastTurn = turn
        }

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
                triggerStep(scene, step, energy, steering)
                lastStep = step
            }

            val kick = if (kickAge < 0.8) {
                val frequency = 49.0 + 95.0 * exp(-kickAge * 24.0)
                kickPhase += TWO_PI * frequency * frameTime
                sin(kickPhase) * exp(-kickAge * 13.5) * 0.26
            } else 0.0
            val noise = random.nextDouble() * 2.0 - 1.0
            val snare = if (snareAge < 0.4) noise * exp(-snareAge * 24.0) * 0.105 else 0.0
            val hatNoise = noise - previousNoise * 0.75
            previousNoise = noise
            val hat = if (hatAge < 0.2) hatNoise * exp(-hatAge * 78.0) * (0.014 + energy * 0.017) else 0.0

            bassPhase += TWO_PI * bassFrequency * frameTime
            val bassWave = when (activePreset) {
                1 -> sin(bassPhase) * 0.92 + sin(bassPhase * 3.0) * (0.05 + accelerator * 0.07)
                2 -> sin(bassPhase) * 0.70 + sin(bassPhase * 2.0) * (0.10 + accelerator * 0.13)
                3 -> sin(bassPhase) * 0.78 + sin(bassPhase * 2.0) * (0.16 + accelerator * 0.18) + sin(bassPhase * 3.0) * 0.09
                4 -> sin(bassPhase) * 0.60 + sin(bassPhase * 2.0) * (0.25 + accelerator * 0.20) + sin(bassPhase * 4.0) * 0.10
                else -> sin(bassPhase) * 0.82 + sin(bassPhase * 2.0) * (0.16 + accelerator * 0.20)
            }
            val bass = if (bassAge < 1.4) bassWave * exp(-bassAge * (3.4 + accelerator * 1.3)) *
                scene.bassGain * (0.82 + accelerator * 0.34) else 0.0

            for (voice in padPhase.indices) padPhase[voice] += TWO_PI * padFrequency[voice] * frameTime
            val padEnvelope = (0.84 + energy * 0.16) * scene.padGain * (1.0 - 0.20 * exp(-kickAge * 12.0)) *
                (1.0 - brake * 0.38)
            val padLeft = (sin(padPhase[0]) * 0.50 + sin(padPhase[1]) * 0.32 + sin(padPhase[2]) * 0.18) * padEnvelope
            val padRight = (sin(padPhase[0] + 0.22) * 0.40 + sin(padPhase[1] + 0.58) * 0.36 +
                sin(padPhase[2] + 1.05) * 0.24) * padEnvelope

            leadPhase += TWO_PI * leadFrequency * frameTime
            val leadWave = when (activePreset) {
                1 -> sin(leadPhase) * 0.85 + sin(leadPhase * 2.01) * 0.12
                2 -> sin(leadPhase) * (0.92 + sin(beat * TWO_PI / 8.0) * 0.06)
                3 -> sin(leadPhase) * 0.76 + sin(leadPhase * 2.0) * 0.20 + sin(leadPhase * 3.0) * 0.08
                4 -> sin(leadPhase) * 0.55 + sin(leadPhase * 2.0) * 0.30 + sin(leadPhase * 4.0) * 0.15
                else -> sin(leadPhase) + sin(leadPhase * 2.0) * scene.brightness * 0.28
            }
            val lead = if (leadAge < 1.0) leadWave * exp(-leadAge * 5.0) * scene.leadGain *
                (0.55 + energy * 0.45) else 0.0
            val echoLeft = delayLeft[delayPosition]
            val echoRight = delayRight[delayPosition]
            delayLeft[delayPosition] = lead * 0.28 + echoRight * 0.26
            delayRight[delayPosition] = lead * 0.18 + echoLeft * 0.26
            delayPosition = (delayPosition + 1) % delayLeft.size

            val rhythm = (kick + snare + hat * (1.0 + if (wiper) 0.35 else 0.0)) *
                (0.70 + energy * 0.30) * (1.0 - brake * 0.28)
            val surge = if (momentum > 0.18) hatNoise * momentum * 0.008 else 0.0
            val texture = if (wiper && hatAge < 0.12) hatNoise * exp(-hatAge * 36.0) * 0.025 else 0.0
            val leadLeft = lead * (0.66 - steering * 0.18)
            val leadRight = lead * (0.66 + steering * 0.18)
            val left = tanh((rhythm + bass + padLeft + leadLeft + echoLeft + surge + texture) * 1.75) * 0.78 * transition
            val right = tanh((rhythm + bass + padRight + leadRight + echoRight - surge - texture) * 1.75) * 0.78 * transition
            pcm[output++] = (left * 32767.0).toInt().toShort()
            pcm[output++] = (right * 32767.0).toInt().toShort()

            kickAge += frameTime
            snareAge += frameTime
            hatAge += frameTime
            bassAge += frameTime
            leadAge += frameTime
        }
    }

    private fun triggerStep(scene: Soundscape, absoluteStep: Long, energy: Double, steering: Double) {
        val position = (absoluteStep % 16).toInt()
        val bar = absoluteStep / 16
        val chord = scene.chords[(bar % scene.chords.size).toInt()]
        if (position == 0) {
            val root = scene.rootHz * 2.0 * ratio(chord)
            padFrequency[0] = root
            padFrequency[1] = root * ratio(scene.scale[2])
            padFrequency[2] = root * ratio(scene.scale[4])
        }
        if (scene.kick.hasStep(position) && (energy >= 0.22 || position == 0 || position == 8)) {
            kickAge = 0.0
            kickPhase = 0.0
        }
        if (scene.snare.hasStep(position) && energy >= 0.12) snareAge = 0.0
        if ((scene.hat.hasStep(position) && energy >= 0.15) ||
            (energy >= 0.76 && position % 2 == 1)) hatAge = 0.0
        if (scene.bass.hasStep(position)) {
            val degree = if (position % 8 == 0) 0 else if (position % 4 == 0) 2 else 1
            bassFrequency = scene.rootHz * ratio(chord + scene.scale[degree])
            bassAge = 0.0
            bassPhase = 0.0
        }
        val note = scene.melody[position]
        if (note >= 0 && energy >= 0.24 && (bar % 4 != 3L || position % 4 == 0)) {
            val octave = if ((bar / 4) % 2 == 1L) 12 else 0
            val directed = (note + if (steering > 0.28) 1 else if (steering < -0.28) -1 else 0)
                .coerceIn(scene.scale.indices)
            leadFrequency = scene.rootHz * 4.0 * ratio(chord + scene.scale[directed] + octave)
            leadAge = 0.0
            leadPhase = 0.0
        }
        if (pendingTurn != 0 && (position == 0 || position == 8)) {
            val degree = if (pendingTurn < 0) 2 else 4
            leadFrequency = scene.rootHz * 4.0 * ratio(chord + scene.scale[degree])
            leadAge = 0.0
            leadPhase = 0.0
            pendingTurn = 0
        }
        if (bar % 8 == 7L && position == 14 && energy >= 0.48) snareAge = 0.0
    }

    private fun Int.hasStep(position: Int) = (this and (1 shl position)) != 0
    private fun ratio(semitones: Int) = 2.0.pow(semitones / 12.0)
}
