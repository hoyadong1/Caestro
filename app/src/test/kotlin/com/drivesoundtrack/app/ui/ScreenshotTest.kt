package com.drivesoundtrack.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.Soundscapes
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Looking tool, not a check: draws the app's tabs on the JVM so the screen can be reviewed
 * without an emulator. The size matches the app area of the Pleos Connect display.
 *
 *   $env:SCREENSHOTS = "1"; .\gradlew.bat :app:testDebugUnitTest --tests 'ScreenshotTest'
 *
 * Writes PNG files into app/build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w1678dp-h1210dp-land-mdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val canon = Soundscapes.all.indexOfFirst { it.name == "Canon" }
    private val parked = PlayerStatus(
        playing = true, preset = canon, carAvailable = true, kmh = 0.0, bpm = 66, layers = 1, layerMask = 0b1, album = 1,
        myAlbum = listOf(canon), instruments = Soundscapes.all[canon].piece!!.defaults.map { Instruments.indexOf(it) },
    )

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

    private fun shoot(name: String) {
        compose.mainClock.advanceTimeBy(1200)
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test
    fun tabs() {
        assumeTrue("set SCREENSHOTS to draw the screens", System.getenv("SCREENSHOTS") != null)
        // The stage animates forever; drive the clock by hand.
        compose.mainClock.autoAdvance = false
        var status by mutableStateOf(parked)
        compose.setContent { MainScreen(status, restricted = false, canRequestPermission = false, actions = NoActions) }
        shoot("1-now-playing-parked")
        compose.onNodeWithContentDescription("악단").performSemanticsAction(SemanticsActions.OnClick)
        shoot("3-band-bass")
        compose.onNodeWithContentDescription("2단계", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        shoot("4-band-rhythm")
        compose.onNodeWithContentDescription("5단계", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        shoot("5-band-melody")
        // Driving at 47 km/h: the rail locks and only Now playing is shown.
        status = parked.copy(kmh = 47.0, bpm = 68, layers = 3, layerMask = 0b111)
        shoot("6-now-playing-driving")
        status = parked
        compose.onNodeWithContentDescription("앨범").performSemanticsAction(SemanticsActions.OnClick)
        shoot("2-albums")
    }
}
