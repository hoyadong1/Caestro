package com.drivesoundtrack.app;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** Procedural, original PCM soundtrack. No commercial recordings or network calls. */
public final class SynthEngine {
    private final SoundRenderer renderer;
    private volatile boolean running;
    private Thread worker;
    private AudioTrack track;

    public SynthEngine(SoundModel model) { renderer = new SoundRenderer(model); }
    public void setPreset(int preset) { renderer.setPreset(preset); }

    public synchronized void start() {
        if (running) return;
        int min = AudioTrack.getMinBufferSize(SoundRenderer.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferBytes = Math.max(min, 8192);
        track = new AudioTrack.Builder()
            .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new AudioFormat.Builder().setSampleRate(SoundRenderer.RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufferBytes).setTransferMode(AudioTrack.MODE_STREAM).build();
        running = true;
        track.play();
        worker = new Thread(this::render, "soundtrack-synth");
        worker.start();
    }

    public synchronized void stop() {
        running = false;
        if (worker != null) { worker.interrupt(); worker = null; }
        if (track != null) {
            try { track.pause(); track.flush(); } catch (IllegalStateException ignored) { }
            track.release(); track = null;
        }
    }

    private void render() {
        short[] pcm = new short[1024];
        while (running) {
            renderer.render(pcm);
            AudioTrack current = track;
            if (current == null || !running) break;
            int offset = 0;
            while (offset < pcm.length && running) {
                int n;
                try { n = current.write(pcm, offset, pcm.length - offset); }
                catch (IllegalStateException ex) { return; }
                if (n <= 0) break;
                offset += n;
            }
        }
    }
}
