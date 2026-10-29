package com.drivesoundtrack.app;

import android.car.Car;
import android.car.drivingstate.CarUxRestrictions;
import android.car.drivingstate.CarUxRestrictionsManager;
import android.content.Context;

/** Uses AAOS UX restrictions to lock setup controls while driving. */
public final class DriveRestrictionsReader {
    public interface Listener { void onRestricted(boolean restricted); }
    private Car car;
    private CarUxRestrictionsManager manager;

    public void start(Context context, Listener listener) {
        try {
            car = Car.createCar(context);
            if (car == null) return;
            manager = (CarUxRestrictionsManager) car.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE);
            if (manager == null) return;
            manager.registerListener(value -> listener.onRestricted(value.isRequiresDistractionOptimization()));
            CarUxRestrictions current = manager.getCurrentCarUxRestrictions();
            if (current != null) listener.onRestricted(current.isRequiresDistractionOptimization());
        } catch (Exception | LinkageError ex) { stop(); }
    }

    public void stop() {
        if (manager != null) {
            try { manager.unregisterListener(); } catch (Exception ignored) { }
        }
        if (car != null) {
            try { car.disconnect(); } catch (Exception ignored) { }
        }
        manager = null; car = null;
    }
}
