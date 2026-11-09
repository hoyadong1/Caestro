package com.drivesoundtrack.app.audio

import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.ln

/**
 * Recorded notes of real instruments from VSCO 2 Community Edition (Versilian Studios, CC0 / public
 * domain). `tools`-built files in assets/samples: one 24 kHz mono 16-bit .pcm per instrument with its
 * notes back to back, and bank.txt with one line per note:
 *   <id> <kind> <rootHz or name> <offset> <length> <loopStart> <loopEnd>
 * Sustained notes carry a crossfaded loop so a note can be held as long as the score asks.
 */
internal object SampleBank {
    const val RATE = 24000.0

    class Note(val root: Double, val name: String, val offset: Int, val length: Int, val loopStart: Int, val loopEnd: Int)

    class Set(val id: String, val data: ShortArray, val notes: List<Note>) {
        private val byName = notes.associateBy { it.name }
        /** The recorded note closest in pitch, so it is shifted as little as possible. */
        fun nearest(hz: Double): Note {
            var best = notes[0]
            var distance = Double.MAX_VALUE
            for (n in notes) {
                val d = abs(ln(hz / n.root))
                if (d < distance) { distance = d; best = n }
            }
            return best
        }
        fun named(name: String): Note? = byName[name]
    }

    /** Opens a file of assets/samples. The app sets the asset manager; tests read the source tree. */
    @Volatile var opener: ((String) -> InputStream)? = null

    private val sets: Map<String, Set> by lazy { load() }
    /** Downloaded instruments' notes, in the same layout as the app's own bank. */
    @Volatile private var downloaded: Map<String, Set> = emptyMap()

    fun set(id: String): Set? = sets[id] ?: downloaded[id]

    @Synchronized fun register(set: Set) { downloaded = downloaded + (set.id to set) }

    private fun load(): Map<String, Set> = try {
        val open = opener ?: { name: String -> File("src/main/assets/samples", name).inputStream() }
        val lines = open("bank.txt").bufferedReader().readLines().filter { it.isNotBlank() }.map { it.split(' ') }
        lines.groupBy { it[0] }.mapValues { (id, rows) ->
            val bytes = open("$id.pcm").use(InputStream::readBytes)
            val data = ShortArray(bytes.size / 2)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(data)
            Set(id, data, rows.map { r ->
                val root = r[2].toDoubleOrNull() ?: 0.0
                Note(root, r[2], r[3].toInt(), r[4].toInt(), r[5].toInt(), r[6].toInt())
            })
        }
    } catch (ex: Exception) {
        emptyMap() // Without the bank the recorded instruments stay silent; the synthesised ones still play.
    }
}

/** Reads a recorded note at any pitch: 4-point Hermite interpolation, looping held notes. */
internal class SamplePlayer {
    private var data: ShortArray? = null
    private var note: SampleBank.Note? = null
    private var position = 0.0
    val playing get() = note != null

    fun start(set: SampleBank.Set?, note: SampleBank.Note?) {
        this.data = set?.data
        this.note = note
        position = (note?.offset ?: 0).toDouble()
    }

    fun stop() { note = null }

    private fun at(index: Int, n: SampleBank.Note, d: ShortArray): Double {
        var i = index
        if (n.loopEnd > 0) { if (i >= n.loopEnd) i -= n.loopEnd - n.loopStart }
        else if (i >= n.offset + n.length) return 0.0
        return d[i.coerceAtLeast(n.offset)] * (1.0 / 32768.0)
    }

