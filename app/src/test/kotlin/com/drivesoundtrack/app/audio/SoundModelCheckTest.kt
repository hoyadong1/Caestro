package com.drivesoundtrack.app.audio

import org.junit.Test

class SoundModelCheckTest {
    @Test
    fun runChecks() {
        val model = SoundModel()
        val idleBpm = model.bpm()
        model.setSpeed(100.0)
        repeat(250) { model.tick() }
        check(model.bpm() > idleBpm && model.bpm() < idleBpm + 8) { "speed must not dominate tempo" }

        model.setAccelerator(80.0)
        model.setBrake(true)
        model.setSteering(-60.0)
        model.setTurn(-1)
        model.setWiper(true)
        repeat(120) { model.tick() }
        check(model.accelerator() > 0.7 && model.pedalAvailable()) { "pedal controls bass" }
        check(model.brake() > 0.9 && model.steering() < -0.4) { "independent brake and steering" }
        check(model.turn() == -1 && model.wiper()) { "event and texture signals" }

        model.setBrake(null)
        model.setSteering(null)
        model.setWiper(null)
        repeat(120) { model.tick() }
        check(!model.brakeAvailable() && !model.steeringAvailable() && !model.wiperAvailable()) { "unknown signals remain unknown" }
        check(model.brake() < 0.01 && model.steering() > -0.01) { "lost signals fade to neutral" }

        model.setSpeed(-100.0)
        model.setAccelerator(Double.NaN)
        repeat(350) { model.tick() }
        check(model.speed() in 0.0..0.01) { "speed clamp" }
        check(model.bpm().isFinite() && model.intensity().isFinite()) { "finite output" }
        for ((speed, expected) in listOf(0.0 to 1, 20.0 to 2, 42.0 to 3,
            62.0 to 4, 88.0 to 5, 112.0 to 6)) {
            val layers = SoundModel()
            layers.setSpeed(speed)
            repeat(300) { layers.tick() }
            check(layers.layerCount() == expected) { "$speed km/h should have $expected instruments" }
        }
        val descending = SoundModel()
        descending.setSpeed(130.0)
        repeat(300) { descending.tick() }
        check(descending.layerMask() == 63)
        descending.setSpeed(0.0)
        repeat(300) { descending.tick() }
        check(descending.layerMask() == 1) { "all speed layers must leave at rest" }
        println("SoundModel checks passed")
    }
}
