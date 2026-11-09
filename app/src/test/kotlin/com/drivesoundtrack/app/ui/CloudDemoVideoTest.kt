package com.drivesoundtrack.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performSemanticsAction
import com.drivesoundtrack.app.audio.Albums
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.SoundModel
import com.drivesoundtrack.app.audio.SoundRenderer
import com.drivesoundtrack.app.audio.Soundscapes
import com.drivesoundtrack.app.library.CloudLibrary
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.RandomAccessFile

/**
 * Making tool, not a check: the frames and sound of the cloud demo video, without an emulator.
 * The screens are the app's own Compose UI drawn on the JVM; the sound is the app's audio engine
 * playing the downloaded score with the downloaded instruments along the same speed curve.
 *
 *   $env:DEMO_VIDEO = "1"; .\gradlew.bat :app:testDebugUnitTest --tests '*CloudDemoVideoTest*'
 *   then tools/cloud/make_demo_video.ps1
 *
 * Writes app/build/demo: PNG frames in frames, frames.txt (ffmpeg concat list), captions.srt, drive.wav, timing.txt.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1678dp-h1210dp-land-mdpi")
class CloudDemoVideoTest {
    @get:Rule val compose = createComposeRule()

    private object NoActions : PlayerActions {
        override fun togglePlay() {}
        override fun toggleScenario() {}
        override fun selectTrack(album: Int, index: Int) {}
        override fun setInstrument(stage: Int, instrument: Int, band: Boolean) {}
        override fun resetInstruments(band: Boolean) {}
        override fun toggleMyAlbum(index: Int) {}
        override fun skip(direction: Int) {}
        override fun audition(stage: Int) {}
        override fun setInstruments(ids: IntArray, band: Boolean) {}
        override fun demoSpeed(kmh: Double) {}
        override fun demoAccelerator(percent: Double) {}
        override fun demoBrake(active: Boolean) {}
        override fun demoLeftTurn(active: Boolean) {}
        override fun demoWiper(active: Boolean) {}
        override fun requestVehiclePermissions() {}
    }

    private val out = File("build/demo")
    private val frames = File(out, "frames")
    private val list = StringBuilder()
    private val captions = StringBuilder()
    private var frame = 0
    private var clock = 0.0
    private var captionStart = 0.0
    private var caption: String? = null

    private fun tap(description: String, substring: Boolean = false) {
        compose.onNodeWithContentDescription(description, substring = substring).performSemanticsAction(SemanticsActions.OnClick)
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(300)
    }

    /** Draws the screen once and shows it for [seconds]; the stage animation moves on by [advanceMs]. */
    private fun hold(seconds: Double, advanceMs: Long = 400) {
        Snapshot.sendApplyNotifications()
        compose.mainClock.advanceTimeBy(advanceMs)
        val file = File(frames, "%05d.png".format(frame++))
        compose.onRoot().captureRoboImage(file.path)
        list.append("file 'frames/${file.name}'\nduration %.3f\n".format(seconds))
        clock += seconds
    }

    private fun say(text: String?) {
        caption?.let { captions.append("${captions.lines().count { it.contains("-->") } + 1}\n${t(captionStart)} --> ${t(clock)}\n$it\n\n") }
        caption = text
        captionStart = clock
    }

    private fun t(s: Double): String {
        val ms = (s * 1000).toLong()
        return "%02d:%02d:%02d,%03d".format(ms / 3600000, ms / 60000 % 60, ms / 1000 % 60, ms % 1000)
    }

    @Test
    fun cloudDemo() {
        assumeTrue("set DEMO_VIDEO to make the demo video frames", System.getenv("DEMO_VIDEO") != null)
        frames.deleteRecursively(); frames.mkdirs()
        compose.mainClock.autoAdvance = false

        val base = File("../docs/cloud/").canonicalFile.toURI().toString()
        val root = java.nio.file.Files.createTempDirectory("library").toFile()
        val items = CloudLibrary.fetchCatalog(base)
        val canon = Soundscapes.all.indexOfFirst { it.name == "Canon" }
        val parked = PlayerStatus(playing = true, preset = canon, carAvailable = true, kmh = 0.0, bpm = 66, layers = 1, layerMask = 1,
            album = Albums.CLASSIC, instruments = Soundscapes.all[canon].piece!!.defaults.map { Instruments.indexOf(it) })
        var status by mutableStateOf(parked)
        var cloud by mutableStateOf(CloudState(items = items))
        compose.setContent { MainScreen(status, restricted = false, canRequestPermission = false, actions = NoActions, cloud = cloud) }

        // 1. Parked: open Get and see what the cloud offers.
        say("정차 중 ‘받기’ 탭: 웹 제작 플랫폼에서 만든 악보·악기가 클라우드에 올라와 있습니다")
        tap("받기"); hold(4.0)
        say("차에서는 만들지 않고 내려받기만 합니다 · 악보 「Largo」 받기")
        cloud = cloud.copy(downloading = "largo"); hold(2.0)
        for (item in items) CloudLibrary.download(item, root, base)
        CloudLibrary.load(root)
        cloud = cloud.copy(downloading = null, installed = setOf("largo"), message = "「Largo」을 받았습니다. 앨범의 ‘받은 곡’에 있어요.")
        hold(2.5)
        say("녹음을 올린 악기(트롬본)와 음색을 디자인한 악기(핸드팬)도 받기")
        cloud = cloud.copy(downloading = "trombone"); hold(1.5)
        cloud = cloud.copy(downloading = "handpan", installed = setOf("largo", "trombone"), message = "「트롬본」을 받았습니다. 악단에서 고를 수 있어요."); hold(1.5)
        cloud = cloud.copy(downloading = null, installed = setOf("largo", "trombone", "handpan"), message = "「핸드팬」을 받았습니다. 악단에서 고를 수 있어요.")
        hold(2.5)

        // 2. The score is in Albums; the instruments are in the band.
        val largo = Soundscapes.all.indexOfFirst { it.cloudId == "largo" }
        val band = listOf("trombone", "orchperc", "strings", "handpan", "trombone", "handpan").map { Instruments.indexOf(it) }
        val library = Soundscapes.all.size * 1000 + Instruments.all.size
        status = parked.copy(preset = largo, album = Albums.DOWNLOADED, bpm = 58, library = library)
        say("받은 악보는 앨범의 ‘받은 곡’에 들어갑니다")
        tap("앨범"); hold(3.5)
        say("받은 악기는 악단에서 고릅니다 · 선율에 트롬본")
        status = status.copy(instruments = band, band = band)
        tap("악단"); tap("5단계", substring = true); hold(3.5)
        say("분산음에 핸드팬 · 고른 단계는 정차 중에도 바로 미리 들려줍니다")
        tap("4단계", substring = true); hold(3.5)
        tap("지금 재생")

        // 3. Drive: the downloaded score with the downloaded instruments, speed adding stages.
        val introSeconds = clock
        val model = SoundModel().apply { setPreset(largo) }
        val renderer = SoundRenderer(model).apply { setPreset(largo); setOrchestration(largo, band.toIntArray()) }
        val buffer = ShortArray(1024 * 2)
        val pcm = java.io.ByteArrayOutputStream()
        val legs = listOf(
            Triple(0.0, 10.0, "정차 · 받은 트롬본이 베이스로 연주합니다"),
            Triple(30.0, 12.0, "출발 · 18 km/h부터 타악기 합류"),
            Triple(60.0, 12.0, "60 km/h · 현악 화음과 받은 핸드팬 분산음"),
            Triple(92.0, 12.0, "90 km/h · 받은 트롬본이 「라르고」 선율"),
            Triple(118.0, 14.0, "118 km/h · 핸드팬이 고음까지, 여섯 단계 모두"),
            Triple(0.0, 14.0, "감속 · 악기가 하나씩 빠지고 다시 베이스만"),
        )
        val stageKmh = listOf(0, 18, 38, 58, 82, 105)
        val scene = Soundscapes.all[largo]
        var kmh = 0.0
        val framesPerSecond = 2
        val blocksPerFrame = SoundRenderer.RATE / 1024.0 / framesPerSecond
        var nextFrame = 0.0
        var block = 0
        for ((target, seconds, text) in legs) {
            say(text)
            val from = kmh
            val blocks = (seconds * SoundRenderer.RATE / 1024).toInt()
            for (b in 0 until blocks) {
                kmh = from + (target - from) * minOf(1.0, (b + 1.0) / (blocks * 0.4))
                model.setSpeed(kmh)
                renderer.render(buffer)
                for (s in buffer) { pcm.write(s.toInt() and 0xff); pcm.write(s.toInt() shr 8 and 0xff) }
                if (block >= nextFrame) {
                    nextFrame += blocksPerFrame
                    val mask = stageKmh.foldIndexed(0) { i, m, at -> if (kmh >= at) m or (1 shl i) else m }
                    status = status.copy(kmh = kmh, layerMask = mask, layers = Integer.bitCount(mask),
                        bpm = (scene.baseBpm + scene.tempoRange * (kmh / 130.0)).toInt())
                    hold(1.0 / framesPerSecond, advanceMs = 500)
                }
                block++
            }
        }
        say(null)
        val driveSeconds = block * 1024.0 / SoundRenderer.RATE
        writeWav(File(out, "drive.wav"), pcm.toByteArray())
        // The concat demuxer needs the last file listed twice for its duration to count.
        list.append("file 'frames/%05d.png'\n".format(frame - 1))
        File(out, "frames.txt").writeText(list.toString())
        File(out, "captions.srt").writeText(captions.toString())
        File(out, "timing.txt").writeText("intro %.3f\ndrive %.3f\nvideo %.3f\n".format(introSeconds, driveSeconds, clock))
    }

    private fun writeWav(file: File, data: ByteArray) = RandomAccessFile(file, "rw").use { o ->
        o.setLength(0)
        fun i32(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte()))
        fun i16(v: Int) = o.write(byteArrayOf(v.toByte(), (v shr 8).toByte()))
        o.writeBytes("RIFF"); i32(36 + data.size); o.writeBytes("WAVEfmt "); i32(16); i16(1); i16(2)
        i32(SoundRenderer.RATE); i32(SoundRenderer.RATE * 4); i16(4); i16(16); o.writeBytes("data"); i32(data.size)
        o.write(data)
    }
}
