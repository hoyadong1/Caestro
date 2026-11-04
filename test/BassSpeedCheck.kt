import com.drivesoundtrack.app.SoundModel
import com.drivesoundtrack.app.SoundRenderer
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

fun main() {
    val speeds = listOf(0.0, 10.0, 20.0, 35.0, 55.0, 80.0, 110.0, 130.0)
    val results = speeds.map { it to bassAt(it) }
    for ((speed, result) in results) {
        println("%3.0f km/h  RMS %.5f  edge %.5f".format(speed, result.first, result.second))
    }
    check(results.last().second.first > results.first().second.first * 1.25) {
        "high-speed bass is not meaningfully stronger than stationary bass"
    }
    check(results.last().second.second > results.first().second.second * 1.25) {
        "high-speed bass is not meaningfully brighter than stationary bass"
    }
}
