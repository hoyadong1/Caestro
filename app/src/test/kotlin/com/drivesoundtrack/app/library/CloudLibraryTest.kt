package com.drivesoundtrack.app.library

import com.drivesoundtrack.app.audio.Albums
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.Piece
import com.drivesoundtrack.app.audio.PieceEngine
import com.drivesoundtrack.app.audio.SoundModel
import com.drivesoundtrack.app.audio.SoundRenderer
import com.drivesoundtrack.app.audio.Soundscapes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The car side of the cloud: list the example cloud in docs/cloud (what GitHub Pages serves), download
 * every pack the way the app does, load them, and check they play like the app's own content.
 * Robolectric only provides org.json here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CloudLibraryTest {
    private val base = File("../docs/cloud/").canonicalFile.toURI().toString()

    private fun install(): File {
        val root = Files.createTempDirectory("library").toFile()
        val items = CloudLibrary.fetchCatalog(base)
        for (item in items) CloudLibrary.download(item, root, base)
        CloudLibrary.load(root)
        return root
    }

    @Test
    fun catalogListsScoresAndInstruments() {
        val items = CloudLibrary.fetchCatalog(base)
        check(items.map { it.id }.toSet() == setOf("largo", "trombone", "handpan")) { items.map { it.id } }
        for (item in items) {
            val size = item.files.sumOf { File("../docs/cloud", it).length() }
            check(size == item.bytes) { "${item.id}: catalog says ${item.bytes} bytes, files are $size" }
        }
    }

    @Test
    fun downloadedPacksJoinTheAlbumsAndTheBand() {
        val root = install()
        check(CloudLibrary.installed(root) == setOf("largo", "trombone", "handpan"))
        check(root.listFiles()!!.none { it.name.endsWith(".part") })

        val trombone = Instruments.indexOf("trombone")
        val handpan = Instruments.indexOf("handpan")
        check(Instruments.all[trombone].cloud && Instruments.all[trombone].real)
        check(Instruments.all[handpan].cloud && !Instruments.all[handpan].real)
        for (stage in 0..5) {
            check((trombone in Instruments.options[stage]) == (stage in listOf(0, 2, 4))) { "trombone on stage $stage" }
            check((handpan in Instruments.options[stage]) == (stage in listOf(3, 4, 5))) { "handpan on stage $stage" }
            // "None" stays last where a stage can rest.
            if (stage > 0) check(Instruments.all[Instruments.options[stage].last()].id == "none")
        }

        val largo = Soundscapes.all.indexOfFirst { it.cloudId == "largo" }
        check(largo >= 0)
        check(largo in Albums.downloaded && largo !in Albums.classics && largo !in Albums.originals)
        // (aStudioMadePackPlaysInTheCar may have loaded its own score into the same process.)
        check(Albums.tracks(Albums.DOWNLOADED, IntArray(0)).filter { !Soundscapes.all[it].cloudId!!.startsWith("studio-") } == listOf(largo))
        // The app's own content keeps its place.
        check(Soundscapes.all[0].name == "Pulse" && Instruments.all[0].id == "cello")

        // Loading again (the screen and the service both do) adds nothing twice.
        val tracks = Soundscapes.all.size
        val kinds = Instruments.all.size
        CloudLibrary.load(root)
        check(Soundscapes.all.size == tracks && Instruments.all.size == kinds)
    }

    @Test
    fun theDownloadedScoreIsConsistent() {
        install()
        val piece = Soundscapes.all.first { it.cloudId == "largo" }.piece!!
        var position = 0
        for (chord in piece.chords) { check(chord.start == position); position += chord.length }
        check(position == piece.lengthSteps)
        val strong = piece.melody.filter { it.start % 4 == 0 }
        val fit = strong.count { note -> piece.chordAt(note.start).let { c -> c.intervals.any { (c.root + it) % 12 == note.midi % 12 } } }
        check(fit >= strong.size * 0.6) { "only $fit of ${strong.size} strong-beat notes fit the chords" }
        check(piece.melody.all { it.midi in 55..96 })
        println("Largo: ${piece.lengthBars} bars, $fit/${strong.size} strong-beat chord tones")
    }

    /** Downloaded instruments sit at the level of the app's own on the same stage. */
    @Test
    fun downloadedInstrumentsMatchTheBand() {
        install()
        val piece = Soundscapes.all.first { it.cloudId == "largo" }.piece!!
        fun rms(stage: Int, id: String) = stageRms(piece, stage, Instruments.indexOf(id))
        for ((stage, own, cloud) in listOf(Triple(4, "horn", "trombone"), Triple(2, "horn", "trombone"),
                Triple(0, "cello", "trombone"), Triple(3, "harp", "handpan"), Triple(5, "glock", "handpan"))) {
            val a = rms(stage, own); val b = rms(stage, cloud)
            println("stage $stage %-8s %.4f  %-8s %.4f".format(own, a, cloud, b))
            check(b > a * 0.5 && b < a * 2.0) { "$cloud on stage $stage at $b, $own at $a" }
        }
    }

    private class Drive(val peak: Int, val levels: List<Double>, val jumps: List<Int>, val pcm: ByteArray)

    /** Plays [track] with [band] along a drive: parked, 30, 60, 90, 120 km/h, parked again. */
    private fun drive(track: Int, band: IntArray): Drive {
        val model = SoundModel().apply { setPreset(track) }
        val renderer = SoundRenderer(model).apply { setPreset(track); setOrchestration(track, band) }
        val buffer = ShortArray(1024 * 2)
        val pcm = java.io.ByteArrayOutputStream()
        var peak = 0
        val previous = IntArray(2)
        val legs = listOf(0.0 to 12.0, 30.0 to 12.0, 60.0 to 12.0, 90.0 to 12.0, 120.0 to 16.0, 0.0 to 14.0)
        var kmh = 0.0
        val levels = mutableListOf<Double>()
        val jumps = mutableListOf<Int>()
        for ((index, leg) in legs.withIndex()) {
            val (target, seconds) = leg
            val from = kmh
            val blocks = (seconds * SoundRenderer.RATE / 1024).toInt()
            var sum = 0.0
            var jump = 0
            for (block in 0 until blocks) {
                kmh = from + (target - from) * minOf(1.0, (block + 1.0) / (blocks * 0.4))
                model.setSpeed(kmh)
                renderer.render(buffer)
                for (i in buffer.indices) {
                    val s = buffer[i].toInt()
                    peak = maxOf(peak, abs(s))
                    // Like ClickFreeTest: skip the first second while the model settles.
                    if (index > 0 || block >= SoundRenderer.RATE / 1024) jump = maxOf(jump, abs(s - previous[i % 2]))
                    previous[i % 2] = s
                    sum += s.toDouble() * s
                }
                for (s in buffer) { pcm.write(s.toInt() and 0xff); pcm.write(s.toInt() shr 8 and 0xff) }
            }
            levels += sqrt(sum / (blocks * 2048))
            jumps += jump
        }
        return Drive(peak, levels, jumps, pcm.toByteArray())
    }

    /** The downloaded score with downloaded instruments along a drive: no clipping, no clicks, not silent. */
    @Test
    fun downloadedScorePlaysCleanly() {
        install()
        val track = Soundscapes.all.indexOfFirst { it.cloudId == "largo" }
        val own = IntArray(6) { Instruments.indexOf(Soundscapes.all[track].piece!!.defaults[it]) }
        val cloud = intArrayOf(Instruments.indexOf("trombone"), Instruments.indexOf("orchperc"), Instruments.indexOf("strings"),
            Instruments.indexOf("handpan"), Instruments.indexOf("trombone"), Instruments.indexOf("handpan"))
        val reference = drive(track, own)
        val result = drive(track, cloud)
        println("Largo, app instruments:   peak ${reference.peak}, rms ${reference.levels.map { it.toInt() }}, steps ${reference.jumps}")
        println("Largo, cloud instruments: peak ${result.peak}, rms ${result.levels.map { it.toInt() }}, steps ${result.jumps}")
        check(result.peak < 30000) { "clips: ${result.peak}" }
        // Parked, only the bass (the trombone) plays: the app's own click rule applies.
        check(result.jumps[0] < 900) { "a click while parked: ${result.jumps[0]}" }
        // With the band playing, attacks are no sharper than with the app's own instruments.
        for (leg in 1..5) check(result.jumps[leg] < reference.jumps[leg] * 1.5 + 300) {
            "leg $leg steps ${result.jumps[leg]} vs ${reference.jumps[leg]} with the app's instruments"
        }
        check(result.levels.all { it > 150 }) { "too quiet somewhere: ${result.levels}" }
        check(result.levels[4] > result.levels[0]) { "more instruments at speed should be fuller: ${result.levels}" }
        System.getenv("CLOUD_PREVIEW")?.let { name ->
            val dir = File("build/preview").apply { mkdirs() }
            writeWav(File(dir, "$name.wav"), result.pcm)
        }
    }

    /**
     * A pack written by the web creator platform (docs/studio): `node tools/studio/check.mjs` writes one to
     * build/studio-pack (a zip downloaded from the studio page can be unpacked there too). The car downloads,
     * loads and plays it like the example cloud. Skipped when there is none.
     */
    @Test
    fun aStudioMadePackPlaysInTheCar() {
        val dir = File("build/studio-pack")
        org.junit.Assume.assumeTrue("run node tools/studio/check.mjs first", File(dir, "catalog.json").isFile)
        val studio = dir.canonicalFile.toURI().toString()
        val root = Files.createTempDirectory("library").toFile()
        val items = CloudLibrary.fetchCatalog(studio)
        for (item in items) {
            check(item.files.sumOf { File(dir, it).length() } == item.bytes) { "${item.id}: bytes" }
            CloudLibrary.download(item, root, studio)
        }
        CloudLibrary.load(root)
        check(CloudLibrary.installed(root) == items.map { it.id }.toSet()) { "not every pack installed: ${CloudLibrary.installed(root)}" }
        for (item in items.filter { !it.isScore }) {
            val index = Instruments.indexOf(item.id)
            for (stage in 0..5) check((index in Instruments.options[stage]) == (stage in item.stages && stage != 1)) { "${item.id} on stage $stage" }
        }
        for (item in items.filter { it.isScore }) {
            val track = Soundscapes.all.indexOfFirst { it.cloudId == item.id }
            check(track >= 0 && track in Albums.downloaded) { item.id }
            // Each stage plays the suggested instrument when the car has it, else the default band.
            val band = IntArray(6) { stage ->
                val id = Soundscapes.all[track].piece!!.defaults[stage]
                val studioMade = items.firstOrNull { it.id == id && stage in it.stages }
                Instruments.indexOf(studioMade?.id ?: id)
            }
            val result = drive(track, band)
            println("Studio pack ${item.id}: band ${band.map { Instruments.all[it].id }}, peak ${result.peak}, rms ${result.levels.map { it.toInt() }}")
            check(result.peak < 30000) { "clips: ${result.peak}" }
            check(result.levels.all { it > 100 }) { "too quiet somewhere: ${result.levels}" }
        }
    }

    @Test
    fun aBrokenPackIsNotInstalled() {
        val root = Files.createTempDirectory("library").toFile()
        val bad = CloudLibrary.parseCatalog("""{"items":[{"id":"evil","type":"score","title":"x","files":["../catalog.json"]}]}""").single()
        check(runCatching { CloudLibrary.download(bad, root, base) }.isFailure)
        val wrong = CloudLibrary.parseCatalog("""{"items":[{"id":"catalog","type":"score","title":"x","files":["catalog.json"]}]}""").single()
        check(runCatching { CloudLibrary.download(wrong, root, base) }.isFailure)
        check(CloudLibrary.installed(root).isEmpty() && root.listFiles()!!.isEmpty())
        // Ids that could escape the folder are not even listed.
        check(CloudLibrary.parseCatalog("""{"items":[{"id":"../x","type":"score","title":"x","files":[]}]}""").isEmpty())
    }

    private fun stageRms(piece: Piece, stage: Int, kind: Int): Double {
        val engine = PieceEngine(piece)
        val ids = IntArray(6) { Instruments.indexOf(piece.defaults[it]) }.also { it[stage] = kind }
        engine.setOrchestration(ids)
        val mixes = DoubleArray(6).also { it[stage] = 1.0 }
        val scene = Soundscapes.all.first { it.piece === piece }
        val bpm = scene.baseBpm
        val stepSamples = (60.0 / bpm / 4.0 * 44100).toInt()
        var sum = 0.0; var count = 0
        for (step in 0 until piece.lengthSteps) {
            engine.step(step % piece.stepsPerBar, (step / piece.stepsPerBar).toLong(), 0, intArrayOf(0, 4, 7, 12, 16), scene, 2, bpm, false)
            repeat(stepSamples) {
                engine.render(0.6, 0.2, 0.0, mixes, 1.0, bpm)
                val v = if (stage == 0) engine.bass else (engine.left + engine.right) * 0.5
                sum += v * v; count++
            }
        }
        return sqrt(sum / count)
    }

    private fun writeWav(file: File, data: ByteArray) = RandomAccessFile(file, "rw").use { out ->
        out.setLength(0)
        fun i32(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun i16(v: Int) = out.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        out.writeBytes("RIFF"); i32(36 + data.size); out.writeBytes("WAVEfmt "); i32(16); i16(1); i16(2)
        i32(SoundRenderer.RATE); i32(SoundRenderer.RATE * 4); i16(4); i16(16); out.writeBytes("data"); i32(data.size)
        out.write(data)
    }
}
