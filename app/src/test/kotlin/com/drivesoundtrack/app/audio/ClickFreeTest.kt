package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.abs

/**
 * At low speed only the bass (and soft pad) play, so a note that starts or stops mid-waveform is
 * heard as crackle. A click shows up as a sample-to-sample jump far larger than a smooth bass
 * note can make, so the largest jump must stay small for every soundscape.
 */
class ClickFreeTest {
    private fun largestJump(preset: Int, kmh: Double): Int {
        val model = SoundModel()
        model.setPreset(preset)
        model.setSpeed(kmh)
        val renderer = SoundRenderer(model)
        renderer.setPreset(preset)
        val buffer = ShortArray(1024 * 2)
        var previous = IntArray(2)
        var largest = 0
        // Skip the first second while the model settles, then listen for eight seconds.
        repeat(44100 * 9 / 1024) { block ->
            renderer.render(buffer)
            if (block < 44100 / 1024) {
                previous = intArrayOf(buffer[buffer.size - 2].toInt(), buffer[buffer.size - 1].toInt())
                return@repeat
            }
            for (i in buffer.indices) {
                val channel = i % 2
                val value = buffer[i].toInt()
                largest = maxOf(largest, abs(value - previous[channel]))
                previous[channel] = value
            }
        }
        return largest
    }

    @Test
    fun parkedAndCrawlingBassHasNoClicks() {
        for (preset in Soundscapes.all.indices) for (kmh in listOf(0.0, 8.0, 15.0)) {
            val jump = largestJump(preset, kmh)
            // A 110 Hz bass at full level moves at most ~600 per sample; clicks were 1,500+.
            check(jump < 900) { "${Soundscapes.all[preset].name} at $kmh km/h jumps $jump per sample" }
        }
    }
}
