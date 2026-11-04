package com.drivesoundtrack.app

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log

/** Read-only selected gear and transmission gear; either property may be absent. */
class CarGearReader {
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private val registered = mutableListOf<Int>()
    private var generation = 0
    private var selected: Int? = null
    private var current: Int? = null

    fun start(context: Context, onGear: (Int?, Int?) -> Unit) {
        stop()
        val activeGeneration = generation
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                Car.CarServiceLifecycleListener { connected, ready ->
                    if (activeGeneration != generation) return@CarServiceLifecycleListener
                    clearSubscription()
                    if (!ready) {
                        selected = null; current = null; onGear(null, null)
                        return@CarServiceLifecycleListener
                    }
                    try {
                        val properties = connected.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
                            ?: return@CarServiceLifecycleListener
                        val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
                            override fun onChangeEvent(value: CarPropertyValue<*>) {
                                if (activeGeneration != generation) return
                                val gear = (value.value as? Number)?.toInt() ?: return
                                when (value.propertyId) {
                                    VehiclePropertyIds.GEAR_SELECTION -> selected = gear
                                    VehiclePropertyIds.CURRENT_GEAR -> current = gear
                                    else -> return
                                }
                                onGear(selected, current)
                            }
                            override fun onErrorEvent(propertyId: Int, areaId: Int) = Unit
                        }
                        manager = properties
                        callback = gearCallback
                        for (property in intArrayOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.CURRENT_GEAR)) {
                            try {
                                if (properties.registerCallback(gearCallback, property,
                                        CarPropertyManager.SENSOR_RATE_ONCHANGE)) {
                                    registered += property
                                    val initial = properties.getIntProperty(property, 0)
                                    if (property == VehiclePropertyIds.GEAR_SELECTION) selected = initial
                                    else current = initial
                                }
                            } catch (ex: Exception) {
                                Log.w("DriveSoundtrack", "Gear property $property unavailable", ex)
                            }
                        }
                        onGear(selected, current)
                    } catch (ex: Exception) {
                        Log.w("DriveSoundtrack", "Gear reader unavailable", ex)
                        clearSubscription()
                        onGear(null, null)
                    } catch (ex: LinkageError) {
                        Log.w("DriveSoundtrack", "Gear reader unavailable", ex)
                        clearSubscription()
                        onGear(null, null)
                    }
                })
        } catch (ex: Exception) {
            Log.w("DriveSoundtrack", "Gear service unavailable", ex)
            onGear(null, null)
        } catch (ex: LinkageError) {
            Log.w("DriveSoundtrack", "Gear service unavailable", ex)
            onGear(null, null)
        }
    }

    private fun clearSubscription() {
        val cb = callback
        if (cb != null) manager?.let { properties ->
            for (property in registered) {
                try { properties.unregisterCallback(cb, property) } catch (_: Exception) { }
            }
        }
        registered.clear()
        callback = null
        manager = null
    }

    fun stop() {
        generation++
        clearSubscription()
        car?.let { try { it.disconnect() } catch (_: Exception) { } }
        car = null
        selected = null
        current = null
    }
}
