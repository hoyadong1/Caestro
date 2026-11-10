package com.drivesoundtrack.app

import android.content.Intent
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.SoundModel
import com.drivesoundtrack.app.audio.SoundRenderer
import com.drivesoundtrack.app.audio.Soundscapes
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The real playback path without an emulator: the app's service gets the same commands the band screen
 * sends (choose a track, change an instrument for the band or for this track), and the sound its own
 * renderer produces is compared with a run that made no change. A change must be heard at once where it
 * applies, and nowhere else.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceInstrumentSwapTest {
    private val rate = SoundRenderer.RATE

    private class Run(val service: SoundtrackService) {
        val renderer: SoundRenderer = field(field(service, "engine")!!, "renderer") as SoundRenderer
        val model: SoundModel = field(service, "model") as SoundModel
        companion object {
            fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
        }
    }

    private fun start(): Run {
        RuntimeEnvironment.getApplication().getSharedPreferences("soundtrack", 0).edit().clear()
            .putBoolean("threeScenesMigrated", true).putBoolean("realInstruments", true).commit()
        // No vehicle services here: binding to them stays pending instead of connecting to nothing.
        org.robolectric.Shadows.shadowOf(RuntimeEnvironment.getApplication()).setComponentNameAndServiceForBindService(
            android.content.ComponentName("test.vehicle", "test.vehicle.Absent"), android.os.Binder())
        val service = Robolectric.buildService(SoundtrackService::class.java).create().get()
        return Run(service)
    }

    private fun Run.send(action: String, extras: Intent.() -> Unit = {}) {
        service.onStartCommand(Intent(action).apply(extras), 0, 1)
    }

    /** Mono level per 0.25 s window, and spectrum-free "fingerprint" via zero crossings and energy. */
    private fun Run.render(seconds: Double): ShortArray {
        val out = ShortArray((seconds * rate).toInt() * 2)
        val buffer = ShortArray(1024 * 2)
        var at = 0
        while (at < out.size) {
            model.setSpeed(110.0)
            renderer.render(buffer)
            val n = minOf(buffer.size, out.size - at)
            buffer.copyInto(out, at, 0, n); at += n
        }
        return out
    }

    /** Relative difference of two captures over [from, to) seconds. */
    private fun gap(a: ShortArray, b: ShortArray, from: Double, to: Double): Double {
        var d = 0.0; var e = 0.0
        for (i in (from * rate).toInt() * 2 until (to * rate).toInt() * 2) {
            val x = a[i].toDouble(); val y = b[i].toDouble()
            d += (x - y) * (x - y); e += y * y
        }
        return sqrt(d / e.coerceAtLeast(1.0))
    }

    private fun scenario(track: Int, setup: Run.() -> Unit, change: (Run.() -> Unit)?): ShortArray {
        val run = start()
        run.send(SoundtrackService.PRESET) { putExtra("preset", track) }
        run.setup()
        // Settle the speed so every stage plays, as at 110 km/h.
        run.model.setSpeed(110.0)
        val first = run.render(6.0)
        change?.invoke(run)
        val second = run.render(6.0)
        return first + second
    }

    private fun Run.instrument(stage: Int, id: String, band: Boolean) = send(SoundtrackService.INSTRUMENT) {
        putExtra("stage", stage); putExtra("instrument", Instruments.indexOf(id)); putExtra("band", band)
    }

    @Test
    fun changesAreHeardWhereTheyApply() {
        val canon = Soundscapes.all.indexOfFirst { it.name == "Canon" }
        val pulse = Soundscapes.all.indexOfFirst { it.name == "Pulse" }
        val control = scenario(canon, {}, null)
        val again = scenario(canon, {}, null)
        val same = gap(again, control, 1.0, 12.0)
        println("two runs without a change differ by %.3f".format(same))
        val problems = mutableListOf<String>()
        fun expect(name: String, heard: Boolean, capture: ShortArray, reference: ShortArray) {
            val before = gap(capture, reference, 1.0, 5.9)
            val after = gap(capture, reference, 7.0, 12.0)
            println("%-34s before %.3f  after %.3f  %s".format(name, before, after, if (after > 0.2) "changed" else "unchanged"))
            if (before > 0.05 + same) problems += "$name: differs before the change ($before)"
            if (heard && after < 0.2) problems += "$name: the change was not heard ($after)"
            if (!heard && after > 0.05 + same) problems += "$name: something changed that should not ($after)"
        }
        for ((stage, id) in listOf(0 to "synthbass", 1 to "electro", 2 to "organ", 3 to "marimba", 4 to "trumpet", 5 to "glock")) {
            expect("band: stage $stage → $id", true, scenario(canon, {}, { instrument(stage, id, band = true) }), control)
            expect("this track: stage $stage → $id", true, scenario(canon, {}, { instrument(stage, id, band = false) }), control)
        }
        // A track arranged on its own keeps its arrangement when the band changes.
        val ownControl = scenario(canon, { instrument(3, "marimba", band = false) }, null)
        expect("own track, band changes melody", false,
            scenario(canon, { instrument(3, "marimba", band = false) }, { instrument(4, "trumpet", band = true) }), ownControl)
        // An original soundscape is not played by the band at all.
        val pulseControl = scenario(pulse, {}, null)
        expect("original track, band changes melody", false, scenario(pulse, {}, { instrument(4, "trumpet", band = true) }), pulseControl)
        check(problems.isEmpty()) { problems.joinToString("\n") }
        check(abs(same) < 0.05) { "the renderer is not repeatable: $same" }
    }
}
