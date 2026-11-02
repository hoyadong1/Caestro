import com.drivesoundtrack.app.SoundModel
import com.drivesoundtrack.app.SoundRenderer
import com.drivesoundtrack.app.Soundscapes
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max

/** Forty-second listening preview: eight seconds of each original soundscape. */
fun main(args: Array<String>) {
    require(args.size == 1) { "output WAV path required" }
    val model = SoundModel()
    val renderer = SoundRenderer(model)
    val secondsPerScene = 8
    val totalFrames = SoundRenderer.RATE * secondsPerScene * Soundscapes.all.size
    val samples = ShortArray(1024 * SoundRenderer.CHANNELS)
    val speed = doubleArrayOf(0.0, 35.0, 70.0, 105.0, 55.0)
    var frame = 0
    var peak = 0

    FileOutputStream(args[0]).use { out ->
        val dataBytes = totalFrames * SoundRenderer.CHANNELS * 2
        out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(36 + dataBytes)
        out.write("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(16)
        out.writeShort(1)
        out.writeShort(SoundRenderer.CHANNELS)
        out.writeInt(SoundRenderer.RATE)
        out.writeInt(SoundRenderer.RATE * SoundRenderer.CHANNELS * 2)
        out.writeShort(SoundRenderer.CHANNELS * 2)
        out.writeShort(16)
        out.write("data".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(dataBytes)

        while (frame < totalFrames) {
            val scene = (frame / (SoundRenderer.RATE * secondsPerScene)).coerceAtMost(Soundscapes.all.lastIndex)
            model.setPreset(scene)
            model.setSpeed(speed[scene])
            renderer.setPreset(scene)
            renderer.render(samples)
            val framesToWrite = minOf(samples.size / SoundRenderer.CHANNELS, totalFrames - frame)
            for (i in 0 until framesToWrite * SoundRenderer.CHANNELS) {
                val value = samples[i]
                peak = max(peak, abs(value.toInt()))
                out.write(value.toInt())
                out.write(value.toInt() ushr 8)
            }
            frame += framesToWrite
        }
    }
    check(peak in 1000..32766) { "unexpected audio peak: $peak" }
    println("WAV preview written: ${Soundscapes.all.joinToString { it.name }}; peak=$peak")
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
