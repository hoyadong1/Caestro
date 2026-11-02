import com.drivesoundtrack.app.SoundModel
import com.drivesoundtrack.app.SoundRenderer
import kotlin.math.abs

private fun render(configure: (SoundModel) -> Unit): ShortArray {
    val model = SoundModel()
    model.setSpeed(36.0)
    configure(model)
    val renderer = SoundRenderer(model)
    val out = ShortArray(44100 * 2)
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

fun main() {
    val neutral = render { }
    val pedal = render { it.setAccelerator(85.0) }
    val brake = render { it.setBrake(true) }
    val steering = render { it.setSteering(-350.0) }
    val turn = render { it.setTurn(1) }
    val wiper = render { it.setWiper(true) }
    for ((name, audio) in listOf("pedal" to pedal, "brake" to brake,
        "steering" to steering, "turn" to turn, "wiper" to wiper)) {
        val delta = difference(neutral, audio)
        check(delta > 0.0001) { "$name has no audible PCM effect" }
        check(audio.any { it.toInt() != 0 }) { "$name output is silent" }
        println("$name PCM delta: ${"%.5f".format(delta)}")
    }
}
