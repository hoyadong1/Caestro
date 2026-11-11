package com.drivesoundtrack.app.audio

/**
 * Public-domain classical pieces, entered by hand from the composers' own melodies and harmony
 * (no recordings, no modern arrangements). A step is a sixteenth note; a bar has
 * [Piece.stepsPerBar] steps (16 in 4/4, 12 in 3/4). The pieces loop.
 */
internal class Note(val start: Int, val length: Int, val midi: Int)

internal class Chord(
    val start: Int,
    val length: Int,
    /** Pitch class of the chord root. */
    val root: Int,
    /** Semitones above the root, starting with 0. */
    val intervals: IntArray,
    /** Pitch class of the bass note (slash chords), or the root. */
    val bassClass: Int,
    /** Optional explicit voicing (MIDI, low to high), used by the arpeggio. */
    val voicing: IntArray?,
) {
    /** Close chord around middle C for the harmony stage. */
    lateinit var harmony: IntArray
    /** Five notes the arpeggio pattern indexes. */
    lateinit var arp: IntArray
    /** Five chord tones relative to the root, as the shared renderer expects. */
    lateinit var tones: IntArray
    /** Root in semitones above the piece's tonic (0..11). */
    var relativeRoot = 0
    val top get() = harmony.last()
}

internal enum class HighLine { BELLS, CANON, OCTAVE }
internal enum class BassLine { EXPLICIT, HALVES, ROOT_FIFTH, VOICING }

class Piece internal constructor(
    val title: String,
    val composer: String,
    val beatsPerBar: Int,
    internal val tonic: Int,
    internal val chords: List<Chord>,
    melodyLine: List<Note>,
    bassStyle: BassLine,
    explicitBass: List<Note>,
    internal val arp: IntArray,
    internal val comp: BooleanArray,
    internal val high: HighLine,
    internal val canonBars: Int,
    /** Default instrument id per stage. */
    val defaults: List<String>,
    /**
     * Optional lines that may change bar by bar (verse and chorus played differently), tiled over the piece:
     * the drums as a bit mask per step ([Score.DRUM_LOW] ...), and the arpeggio and accompaniment patterns.
     * Without them the rhythm section plays its own groove and [arp] / [comp] repeat every bar.
     */
    drumLine: IntArray? = null,
    arpLine: IntArray? = null,
    compLine: BooleanArray? = null,
) {
    val stepsPerBar = beatsPerBar * 4
    val lengthSteps = chords.last().start + chords.last().length
    val lengthBars = lengthSteps / stepsPerBar
    private val chordIndex = IntArray(lengthSteps)
    private val melodyAt = arrayOfNulls<Note>(lengthSteps)
    private val bassAt = arrayOfNulls<Note>(lengthSteps)
    internal val melody: List<Note> = melodyLine
    private val drums = drumLine?.let { tile(it) }
    private val arps = arpLine?.let { tile(it) }
    private val comps = compLine?.let { line -> tile(IntArray(line.size) { if (line[it]) 1 else 0 }) }

    private fun tile(line: IntArray): IntArray {
        require(line.isNotEmpty() && line.size % stepsPerBar == 0 && lengthSteps % line.size == 0) {
            "$title: a line of ${line.size} steps does not fit $lengthSteps"
        }
        return IntArray(lengthSteps) { line[it % line.size] }
    }

    init {
        require(lengthSteps % stepsPerBar == 0) { "$title does not end on a bar line" }
        require(arp.size == stepsPerBar && comp.size == stepsPerBar) { "$title patterns must be one bar long" }
        chords.forEachIndexed { i, chord ->
            for (s in chord.start until chord.start + chord.length) chordIndex[s] = i
            chord.relativeRoot = ((chord.root - tonic) % 12 + 12) % 12
            val iv = chord.intervals
            chord.tones = if (iv.size >= 4) intArrayOf(0, iv[1], iv[2], iv[3], 12) else intArrayOf(0, iv[1], iv[2], 12, 12 + iv[1])
            val voicing = chord.voicing
            if (voicing != null) {
                chord.arp = voicing
                chord.harmony = voicing.copyOfRange(1, voicing.size)
            } else {
                val low = lowest(chord.root, 53)
                chord.harmony = IntArray(iv.size) { low + iv[it] }
                val arpRoot = lowest(chord.root, 55)
                chord.arp = IntArray(5) { arpRoot + chord.tones[it] }
            }
        }
        for (note in melodyLine) { require(note.start + note.length <= lengthSteps); melodyAt[note.start] = note }
        val bass = when (bassStyle) {
            BassLine.EXPLICIT -> explicitBass
            BassLine.VOICING -> chords.map { Note(it.start, it.length, it.voicing?.first() ?: lowest(it.bassClass, 36)) }
            BassLine.HALVES -> chords.flatMap { chord ->
                val half = stepsPerBar / 2
                (chord.start until chord.start + chord.length step half).map { s ->
                    Note(s, minOf(half, chord.start + chord.length - s), lowest(chord.bassClass, 36))
                }
            }
            BassLine.ROOT_FIFTH -> chords.flatMap { chord ->
                (chord.start until chord.start + chord.length step 4).map { s ->
                    val fifth = (s / 4) % 2 == 1
                    Note(s, 4, lowest(if (fifth) (chord.root + chord.intervals[2]) % 12 else chord.bassClass, if (fifth) 33 else 36))
                }
            }
        }
        for (note in bass) { require(note.start + note.length <= lengthSteps); bassAt[note.start] = note }
    }

    internal fun chordAt(step: Int): Chord = chords[chordIndex[step]]
    /** Drum hits at [step] ([Score.DRUM_LOW] | ...), or null when the piece leaves the groove to the rhythm section. */
    internal fun drumsAt(step: Int): Int? = drums?.get(step)
    internal fun arpAt(step: Int): Int = arps?.get(step) ?: arp[step % stepsPerBar]
    internal fun compAt(step: Int): Boolean = comps?.let { it[step] != 0 } ?: comp[step % stepsPerBar]
    internal fun melodyAt(step: Int): Note? = melodyAt[step]
    internal fun bassAt(step: Int): Note? = bassAt[step]

    private companion object {
        /** The lowest MIDI note of [pitchClass] at or above [floor]. */
        fun lowest(pitchClass: Int, floor: Int): Int = floor + ((pitchClass - floor) % 12 + 12) % 12
    }
}

