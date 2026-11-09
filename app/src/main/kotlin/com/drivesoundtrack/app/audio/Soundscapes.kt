package com.drivesoundtrack.app.audio

/**
 * A track: one of the original sound worlds (16-step patterns, each bit a sixteenth note) or a
 * classical [piece] played by [PieceEngine]. Patterns of piece tracks are unused placeholders.
 */
data class Soundscape(
    val name: String,
    val character: String,
    val accent: Int,
    val baseBpm: Double,
    val rootHz: Double,
    val chords: IntArray,
    val scale: IntArray,
    val kick: Int,
    val snare: Int,
    val hat: Int,
    val bass: Int,
    val melody: IntArray,
    val padGain: Double,
    val bassGain: Double,
    val leadGain: Double,
    val brightness: Double,
    /** Optional chord-specific note sets, indexed by bar in the chord cycle. */
    val chordTones: Array<IntArray>? = null,
    /** BPM added at 130 km/h. */
    val tempoRange: Double = 6.0,
    val piece: Piece? = null,
)

private fun classical(piece: Piece, character: String, accent: Int, bpm: Double, tempoRange: Double = 6.0) = Soundscape(
    piece.title, character, accent, bpm, midiHz(36 + piece.tonic),
    intArrayOf(0), intArrayOf(0, 2, 4, 5, 7, 9, 11), 0, 0, 0, 0, IntArray(16) { -1 },
    0.0, 0.0, 0.0, 0.0, tempoRange = tempoRange, piece = piece,
)

object Soundscapes {
    val all = listOf(
        Soundscape("Pulse", "electro drive", 0x66C9FF, 96.0, 55.0,
            intArrayOf(0, -4, 3, -2), intArrayOf(0, 3, 7, 10, 12),
            0x1111, 0x1010, 0x5555, 0x9191,
            intArrayOf(0, -1, -1, -1, 2, -1, -1, -1, 3, -1, -1, -1, 1, -1, -1, -1),
            0.075, 0.14, 0.075, 0.58,
            arrayOf(
                intArrayOf(0, 3, 7, 10, 12), // A minor 7
                intArrayOf(0, 4, 7, 9, 12),  // F major 6
                intArrayOf(0, 4, 7, 9, 12),  // C major 6
                intArrayOf(0, 4, 7, 10, 12), // G dominant 7
            )),
        // Coast uses its own instruments (CoastEngine): warm, bright and unhurried, C major.
        Soundscape("Coast", "sunlit cruise", 0xF2C46D, 90.0, 65.41,
            intArrayOf(0, 5, 9, 7), intArrayOf(0, 2, 4, 7, 9),
            0x1011, 0x0100, 0x2222, 0x1101,
            intArrayOf(-1, -1, 2, -1, -1, -1, 3, -1, -1, -1, 4, -1, 2, -1, -1, -1),
            0.15, 0.10, 0.06, 0.28,
            arrayOf(
                intArrayOf(0, 4, 7, 12, 16), // C major
                intArrayOf(0, 4, 7, 12, 16), // F major
                intArrayOf(0, 3, 7, 12, 15), // A minor
                intArrayOf(0, 4, 7, 9, 12),  // G major 6
            )),
        // Neon uses its own electro engine (NeonEngine); the patterns below only feed the shared
        // voices' bookkeeping. Harmony: D minor, i - iv - VI - VII.
        Soundscape("Neon", "night electro", 0x5EE6C8, 104.0, 73.42,
            intArrayOf(0, 5, -4, -2), intArrayOf(0, 3, 7, 10, 12),
            0x1111, 0x1010, 0x4444, 0x1111,
            intArrayOf(0, -1, -1, -1, -1, -1, 2, -1, -1, -1, 3, -1, -1, -1, -1, -1),
            0.10, 0.12, 0.07, 0.45,
            arrayOf(
                intArrayOf(0, 3, 7, 10, 12), // D minor 7
                intArrayOf(0, 3, 7, 10, 12), // G minor 7
                intArrayOf(0, 4, 7, 11, 12), // B-flat major 7
                intArrayOf(0, 4, 7, 9, 12),  // C major 6
            )),
        classical(Pieces.canon, "파헬벨 · 캐논 D장조", 0xE7A9C8, 66.0),
        classical(Pieces.prelude, "바흐 · 평균율 전주곡 C장조", 0x9FD3F5, 66.0),
        classical(Pieces.gymnopedie, "사티 · 짐노페디 1번", 0xC9B8F2, 70.0, tempoRange = 4.0),
        classical(Pieces.odeToJoy, "베토벤 · 환희의 송가", 0xF5D27A, 92.0),
        classical(Pieces.mountainKing, "그리그 · 산왕의 궁전에서", 0xE8836B, 80.0, tempoRange = 40.0),
    )
}

/** Tracks grouped into albums; an album plays its tracks one after another (about three minutes each). */
object Albums {
    const val ORIGINAL = 0
    const val CLASSIC = 1
    const val MINE = 2
    val names = listOf("오리지널", "클래식 드라이브", "내 앨범")
    val originals: IntArray = Soundscapes.all.indices.filter { Soundscapes.all[it].piece == null }.toIntArray()
    val classics: IntArray = Soundscapes.all.indices.filter { Soundscapes.all[it].piece != null }.toIntArray()
    val defaultMine: List<String> = listOf("Canon", "Coast", "Gymnopédie", "Ode to Joy")

    fun tracks(album: Int, mine: IntArray): IntArray = when (album) {
        ORIGINAL -> originals
        CLASSIC -> classics
        else -> mine
    }
}
