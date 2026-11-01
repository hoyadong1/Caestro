package com.drivesoundtrack.app

import java.util.Random
import kotlin.math.*

/** Platform-independent PCM generator shared by the Android app and desktop preview. */
class SoundRenderer(private val model: SoundModel) {
    companion object { const val RATE = 22050; private val NOTES = doubleArrayOf(1.0, 1.1892, 1.3348, 1.4983) }
    private val random = Random(1049)
    @Volatile private var preset = 0
    private var beat = 0.0
    private var kickAge = 1.0
    private var snareAge = 1.0
    private var hatAge = 1.0
    private var lastBeat = -1L
    private var lastHatStep = -1L
    private var bassPhase = 0.0
    private var padPhase = 0.0
    private var lowPass = 0.0

    fun setPreset(value: Int) { preset = value.coerceIn(0, 2) }

    fun render(pcm: ShortArray) {
        model.tick()
        for (i in pcm.indices) {
            val style = preset
            val driveMode = model.driveMode()
            val intensity = model.intensity()
            val bpm = model.bpm()
            beat += bpm / (60.0 * RATE)
            val beatIndex = floor(beat).toLong()
            if (beatIndex != lastBeat) {
                val barBeat = (beatIndex % 4).toInt()
                if (style == 0 || (style == 1 && barBeat == 0) || (style == 2 && (barBeat == 0 || barBeat == 2))) kickAge = 0.0
                if ((style == 0 && (barBeat == 1 || barBeat == 3)) || (style != 0 && barBeat == 2)) snareAge = 0.0
                lastBeat = beatIndex
            }
            val subdivision = if (driveMode == 1 || style == 1) 1 else if (driveMode == 2 || intensity > 0.7) 4 else 2
            val hatStep = floor(beat * subdivision).toLong()
            if (hatStep != lastHatStep) { hatAge = 0.0; lastHatStep = hatStep }
            val base = if (style == 1) 55.0 else if (style == 2) 73.42 else 65.41
            val frequency = base * NOTES[(beatIndex / 4 % NOTES.size).toInt()]
            bassPhase += 2 * PI * frequency / RATE
            padPhase += 2 * PI * (frequency * 2.005) / RATE
            val bassWave = if (style == 2) sin(bassPhase) else tanh(sin(bassPhase) * 2.2)
            val bass = bassWave * (0.11 + intensity * 0.03)
            val pad = sin(padPhase) * (if (style == 1) 0.1 else if (style == 2) 0.08 else 0.04) * (if (driveMode == 1) 1.2 else 1.0)
            val kick = sin(2 * PI * (55 + 70 * exp(-kickAge * 35)) * kickAge) * exp(-kickAge * 18) * 0.28
            val snare = (random.nextDouble() * 2 - 1) * exp(-snareAge * 28) * (if (style == 1) 0.06 else 0.12)
            val hat = (random.nextDouble() * 2 - 1) * exp(-hatAge * 90) * (0.025 + intensity * 0.025)
            val cutoff = 0.015 + intensity * 0.055 + (if (driveMode == 2) 0.02 else 0.0)
            lowPass += (bass + pad - lowPass) * cutoff
            val sample = max(-0.8, min(0.8, lowPass * 0.8 + kick + snare + hat))
            pcm[i] = (sample * 32767).toInt().toShort()
            val dt = 1.0 / RATE
            kickAge += dt; snareAge += dt; hatAge += dt
        }
    }
}
