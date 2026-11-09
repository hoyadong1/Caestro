package com.drivesoundtrack.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.drivesoundtrack.app.ui.MainScreen
import com.drivesoundtrack.app.ui.PlayerActions
import com.drivesoundtrack.app.ui.PlayerStatus
import com.drivesoundtrack.app.vehicle.CarPedalReader
import com.drivesoundtrack.app.vehicle.DriveRestrictionsReader

class MainActivity : ComponentActivity(), PlayerActions {
    companion object {
        private const val SPEED_PERMISSION = "android.car.permission.CAR_SPEED"
        private const val PEDAL_PERMISSION = CarPedalReader.PERMISSION
    }

    private var status by mutableStateOf(PlayerStatus())
    private var restricted by mutableStateOf(false)
    private var restrictionsKnown by mutableStateOf(false)
    private var permissionsChanged by mutableStateOf(0)
    private var restrictionsReader: DriveRestrictionsReader? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) { status = PlayerStatus.from(intent) }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContent {
            permissionsChanged // Re-evaluate the prompt after a permission result.
            MainScreen(status, restricted, canRequestPermission(), this)
        }
        // Opening the app connects the soundtrack right away; the button then only disconnects.
        if (state == null) send(SoundtrackService.PLAY) else startService(Intent(this, SoundtrackService::class.java))
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(receiver, IntentFilter(SoundtrackService.STATUS), RECEIVER_NOT_EXPORTED)
        send(SoundtrackService.STATUS_REQUEST)
        try {
            restrictionsReader = DriveRestrictionsReader().also { reader ->
                reader.start(this) { value -> runOnUiThread { restricted = value; restrictionsKnown = true } }
            }
        } catch (_: LinkageError) { }
    }

    override fun onStop() {
        restrictionsReader?.stop(); restrictionsReader = null; restrictionsKnown = false
        unregisterReceiver(receiver)
        super.onStop()
    }

    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        if (results.values.any { it }) send(SoundtrackService.RECONNECT_SPEED)
        permissionsChanged++
    }

    private fun canRequestPermission(): Boolean = restrictionsKnown && !restricted && !status.carAvailable &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE) &&
        (checkSelfPermission(SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED ||
            (pedalPermissionSupported() && checkSelfPermission(PEDAL_PERMISSION) != PackageManager.PERMISSION_GRANTED))

    private fun pedalPermissionSupported(): Boolean = try {
        packageManager.getPermissionInfo(PEDAL_PERMISSION, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) { false }

    // PlayerActions

    override fun togglePlay() = send(when {
        status.scenarioEnabled -> SoundtrackService.SCENARIO_TOGGLE // stops the demo and its playback
        status.playing -> SoundtrackService.PAUSE
        else -> SoundtrackService.PLAY
    })
    override fun toggleScenario() = send(SoundtrackService.SCENARIO_TOGGLE)
    override fun selectTrack(album: Int, index: Int) = send(SoundtrackService.PRESET) { putExtra("album", album); putExtra("preset", index) }
    override fun setInstrument(stage: Int, instrument: Int, band: Boolean) = send(SoundtrackService.INSTRUMENT) {
        putExtra("stage", stage); putExtra("instrument", instrument); putExtra("band", band)
    }
    override fun resetInstruments(band: Boolean) = send(SoundtrackService.RESET_INSTRUMENTS) { putExtra("band", band) }
    override fun toggleMyAlbum(index: Int) = send(SoundtrackService.MY_ALBUM_TOGGLE) { putExtra("preset", index) }
    override fun skip(direction: Int) = send(SoundtrackService.SKIP) { putExtra("direction", direction) }
    override fun audition(stage: Int) = send(SoundtrackService.AUDITION) { putExtra("stage", stage) }
    override fun setInstruments(ids: IntArray, band: Boolean) =
        send(SoundtrackService.INSTRUMENT_SET) { putExtra("instruments", ids); putExtra("band", band) }
    override fun demoSpeed(kmh: Double) {
        if (!status.carAvailable) send(SoundtrackService.DEMO_SPEED) { putExtra("kmh", kmh) }
    }
    override fun demoAccelerator(percent: Double) = band("accelerator") { putExtra("value", percent) }
    override fun demoBrake(active: Boolean) = band("brake") { putExtra("active", active) }
    override fun demoLeftTurn(active: Boolean) {
        band("turn") { putExtra("value", if (active) -1 else 0) }
        band("steering") { putExtra("value", if (active) -150.0 else 0.0) }
    }
    override fun demoWiper(active: Boolean) = band("wiper") { putExtra("active", active) }
    override fun requestVehiclePermissions() {
        val requested = mutableListOf(SPEED_PERMISSION)
        if (pedalPermissionSupported()) requested += PEDAL_PERMISSION
        permissionRequest.launch(requested.toTypedArray())
    }

    private fun band(instrument: String, extras: Intent.() -> Unit) {
        if (!status.carAvailable) send(SoundtrackService.DEMO_BAND) { putExtra("instrument", instrument); extras() }
    }

    private fun send(action: String, extras: Intent.() -> Unit = {}) {
        val intent = Intent(this, SoundtrackService::class.java).setAction(action).apply(extras)
        if (action == SoundtrackService.PLAY || action == SoundtrackService.SCENARIO_TOGGLE)
            startForegroundService(intent) else startService(intent)
    }
}
