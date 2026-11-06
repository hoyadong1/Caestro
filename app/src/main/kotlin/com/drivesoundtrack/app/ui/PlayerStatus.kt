package com.drivesoundtrack.app.ui

import android.content.Intent
import com.drivesoundtrack.app.audio.Soundscapes

/** Snapshot of [com.drivesoundtrack.app.SoundtrackService] state, delivered by its STATUS broadcast. */
data class PlayerStatus(
    val playing: Boolean = false,
    val preset: Int = 0,
    val carAvailable: Boolean = false,
    val scenarioEnabled: Boolean = false,
    val scenarioAvailable: Boolean = false,
    val kmh: Double = 0.0,
    val bpm: Int = Soundscapes.all[0].baseBpm.toInt(),
    val layers: Int = 1,
    val layerMask: Int = 1,
    val bassSolo: Boolean = false,
    val accelerator: Double = 0.0,
    val brake: Double = 0.0,
    val steering: Double = 0.0,
    val turn: Int = 0,
    val wiper: Boolean = false,
) {
    companion object {
        fun from(intent: Intent): PlayerStatus {
            val preset = intent.getIntExtra("preset", 0).coerceIn(Soundscapes.all.indices)
            return PlayerStatus(
                playing = intent.getBooleanExtra("playing", false),
                preset = preset,
                carAvailable = intent.getBooleanExtra("carAvailable", false),
                scenarioEnabled = intent.getBooleanExtra("scenarioEnabled", false),
                scenarioAvailable = intent.getBooleanExtra("scenarioAvailable", false),
                kmh = intent.getDoubleExtra("kmh", 0.0),
                bpm = Math.round(intent.getDoubleExtra("bpm", Soundscapes.all[preset].baseBpm)).toInt(),
                layers = intent.getIntExtra("layers", 1),
                layerMask = intent.getIntExtra("layerMask", 1),
                bassSolo = intent.getBooleanExtra("bassSolo", false),
                accelerator = intent.getDoubleExtra("accelerator", 0.0),
                brake = intent.getDoubleExtra("brake", 0.0),
                steering = intent.getDoubleExtra("steering", 0.0),
                turn = intent.getIntExtra("turn", 0),
                wiper = intent.getBooleanExtra("wiper", false),
            )
        }
    }
}

/** UI actions the screen can request from the host activity. */
interface PlayerActions {
    fun togglePlay()
    fun toggleScenario()
    fun selectPreset(index: Int)
    fun demoSpeed(kmh: Double)
    fun demoAccelerator(percent: Double)
    fun demoBrake(active: Boolean)
    fun demoLeftTurn(active: Boolean)
    fun demoWiper(active: Boolean)
    fun requestVehiclePermissions()
}
