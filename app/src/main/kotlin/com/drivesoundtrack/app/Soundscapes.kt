package com.drivesoundtrack.app

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
)

object Soundscapes {
    val all = listOf(
        Soundscape("Pulse", "electro drive", 0x66E3D5, 96.0, 55.0,
            intArrayOf(0, -3, 5, 7), intArrayOf(0, 2, 3, 7, 10),
            0x1111, 0x1010, 0x5555, 0x9191,
            intArrayOf(0, -1, 2, -1, 3, -1, 2, -1, 4, -1, 2, -1, 3, -1, 1, -1),
            0.08, 0.14, 0.09, 0.75),
        Soundscape("Night", "future garage", 0xB7A3FF, 82.0, 73.42,
            intArrayOf(0, -2, 3, -4), intArrayOf(0, 2, 3, 7, 9),
            0x1091, 0x0100, 0x4444, 0x4811,
            intArrayOf(-1, -1, 3, -1, -1, 2, -1, -1, 4, -1, -1, 1, -1, -1, 2, -1),
            0.12, 0.11, 0.07, 0.38),
        Soundscape("Coast", "ambient house", 0x83D5FF, 88.0, 65.41,
            intArrayOf(0, 5, 9, 7), intArrayOf(0, 2, 4, 7, 9),
            0x1011, 0x0100, 0x2222, 0x1101,
            intArrayOf(-1, -1, 2, -1, -1, -1, 3, -1, -1, -1, 4, -1, 2, -1, -1, -1),
            0.15, 0.10, 0.06, 0.28),
        Soundscape("Horizon", "cinematic motion", 0xF8BD81, 76.0, 49.0,
            intArrayOf(0, 3, -2, 5), intArrayOf(0, 2, 3, 7, 8),
            0x1111, 0x0100, 0x4444, 0x9111,
            intArrayOf(0, -1, -1, 2, -1, -1, 3, -1, 4, -1, -1, 3, -1, 2, -1, -1),
            0.16, 0.13, 0.08, 0.50),
        Soundscape("Circuit", "melodic drive", 0xF394B9, 106.0, 55.0,
            intArrayOf(0, -2, 3, 5), intArrayOf(0, 3, 5, 7, 10),
            0x1111, 0x1010, 0x4444, 0x5151,
            intArrayOf(0, -1, -1, -1, 2, -1, -1, -1, 4, -1, -1, -1, 3, -1, 2, -1),
            0.13, 0.14, 0.09, 0.56),
    )
}
