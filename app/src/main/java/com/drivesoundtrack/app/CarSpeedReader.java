package com.drivesoundtrack.app;

import android.car.Car;
import android.car.VehiclePropertyIds;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.CarPropertyManager;
import android.content.Context;
import android.util.Log;

/** Reads cluster speed from AAOS; the caller falls back to demo speed when unavailable. */
public final class CarSpeedReader {
    public interface Listener { void onSpeed(double kmh); }
    private Car car;
    private CarPropertyManager manager;
    private CarPropertyManager.CarPropertyEventCallback callback;

    public boolean start(Context context, Listener listener) {
        try {
            car = Car.createCar(context);
            if (car == null) return false;
            manager = (CarPropertyManager) car.getCarManager(Car.PROPERTY_SERVICE);
            if (manager == null) return false;
            callback = new CarPropertyManager.CarPropertyEventCallback() {
                @Override public void onChangeEvent(CarPropertyValue value) {
                    if (value.getPropertyId() == VehiclePropertyIds.PERF_VEHICLE_SPEED && value.getValue() instanceof Number) {
                        listener.onSpeed(((Number) value.getValue()).doubleValue() * 3.6);
                    }
                }
                @Override public void onErrorEvent(int propertyId, int areaId) { }
            };
            if (!manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                Log.w("DriveSoundtrack", "Vehicle speed subscription was rejected");
                stop();
                return false;
            }
            try {
                CarPropertyValue<Float> initial = manager.getProperty(Float.class, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0);
                if (initial != null && initial.getValue() != null) listener.onSpeed(initial.getValue() * 3.6);
            } catch (RuntimeException ex) {
                Log.w("DriveSoundtrack", "Initial vehicle speed unavailable; waiting for subscription", ex);
            }
            return true;
        } catch (Exception | LinkageError ex) {
            Log.w("DriveSoundtrack", "Vehicle speed subscription unavailable", ex);
            stop();
            return false;
        }
    }

    public void stop() {
        if (manager != null && callback != null) {
            try { manager.unregisterCallback(callback); } catch (Exception ignored) { }
        }
        if (car != null) {
            try { car.disconnect(); } catch (Exception ignored) { }
        }
        callback = null; manager = null; car = null;
    }
}
