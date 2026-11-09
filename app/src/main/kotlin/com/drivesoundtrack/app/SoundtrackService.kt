package com.drivesoundtrack.app

import com.drivesoundtrack.app.audio.Albums
import com.drivesoundtrack.app.audio.Instruments
import com.drivesoundtrack.app.audio.SoundModel
import com.drivesoundtrack.app.audio.Soundscapes
import com.drivesoundtrack.app.audio.SynthEngine
import com.drivesoundtrack.app.vehicle.CarPedalReader
import com.drivesoundtrack.app.vehicle.CarSpeedReader
import com.drivesoundtrack.app.vehicle.DriveRestrictionsReader
import com.drivesoundtrack.app.vehicle.PleosBandReader
import com.drivesoundtrack.app.vehicle.PleosDriveModeReader
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.service.media.MediaBrowserService
import android.util.Log

class SoundtrackService : MediaBrowserService() {
    companion object {
        const val PLAY = "com.drivesoundtrack.PLAY"
        const val PAUSE = "com.drivesoundtrack.PAUSE"
        const val PRESET = "com.drivesoundtrack.PRESET"
        const val DEMO_SPEED = "com.drivesoundtrack.DEMO_SPEED"
        const val DEMO_BAND = "com.drivesoundtrack.DEMO_BAND"
        const val STATUS = "com.drivesoundtrack.STATUS"
        const val STATUS_REQUEST = "com.drivesoundtrack.STATUS_REQUEST"
        const val RECONNECT_SPEED = "com.drivesoundtrack.RECONNECT_SPEED"
        const val ARM_DEBUG_CAPTURE = "com.drivesoundtrack.ARM_DEBUG_CAPTURE"
        const val DEBUG_BASS_SOLO = "com.drivesoundtrack.DEBUG_BASS_SOLO"
        const val DEBUG_SPEED_JOURNEY = "com.drivesoundtrack.DEBUG_SPEED_JOURNEY"
        const val DEBUG_SPEED_HOLD = "com.drivesoundtrack.DEBUG_SPEED_HOLD"
        const val DEBUG_PRESET = "com.drivesoundtrack.DEBUG_PRESET"
        const val SCENARIO_TOGGLE = "com.drivesoundtrack.SCENARIO_TOGGLE"
        const val INSTRUMENT = "com.drivesoundtrack.INSTRUMENT"
        const val RESET_INSTRUMENTS = "com.drivesoundtrack.RESET_INSTRUMENTS"
        const val MY_ALBUM_TOGGLE = "com.drivesoundtrack.MY_ALBUM_TOGGLE"
        const val SKIP = "com.drivesoundtrack.SKIP"
        private const val CHANNEL = "drive_soundtrack_playback"
        private val NAMES = Soundscapes.all.map { it.name }
    }

