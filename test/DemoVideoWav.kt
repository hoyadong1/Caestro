import com.drivesoundtrack.app.SoundModel
import com.drivesoundtrack.app.SoundRenderer
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/** Audio for the Pleos lab sequence in demo/record-v03.ps1, rendered by the app's Kotlin synth. */
fun main(args: Array<String>) {
    require(args.size == 1) { "output WAV path required" }
    val model = SoundModel()
    val renderer = SoundRenderer(model)
    model.setPreset(4)
    renderer.setPreset(4)
    val seconds = 70
    val totalFrames = SoundRenderer.RATE * seconds
    val samples = ShortArray(1024 * SoundRenderer.CHANNELS)
    var frame = 0
    var stage = -1

    FileOutputStream(args[0]).use { out ->
        val bytes = totalFrames * SoundRenderer.CHANNELS * 2
        out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(36 + bytes)
        out.write("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(16)
        out.writeShort(1)
        out.writeShort(SoundRenderer.CHANNELS)
        out.writeInt(SoundRenderer.RATE)
        out.writeInt(SoundRenderer.RATE * SoundRenderer.CHANNELS * 2)
        out.writeShort(SoundRenderer.CHANNELS * 2)
        out.writeShort(16)
        out.write("data".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(bytes)

        while (frame < totalFrames) {
            val time = frame.toDouble() / SoundRenderer.RATE
            val nextStage = when {
                time < 8 -> 0
                time < 18 -> 1
                time < 28 -> 2
                time < 37 -> 3
                time < 46 -> 4
                time < 50 -> 5
                else -> 6
            }
            if (nextStage != stage) {
                stage = nextStage
                when (stage) {
                    0 -> model.setSpeed(0.0)
                    1 -> model.setSpeed(35.0)
                    2 -> { model.setSpeed(55.0); model.setBrake(true) }
                    3 -> { model.setSpeed(36.0); model.setBrake(false); model.setSteering(150.0) }
                    4 -> model.setTurn(-1)
                    5 -> { model.setTurn(0); model.setSpeed(0.0); model.setSteering(0.0) }
                    6 -> { model.setPreset(0); renderer.setPreset(0) }
                }
            }
            renderer.render(samples)
            val count = minOf(samples.size / SoundRenderer.CHANNELS, totalFrames - frame)
            for (i in 0 until count * SoundRenderer.CHANNELS) {
                val value = samples[i].toInt()
                out.write(value)
                out.write(value ushr 8)
            }
            frame += count
        }
    }
    println("Rendered $seconds seconds from SoundRenderer")
}

private fun FileOutputStream.writeShort(value: Int) {
    write(value)
    write(value ushr 8)
}

private fun FileOutputStream.writeInt(value: Int) {
    write(value)
    write(value ushr 8)
    write(value ushr 16)
    write(value ushr 24)
}
