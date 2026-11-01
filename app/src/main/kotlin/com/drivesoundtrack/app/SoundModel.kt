package com.drivesoundtrack.app

import kotlin.math.max
import kotlin.math.min

/** Pure mapping from vehicle context to bounded musical parameters. */
class SoundModel {
    private var targetKmh = 0.0
    private var smoothedKmh = 0.0
    private var currentBpm = 92.0
    private var currentDriveMode = 0

    @Synchronized fun setSpeed(kmh: Double) {
        if (!kmh.isFinite()) return
        targetKmh = max(0.0, min(130.0, kmh))
    }

    /** Called once per audio buffer, so a steady speed still converges. */
    @Synchronized fun tick() {
        smoothedKmh += (targetKmh - smoothedKmh) * 0.04
        val modeAdjustment = when (currentDriveMode) { 2 -> 5.0; 1 -> -4.0; else -> 0.0 }
        val targetBpm = 92.0 + min(43.0, smoothedKmh * 0.4) + modeAdjustment
        currentBpm += (targetBpm - currentBpm) * 0.025
    }

    @Synchronized fun setDriveMode(mode: Int) { currentDriveMode = mode }
    @Synchronized fun bpm(): Double = currentBpm
    @Synchronized fun intensity(): Double = min(1.0, smoothedKmh / 90.0)
    @Synchronized fun speed(): Double = smoothedKmh
    @Synchronized fun targetSpeed(): Double = targetKmh
    @Synchronized fun driveMode(): Int = currentDriveMode
}