/** A tiny text notation so the scores stay readable. Bar lines ("|") are checked. */
internal object Score {
    private val letters = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)

    fun pitchClass(name: String): Int {
        var pc = letters.getValue(name[0])
        for (ch in name.drop(1)) when (ch) { '#' -> pc++; 'b' -> pc-- }
        return (pc + 12) % 12
    }

    fun midi(name: String): Int {
        val octaveAt = name.indexOfFirst { it.isDigit() }
        require(octaveAt > 0) { "no octave in $name" }
        var pc = letters.getValue(name[0])
        for (ch in name.substring(1, octaveAt)) when (ch) { '#' -> pc++; 'b' -> pc-- }
        return 12 * (name.substring(octaveAt).toInt() + 1) + pc
    }

    /** "F#5:4 E5 D5 C#5 | B4 ..."; "r" rests, "-" ties into the previous note, ":n" sets the length in steps. */
    fun notes(text: String, stepsPerBar: Int, tileTo: Int? = null): List<Note> {
        val out = mutableListOf<Note>()
        var position = 0
        var length = 4
        for (token in text.trim().split(Regex("\\s+"))) {
            if (token == "|") { require(position % stepsPerBar == 0) { "bar line at step $position in \"$text\"" }; continue }
            val parts = token.split(':')
            if (parts.size > 1) length = parts[1].toInt()
            when (parts[0]) {
                "r" -> {}
                "-" -> { val prev = out.removeAt(out.lastIndex); out += Note(prev.start, prev.length + length, prev.midi) }
                else -> out += Note(position, length, midi(parts[0]))
            }
            position += length
        }
        require(position % stepsPerBar == 0) { "line ends mid-bar at $position" }
        if (tileTo == null || position >= tileTo) return out
        require(tileTo % position == 0) { "line of $position steps does not tile $tileTo" }
        return (0 until tileTo / position).flatMap { k -> out.map { Note(it.start + k * position, it.length, it.midi) } }
    }

    private val chordSymbol = Regex("^([A-G][#b]?)(maj7|m7|m6|dim7|dim|aug|sus4|m|7|6)?(?:/([A-G][#b]?))?$")
    private val qualities = mapOf(
        "" to intArrayOf(0, 4, 7), "m" to intArrayOf(0, 3, 7), "7" to intArrayOf(0, 4, 7, 10),
        "maj7" to intArrayOf(0, 4, 7, 11), "m7" to intArrayOf(0, 3, 7, 10), "m6" to intArrayOf(0, 3, 7, 9),
        "6" to intArrayOf(0, 4, 7, 9), "dim" to intArrayOf(0, 3, 6), "dim7" to intArrayOf(0, 3, 6, 9),
        "aug" to intArrayOf(0, 4, 8), "sus4" to intArrayOf(0, 5, 7),
    )

    /** "D:4 A Bm F#m | G D G A"; optional [voicings] give one explicit chord per symbol ("C3 E3 G3 C4 E4 | ..."). */
    fun chords(text: String, stepsPerBar: Int, tileTo: Int? = null, voicings: String? = null): List<Chord> {
        val explicit = voicings?.split('|')?.map { v -> v.trim().split(Regex("\\s+")).map(::midi).toIntArray() }
        val out = mutableListOf<Chord>()
        var position = 0
        var length = stepsPerBar
        for (token in text.trim().split(Regex("\\s+"))) {
            if (token == "|") { require(position % stepsPerBar == 0) { "chord bar line at step $position" }; continue }
            val parts = token.split(':')
            if (parts.size > 1) length = parts[1].toInt()
            val match = requireNotNull(chordSymbol.matchEntire(parts[0])) { "bad chord ${parts[0]}" }
            val root = pitchClass(match.groupValues[1])
            val bass = match.groupValues[3].takeIf { it.isNotEmpty() }?.let(::pitchClass) ?: root
            out += Chord(position, length, root, qualities.getValue(match.groupValues[2]), bass, explicit?.get(out.size))
            position += length
        }
        require(position % stepsPerBar == 0) { "chords end mid-bar" }
        if (explicit != null) require(explicit.size == out.size) { "${explicit.size} voicings for ${out.size} chords" }
        if (tileTo == null || position >= tileTo) return out
        require(tileTo % position == 0)
        return (0 until tileTo / position).flatMap { k ->
            out.map { Chord(it.start + k * position, it.length, it.root, it.intervals, it.bassClass, it.voicing) }
        }
    }

    const val DRUM_LOW = 1
    const val DRUM_BACK = 2
    const val DRUM_HIGH = 4
    const val DRUM_ACCENT = 8

    /** Tokens of a line of whole bars ("|" between bars, checked). */
    private fun barTokens(text: String, stepsPerBar: Int): List<String> {
        val out = mutableListOf<String>()
        for (token in text.trim().split(Regex("\\s+"))) {
            if (token == "|") { require(out.size % stepsPerBar == 0) { "bar line at step ${out.size}" }; continue }
            out += token
        }
        require(out.isNotEmpty() && out.size % stepsPerBar == 0) { "line of ${out.size} steps is not whole bars" }
        return out
    }

    /** "K . H . S . H . | K K H . S . H A": per step any of K (low drum), S (backbeat), H (high), A (accent), or ".". */
    fun drums(text: String, stepsPerBar: Int): IntArray = barTokens(text, stepsPerBar).map { token ->
        if (token == ".") 0 else token.fold(0) { mask, ch ->
            mask or when (ch) { 'K' -> DRUM_LOW; 'S' -> DRUM_BACK; 'H' -> DRUM_HIGH; 'A' -> DRUM_ACCENT; else -> error("bad drum $ch") }
        }
    }.toIntArray()

    /** Arpeggio pattern over one or more bars ("0 . 2 . | 1 . 3 ."). */
    fun patternLine(text: String, stepsPerBar: Int): IntArray =
        barTokens(text, stepsPerBar).map { if (it == ".") -1 else it.toInt() }.toIntArray()

    /** Accompaniment pattern over one or more bars ("x . . . | x . x ."). */
    fun hitsLine(text: String, stepsPerBar: Int): BooleanArray = barTokens(text, stepsPerBar).map { it == "x" }.toBooleanArray()

    /** One bar of indices ("0 . 2 . 1 ."): a number plays that note, "." rests. */
    fun pattern(text: String): IntArray = text.trim().split(Regex("\\s+")).map { if (it == ".") -1 else it.toInt() }.toIntArray()
    fun hits(text: String): BooleanArray = text.trim().split(Regex("\\s+")).map { it == "x" }.toBooleanArray()
}

