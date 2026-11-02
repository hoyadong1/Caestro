package com.drivesoundtrack.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bounded, smoothed musical controls. Unknown vehicle values stay unknown in the UI. */
class SoundModel {
    private var targetKmh = 0.0
    private var smoothedKmh = 0.0
    private var previousTargetKmh = 0.0
    private var lastSpeedNanos = 0L
    private var currentBpm = Soundscapes.all.first().baseBpm
    private var currentDriveMode = 0
    private var currentPreset = 0
    private var momentum = 0.0
    private var energy = 0.0
    private var throttleTarget = 0.0
    private var throttle = 0.0
    private var pedalValue: Double? = null
    private var brakeValue: Boolean? = null
    private var brakeMix = 0.0
    private var steeringValue: Double? = null
    private var steeringMix = 0.0
    private var turnValue: Int? = null
    private var wiperValue: Boolean? = null

    @Synchronized fun setSpeed(kmh: Double) {
        if (!kmh.isFinite()) return
        val now = System.nanoTime()
        val elapsed = if (lastSpeedNanos == 0L) 1.0 else ((now - lastSpeedNanos) / 1e9).coerceIn(0.08, 2.0)
        lastSpeedNanos = now
        previousTargetKmh = targetKmh
        targetKmh = kmh.coerceIn(0.0, 130.0)
        // Speed derivative is a bounded substitute for thrust, never displayed as pedal pressure.
        if (pedalValue == null) throttleTarget = (max(0.0, targetKmh - previousTargetKmh) / elapsed / 18.0).coerceIn(0.0, 1.0)
    }

    @Synchronized fun setAccelerator(percent: Double?) {
        pedalValue = percent?.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0)
        if (pedalValue != null) throttleTarget = pedalValue!! / 100.0
    }
    @Synchronized fun setBrake(pressed: Boolean?) { brakeValue = pressed }
    @Synchronized fun setSteering(degrees: Double?) { steeringValue = degrees?.takeIf { it.isFinite() }?.coerceIn(-540.0, 540.0) }
    @Synchronized fun setTurn(direction: Int?) { turnValue = direction?.coerceIn(-1, 1) }
    @Synchronized fun setWiper(active: Boolean?) { wiperValue = active }
    @Synchronized fun setDriveMode(mode: Int) { currentDriveMode = mode.coerceIn(0, 2) }
    @Synchronized fun setPreset(index: Int) { currentPreset = index.coerceIn(Soundscapes.all.indices) }

    /** Called once per audio buffer, including while speed holds steady. */
    @Synchronized fun tick() {
        val delta = targetKmh - smoothedKmh
        smoothedKmh += delta * 0.035
        momentum += (min(1.0, abs(delta) / 55.0) - momentum) * 0.045
        if (pedalValue == null) throttleTarget *= 0.95
        throttle += (throttleTarget - throttle) * 0.07
        brakeMix += ((if (brakeValue == true) 1.0 else 0.0) - brakeMix) * 0.08
        steeringMix += (((steeringValue ?: 0.0) / 540.0) - steeringMix) * 0.055
        val driveBoost = when (currentDriveMode) { 2 -> 0.08; 1 -> -0.08; else -> 0.0 }
        val targetEnergy = (0.17 + smoothedKmh / 130.0 * 0.37 + throttle * 0.25 + momentum * 0.13 + driveBoost - brakeMix * 0.13).coerceIn(0.0, 1.0)
        energy += (targetEnergy - energy) * 0.045
        currentBpm += (targetBpm() - currentBpm) * 0.05
    }

    @Synchronized fun targetBpm(): Double {
        val scene = Soundscapes.all[currentPreset]
        val modeOffset = when (currentDriveMode) { 2 -> 2.0; 1 -> -2.0; else -> 0.0 }
        return scene.baseBpm + min(6.0, targetKmh / 130.0 * 6.0) + modeOffset
    }

    @Synchronized fun bpm(): Double = currentBpm
    @Synchronized fun intensity(): Double = energy
    @Synchronized fun momentum(): Double = momentum
    @Synchronized fun speed(): Double = smoothedKmh
    @Synchronized fun targetSpeed(): Double = targetKmh
    @Synchronized fun accelerator(): Double = throttle
    @Synchronized fun pedalAvailable(): Boolean = pedalValue != null
    @Synchronized fun brake(): Double = brakeMix
    @Synchronized fun brakeAvailable(): Boolean = brakeValue != null
    @Synchronized fun steering(): Double = steeringMix
    @Synchronized fun steeringAvailable(): Boolean = steeringValue != null
    @Synchronized fun turn(): Int = turnValue ?: 0
    @Synchronized fun turnAvailable(): Boolean = turnValue != null
    @Synchronized fun wiper(): Boolean = wiperValue == true
    @Synchronized fun wiperAvailable(): Boolean = wiperValue != null
    @Synchronized fun driveMode(): Int = currentDriveMode
    @Synchronized fun preset(): Int = currentPreset
    @Synchronized fun layerCount(): Int = 2 + (energy * 2.0).toInt().coerceIn(0, 2) + if (wiperValue == true) 1 else 0
}
