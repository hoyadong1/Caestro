package com.drivesoundtrack.app

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
        const val DEBUG_PRESET = "com.drivesoundtrack.DEBUG_PRESET"
        private const val CHANNEL = "drive_soundtrack_playback"
        private val NAMES = Soundscapes.all.map { it.name }
    }

    private lateinit var model: SoundModel
    private lateinit var engine: SynthEngine
    private lateinit var speedReader: CarSpeedReader
    private lateinit var gearReader: CarGearReader
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
    @Volatile private var selectedGear: Int? = null
    @Volatile private var currentGear: Int? = null
    private var bassSolo = false
    private val handler = Handler(Looper.getMainLooper())
    private val statusTicker = object : Runnable {
        override fun run() {
            if (playing) { bandReader.pollWiperSwitch(model); broadcastStatus(); handler.postDelayed(this, 800) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        model = SoundModel()
        engine = SynthEngine(model)
        preset = getSharedPreferences("soundtrack", MODE_PRIVATE).getInt("preset", 0)
            .coerceIn(Soundscapes.all.indices)
        model.setPreset(preset)
        engine.setPreset(preset)
        speedReader = CarSpeedReader()
        connectSpeedReader()
        gearReader = CarGearReader().also { reader ->
            reader.start(this) { selected, current ->
                selectedGear = selected
                currentGear = current
                Log.i("DriveSoundtrack", "Gear selection=$selected current=$current")
                broadcastStatus()
            }
        }
        driveModeReader = PleosDriveModeReader().also { it.start(this, model) }
        bandReader = PleosBandReader().also { it.start(this, model) { broadcastStatus() } }
        pedalReader = CarPedalReader().also { it.start(this) { value -> model.setAccelerator(value); broadcastStatus() } }
        restrictionsReader = DriveRestrictionsReader().also { it.start(this) { value -> restricted = value } }
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener { change -> if (change <= 0) pause() }.build()
        session = MediaSession(this, "DriveSoundtrack")
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        session.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = pause()
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                mediaId?.toIntOrNull()?.let { setPreset(it); play() }
            }
            override fun onSkipToNext() = setPreset((preset + 1) % NAMES.size)
            override fun onSkipToPrevious() = setPreset((preset + NAMES.size - 1) % NAMES.size)
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
                engine.setDebugSpeedJourney(intent.getBooleanExtra("enabled", false))
            }
            DEBUG_PRESET -> if ((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 && !playing) {
                setPreset(intent.getIntExtra("preset", 0), debugOverride = true)
            }
            PRESET -> setPreset(intent.getIntExtra("preset", 0))
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
        return Service.START_STICKY
    }

    private fun connectSpeedReader() {
        carAvailable = false
        speedReader.start(this,
            { available ->
                carAvailable = available
                if (!available) model.setSpeed(0.0)
                broadcastStatus()
            },
            { kmh -> model.setSpeed(kmh); broadcastStatus() })
        broadcastStatus()
    }

    private fun setPreset(choice: Int, debugOverride: Boolean = false) {
        if (!debugOverride && (restricted || (carAvailable && model.targetSpeed() >= 5))) return
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
            startForeground(1, Notification.Builder(this, CHANNEL)
                .setContentTitle("Drive Soundtrack")
                .setContentText("${NAMES[preset]} 재생 중")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setVisibility(Notification.VISIBILITY_PUBLIC).build())
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

    private fun updateSession() {
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, NAMES[preset])
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Drive Soundtrack")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, Soundscapes.all[preset].character).build())
        session.setPlaybackState(PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 0, 1.0f).build())
    }

    private fun broadcastStatus() {
        val status = Intent(STATUS).setPackage(packageName)
        status.putExtra("playing", playing)
        status.putExtra("preset", preset)
        status.putExtra("carAvailable", carAvailable)
        status.putExtra("kmh", model.targetSpeed())
        status.putExtra("bpm", if (playing) model.bpm() else model.targetBpm())
        status.putExtra("energy", model.intensity())
        status.putExtra("momentum", model.momentum())
        status.putExtra("driveMode", model.driveMode())
        status.putExtra("layers", model.layerCount())
        status.putExtra("selectedGear", selectedGear ?: 0)
        status.putExtra("currentGear", currentGear ?: 0)
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
        sendBroadcast(status)
    }

    override fun onDestroy() {
        pause()
        handler.removeCallbacks(statusTicker)
        speedReader.stop()
        gearReader.stop()
        driveModeReader.stop()
        bandReader.stop()
        pedalReader.stop()
        restrictionsReader.stop()
        session.release()
        super.onDestroy()
    }
}
