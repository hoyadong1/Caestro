import com.drivesoundtrack.app.SoundModel
import com.drivesoundtrack.app.SoundRenderer
import com.drivesoundtrack.app.Soundscapes
import kotlin.math.abs
import kotlin.math.sqrt

fun main() {
    val allowedNotes = listOf(
        setOf(0, 2, 3, 5, 7, 8, 10), // Pulse: A natural minor
        setOf(0, 2, 3, 5, 7, 8, 10), // Night: D natural minor
        setOf(0, 2, 4, 5, 7, 9, 11), // Coast: C major
        setOf(0, 2, 3, 5, 7, 8, 10), // Horizon: G natural minor
        setOf(0, 2, 3, 5, 7, 8, 10), // Circuit: A natural minor
    )
    Soundscapes.all.forEachIndexed { sceneIndex, scene ->
        val tones = requireNotNull(scene.chordTones) { "${scene.name} has no chord voicings" }
        check(tones.size == scene.chords.size)
        val chords = tones.indices.map { i ->
            check(tones[i].size == 5)
            check(tones[i].take(3).let { it[0] == 0 && it[1] in 3..4 && it[2] == 7 }) {
                "${scene.name} chord $i does not begin with a stable triad"
            }
            tones[i].map { note -> ((scene.chords[i] + note) % 12 + 12) % 12 }.toSet()
        }
        check(chords.all { allowedNotes[sceneIndex].containsAll(it) }) {
            "${scene.name} chord has a note outside its key"
        }
        check(chords.indices.all { chords[it].intersect(chords[(it + 1) % chords.size]).isNotEmpty() }) {
            "${scene.name} adjacent chords have no common tone"
        }
    }

    val model = SoundModel()
    model.setPreset(0)
    val renderer = SoundRenderer(model)
    val buffer = ShortArray(1024 * 2)
    for (speed in listOf(0.0, 20.0, 42.0, 62.0, 88.0, 120.0, 0.0)) {
        model.setSpeed(speed)
        var sumSquares = 0.0
        var peak = 0
        repeat(180) {
            renderer.render(buffer)
            for (sample in buffer) {
                val magnitude = abs(sample.toInt())
                peak = maxOf(peak, magnitude)
                sumSquares += magnitude.toDouble() * magnitude
            }
        }
        val rms = sqrt(sumSquares / (buffer.size * 180))
        check(rms > 100 && peak < 30000) { "Unbalanced output at $speed km/h: rms=$rms peak=$peak" }
        println("${speed.toInt()} km/h: ${model.layerCount()} layers, RMS ${rms.toInt()}, peak $peak")
    }
    model.setSpeed(130.0)
    Soundscapes.all.forEachIndexed { index, scene ->
        model.setPreset(index)
        renderer.setPreset(index)
        var peak = 0
        repeat(240) {
            renderer.render(buffer)
            for (sample in buffer) peak = maxOf(peak, abs(sample.toInt()))
        }
        check(model.layerCount() == 6 && peak < 30000) {
            "${scene.name} six-layer mix clips or has missing layers: peak=$peak"
        }
        println("${scene.name}: six-layer peak $peak")
    }
}
