package com.drivesoundtrack.app.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Bounded, smoothed musical controls. Unknown vehicle values stay unknown in the UI. */
class SoundModel {
    companion object {
        /** Stages join at the speeds the screen shows and leave 3 km/h below, just enough not to flutter at a steady speed. */
        private val LAYER_ENTER = doubleArrayOf(18.0, 38.0, 58.0, 82.0, 105.0)
        private val LAYER_LEAVE = doubleArrayOf(15.0, 35.0, 55.0, 79.0, 102.0)
        private const val STEERING_DEADZONE = 5.0
        private const val STEERING_FULL = 90.0
    }
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
    private var accelerationTarget = 0.0
    private var acceleration = 0.0
    private var rhythmLevel = 0
    private var layerMask = 1 // bass, drums, chords, arp, melody, shimmer
    private var audibleLayerMask = 1
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
        accelerationTarget = ((targetKmh - previousTargetKmh) / elapsed / 18.0).coerceIn(-1.0, 1.0)
        // Positive speed derivative is a bounded substitute for thrust, never displayed as pedal pressure.
        if (pedalValue == null) throttleTarget = max(0.0, accelerationTarget)
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

    /**
     * Wheel angle -> -1..1 on the range drivers actually use: lane changes are 10-30 degrees,
     * ordinary corners 45-90. Small corrections under 5 degrees are ignored.
     */
    private fun steeringCurve(degrees: Double): Double {
        val magnitude = ((abs(degrees) - STEERING_DEADZONE) / (STEERING_FULL - STEERING_DEADZONE)).coerceIn(0.0, 1.0)
        return if (degrees < 0) -magnitude else magnitude
    }

    /** Called once per audio buffer, including while speed holds steady. */
    @Synchronized fun tick() {
        val delta = targetKmh - smoothedKmh
        smoothedKmh += delta * 0.035
        momentum += (min(1.0, abs(delta) / 55.0) - momentum) * 0.045
        if (pedalValue == null) throttleTarget *= 0.95
        accelerationTarget *= 0.95
        throttle += (throttleTarget - throttle) * 0.07
        acceleration += (accelerationTarget - acceleration) * 0.07
        brakeMix += ((if (brakeValue == true) 1.0 else 0.0) - brakeMix) * 0.08
        steeringMix += (steeringCurve(steeringValue ?: 0.0) - steeringMix) * 0.055
        val driveBoost = when (currentDriveMode) { 2 -> 0.08; 1 -> -0.08; else -> 0.0 }
        val targetEnergy = (0.17 + smoothedKmh / 130.0 * 0.37 + throttle * 0.25 + momentum * 0.13 + driveBoost - brakeMix * 0.13).coerceIn(0.0, 1.0)
        energy += (targetEnergy - energy) * 0.045
        currentBpm += (targetBpm() - currentBpm) * 0.05
        rhythmLevel = when (rhythmLevel) {
            0 -> if (smoothedKmh >= 20.0) 1 else 0
            1 -> if (smoothedKmh >= 52.0) 2 else if (smoothedKmh < 15.0) 0 else 1
            else -> if (smoothedKmh < 45.0) 1 else 2
        }
        // Stages follow the speed the screen shows (not the smoothed one), so the ladder and the music agree.
        var nextMask = 1
        for (i in LAYER_ENTER.indices) {
            val bit = 1 shl (i + 1)
            if (targetKmh >= (if (layerMask and bit != 0) LAYER_LEAVE[i] else LAYER_ENTER[i])) nextMask = nextMask or bit
        }
        layerMask = nextMask
    }

    @Synchronized fun targetBpm(): Double {
        val scene = Soundscapes.all[currentPreset]
        val modeOffset = when (currentDriveMode) { 2 -> 2.0; 1 -> -2.0; else -> 0.0 }
        return scene.baseBpm + targetKmh / 130.0 * scene.tempoRange + modeOffset
    }

    @Synchronized fun bpm(): Double = currentBpm
    @Synchronized fun intensity(): Double = energy
    @Synchronized fun momentum(): Double = momentum
    @Synchronized fun speed(): Double = smoothedKmh
    @Synchronized fun targetSpeed(): Double = targetKmh
    @Synchronized fun accelerator(): Double = throttle
    @Synchronized fun acceleration(): Double = acceleration
    @Synchronized fun speedLevel(): Double = (smoothedKmh / 130.0).coerceIn(0.0, 1.0)
    @Synchronized fun rhythmLevel(): Int = rhythmLevel
    @Synchronized fun layerMask(): Int = layerMask
    @Synchronized fun setAudibleLayerMask(mask: Int) { audibleLayerMask = mask }
    @Synchronized fun audibleLayerMask(): Int = audibleLayerMask
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
    @Synchronized fun layerCount(): Int = Integer.bitCount(layerMask)
}
