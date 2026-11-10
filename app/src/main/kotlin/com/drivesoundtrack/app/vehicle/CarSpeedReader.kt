package com.drivesoundtrack.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/** Reads cluster speed and reconnects when the AAOS car service restarts. */
class CarSpeedReader {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private var generation = 0

    fun start(context: Context, onConnection: (Boolean) -> Unit, onSpeed: (Double) -> Unit) {
        stop()
        val currentGeneration = generation
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                Car.CarServiceLifecycleListener { connected, ready ->
                    if (currentGeneration != generation) return@CarServiceLifecycleListener
                    clearSubscription()
                    if (!ready) {
                        onConnection(false)
                        return@CarServiceLifecycleListener
                    }
                    try {
                        val properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                        if (properties == null) {
                            onConnection(false)
                            return@CarServiceLifecycleListener
                        }
                        val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
                            override fun onChangeEvent(value: CarPropertyValue<*>) {
                                if (currentGeneration == generation && value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                                    (value.value as? Number)?.let { onSpeed(it.toDouble() * 3.6) }
                                }
                            }
                            override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
                        }
                        if (!properties.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED,
                                CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                            Log.w("Caestro", "Vehicle speed subscription was rejected")
                            onConnection(false)
                            return@CarServiceLifecycleListener
                        }
                        manager = properties
                        callback = speedCallback
                        onConnection(true)
                        try {
                            onSpeed(properties.getFloatProperty(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0).toDouble() * 3.6)
                        } catch (ex: RuntimeException) {
                            Log.w("Caestro", "Initial vehicle speed unavailable; waiting for subscription", ex)
                        }
                    } catch (ex: Exception) {
                        Log.w("Caestro", "Vehicle speed subscription unavailable", ex)
                        clearSubscription()
                        onConnection(false)
                    } catch (ex: LinkageError) {
                        Log.w("Caestro", "Vehicle speed subscription unavailable", ex)
                        clearSubscription()
                        onConnection(false)
                    }
                })
            if (car == null) onConnection(false)
        } catch (ex: Exception) {
            Log.w("Caestro", "Car service connection unavailable", ex)
            onConnection(false)
        } catch (ex: LinkageError) {
            Log.w("Caestro", "Car service connection unavailable", ex)
            onConnection(false)
        }
    }

    private fun clearSubscription() {
        val registeredCallback = callback
        if (registeredCallback != null) manager?.let {
            try { it.unregisterCallback(registeredCallback) } catch (_: Exception) { }
        }
        callback = null
        manager = null
    }

    fun stop() {
        generation++
        clearSubscription()
        car?.let { try { it.disconnect() } catch (_: Exception) { } }
        car = null
    }
}
