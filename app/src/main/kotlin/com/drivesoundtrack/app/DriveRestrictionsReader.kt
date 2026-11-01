package com.drivesoundtrack.app

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context

/** Uses AAOS UX restrictions to lock setup controls while driving. */
class DriveRestrictionsReader {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null

    fun start(context: Context, listener: (Boolean) -> Unit) {
        try {
            val connected = Car.createCar(context) ?: return
            car = connected
            val restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            manager = restrictions
            restrictions.registerListener { listener(it.isRequiresDistractionOptimization) }
            restrictions.currentCarUxRestrictions?.let { listener(it.isRequiresDistractionOptimization) }
        } catch (_: Exception) { stop() } catch (_: LinkageError) { stop() }
    }

    fun stop() {
        manager?.let { try { it.unregisterListener() } catch (_: Exception) { } }
        car?.let { try { it.disconnect() } catch (_: Exception) { } }
        manager = null
        car = null
    }
}
