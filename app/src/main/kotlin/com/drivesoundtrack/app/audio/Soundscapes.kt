package com.drivesoundtrack.app.audio

/** Original musical identities. Patterns are 16-step bars; each bit is one sixteenth note. */
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
        Soundscape("Night", "future garage", 0xB7A3FF, 82.0, 73.42,
            intArrayOf(0, -2, 3, -4), intArrayOf(0, 2, 3, 7, 9),
            0x1091, 0x0100, 0x4444, 0x4811,
            intArrayOf(-1, -1, 3, -1, -1, 2, -1, -1, 4, -1, -1, 1, -1, -1, 2, -1),
            0.12, 0.11, 0.07, 0.38,
            arrayOf(
                intArrayOf(0, 3, 7, 12, 15), // D minor
                intArrayOf(0, 4, 7, 9, 12),  // C major 6
                intArrayOf(0, 4, 7, 12, 16), // F major
                intArrayOf(0, 4, 7, 12, 16), // B-flat major
            )),
        Soundscape("Coast", "ambient house", 0x83D5FF, 88.0, 65.41,
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
        Soundscape("Horizon", "cinematic motion", 0xF8BD81, 76.0, 49.0,
            intArrayOf(0, 3, -2, 5), intArrayOf(0, 2, 3, 7, 8),
            0x1111, 0x0100, 0x4444, 0x9111,
            intArrayOf(0, -1, -1, 2, -1, -1, 3, -1, 4, -1, -1, 3, -1, 2, -1, -1),
            0.16, 0.13, 0.08, 0.50,
            arrayOf(
                intArrayOf(0, 3, 7, 12, 15), // G minor
                intArrayOf(0, 4, 7, 12, 16), // B-flat major
                intArrayOf(0, 4, 7, 12, 16), // F major
                intArrayOf(0, 3, 7, 12, 15), // C minor
            )),
        Soundscape("Circuit", "melodic drive", 0xF394B9, 106.0, 55.0,
            intArrayOf(0, -2, 3, 5), intArrayOf(0, 3, 5, 7, 10),
            0x1111, 0x1010, 0x4444, 0x5151,
            intArrayOf(0, -1, -1, -1, 2, -1, -1, -1, 4, -1, -1, -1, 3, -1, 2, -1),
            0.13, 0.14, 0.09, 0.56,
            arrayOf(
                intArrayOf(0, 3, 7, 10, 12), // A minor 7
                intArrayOf(0, 4, 7, 9, 12),  // G major 6
                intArrayOf(0, 4, 7, 12, 16), // C major
                intArrayOf(0, 3, 7, 10, 12), // D minor 7
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
    )
}
