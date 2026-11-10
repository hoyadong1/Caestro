package com.drivesoundtrack.app.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Renders every built-in instrument with the app's own voices, so the web studio previews the band with
 * the sound the car makes. Run with STUDIO_BANK set; writes build/studio-bank/<id>.wav and bank.json,
 * which tools/studio/bank.py turns into docs/studio/bank/.
 *
 * Melodic instruments: one note every five semitones over the instrument's range, each in its own slot.
 * Rhythm sections: one bar in 4/4 and one in 3/4 at 90 BPM, at the busiest drum level.
 */
class StudioBankExport {
    private val rate = SoundRenderer.RATE
    private val lead = 0.05 // seconds of silence before each slot's note

    @Test
    fun export() {
        assumeTrue("set STUDIO_BANK to export the studio's instrument bank", System.getenv("STUDIO_BANK") != null)
        val out = File("build/studio-bank").apply { deleteRecursively(); mkdirs() }
        val controls = Controls().apply { speed = 0.5; accelerator = 0.2 }
        val entries = mutableListOf<String>()
        val rendered = mutableMapOf<String, DoubleArray>()
        val metas = mutableMapOf<String, String>()
        for ((index, kind) in Instruments.all.withIndex()) {
            if (kind.cloud || kind.id == "none") continue
            val stages = (0..5).filter { index in Instruments.options[it] }
            if (kind.kit >= 0 || kind.sampleKit >= 0) {
                val bars = mutableListOf<Double>()
                val loops = mutableListOf<String>()
                for (beats in listOf(4, 3)) {
                    val part = Instruments.build(index, 1)!!
                    val spb = beats * 4
                    val stepSamples = (60.0 / 90.0 / 4.0 * rate).toInt()
                    val take = mutableListOf<Double>()
                    for (bar in 0 until 3) for (step in 0 until spb) {
                        (part as? Kit)?.step(step, spb, bar.toLong(), 2, false, midiHz(36), stepSamples)
                        (part as? SampleKit)?.step(step, spb, bar.toLong(), 2, false, midiHz(36))
                        repeat(stepSamples) { part.render(controls); if (bar == 1) take += (part.left + part.right) * 0.5 * kind.gain }
                    }
                    loops += "\"$beats\": [${"%.4f".format(bars.size.toDouble() / rate)}, ${"%.4f".format(take.size.toDouble() / rate)}]"
                    bars += take
                }
                rendered[kind.id] = bars.toDoubleArray()
                metas[kind.id] = "\"kind\": \"rhythm\", \"bpm\": 90, \"loops\": {${loops.joinToString(", ")}}"
            } else {
                val sustained = kind.sustained
                val slot = if (sustained) 4.2 else 2.6
                val gate = if (sustained) 3.6 else 0.9
                val notes = mutableListOf<String>()
                val audio = mutableListOf<Double>()
                var midi = kind.minMidi
                val list = mutableListOf<Int>()
                while (midi <= kind.maxMidi) { list += midi; midi += 5 }
                if (list.last() < kind.maxMidi - 2) list += kind.maxMidi
                for (m in list) {
                    val part = Instruments.build(index, stages.firstOrNull() ?: 0) as Poly
                    val start = audio.size
                    repeat((lead * rate).toInt()) { audio += 0.0 }
                    part.play(midiHz(m), (gate * rate).toInt(), 0.9, 0.0)
                    repeat(((slot - lead) * rate).toInt()) { part.render(controls); audio += (part.left + part.right) * 0.5 * kind.gain }
                    notes += "{\"midi\": $m, \"at\": ${"%.4f".format(start.toDouble() / rate + lead)}}"
                }
                rendered[kind.id] = audio.toDoubleArray()
                metas[kind.id] = "\"kind\": \"melodic\", \"slot\": ${slot - lead}, \"notes\": [${notes.joinToString(", ")}]"
            }
            entries += kind.id
            println("${kind.id}: ${"%.1f".format(rendered[kind.id]!!.size.toDouble() / rate)} s")
        }
        // One scale for the whole bank keeps the instruments' levels relative to each other, as in the app.
        val peak = rendered.values.maxOf { a -> a.maxOf { abs(it) } }
        val scale = 0.9 / peak
        for ((id, a) in rendered) writeWav(File(out, "$id.wav"), a, scale)
        val json = entries.joinToString(",\n") { id ->
            val kind = Instruments.all.first { it.id == id }
            val index = Instruments.all.indexOf(kind)
            val stages = (0..5).filter { index in Instruments.options[it] }
            " \"$id\": {\"label\": \"${kind.label}\", \"real\": ${kind.real}, \"sustained\": ${kind.sustained}, \"min\": ${kind.minMidi}, \"max\": ${kind.maxMidi}, " +
                "\"stages\": $stages, \"file\": \"$id.mp3\", ${metas[id]}}"
        }
        File(out, "bank.json").writeText("{\n \"scale\": ${"%.6f".format(scale)},\n \"stageGain\": [0.085, 1.0, 0.036, 0.054, 0.066, 0.036],\n \"instruments\": {\n$json\n }\n}\n")
        println("bank: ${entries.size} instruments, scale $scale")
    }

    private fun writeWav(file: File, data: DoubleArray, scale: Double) = RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.writeBytes("RIFF"); i32(36 + data.size * 2); out.writeBytes("WAVEfmt "); i32(16); i16(1); i16(1)
        i32(rate); i32(rate * 2); i16(2); i16(16); out.writeBytes("data"); i32(data.size * 2)
        val bytes = ByteArray(data.size * 2)
        for (i in data.indices) {
            val v = (data[i] * scale * 32767).toInt().coerceIn(-32768, 32767)
            bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
        }
        out.write(bytes)
    }
}