    private lateinit var model: SoundModel
    private lateinit var engine: SynthEngine
    private lateinit var speedReader: CarSpeedReader
    private lateinit var driveModeReader: PleosDriveModeReader
    private lateinit var bandReader: PleosBandReader
    private lateinit var pedalReader: CarPedalReader
    private lateinit var restrictionsReader: DriveRestrictionsReader
    private lateinit var session: MediaSession
    private lateinit var audioManager: AudioManager
    private lateinit var focusRequest: AudioFocusRequest
    private var playing = false
    private var carAvailable = false
    @Volatile private var restricted = false
    private var preset = 0
    private var album = Albums.ORIGINAL
    private var myAlbum = IntArray(0)
    /** Instrument per stage for each classical track (null for the original soundscapes). */
    /** A track's own instruments; null when it follows the band (or, without a band, its own suggestion). */
    private val orchestrations = arrayOfNulls<IntArray>(Soundscapes.all.size)
    /** "My band": the instruments every classical track uses unless it has its own. Null = each track's suggestion. */
    private var band: IntArray? = null
    private var bassSolo = false
    private var debugJourneyEnabled = false
    private var scenarioEnabled = false
    private var vehicleKmh = 0.0
    private var loggedVehicleKmh = Int.MIN_VALUE
    private val pleosEmulator by lazy { Build.PRODUCT.contains("crp_avd", ignoreCase = true) }
    private val handler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            if (playing) { bandReader.pollWiperSwitch(model); followAlbum(); broadcastStatus(); handler.postDelayed(this, 800) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        model = SoundModel()
        // Recorded instruments read their notes from the APK's assets.
        com.drivesoundtrack.app.audio.SampleBank.opener = { name -> assets.open("samples/$name") }
        engine = SynthEngine(model)
        val preferences = getSharedPreferences("soundtrack", MODE_PRIVATE)
        preset = preferences.getInt("preset", 0).coerceIn(Soundscapes.all.indices)
        if (!preferences.getBoolean("threeScenesMigrated", false)) {
            // 0.9.0 went from six soundscapes to three, so an index saved earlier may point elsewhere.
            preset = 0
            preferences.edit().putInt("preset", preset).putBoolean("threeScenesMigrated", true).apply()
        }
        album = preferences.getInt("album", Albums.ORIGINAL).coerceIn(Albums.names.indices)
        myAlbum = (preferences.getString("myAlbum", null)?.split('|') ?: Albums.defaultMine)
            .mapNotNull { name -> NAMES.indexOf(name).takeIf { it >= 0 } }.toIntArray()
        // 0.12.0 moved to recorded instruments and new suggestions: earlier per-track choices start over.
        if (!preferences.getBoolean("realInstruments", false)) {
            val edit = preferences.edit()
            for (key in preferences.all.keys) if (key.startsWith("instruments_")) edit.remove(key)
            edit.putBoolean("realInstruments", true).apply()
        }
        band = preferences.getString("band", null)?.let { parseInstruments(it, Instruments.BAND_DEFAULT) }
        for (i in Soundscapes.all.indices) {
            val piece = Soundscapes.all[i].piece ?: continue
            val saved = preferences.getString("instruments_${NAMES[i]}", null) ?: continue
            orchestrations[i] = parseInstruments(saved, piece.defaults)
                .takeUnless { it.contentEquals(suggested(i)) } // a choice equal to the suggestion follows the band
        }
        applyAll()
        model.setPreset(preset)
        engine.setPreset(preset)
        engine.setPlaylist(Albums.tracks(album, myAlbum))
        speedReader = CarSpeedReader()
        connectSpeedReader()
        driveModeReader = PleosDriveModeReader().also { it.start(this, model) }
        bandReader = PleosBandReader().also { it.start(this, model) { broadcastStatus() } }
        pedalReader = CarPedalReader().also { it.start(this) { value -> model.setAccelerator(value); broadcastStatus() } }
        restrictionsReader = DriveRestrictionsReader().also { it.start(this) { value ->
            restricted = value
            broadcastStatus()
        } }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change ->
                if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
                    Log.i("DriveSoundtrack", "Audio focus change: $change")
                if (change <= 0) pause()
            }.build()
        session = MediaSession(this, "DriveSoundtrack")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                mediaId?.toIntOrNull()?.let { setPreset(it); play() }
            }
            override fun onSkipToNext() = skip(1)
            override fun onSkipToPrevious() = skip(-1)
        })
        session.isActive = true
        sessionToken = session.sessionToken
        updateSession()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Drive Soundtrack", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot = BrowserRoot("presets", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val items = mutableListOf<MediaBrowser.MediaItem>()
        if (parentId == "presets") {
            for (i in NAMES.indices) {
                val metadata = MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, i.toString())
                    .putString(MediaMetadata.METADATA_KEY_TITLE, NAMES[i])
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Drive Soundtrack").build()
                items += MediaBrowser.MediaItem(metadata.description, MediaBrowser.MediaItem.FLAG_PLAYABLE)
            }
        }
        result.sendResult(items)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Any command may arrive through startForegroundService (the scenario button, media
        // controls, a refused PLAY). Android kills the app unless startForeground follows within
        // seconds, so always enter the foreground first and leave it again if nothing is playing.
        val enteredForeground = enterForeground()
        when (intent?.action) {
            PLAY -> play()
            PAUSE -> pause()
            ARM_DEBUG_CAPTURE -> if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && !playing) {
                engine.armCapture(java.io.File(filesDir, "live-demo.wav"))
            }
            DEBUG_BASS_SOLO -> if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && !playing) {
                bassSolo = intent.getBooleanExtra("enabled", false)
                engine.setBassSolo(bassSolo)
                broadcastStatus()
            }
            DEBUG_SPEED_JOURNEY -> if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && !playing) {
                val enabled = intent.getBooleanExtra("enabled", false)
                debugJourneyEnabled = enabled
                engine.setDebugSpeedJourney(enabled)
            }
            DEBUG_SPEED_HOLD -> if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && !playing) {
                debugJourneyEnabled = true
                engine.setDebugSpeedJourney(false)
                model.setSpeed(intent.getDoubleExtra("kmh", 0.0))
                broadcastStatus()
            }
            DEBUG_PRESET -> if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && !playing) {
                setPreset(intent.getIntExtra("preset", 0), debugOverride = true)
            }
            PRESET -> {
                val chosenAlbum = intent.getIntExtra("album", -1)
                if (chosenAlbum in Albums.names.indices && !locked()) setAlbum(chosenAlbum)
                setPreset(intent.getIntExtra("preset", 0))
            }
            INSTRUMENT -> setInstrument(intent.getIntExtra("stage", -1), intent.getIntExtra("instrument", -1),
                intent.getBooleanExtra("band", false))
            RESET_INSTRUMENTS -> resetInstruments(intent.getBooleanExtra("band", false))
            MY_ALBUM_TOGGLE -> toggleMyAlbum(intent.getIntExtra("preset", -1))
            SKIP -> skip(if (intent.getIntExtra("direction", 1) < 0) -1 else 1)
            SCENARIO_TOGGLE -> if (scenarioEnabled) stopScenario() else startScenario()
            STATUS_REQUEST -> broadcastStatus()
            RECONNECT_SPEED -> {
                speedReader.stop()
                connectSpeedReader()
                pedalReader.start(this) { value -> model.setAccelerator(value); broadcastStatus() }
            }
            DEMO_SPEED -> if (!carAvailable) {
                model.setSpeed(intent.getDoubleExtra("kmh", 0.0))
                broadcastStatus()
            }
            DEMO_BAND -> if (!carAvailable) {
                when (intent.getStringExtra("instrument")) {
                    "brake" -> model.setBrake(intent.getBooleanExtra("active", false))
                    "steering" -> model.setSteering(intent.getDoubleExtra("value", 0.0))
                    "turn" -> model.setTurn(intent.getIntExtra("value", 0))
                    "wiper" -> model.setWiper(intent.getBooleanExtra("active", false))
                    "accelerator" -> model.setAccelerator(intent.getDoubleExtra("value", 0.0))
                }
                broadcastStatus()
            }
        }
        if (enteredForeground && !playing) stopForeground(STOP_FOREGROUND_REMOVE)
        return Service.START_STICKY
    }

    private fun playbackNotification(): Notification = Notification.Builder(this, CHANNEL)
        .setContentTitle("Drive Soundtrack")
        .setContentText("${NAMES[preset]} 재생 중")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setVisibility(Notification.VISIBILITY_PUBLIC).build()

    private fun enterForeground(): Boolean = try {
        startForeground(1, playbackNotification())
        true
    } catch (ex: RuntimeException) {
        Log.w("DriveSoundtrack", "Foreground service not allowed now", ex)
        false
    }

    private fun connectSpeedReader() {
        carAvailable = false
        speedReader.start(this,
            { available ->
                carAvailable = available
                if (!available && !debugJourneyEnabled && !scenarioEnabled) model.setSpeed(0.0)
                broadcastStatus()
            },
            { kmh ->
                vehicleKmh = kmh
                if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && kmh.toInt() != loggedVehicleKmh) {
                    loggedVehicleKmh = kmh.toInt()
                    Log.i("DriveSoundtrack", "Vehicle speed received: $loggedVehicleKmh km/h")
                }
                if (scenarioEnabled && kmh >= 5.0) handOffScenarioToVehicle()
                if (!debugJourneyEnabled && !scenarioEnabled) model.setSpeed(kmh)
                broadcastStatus()
            })
        broadcastStatus()
    }

    /** Choices on the screen are locked while driving; the album still moves on by itself. */
    private fun locked() = restricted || (carAvailable && !scenarioEnabled && vehicleKmh >= 5)

    private fun setAlbum(choice: Int) {
        album = choice
        engine.setPlaylist(Albums.tracks(album, myAlbum))
        getSharedPreferences("soundtrack", MODE_PRIVATE).edit().putInt("album", album).apply()
    }

    private fun skip(direction: Int) {
        val tracks = Albums.tracks(album, myAlbum).takeIf { it.isNotEmpty() } ?: Soundscapes.all.indices.toList().toIntArray()
        val index = tracks.indexOf(preset)
        setPreset(tracks[Math.floorMod((if (index < 0) 0 else index) + direction, tracks.size)])
    }

    /** The renderer moved to the album's next track on its own. */
    private fun followAlbum() {
        val now = engine.track()
        if (now == preset) return
        preset = now
        getSharedPreferences("soundtrack", MODE_PRIVATE).edit().putInt("preset", preset).apply()
        updateSession()
        if (playing) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(1, playbackNotification())
    }

    private fun parseInstruments(saved: String, defaults: List<String>): IntArray {
        val ids = saved.split('|')
        return IntArray(6) { stage ->
            Instruments.all.indexOfFirst { it.id == ids.getOrNull(stage) }.takeIf { it in Instruments.options[stage] }
                ?: Instruments.indexOf(defaults[stage])
        }
    }

    private fun idsOf(instruments: IntArray) = instruments.joinToString("|") { Instruments.all[it].id }

    /** What a track plays when it has no instruments of its own: the band, or the track's suggestion. */
    private fun suggested(track: Int): IntArray =
        band?.copyOf() ?: IntArray(6) { Instruments.indexOf(Soundscapes.all[track].piece!!.defaults[it]) }

    private fun effective(track: Int): IntArray? =
        if (Soundscapes.all[track].piece == null) null else orchestrations[track]?.copyOf() ?: suggested(track)

    private fun applyAll() {
        for (i in Soundscapes.all.indices) effective(i)?.let { engine.setOrchestration(i, it) }
    }

    /** Changes one stage of the band (every track without its own choice) or of the playing track only. */
    private fun setInstrument(stage: Int, instrument: Int, forBand: Boolean) {
        if (locked() || stage !in 0..5 || instrument !in Instruments.options[stage]) return
        val preferences = getSharedPreferences("soundtrack", MODE_PRIVATE).edit()
        if (forBand) {
            val next = (band ?: IntArray(6) { Instruments.indexOf(Instruments.BAND_DEFAULT[it]) }).copyOf()
            next[stage] = instrument
            band = next
            preferences.putString("band", idsOf(next))
        } else {
            val current = effective(preset) ?: return
            current[stage] = instrument
            orchestrations[preset] = current.takeUnless { it.contentEquals(suggested(preset)) }
            if (orchestrations[preset] == null) preferences.remove("instruments_${NAMES[preset]}")
            else preferences.putString("instruments_${NAMES[preset]}", idsOf(current))
        }
        preferences.apply()
        applyAll()
        broadcastStatus()
    }

    /** Band: back to each track's suggestion. Track: back to following the band. */
    private fun resetInstruments(forBand: Boolean) {
        if (locked()) return
        val preferences = getSharedPreferences("soundtrack", MODE_PRIVATE).edit()
        if (forBand) { band = null; preferences.remove("band") }
        else { orchestrations[preset] = null; preferences.remove("instruments_${NAMES[preset]}") }
        preferences.apply()
        applyAll()
        broadcastStatus()
    }

    private fun toggleMyAlbum(track: Int) {
        if (track !in Soundscapes.all.indices || locked()) return
        myAlbum = if (track in myAlbum) myAlbum.filter { it != track }.toIntArray() else myAlbum + track
        getSharedPreferences("soundtrack", MODE_PRIVATE).edit()
            .putString("myAlbum", myAlbum.joinToString("|") { NAMES[it] }).apply()
        if (album == Albums.MINE) engine.setPlaylist(myAlbum)
        broadcastStatus()
    }

    private fun setPreset(choice: Int, debugOverride: Boolean = false) {
        if (!debugOverride && locked()) return
        preset = choice.coerceIn(Soundscapes.all.indices)
        model.setPreset(preset)
        engine.setPreset(preset)
        getSharedPreferences("soundtrack", MODE_PRIVATE).edit().putInt("preset", preset).apply()
        updateSession()
        broadcastStatus()
    }

    private fun play() {
        if (playing) return
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return
        playing = true
        try {
            startForeground(1, playbackNotification())
        } catch (ex: RuntimeException) {
            Log.w("DriveSoundtrack", "Playback cannot start in the current vehicle state", ex)
            playing = false
            audioManager.abandonAudioFocusRequest(focusRequest)
            updateSession()
            broadcastStatus()
            return
        }
        engine.start { ex ->
            Log.w("DriveSoundtrack", "Audio output failed", ex)
            pause()
        }
        updateSession()
        broadcastStatus()
        handler.postDelayed(statusTicker, 800)
    }

    private fun pause() {
        if (!playing) return
        playing = false
        handler.removeCallbacks(statusTicker)
        engine.stop()
        audioManager.abandonAudioFocusRequest(focusRequest)
        stopForeground(STOP_FOREGROUND_REMOVE)
        updateSession()
        broadcastStatus()
    }

    private fun startScenario() {
        if (!pleosEmulator || restricted || vehicleKmh >= 5.0) return
        if (playing) pause()
        debugJourneyEnabled = false
        scenarioEnabled = true
        model.setSpeed(0.0)
        engine.setDebugSpeedJourney(true)
        play()
        if (!playing) stopScenario()
    }

    private fun stopScenario() {
        if (!scenarioEnabled) return
        pause()
        scenarioEnabled = false
        engine.setDebugSpeedJourney(false)
        model.setSpeed(if (carAvailable) vehicleKmh else 0.0)
        broadcastStatus()
    }

    /** The car started moving during the parked demo: keep the music and follow the real speed. */
    private fun handOffScenarioToVehicle() {
        if (!scenarioEnabled) return
        scenarioEnabled = false
        engine.setDebugSpeedJourney(false)
        model.setSpeed(vehicleKmh)
        broadcastStatus()
    }

    private fun updateSession() {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, NAMES[preset])
            .putString(MediaMetadata.METADATA_KEY_ARTIST, Soundscapes.all[preset].piece?.composer ?: "Drive Soundtrack")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, Albums.names[album]).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1.0f).build())
    }

    private fun broadcastStatus() {
        val status = Intent(STATUS).setPackage(packageName)
        status.putExtra("playing", playing)
        status.putExtra("preset", preset)
        status.putExtra("carAvailable", carAvailable && !debugJourneyEnabled)
        status.putExtra("scenarioEnabled", scenarioEnabled)
        status.putExtra("scenarioAvailable", pleosEmulator && !restricted && vehicleKmh < 5.0)
        status.putExtra("kmh", model.targetSpeed())
        status.putExtra("bpm", if (playing) model.bpm() else model.targetBpm())
        status.putExtra("energy", model.intensity())
        status.putExtra("momentum", model.momentum())
        status.putExtra("driveMode", model.driveMode())
        val displayedLayers = if (playing) model.audibleLayerMask() else 1
        status.putExtra("layers", Integer.bitCount(displayedLayers))
        status.putExtra("layerMask", displayedLayers)
        status.putExtra("bassSolo", bassSolo)
        status.putExtra("accelerator", model.accelerator())
        status.putExtra("pedalAvailable", model.pedalAvailable())
        status.putExtra("brake", model.brake())
        status.putExtra("brakeAvailable", model.brakeAvailable())
        status.putExtra("steering", model.steering())
        status.putExtra("steeringAvailable", model.steeringAvailable())
        status.putExtra("turn", model.turn())
        status.putExtra("turnAvailable", model.turnAvailable())
        status.putExtra("wiper", model.wiper())
        status.putExtra("wiperAvailable", model.wiperAvailable())
        status.putExtra("album", album)
        status.putExtra("myAlbum", myAlbum)
        status.putExtra("instruments", effective(preset) ?: IntArray(0))
        status.putExtra("band", band ?: IntArray(0))
        status.putExtra("ownInstruments", orchestrations[preset] != null)
        sendBroadcast(status)
    }

    override fun onDestroy() {
        pause()
        handler.removeCallbacks(statusTicker)
        speedReader.stop()
        driveModeReader.stop()
        bandReader.stop()
        pedalReader.stop()
        restrictionsReader.stop()
        session.release()
        super.onDestroy()
    }
}
