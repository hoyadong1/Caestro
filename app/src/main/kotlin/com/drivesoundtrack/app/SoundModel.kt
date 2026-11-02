package com.drivesoundtrack.app

import kotlin.math.abs
import kotlin.math.min

/** Bounded, smoothed vehicle state used by both the synthesizer and the UI. */
class SoundModel {
    private var targetKmh = 0.0
    private var smoothedKmh = 0.0
    private var currentBpm = Soundscapes.all.first().baseBpm
    private var currentDriveMode = 0
    private var currentPreset = 0
    private var momentum = 0.0
    private var energy = 0.0

    @Synchronized fun setSpeed(kmh: Double) {
        if (kmh.isFinite()) targetKmh = kmh.coerceIn(0.0, 130.0)
    }

    @Synchronized fun setDriveMode(mode: Int) { currentDriveMode = mode.coerceIn(0, 2) }
    @Synchronized fun setPreset(index: Int) { currentPreset = index.coerceIn(Soundscapes.all.indices) }

    /** Called once per audio buffer, including while the vehicle speed holds steady. */
    @Synchronized fun tick() {
        val delta = targetKmh - smoothedKmh
        smoothedKmh += delta * 0.035
        momentum += (min(1.0, abs(delta) / 55.0) - momentum) * 0.045
        val driveBoost = when (currentDriveMode) { 2 -> 0.12; 1 -> -0.10; else -> 0.0 }
        val targetEnergy = (smoothedKmh / 125.0 * 0.78 + momentum * 0.22 + driveBoost).coerceIn(0.0, 1.0)
        energy += (targetEnergy - energy) * 0.045
        currentBpm += (targetBpm() - currentBpm) * 0.05
    }

    @Synchronized fun targetBpm(): Double {
        val scene = Soundscapes.all[currentPreset]
        val modeOffset = when (currentDriveMode) { 2 -> 4.0; 1 -> -3.0; else -> 0.0 }
        return scene.baseBpm + min(scene.speedBpm, targetKmh / 130.0 * scene.speedBpm) + modeOffset
    }

    @Synchronized fun bpm(): Double = currentBpm
    @Synchronized fun intensity(): Double = energy
    @Synchronized fun momentum(): Double = momentum
    @Synchronized fun speed(): Double = smoothedKmh
    @Synchronized fun targetSpeed(): Double = targetKmh
    @Synchronized fun driveMode(): Int = currentDriveMode
    @Synchronized fun preset(): Int = currentPreset
    @Synchronized fun layerCount(): Int = 1 + (energy * 3.0).toInt().coerceIn(0, 3)
}
