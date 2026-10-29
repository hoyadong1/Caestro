package com.drivesoundtrack.app;

import java.util.Random;

/** Platform-independent PCM generator shared by the Android app and desktop preview. */
public final class SoundRenderer {
    public static final int RATE = 22050;
    private static final double[] NOTES = {1, 1.1892, 1.3348, 1.4983};
    private final SoundModel model;
    private final Random random = new Random(1049);
    private volatile int preset;
    private double beat, kickAge = 1, snareAge = 1, hatAge = 1;
    private long lastBeat = -1, lastHatStep = -1;
    private double bassPhase, padPhase, lowPass;

    public SoundRenderer(SoundModel model) { this.model = model; }
    public void setPreset(int value) { preset = Math.max(0, Math.min(2, value)); }

    public void render(short[] pcm) {
        model.tick();
        for (int i = 0; i < pcm.length; i++) {
            int style = preset;
            int driveMode = model.driveMode();
            double intensity = model.intensity();
            double bpm = model.bpm();
            beat += bpm / (60.0 * RATE);
            long beatIndex = (long) Math.floor(beat);
            if (beatIndex != lastBeat) {
                int barBeat = (int) (beatIndex % 4);
                if (style == 0 || (style == 1 && barBeat == 0) || (style == 2 && (barBeat == 0 || barBeat == 2))) kickAge = 0;
                if ((style == 0 && (barBeat == 1 || barBeat == 3)) || (style != 0 && barBeat == 2)) snareAge = 0;
                lastBeat = beatIndex;
            }
            int subdivision = driveMode == 1 || style == 1 ? 1 : driveMode == 2 || intensity > 0.7 ? 4 : 2;
            long hatStep = (long) Math.floor(beat * subdivision);
            if (hatStep != lastHatStep) { hatAge = 0; lastHatStep = hatStep; }
            double base = style == 1 ? 55 : style == 2 ? 73.42 : 65.41;
            double frequency = base * NOTES[(int) (beatIndex / 4 % NOTES.length)];
            bassPhase += 2 * Math.PI * frequency / RATE;
            padPhase += 2 * Math.PI * (frequency * 2.005) / RATE;
            double bassWave = style == 2 ? Math.sin(bassPhase) : Math.tanh(Math.sin(bassPhase) * 2.2);
            double bass = bassWave * (0.11 + intensity * 0.03);
            double pad = Math.sin(padPhase) * (style == 1 ? 0.1 : style == 2 ? 0.08 : 0.04) * (driveMode == 1 ? 1.2 : 1.0);
            double kick = Math.sin(2 * Math.PI * (55 + 70 * Math.exp(-kickAge * 35)) * kickAge) * Math.exp(-kickAge * 18) * 0.28;
            double snare = (random.nextDouble() * 2 - 1) * Math.exp(-snareAge * 28) * (style == 1 ? 0.06 : 0.12);
            double hat = (random.nextDouble() * 2 - 1) * Math.exp(-hatAge * 90) * (0.025 + intensity * 0.025);
            double cutoff = 0.015 + intensity * 0.055 + (driveMode == 2 ? 0.02 : 0);
            lowPass += (bass + pad - lowPass) * cutoff;
            double sample = Math.max(-0.8, Math.min(0.8, lowPass * 0.8 + kick + snare + hat));
            pcm[i] = (short) (sample * 32767);
            double dt = 1.0 / RATE;
            kickAge += dt; snareAge += dt; hatAge += dt;
        }
    }
}
