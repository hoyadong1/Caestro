package com.drivesoundtrack.app.audio

import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

/**
 * Drives one route at 60 km/h: straight, a left lane change, a long left curve, straight, a long
 * right curve, straight. In a curve the strings swell and the image leans into the turn; on the
 * straight they settle. Also writes the route to build/preview/steering-route.wav for listening.
 */
class SteeringResponseTest {
    private data class Leg(val seconds: Double, val degrees: Double)
    private val route = listOf(
        Leg(6.0, 0.0), Leg(4.0, -25.0), Leg(4.0, 0.0), Leg(10.0, -70.0),
        Leg(6.0, 0.0), Leg(10.0, 90.0), Leg(6.0, 0.0),
    )

    // One-pole low-pass filters used as a rough band splitter.
    private var lp250 = 0.0; private var lp300 = 0.0; private var lp2000 = 0.0
    private fun coefficient(hz: Double) = 1.0 - kotlin.math.exp(-2.0 * Math.PI * hz / SoundRenderer.RATE)
    private val A250 = coefficient(250.0); private val A300 = coefficient(300.0); private val A2000 = coefficient(2000.0)

    @Test
    fun curvesSwellAndLeanIntoTheTurn() {
        val model = SoundModel().apply { setSpeed(60.0); setSteering(0.0) }
        val renderer = SoundRenderer(model)
        val buffer = ShortArray(1024 * 2)
        val pcm = ArrayList<Short>()
        val legEnergy = mutableListOf<Pair<Double, Double>>() // (left rms, right rms) over each leg's 2nd half
        val legBrightness = mutableListOf<Double>() // mono: energy 300-2000 Hz / energy below 250 Hz
        for (leg in route) {
            model.setSteering(leg.degrees)
            val frames = (leg.seconds * SoundRenderer.RATE).toInt()
            var left = 0.0; var right = 0.0; var counted = 0
            var lowEnergy = 0.0; var midEnergy = 0.0
            var done = 0
            while (done < frames) {
                renderer.render(buffer)
                for (i in buffer.indices step 2) {
                    pcm += buffer[i]; pcm += buffer[i + 1]
                    if (done + i / 2 > frames / 2) {
                        left += buffer[i].toDouble() * buffer[i]; right += buffer[i + 1].toDouble() * buffer[i + 1]; counted++
                        val mono = (buffer[i] + buffer[i + 1]) / 2.0
                        lp250 += (mono - lp250) * A250; lp300 += (mono - lp300) * A300; lp2000 += (mono - lp2000) * A2000
                        lowEnergy += lp250 * lp250; midEnergy += (lp2000 - lp300) * (lp2000 - lp300)
                    }
                }
                done += buffer.size / 2
            }
            legEnergy += sqrt(left / counted) to sqrt(right / counted)
            legBrightness += sqrt(midEnergy / lowEnergy)
        }
        writeWav(File("build/preview/steering-route.wav"), pcm)

        val (straightL, straightR) = legEnergy[0]
        val (leftCurveL, leftCurveR) = legEnergy[3]
        val (rightCurveL, rightCurveR) = legEnergy[5]
        check(leftCurveL / leftCurveR > straightL / straightR * 1.4) { "left curve should lean left: $legEnergy" }
        check(rightCurveR / rightCurveL > straightR / straightL * 1.4) { "right curve should lean right: $legEnergy" }
        // The far side drops while the strings fill the near side: overall level stays about the same.
        for ((l, r) in listOf(legEnergy[3], legEnergy[5]))
            check((l + r) in (straightL + straightR) * 0.85..(straightL + straightR) * 1.35) { "curve loudness must stay steady: $legEnergy" }
        println("leg brightness: " + legBrightness.joinToString { "%.3f".format(it) })
        println("leg rms (L, R): " + legEnergy.joinToString { "(%.0f, %.0f)".format(it.first, it.second) })
    }

    private fun writeWav(file: File, pcm: List<Short>) {
        file.parentFile.mkdirs()
        RandomAccessFile(file, "rw").use { out ->
            out.setLength(0)
            val bytes = pcm.size * 2
            fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
            fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
            out.writeBytes("RIFF"); i32(36 + bytes); out.writeBytes("WAVEfmt "); i32(16); i16(1); i16(2)
            i32(SoundRenderer.RATE); i32(SoundRenderer.RATE * 4); i16(4); i16(16); out.writeBytes("data"); i32(bytes)
            val data = ByteArray(bytes)
            pcm.forEachIndexed { i, s -> data[i * 2] = s.toInt().toByte(); data[i * 2 + 1] = (s.toInt() shr 8).toByte() }
            out.write(data)
        }
    }
}