internal object Pieces {
    /** Pachelbel, Canon in D: the two-bar ground with the first violin's variations; the high stage plays them as a canon two bars behind. */
    val canon: Piece by lazy {
        val spb = 16
        val melody = Score.notes("""
            F#5:4 E5 D5 C#5 | B4 A4 B4 C#5 |
            D5:4 C#5 B4 A4 | G4 F#4 G4 E4 |
            D4:2 F#4 A4 G4 F#4 D4 F#4 E4 | D4 B3 D4 A4 G4 B4 A4 G4 |
            D5:1 C#5 D5 D4 C#4 A4 E4 F#4 D4 D5 C#5 B4 C#5 F#5 A5 B5 | G5 F#5 E5 G5 F#5 E5 D5 C#5 B4 A4 G4 F#4 E4 G4 F#4 E4 |""", spb)
        Piece("Canon", "Pachelbel", 4, Score.pitchClass("D"),
            Score.chords("D:4 A Bm F#m | G D G A", spb, tileTo = 128),
            melody, BassLine.EXPLICIT, Score.notes("D3:4 A2 B2 F#2 | G2 D2 G2 A2", spb, tileTo = 128),
            Score.pattern("3 . 1 . 4 . 2 . 3 . 1 . 4 . 2 ."),
            Score.hits("x . . . x . . . x . . . x . . ."),
            HighLine.CANON, 2,
            listOf("cello", "orchperc", "strings", "harp", "violin", "flute"))
    }

