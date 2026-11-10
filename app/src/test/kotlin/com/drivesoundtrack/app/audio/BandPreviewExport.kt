package com.drivesoundtrack.app.audio

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/**
 * Listening sheet for the band: for every classical piece, the default band first, then every built-in
 * instrument each stage offers, played alone for a couple of bars where that stage is busy. One WAV per
 * piece (one scale over all files, so levels compare across pieces), an Audacity label track, and an
 * index.html that plays any clip on click. Run with BAND_PREVIEW set; writes build/band-preview/.
 */
class BandPreviewExport {
    private val rate = 44100
    private val gap = (0.6 * rate).toInt()

    private class Clip(val stage: Int, val id: String, val label: String, val audio: FloatArray)

    /** Growable sample buffer without boxing (a list of Double ran the test JVM out of heap). */
    private class Buf(capacity: Int) {
        var data = FloatArray(maxOf(16, capacity)); var size = 0
        fun add(v: Double) { if (size == data.size) data = data.copyOf(size * 2); data[size++] = v.toFloat() }
        fun addAll(a: FloatArray) { if (size + a.size > data.size) data = data.copyOf(maxOf(size * 2, size + a.size)); a.copyInto(data, size); size += a.size }
        fun toArray() = data.copyOf(size)
    }

    /** [stage] (or the whole band when -1) of [scene], from bar [fromBar] for [bars] bars, mono. */
    private fun render(scene: Soundscape, stage: Int, kind: Int, fromBar: Int, bars: Int): FloatArray {
        val piece = scene.piece!!
        val engine = PieceEngine(piece)
        val ids = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }
        if (stage >= 0) ids[stage] = kind
        engine.setOrchestration(ids)
        val mixes = DoubleArray(6) { if (stage < 0 || it == stage) 1.0 else 0.0 }
        val bpm = scene.baseBpm
        val stepSamples = (60.0 / bpm / 4.0 * rate).toInt()
        val first = fromBar * piece.stepsPerBar
        val last = minOf(piece.lengthSteps, first + bars * piece.stepsPerBar)
        val out = Buf((last - first) * stepSamples + rate)
        for (step in 0 until last) {
            val span = piece.chordAt(step)
            engine.step(step % piece.stepsPerBar, (step / piece.stepsPerBar).toLong(), span.relativeRoot, span.tones, scene, 2, bpm, false)
            repeat(stepSamples) {
                engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm)
                if (step >= first) out.add(if (stage == 0) engine.bass else if (stage < 0) (engine.left + engine.right) * 0.5 + engine.bass else (engine.left + engine.right) * 0.5)
            }
        }
        // Let the last notes ring a little, then fade the tail so clips never end on a click.
        repeat((0.5 * rate).toInt()) { engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm)
            out.add(if (stage == 0) engine.bass else if (stage < 0) (engine.left + engine.right) * 0.5 + engine.bass else (engine.left + engine.right) * 0.5) }
        val a = out.toArray()
        val fade = (0.3 * rate).toInt()
        for (i in 0 until fade) a[a.size - 1 - i] *= i.toFloat() / fade
        return a
    }

    /** First bar of the first 4-bar stretch where the piece's default instrument for [stage] clearly plays. */
    private fun busyBar(scene: Soundscape, stage: Int): Int {
        val piece = scene.piece!!
        val kind = Instruments.indexOf(piece.defaults[stage])
        val whole = render(scene, stage, kind, 0, piece.lengthBars)
        val barSamples = whole.size / piece.lengthBars.coerceAtLeast(1)
        val levels = (0 until piece.lengthBars).map { b ->
            var s = 0.0
            for (i in b * barSamples until minOf(whole.size, (b + 1) * barSamples)) s += whole[i].toDouble() * whole[i]
            s
        }
        val peak = levels.max()
        return levels.indexOfFirst { it > peak * 0.2 }.coerceAtLeast(0).coerceAtMost(maxOf(0, piece.lengthBars - 2))
    }

    @Test
    fun export() {
        assumeTrue("set BAND_PREVIEW to export the band listening sheet", System.getenv("BAND_PREVIEW") != null)
        val out = File("build/band-preview").apply { deleteRecursively(); mkdirs() }
        val scenes = Soundscapes.all.filter { it.piece != null && it.cloudId == null }
        val pieces = scenes.map { scene ->
            val piece = scene.piece!!
            val barSeconds = piece.stepsPerBar * 60.0 / scene.baseBpm / 4.0
            val bars = (5.0 / barSeconds).toInt().coerceIn(1, 4) // about four to six seconds
            val clips = mutableListOf(Clip(-1, "band", "기본 악단 전체 (" + piece.defaults.joinToString(" · ") { id -> Instruments.all.first { it.id == id }.label } + ")",
                render(scene, -1, 0, 0, bars * 2)))
            for (stage in 0..5) {
                val from = busyBar(scene, stage)
                for (kind in Instruments.options[stage]) {
                    val k = Instruments.all[kind]
                    if (k.id == "none" || k.cloud) continue
                    val mark = if (k.id == piece.defaults[stage]) " (기본)" else ""
                    clips += Clip(stage, k.id, "${k.label}$mark", render(scene, stage, kind, from, bars))
                }
            }
            Triple(scene, piece, clips)
        }
        val peak = pieces.flatMap { it.third }.maxOf { c -> c.audio.maxOf { abs(it) } }
        val scale = 0.89 / peak
        val sections = StringBuilder()
        for ((index, entry) in pieces.withIndex()) {
            val (scene, piece, clips) = entry
            val name = "%d_%s".format(index + 1, piece.title.replace(Regex("[^A-Za-z0-9]+"), "_").trim('_'))
            val audio = Buf(rate * 300)
            val labels = StringBuilder()
            val rows = StringBuilder()
            var lastStage = -2
            for (c in clips) {
                val start = audio.size.toDouble() / rate
                audio.addAll(c.audio)
                val end = audio.size.toDouble() / rate
                repeat(gap) { audio.add(0.0) }
                val stageName = if (c.stage < 0) "전체" else "${c.stage + 1}단계 ${Instruments.stageNames[c.stage]}"
                labels.append("%.3f\t%.3f\t%s - %s\n".format(start, end, stageName, c.label))
                if (c.stage != lastStage) {
                    rows.append("<tr class=\"stage\"><th colspan=\"3\">$stageName</th></tr>\n"); lastStage = c.stage
                }
                rows.append("<tr data-start=\"%.3f\" data-end=\"%.3f\"><td class=\"t\">%d:%05.2f</td><td>%s</td><td><button>▶</button></td></tr>\n"
                    .format(start, end, (start / 60).toInt(), start % 60, c.label))
            }
            writeWav(File(out, "$name.wav"), audio.toArray(), scale)
            File(out, "$name.labels.txt").writeText(labels.toString(), Charsets.UTF_8)
            sections.append("<section><h2>${index + 1}. ${scene.character} <small>${piece.title}</small></h2>\n" +
                "<audio controls preload=\"auto\" src=\"$name.wav\"></audio>\n<table>\n$rows</table></section>\n")
            println("$name: ${"%.1f".format(audio.size.toDouble() / rate)} s, ${clips.size} clips")
        }
        File(out, "index.html").writeText(HTML.replace("@SECTIONS@", sections.toString()), Charsets.UTF_8)
    }

    private fun writeWav(file: File, x: FloatArray, scale: Double) {
        RandomAccessFile(file, "rw").use { f ->
            f.setLength(0)
            val data = x.size * 2
            fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
            fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
            f.write("RIFF".toByteArray()); f.write(le32(36 + data)); f.write("WAVE".toByteArray())
            f.write("fmt ".toByteArray()); f.write(le32(16)); f.write(le16(1)); f.write(le16(1))
            f.write(le32(rate)); f.write(le32(rate * 2)); f.write(le16(2)); f.write(le16(16))
            f.write("data".toByteArray()); f.write(le32(data))
            val bytes = ByteArray(data)
            for (i in x.indices) {
                val v = (x[i] * scale * 32767).toInt().coerceIn(-32768, 32767)
                bytes[2 * i] = v.toByte(); bytes[2 * i + 1] = (v shr 8).toByte()
            }
            f.write(bytes)
        }
    }

    private companion object {
        val HTML = """<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><title>카에스트로 악기 미리듣기</title>
<style>
body{font-family:"Malgun Gothic",sans-serif;margin:24px auto;max-width:760px;padding:0 16px;background:#fafafa;color:#222}
h1{font-size:22px} h2{font-size:18px;margin-top:36px} small{color:#888;font-weight:normal}
audio{width:100%;margin:8px 0} table{border-collapse:collapse;width:100%}
td,th{padding:4px 8px;border-bottom:1px solid #eee;text-align:left} tr.stage th{background:#eef;padding-top:10px}
td.t{color:#888;font-variant-numeric:tabular-nums;width:70px} tr[data-start]{cursor:pointer} tr[data-start]:hover{background:#f0f4ff}
tr.playing{background:#dfe8ff!important;font-weight:bold} button{border:0;background:none;cursor:pointer;font-size:14px}
</style></head><body>
<h1>카에스트로 악기 미리듣기</h1>
<p>곡마다 맨 앞은 기본 악단 전체, 그다음은 단계별로 고를 수 있는 악기를 하나씩 단독으로 2마디 안팎 연주한 소리입니다. 줄을 누르면 그 소리만 재생합니다. 모든 파일의 음량 배율이 같아서 악기끼리 크기를 비교할 수 있습니다.</p>
@SECTIONS@
<script>
let stopAt=null, current=null;
document.querySelectorAll('tr[data-start]').forEach(tr=>tr.addEventListener('click',()=>{
  const audio=tr.closest('section').querySelector('audio');
  document.querySelectorAll('audio').forEach(a=>{if(a!==audio)a.pause()});
  if(current)current.classList.remove('playing'); current=tr; tr.classList.add('playing');
  audio.currentTime=+tr.dataset.start; stopAt={audio,end:+tr.dataset.end}; audio.play();
}));
document.querySelectorAll('audio').forEach(a=>a.addEventListener('timeupdate',()=>{
  if(stopAt&&stopAt.audio===a&&a.currentTime>=stopAt.end){a.pause();stopAt=null;if(current)current.classList.remove('playing')}
}));
</script></body></html>
"""
    }
}
