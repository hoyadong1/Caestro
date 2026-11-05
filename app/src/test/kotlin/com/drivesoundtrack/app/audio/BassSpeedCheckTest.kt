package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.sqrt

private fun bassAt(kmh: Double): Pair<Double, Double> {
    val model = SoundModel()
    model.setPreset(4)
    model.setSpeed(kmh)
    repeat(240) { model.tick() } // Let the speed settle; exclude the initial acceleration transient.
    val renderer = SoundRenderer(model)
    renderer.setPreset(4)
    renderer.setBassSolo(true)
    val buffer = ShortArray(1024 * 2)
    var power = 0.0
    var differencePower = 0.0
    var previous = 0.0
    var frames = 0
    repeat(172) {
        renderer.render(buffer)
        for (i in buffer.indices step 2) {
            val value = buffer[i] / 32768.0
            check(buffer[i] == buffer[i + 1]) { "bass solo contains another stereo instrument" }
            power += value * value
            val delta = value - previous
            differencePower += delta * delta
            previous = value
            frames++
        }
    }
    return sqrt(power / frames) to sqrt(differencePower / frames)
}

class BassSpeedCheckTest {
    @Test
    fun runChecks() {
        val speeds = listOf(0.0, 10.0, 20.0, 35.0, 55.0, 80.0, 110.0, 130.0)
        val results = speeds.map { it to bassAt(it) }
        for ((speed, result) in results) {
            println("%3.0f km/h  RMS %.5f  edge %.5f".format(speed, result.first, result.second))
        }
        val levels = results.map { it.second.first }
        check(levels.max() / levels.min() < 1.8) { "bass speed zones differ mainly in loudness" }
        val low = results.first().second
        val road = results.first { it.first == 80.0 }.second
        val high = results.last().second
        check((road.second / road.first) > (low.second / low.first) * 1.4)
        check((high.second / high.first) > (low.second / low.first) * 1.55)
    }
}
