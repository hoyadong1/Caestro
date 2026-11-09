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
    )
}
