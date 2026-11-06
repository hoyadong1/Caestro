package com.drivesoundtrack.app.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Listening tool, not a check: renders one soundscape along a slow, unhurried route so each
 * instrument can be heard for a while before the next one joins.
 *
 *   PREVIEW_PRESET=0 PREVIEW_TAG=after ./gradlew :app:testDebugUnitTest --tests '*PreviewRoute*'
 *
 * Writes build/preview/route-<preset>-<tag>.wav and a matching .csv of the route's legs.
 */
class PreviewRouteTest {
    private data class Leg(val seconds: Double, val toKmh: Double, val label: String)

    private val route = listOf(
        Leg(20.0, 0.0, "정차 · 베이스"),
        Leg(12.0, 26.0, "출발"), Leg(20.0, 26.0, "26 km/h · 드럼 합류"),
        Leg(10.0, 46.0, "가속"), Leg(20.0, 46.0, "46 km/h · 화음 합류"),
        Leg(10.0, 66.0, "가속"), Leg(20.0, 66.0, "66 km/h · 아르페지오 합류"),
        Leg(10.0, 90.0, "가속"), Leg(20.0, 90.0, "90 km/h · 선율 합류"),
        Leg(10.0, 118.0, "가속"), Leg(25.0, 118.0, "118 km/h · 고역 질감 합류 (6개 모두)"),
        Leg(40.0, 0.0, "천천히 감속 · 악기가 하나씩 빠짐"), Leg(10.0, 0.0, "정차"),
    )

    @Test
    fun renderRoute() {
        val preset = System.getenv("PREVIEW_PRESET")?.toIntOrNull()
        assumeTrue("set PREVIEW_PRESET to render a preview", preset != null)
        val tag = System.getenv("PREVIEW_TAG") ?: "preview"
        val model = SoundModel().apply { setPreset(preset!!); setSpeed(0.0) }
        val renderer = SoundRenderer(model).apply { setPreset(preset!!) }
        val buffer = ShortArray(1024 * 2)
        val pcm = java.io.ByteArrayOutputStream()
        val legs = StringBuilder("start_s,label\n")
        var kmh = 0.0
        var seconds = 0.0
        for (leg in route) {
            legs.append("%.2f,%s\n".format(seconds, leg.label))
            val from = kmh
            val blocks = (leg.seconds * SoundRenderer.RATE / 1024).toInt()
            for (block in 0 until blocks) {
                kmh = from + (leg.toKmh - from) * (block + 1) / blocks
                model.setSpeed(kmh)
                renderer.render(buffer)
                for (s in buffer) { pcm.write(s.toInt() and 0xff); pcm.write(s.toInt() shr 8 and 0xff) }
            }
            seconds += blocks * 1024.0 / SoundRenderer.RATE
        }
        val dir = File("build/preview").apply { mkdirs() }
        writeWav(File(dir, "route-$preset-$tag.wav"), pcm.toByteArray())
        File(dir, "route-$preset-$tag.csv").writeText(legs.toString())
    }

    private fun writeWav(file: File, data: ByteArray) = RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.writeBytes("RIFF"); i32(36 + data.size); out.writeBytes("WAVEfmt "); i32(16); i16(1); i16(2)
        i32(SoundRenderer.RATE); i32(SoundRenderer.RATE * 4); i16(4); i16(16); out.writeBytes("data"); i32(data.size)
        out.write(data)
    }
}
