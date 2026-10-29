package com.drivesoundtrack.app;

/** Pure mapping from vehicle context to bounded musical parameters. */
public final class SoundModel {
    private double targetKmh;
    private double smoothedKmh;
    private double bpm = 92;
    private int driveMode;

    public synchronized void setSpeed(double kmh) {
        if (!Double.isFinite(kmh)) return;
        targetKmh = Math.max(0, Math.min(130, kmh));
    }

    /** Called once per audio buffer, so a steady speed still converges. */
    public synchronized void tick() {
        smoothedKmh += (targetKmh - smoothedKmh) * 0.04;
        double targetBpm = 92 + Math.min(43, smoothedKmh * 0.4) + (driveMode == 2 ? 5 : driveMode == 1 ? -4 : 0);
        bpm += (targetBpm - bpm) * 0.025;
    }

    public synchronized void setDriveMode(int mode) { driveMode = mode; }

    public synchronized double bpm() { return bpm; }
    public synchronized double intensity() { return Math.min(1, smoothedKmh / 90.0); }
    public synchronized double speed() { return smoothedKmh; }
    public synchronized double targetSpeed() { return targetKmh; }
    public synchronized int driveMode() { return driveMode; }
}
