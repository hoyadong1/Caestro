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
    val album: Int = 0,
    val myAlbum: List<Int> = emptyList(),
    /** Instrument per stage for a classical track; empty for the original soundscapes. */
    val instruments: List<Int> = emptyList(),
    /** "My band" per stage; empty while each track uses its own suggestion. */
    val band: List<Int> = emptyList(),
    /** The playing track has instruments of its own instead of following the band. */
    val ownInstruments: Boolean = false,
    /** Changes when downloaded scores or instruments were added, so the screen redraws its lists. */
    val library: Int = 0,
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
                album = intent.getIntExtra("album", 0),
                myAlbum = intent.getIntArrayExtra("myAlbum")?.toList() ?: emptyList(),
                instruments = intent.getIntArrayExtra("instruments")?.toList() ?: emptyList(),
                band = intent.getIntArrayExtra("band")?.toList() ?: emptyList(),
                ownInstruments = intent.getBooleanExtra("ownInstruments", false),
                library = intent.getIntExtra("library", 0),
            )
        }
    }
}

/** The "Get" tab: what the cloud offers and what this car has downloaded. */
data class CloudState(
    val items: List<com.drivesoundtrack.app.library.CloudLibrary.Item> = emptyList(),
    val installed: Set<String> = emptySet(),
    val loading: Boolean = false,
    /** Id of the pack being downloaded. */
    val downloading: String? = null,
    val message: String? = null,
    val failed: Boolean = false,
)

/** UI actions the screen can request from the host activity. */
interface PlayerActions {
    fun togglePlay()
    fun toggleScenario()
    fun selectTrack(album: Int, index: Int)
    /** [band]: change "my band" (every track without its own choice) instead of the playing track. */
    fun setInstrument(stage: Int, instrument: Int, band: Boolean)
    fun resetInstruments(band: Boolean)
    fun toggleMyAlbum(index: Int)
    /** Previous (-1) or next (1) track of the album, like the steering-wheel media buttons. */
    fun skip(direction: Int)
    /** Let one stage (1..5) play while its instrument is being chosen; -1 ends it. */
    fun audition(stage: Int)
    /** While stopped: let a stage's instrument (0 = bass) play for a few seconds. */
    fun preview(stage: Int) {}
    /** Set all six stages at once (a preset ensemble). */
    fun setInstruments(ids: IntArray, band: Boolean)
    fun demoSpeed(kmh: Double)
    fun demoAccelerator(percent: Double)
    fun demoBrake(active: Boolean)
    fun demoLeftTurn(active: Boolean)
    fun demoWiper(active: Boolean)
    fun requestVehiclePermissions()
    /** Ask the cloud what it offers. */
    fun refreshCloud() {}
    /** Download one pack (parked only); it then shows up in Albums or the band. */
    fun download(id: String) {}
}
