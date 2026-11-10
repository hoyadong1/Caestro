package com.drivesoundtrack.app.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** Tracks AAOS UX restrictions across car service restarts. */
class DriveRestrictionsReader {
    private var car: Car? = null
    private var manager: CarUxRestrictionsManager? = null
    private var generation = 0

    fun start(context: Context, listener: (Boolean) -> Unit) {
        stop()
        val currentGeneration = generation
        val restrictedWhenUnavailable = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                Car.CarServiceLifecycleListener { connected, ready ->
                    if (currentGeneration != generation) return@CarServiceLifecycleListener
                    clearManager()
                    if (!ready) {
                        listener(restrictedWhenUnavailable)
                        return@CarServiceLifecycleListener
                    }
                    try {
                        val restrictions = connected.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
                        if (restrictions == null) {
                            listener(restrictedWhenUnavailable)
                            return@CarServiceLifecycleListener
                        }
                        restrictions.registerListener {
                            if (currentGeneration == generation) listener(it.isRequiresDistractionOptimization)
                        }
                        manager = restrictions
                        listener(restrictions.currentCarUxRestrictions?.isRequiresDistractionOptimization
                            ?: restrictedWhenUnavailable)
                    } catch (ex: Exception) {
                        Log.w("Caestro", "Driving restrictions unavailable", ex)
                        clearManager()
                        listener(restrictedWhenUnavailable)
                    } catch (ex: LinkageError) {
                        Log.w("Caestro", "Driving restrictions unavailable", ex)
                        clearManager()
                        listener(restrictedWhenUnavailable)
                    }
                })
            if (car == null) listener(restrictedWhenUnavailable)
        } catch (ex: Exception) {
            Log.w("Caestro", "Car service connection unavailable", ex)
            listener(restrictedWhenUnavailable)
        } catch (ex: LinkageError) {
            Log.w("Caestro", "Car service connection unavailable", ex)
            listener(restrictedWhenUnavailable)
        }
    }

    private fun clearManager() {
        manager?.let { try { it.unregisterListener() } catch (_: Exception) { } }
        manager = null
    }

    fun stop() {
        generation++
        clearManager()
        car?.let { try { it.disconnect() } catch (_: Exception) { } }
        car = null
    }
}