    /** Next output sample, advancing by [ratio] source samples per call (pitch × rate conversion). */
    fun next(ratio: Double): Double {
        val n = note ?: return 0.0
        val d = data ?: return 0.0
        val i = position.toInt()
        if (n.loopEnd <= 0 && i >= n.offset + n.length) { note = null; return 0.0 }
        val f = position - i
        val y0 = at(i - 1, n, d); val y1 = at(i, n, d); val y2 = at(i + 1, n, d); val y3 = at(i + 2, n, d)
        val c1 = 0.5 * (y2 - y0)
        val c2 = y0 - 2.5 * y1 + 2.0 * y2 - 0.5 * y3
        val c3 = 0.5 * (y3 - y0) + 1.5 * (y1 - y2)
        position += ratio
        if (n.loopEnd > 0 && position >= n.loopEnd) position -= n.loopEnd - n.loopStart
        return ((c3 * f + c2) * f + c1) * f + y1
    }
}

/** A real instrument's note: the nearest recorded note shifted to pitch, shaped by the envelope. */
internal class SampleVoice(id: String, attack: Double, release: Double) : Voice(Adsr(attack, 60.0, 1.0, release)) {
    private val set = SampleBank.set(id)
    private val player = SamplePlayer()
    private var root = 440.0

    override fun reset() {
        val note = set?.nearest(hz)
        root = note?.root ?: 440.0
        player.start(set, note)
    }

    override fun wave(c: Controls): Double = player.next(pitch(c) / root * (SampleBank.RATE / Dsp.RATE))
}

/**
 * Rhythm sections played with recorded percussion. Like [Kit] they follow the bar (4/4 or 3/4)
 * and grow with the drum level that speed sets, but stay light: no backbeat snare, no hi-hat.
 */
internal class SampleKit(private val style: Int) : Part {
    companion object { const val ORCHESTRA = 0; const val LATIN = 1 }

    override var left = 0.0; private set
    override var right = 0.0; private set
    private val set = SampleBank.set("perc")
    private val players = Array(8) { SamplePlayer() }
    private val gains = DoubleArray(8)
    private val pans = DoubleArray(8)
    private var cursor = 0

    private fun hit(name: String, gain: Double, pan: Double = 0.0) {
        val note = set?.named(name) ?: return
        // Reuse a free player, otherwise the oldest; a cut hit is already far into its decay.
        var pick = -1
        for (k in players.indices) { val i = (cursor + k) % players.size; if (!players[i].playing) { pick = i; break } }
        if (pick < 0) pick = cursor
        cursor = (pick + 1) % players.size
        players[pick].start(set, note); gains[pick] = gain; pans[pick] = pan
    }

    fun step(inBar: Int, stepsPerBar: Int, level: Int, wiper: Boolean) {
        val beats = stepsPerBar / 4
        when (style) {
            ORCHESTRA -> {
                if (inBar == 0) hit("bassdrum", 0.6)
                if (beats == 4 && inBar == 8) hit("bassdrum", 0.38)
                if (inBar == 0 && level >= 1) hit("triangle", 0.35, 0.4)
                if (inBar % 4 == 2 && level >= 1) hit("snaretap", 0.30, -0.25)
                if (inBar % 4 == 2 && level >= 2) hit("sleigh", 0.22, 0.35)
            }
            LATIN -> {
                if (inBar == 0) hit("tumba", 0.85, -0.2)
                if (beats == 4 && (inBar == 6 || inBar == 10)) hit("conga", 0.6, 0.25)
                if (beats == 3 && (inBar == 4 || inBar == 8)) hit("conga", 0.6, 0.25)
                if (inBar % 4 == 2) hit("tambshake", 0.32, 0.3)
                if (level >= 1 && (inBar == 0 || inBar == 6 || (beats == 4 && inBar == 12))) hit("claves", 0.28, -0.35)
            }
        }
        if (wiper && inBar % 4 == 0) hit("tambshake", 0.25, -0.3)
    }

    override fun render(c: Controls) {
        var l = 0.0
        var r = 0.0
        val ratio = SampleBank.RATE / Dsp.RATE
        for (i in players.indices) {
            if (!players[i].playing) continue
            val v = players[i].next(ratio) * gains[i]
            l += v * (1.0 - pans[i]); r += v * (1.0 + pans[i])
        }
        left = l; right = r
    }
}