    /** Bach, Prelude in C major (BWV 846): the broken chords are the arpeggio; the top voice becomes the melody. */
    val prelude: Piece by lazy {
        val spb = 16
        val chords = Score.chords(
            "C | Dm7/C | G7/B | C | Am/C | D7/C | G/B | Cmaj7/B | Am7 | D7 | G | C#dim7/G | Dm/F | Bdim7/F | C/E | Fmaj7/E | Dm7 | G7",
            spb, voicings = """
                C3 E3 G3 C4 E4 | C3 D3 A3 D4 F4 | B2 D3 G3 D4 F4 | C3 E3 G3 C4 E4 | C3 E3 A3 E4 A4 | C3 D3 F#3 A3 D4 |
                B2 D3 G3 D4 G4 | B2 C3 E3 G3 C4 | A2 C3 E3 G3 C4 | D2 A2 D3 F#3 C4 | G2 B2 D3 G3 B3 | G2 Bb2 E3 G3 C#4 |
                F2 A2 D3 A3 D4 | F2 Ab2 D3 F3 B3 | E2 G2 C3 G3 C4 | E2 F2 A2 C3 F3 | D2 F2 A2 C3 F3 | G1 D2 G2 B2 F3""")
        val melody = chords.map { Note(it.start, it.length, it.voicing!!.last() + 12) }
        Piece("Prelude", "J. S. Bach", 4, Score.pitchClass("C"), chords,
            melody, BassLine.VOICING, emptyList(),
            Score.pattern("0 1 2 3 4 2 3 4 0 1 2 3 4 2 3 4"),
            Score.hits("x . . . . . . . x . . . . . . ."),
            HighLine.BELLS, 0,
            listOf("cello", "orchperc", "viola", "piano", "flute", "glasspad"))
    }

