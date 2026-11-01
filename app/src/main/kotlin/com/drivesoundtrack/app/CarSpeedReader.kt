package com.drivesoundtrack.app

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/** Reads cluster speed from AAOS; the caller falls back to demo speed when unavailable. */
class CarSpeedReader {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null

    fun start(context: Context, listener: (Double) -> Unit): Boolean {
        try {
            val connected = Car.createCar(context) ?: return false
            car = connected
            val properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager ?: return false
            manager = properties
            val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
                override fun onChangeEvent(value: CarPropertyValue<*>) {
                    if (value.propertyId == VehiclePropertyIds.PERF_VEHICLE_SPEED) {
                        (value.value as? Number)?.let { listener(it.toDouble() * 3.6) }
                    }
                }
                override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
            }
            callback = speedCallback
            if (!properties.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                Log.w("DriveSoundtrack", "Vehicle speed subscription was rejected")
                stop()
                return false
            }
            try {
                properties.getProperty(Float::class.java, VehiclePropertyIds.PERF_VEHICLE_SPEED, 0)?.value
                    ?.let { listener(it.toDouble() * 3.6) }
            } catch (ex: RuntimeException) {
                Log.w("DriveSoundtrack", "Initial vehicle speed unavailable; waiting for subscription", ex)
            }
            return true
        } catch (ex: Exception) {
            Log.w("DriveSoundtrack", "Vehicle speed subscription unavailable", ex)
        } catch (ex: LinkageError) {
            Log.w("DriveSoundtrack", "Vehicle speed subscription unavailable", ex)
        }
        stop()
        return false
    }

    fun stop() {
        val registeredCallback = callback
        if (registeredCallback != null) manager?.let {
            try { it.unregisterCallback(registeredCallback) } catch (_: Exception) { }
        }
        car?.let { try { it.disconnect() } catch (_: Exception) { } }
        callback = null
        manager = null
        car = null
    }
}
