package com.drivesoundtrack.app.audio

import org.junit.Test
import kotlin.math.sqrt

/**
 * Every recorded instrument must speak on the beat. Bowed sections used to reach half their level
 * 0.4-0.5 s after the note started (half a beat at 92 BPM), so "Ode to Joy" sounded out of time
 * against the percussion. A note played through the app's own voice must now reach half its
 * level within 100 ms, and must not click when it starts.
 */
class OnsetTest {
    private fun onset(kind: Instruments.Kind, midi: Int): Pair<Int, Double> {
        val c = Controls().apply { speed = 0.5 }
        val part = Instruments.build(Instruments.indexOf(kind.id), Instruments.MELODY) as Poly
        part.play(midiHz(midi), (Dsp.RATE * 1.5).toInt(), 0.9)
        val frame = (Dsp.RATE / 100).toInt() // 10 ms
        val levels = DoubleArray(100)
        var previous = 0.0
        var jump = 0.0
        for (k in levels.indices) {
            var sum = 0.0
            repeat(frame) {
                part.render(c)
                val v = (part.left + part.right) * 0.5
                if (k < 2) jump = maxOf(jump, kotlin.math.abs(v - previous))
                previous = v
                sum += v * v
            }
            levels[k] = sqrt(sum / frame)
        }
        // Held notes are judged against their steady level (0.4-1.0 s), struck ones against their peak.
        val steady = if (kind.sustained) levels.copyOfRange(40, 100).average() else levels.maxOrNull()!!
        return levels.indexOfFirst { it >= steady * 0.5 } * 10 to jump / levels.maxOrNull()!!
    }

    @Test
    fun recordedInstrumentsSpeakOnTheBeat() {
        for (kind in Instruments.all.filter { it.real && it.voice != null }) {
            val midi = ((kind.minMidi + kind.maxMidi) / 2).coerceIn(48, 84)
            val (ms, step) = onset(kind, midi)
            println("%-10s half level after %3d ms, first-20ms step %.2f of peak".format(kind.label, ms, step))
            check(ms < 100) { "${kind.label} comes in late: half level after $ms ms" }
            // Held notes now start inside the recording, so they must fade in; struck ones keep their natural attack.
            if (kind.sustained) check(step < 0.1) { "${kind.label} starts with a click: ${"%.2f".format(step)} of its peak in one sample" }
        }
    }
}
