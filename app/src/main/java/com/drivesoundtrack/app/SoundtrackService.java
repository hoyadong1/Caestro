package com.drivesoundtrack.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.AudioAttributes;
import android.media.MediaMetadata;
import android.media.browse.MediaBrowser;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.service.media.MediaBrowserService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;

public final class SoundtrackService extends MediaBrowserService {
    public static final String PLAY = "com.drivesoundtrack.PLAY";
    public static final String PAUSE = "com.drivesoundtrack.PAUSE";
    public static final String PRESET = "com.drivesoundtrack.PRESET";
    public static final String DEMO_SPEED = "com.drivesoundtrack.DEMO_SPEED";
    public static final String STATUS = "com.drivesoundtrack.STATUS";
    public static final String STATUS_REQUEST = "com.drivesoundtrack.STATUS_REQUEST";
    public static final String RECONNECT_SPEED = "com.drivesoundtrack.RECONNECT_SPEED";
    private static final String CHANNEL = "drive_soundtrack_playback";
    private static final String[] NAMES = {"Pulse", "Night", "Coast"};

    private SoundModel model;
    private SynthEngine engine;
    private CarSpeedReader speedReader;
    private PleosDriveModeReader driveModeReader;
    private DriveRestrictionsReader restrictionsReader;
    private MediaSession session;
    private AudioManager audioManager;
    private AudioFocusRequest focusRequest;
    private boolean playing;
    private boolean carAvailable;
    private volatile boolean restricted;
    private int preset;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            if (playing) { broadcastStatus(); handler.postDelayed(this, 800); }
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        model = new SoundModel();
        engine = new SynthEngine(model);
        speedReader = new CarSpeedReader();
        carAvailable = speedReader.start(this, kmh -> { model.setSpeed(kmh); broadcastStatus(); });
        driveModeReader = new PleosDriveModeReader();
        driveModeReader.start(this, model);
        restrictionsReader = new DriveRestrictionsReader();
        restrictionsReader.start(this, value -> restricted = value);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setOnAudioFocusChangeListener(change -> { if (change <= 0) pause(); }).build();
        session = new MediaSession(this, "DriveSoundtrack");
        session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { pause(); }
            @Override public void onPlayFromMediaId(String mediaId, Bundle extras) {
                try { setPreset(Integer.parseInt(mediaId)); play(); } catch (NumberFormatException ignored) { }
            }
            @Override public void onSkipToNext() { setPreset((preset + 1) % 3); }
            @Override public void onSkipToPrevious() { setPreset((preset + 2) % 3); }
        });
        session.setActive(true);
        setSessionToken(session.getSessionToken());
        updateSession();
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "Drive Soundtrack", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot("presets", null);
    }

    @Override public void onLoadChildren(String parentId, Result<List<MediaBrowser.MediaItem>> result) {
        List<MediaBrowser.MediaItem> items = new ArrayList<>();
        if ("presets".equals(parentId)) {
            for (int i = 0; i < NAMES.length; i++) {
                MediaMetadata metadata = new MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, Integer.toString(i))
                    .putString(MediaMetadata.METADATA_KEY_TITLE, NAMES[i])
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "Drive Soundtrack").build();
                items.add(new MediaBrowser.MediaItem(metadata.getDescription(), MediaBrowser.MediaItem.FLAG_PLAYABLE));
            }
        }
        result.sendResult(items);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (PLAY.equals(action)) play();
            else if (PAUSE.equals(action)) pause();
            else if (PRESET.equals(action)) setPreset(intent.getIntExtra("preset", 0));
            else if (STATUS_REQUEST.equals(action)) broadcastStatus();
            else if (RECONNECT_SPEED.equals(action)) {
                speedReader.stop();
                carAvailable = speedReader.start(this, kmh -> { model.setSpeed(kmh); broadcastStatus(); });
                broadcastStatus();
            }
            else if (DEMO_SPEED.equals(action) && !carAvailable) {
                model.setSpeed(intent.getDoubleExtra("kmh", 0));
                broadcastStatus();
            }
        }
        return Service.START_STICKY;
    }

    private void setPreset(int choice) {
        if (restricted || (carAvailable && model.targetSpeed() >= 5)) return;
        preset = Math.max(0, Math.min(2, choice));
        engine.setPreset(preset);
        updateSession(); broadcastStatus();
    }

    private void play() {
        if (playing) return;
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return;
        playing = true;
        try {
            startForeground(1, new Notification.Builder(this, CHANNEL)
                .setContentTitle("Drive Soundtrack")
                .setContentText(NAMES[preset] + " 재생 중")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setVisibility(Notification.VISIBILITY_PUBLIC).build());
        } catch (RuntimeException ex) {
            Log.w("DriveSoundtrack", "Playback cannot start in the current vehicle state", ex);
            playing = false;
            audioManager.abandonAudioFocusRequest(focusRequest);
            updateSession(); broadcastStatus();
            return;
        }
        engine.start(); updateSession(); broadcastStatus();
        handler.postDelayed(statusTicker, 800);
    }

    private void pause() {
        if (!playing) return;
        playing = false;
        handler.removeCallbacks(statusTicker);
        engine.stop();
        audioManager.abandonAudioFocusRequest(focusRequest);
        stopForeground(STOP_FOREGROUND_REMOVE);
        updateSession(); broadcastStatus();
    }

    private void updateSession() {
        session.setMetadata(new MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, NAMES[preset])
            .putString(MediaMetadata.METADATA_KEY_ARTIST, "Drive Soundtrack").build());
        session.setPlaybackState(new PlaybackState.Builder()
            .setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_STOP |
                PlaybackState.ACTION_PLAY_FROM_MEDIA_ID | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, 0, 1.0f).build());
    }

    private void broadcastStatus() {
        Intent status = new Intent(STATUS).setPackage(getPackageName());
        status.putExtra("playing", playing);
        status.putExtra("preset", preset);
        status.putExtra("carAvailable", carAvailable);
        status.putExtra("kmh", model.targetSpeed());
        status.putExtra("bpm", model.bpm());
        sendBroadcast(status);
    }

    @Override public void onDestroy() {
        pause(); handler.removeCallbacks(statusTicker); speedReader.stop(); driveModeReader.stop(); restrictionsReader.stop(); session.release(); super.onDestroy();
    }
}