    /** Satie, Gymnopédie No. 1: the swaying G and D major-seventh bars with the slow melody over them. */
    val gymnopedie: Piece by lazy {
        val spb = 12
        val melody = Score.notes("""
            r:12 | r | r | r |
            r:4 F#5 A5 | G5 F#5 C#5 | B4 C#5 D5 | A4:12 | F#4:12 | - | - | - |
            r:4 F#5 A5 | G5 F#5 C#5 | B4 C#5 D5 | A4:12 | C#5:12 | F#5:12 | E5:12 | - |""", spb)
        Piece("Gymnopédie", "Erik Satie", 3, Score.pitchClass("D"),
            Score.chords("Gmaj7:12 | Dmaj7:12", spb, tileTo = 240),
            melody, BassLine.EXPLICIT, Score.notes("G2:12 | D2:12", spb, tileTo = 240),
            Score.pattern(". . . . 1 . 2 . 3 . 2 ."),
            Score.hits(". . . . x . . . x . . ."),
            HighLine.BELLS, 0,
            listOf("piano", "orchperc", "viola", "harp", "oboe", "glasspad"))
    }

    /** Beethoven, "Ode to Joy" from the Ninth Symphony: the theme, with the high stage doubling it an octave up. */
    val odeToJoy: Piece by lazy {
        val spb = 16
        val a = "F#5:4 F#5 G5 A5 | A5 G5 F#5 E5 | D5 D5 E5 F#5 |"
        val melody = Score.notes("""
            $a F#5:6 E5:2 E5:8 |
            $a E5:6 D5:2 D5:8 |
            E5:4 E5 F#5 D5 | E5:4 F#5:2 G5 F#5:4 D5 | E5:4 F#5:2 G5 F#5:4 E5 | D5:4 E5 A4:8 |
            $a E5:6 D5:2 D5:8 |""", spb)
        Piece("Ode to Joy", "L. van Beethoven", 4, Score.pitchClass("D"),
            Score.chords("""
                D:16 | A7 | D | D:8 A:8 | D:16 | A7 | D | A:8 D:8 |
                A:8 D:8 | A:8 D:8 | A:8 Bm:8 | E7:8 A:8 | D:16 | A7 | D | A:8 D:8""", spb),
            melody, BassLine.HALVES, emptyList(),
            Score.pattern("0 . 2 . 1 . 2 . 0 . 2 . 1 . 2 ."),
            Score.hits("x . . . . . . . x . . . . . . ."),
            HighLine.OCTAVE, 0,
            listOf("cello", "orchperc", "strings", "harp", "clarinet", "glock"))
    }

    /** Grieg, "In the Hall of the Mountain King": the creeping theme in B minor, then on the dominant. Tempo rises with speed. */
    val mountainKing: Piece by lazy {
        val spb = 16
        val a = "B3:2 C#4 D4 E4 F#4 D4 F#4:4 | F4:2 D4 F4:4 E4:2 C4 E4:4 | B3:2 C#4 D4 E4 F#4 D4 F#4 B4 | A4:2 F#4 D4 F#4 A4:8 |"
        val b = "F#4:2 G#4 A#4 B4 C#5 A#4 C#5:4 | D5:2 A#4 D5:4 C#5:2 A#4 C#5:4 | F#4:2 G#4 A#4 B4 C#5 A#4 C#5 F#5 | E5:2 C#5 A#4 C#5 E5:8 |"
        Piece("Mountain King", "Edvard Grieg", 4, Score.pitchClass("B"),
            Score.chords("""
                Bm:16 | Bdim:8 C:8 | Bm:16 | Bm7 | Bm | Bdim:8 C:8 | Bm:16 | Bm7 |
                F#:16 | Daug:8 F#:8 | F#:16 | F#7 | F# | Daug:8 F#:8 | F#:16 | F#7""", spb),
            Score.notes("$a $a $b $b", spb), BassLine.ROOT_FIFTH, emptyList(),
            Score.pattern("0 . 2 . 3 . 2 . 0 . 2 . 3 . 2 ."),
            Score.hits("x . . . x . . . x . . . x . . ."),
            HighLine.OCTAVE, 0,
            listOf("pizz", "timpani", "strings", "marimba", "bassoon", "flute"))
    }
}
