import com.drivesoundtrack.app.SoundModel
import com.drivesoundtrack.app.SoundRenderer
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Renders the Android app's synthesizer without booting an emulator. */
fun main(args: Array<String>) {
    require(args.size in 1..2) { "output WAV path and optional video mode required" }
    val video = args.size == 2 && args[1] == "video"
    val model = SoundModel()
    val renderer = SoundRenderer(model)
    val total = SoundRenderer.RATE * 20
    val audio = ByteArray(total * 2)
    val block = ShortArray(1024)
    var sample = 0
    var peak = 0

    while (sample < total) {
        val second = sample.toDouble() / SoundRenderer.RATE
        model.setSpeed(if (video) {
            when { second < 5 -> 0.0; second < 9 -> 40.0; else -> 90.0 }
        } else {
            when { second < 5 -> 0.0; second < 10 -> 40.0; second < 15 -> 90.0; else -> 20.0 }
        })
        if (video) renderer.setPreset(when { second < 13 -> 0; second < 16 -> 1; else -> 2 })
        renderer.render(block)
        for (raw in block) {
            if (sample >= total) break
            var value = raw
            if (video) {
                val t = sample.toDouble() / SoundRenderer.RATE
                val gain = max(0.0, min(1.0, min((t - 2) / 0.08, (19 - t) / 0.08)))
                value = (value * gain).toInt().toShort()
            }
            peak = max(peak, abs(value.toInt()))
            audio[2 * sample] = value.toByte()
            audio[2 * sample + 1] = (value.toInt() ushr 8).toByte()
            sample++
        }
    }
    check(peak in 1000..32766) { "unexpected audio peak: $peak" }
    FileOutputStream(args[0]).use { out ->
        out.write("RIFF".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(36 + audio.size)
        out.write("WAVEfmt ".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(16)
        out.writeShort(1)
        out.writeShort(1)
        out.writeInt(SoundRenderer.RATE)
        out.writeInt(SoundRenderer.RATE * 2)
        out.writeShort(2)
        out.writeShort(16)
        out.write("data".toByteArray(StandardCharsets.US_ASCII))
        out.writeInt(audio.size)
        out.write(audio)
    }
    println("Preview written; peak=$peak")
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
