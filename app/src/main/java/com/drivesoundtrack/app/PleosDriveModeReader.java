package com.drivesoundtrack.app;

import android.content.Context;
import ai.pleos.playground.vehicle.Vehicle;
import ai.pleos.playground.vehicle.api.DrivingMode;
import ai.pleos.playground.vehicle.api.model.capability.AccessType;
import ai.pleos.playground.vehicle.constant.control.DrivingModeType;
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener;
import kotlin.Unit;

/** Pleos Vehicle SDK read-only integration; unavailable signals leave the neutral groove. */
public final class PleosDriveModeReader {
    private Vehicle vehicle;
    private DrivingMode drivingMode;
    private DrivingModeStateListener listener;
    private volatile boolean closed;

    public void start(Context context, SoundModel model) {
        try {
            vehicle = new Vehicle(context);
            vehicle.initialize();
            drivingMode = vehicle.getDrivingMode();
            drivingMode.checkDrivingModeStateCapabilities(config -> {
                if (closed) return Unit.INSTANCE;
                if (config == null || (config.getAccessType() != AccessType.READ &&
                    config.getAccessType() != AccessType.READ_WRITE)) {
                    model.setDriveMode(0);
                    return Unit.INSTANCE;
                }
                listener = new DrivingModeStateListener() {
                    @Override public void onDrivingModeStateUpdated(DrivingModeType mode) { model.setDriveMode(asInt(mode)); }
                    @Override public void onFailed(Exception error) { model.setDriveMode(0); }
                };
                drivingMode.registerDrivingModeState(listener);
                drivingMode.getCurrentDrivingModeState(mode -> { model.setDriveMode(asInt(mode)); return Unit.INSTANCE; },
                    error -> { model.setDriveMode(0); return Unit.INSTANCE; });
                return Unit.INSTANCE;
            }, error -> { model.setDriveMode(0); return Unit.INSTANCE; });
        } catch (Exception | LinkageError ex) {
            stop();
        }
    }

    private static int asInt(DrivingModeType mode) {
        if (mode == DrivingModeType.ECO) return 1;
        if (mode == DrivingModeType.SPORT) return 2;
        return 0;
    }

    public void stop() {
        closed = true;
        if (drivingMode != null && listener != null) {
            try { drivingMode.unregisterDrivingModeState(listener); } catch (Exception ignored) { }
        }
        if (vehicle != null) {
            try { vehicle.release(); } catch (Exception ignored) { }
        }
        vehicle = null; drivingMode = null; listener = null;
    }
}
