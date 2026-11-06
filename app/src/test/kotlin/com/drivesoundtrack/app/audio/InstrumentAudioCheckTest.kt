package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.abs

private fun render(configure: (SoundModel) -> Unit): ShortArray {
    val model = SoundModel()
    model.setSpeed(36.0)
    configure(model)
    val renderer = SoundRenderer(model)
    val out = ShortArray(44100 * 2 * 4)
    val buffer = ShortArray(1024 * 2)
    var position = 0
    while (position < out.size) {
        renderer.render(buffer)
        val count = minOf(buffer.size, out.size - position)
        buffer.copyInto(out, position, 0, count)
        position += count
    }
    return out
}

private fun difference(a: ShortArray, b: ShortArray): Double {
    var total = 0.0
    for (i in a.indices) total += abs(a[i].toInt() - b[i].toInt())
    return total / a.size / 32768.0
}

class InstrumentAudioCheckTest {
    @Test
    fun runChecks() {
        val neutral = render { }
        val pedal = render { it.setAccelerator(85.0) }
        val brake = render { it.setBrake(true) }
        val steering = render { it.setSteering(-80.0) }
        val turn = render { it.setTurn(1) }
        val wiper = render { it.setWiper(true) }
        for ((name, audio) in listOf("pedal" to pedal, "brake" to brake,
            "steering" to steering, "turn" to turn, "wiper" to wiper)) {
            val delta = difference(neutral, audio)
            check(delta > 0.0001) { "$name has no audible PCM effect" }
            check(audio.any { it.toInt() != 0 }) { "$name output is silent" }
            println("$name PCM delta: ${"%.5f".format(delta)}")
        }

        val leftTurn = render { it.setTurn(-1) }
        val rightTurn = render { it.setTurn(1) }
        check(difference(leftTurn, rightTurn) > 0.001) { "left and right turn motifs sound alike" }
        fun responseEnergy(audio: ShortArray, channel: Int): Double =
            audio.indices.filter { it % 2 == channel }.sumOf { abs(audio[it].toInt() - neutral[it].toInt()).toDouble() }
        check(responseEnergy(leftTurn, 0) > responseEnergy(leftTurn, 1) * 1.12) { "left cue is not left-panned" }
        check(responseEnergy(rightTurn, 1) > responseEnergy(rightTurn, 0) * 1.12) { "right cue is not right-panned" }
        val gentleLeft = render { it.setSteering(-30.0) }
        val hardLeft = render { it.setSteering(-90.0) }
        val hardRight = render { it.setSteering(90.0) }
        check(difference(gentleLeft, hardLeft) > 0.0005) { "steering amount has no PCM effect" }
        check(difference(hardLeft, hardRight) > 0.001) { "steering direction has no PCM effect" }
        val slow = render { it.setSpeed(10.0) }
        val fast = render { it.setSpeed(70.0) }
        check(difference(slow, fast) > 0.005) { "speed progression has no PCM effect" }

        val model = SoundModel()
        model.setSteering(30.0)
        repeat(80) { model.tick() }
        val halfTurn = model.steering()
        model.setSteering(60.0)
        repeat(80) { model.tick() }
        check(model.steering() > halfTurn * 1.8) { "steering amount is not continuous" }
        model.setSpeed(35.0)
        repeat(100) { model.tick() }
        check(model.rhythmLevel() == 1) { "cruise drum layer did not enter" }
        model.setSpeed(55.0)
        repeat(100) { model.tick() }
        check(model.rhythmLevel() == 2 && model.layerCount() <= 4) { "fast drum layer did not enter" }
        model.setSpeed(48.0)
        repeat(100) { model.tick() }
        check(model.rhythmLevel() == 2) { "fast drum layer dropped without hysteresis" }
        model.setSpeed(40.0)
        repeat(100) { model.tick() }
        check(model.rhythmLevel() == 1) { "fast drum layer did not exit" }
        val layered = render {
            it.setSpeed(55.0)
            it.setAccelerator(70.0)
            it.setBrake(true)
            it.setSteering(220.0)
            it.setTurn(-1)
            it.setWiper(true)
        }
        check(difference(layered, neutral) > 0.01) { "combined drive state has no musical effect" }
        check(difference(layered, brake) > 0.005 && difference(layered, leftTurn) > 0.005) {
            "combined drive state collapses to one signal"
        }
        println("directional cues, continuous steering, rhythm layers, and combined state passed")
    }
}
