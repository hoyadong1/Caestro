package com.drivesoundtrack.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/** Optional accelerator percentage; absence is reported, never fabricated as pedal input. */
class CarPedalReader {
    companion object { const val PERMISSION = "android.car.permission.READ_CAR_PEDALS" }
    private var car: Car? = null
    private var manager: CarPropertyManager? = null
    private var callback: CarPropertyManager.CarPropertyEventCallback? = null
    private var generation = 0

    fun start(context: Context, onPedal: (Double?) -> Unit) {
        stop()
        if (context.checkSelfPermission(PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            onPedal(null)
            return
        }
        val current = generation
        try {
            car = Car.createCar(context, null, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT,
                Car.CarServiceLifecycleListener { connected, ready ->
                    if (current != generation) return@CarServiceLifecycleListener
                    clear()
                    if (!ready) { onPedal(null); return@CarServiceLifecycleListener }
                    try {
                        val props = connected.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
                        val listener = object : CarPropertyManager.CarPropertyEventCallback {
                            override fun onChangeEvent(value: CarPropertyValue<*>) {
                                if (current == generation && value.propertyId == VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE) {
                                    onPedal((value.value as? Number)?.toDouble())
                                }
                            }
                            override fun onErrorEvent(propertyId: Int, areaId: Int) {
                                if (current == generation && propertyId == VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE) onPedal(null)
                            }
                        }
                        if (!props.registerCallback(listener, VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE,
                                CarPropertyManager.SENSOR_RATE_UI)) { onPedal(null); return@CarServiceLifecycleListener }
                        manager = props; callback = listener
                        try {
                            onPedal(props.getFloatProperty(VehiclePropertyIds.ACCELERATOR_PEDAL_COMPRESSION_PERCENTAGE, 0).toDouble())
                        } catch (ex: RuntimeException) {
                            Log.w("Caestro", "Initial accelerator value unavailable", ex)
                        }
                    } catch (ex: Exception) { Log.w("Caestro", "Accelerator property unavailable", ex); onPedal(null) }
                      catch (ex: LinkageError) { Log.w("Caestro", "Accelerator property unavailable", ex); onPedal(null) }
                })
            if (car == null) onPedal(null)
        } catch (ex: Exception) { Log.w("Caestro", "Car pedal service unavailable", ex); onPedal(null) }
          catch (ex: LinkageError) { Log.w("Caestro", "Car pedal service unavailable", ex); onPedal(null) }
    }

    private fun clear() {
        callback?.let { try { manager?.unregisterCallback(it) } catch (_: Exception) { } }
        callback = null; manager = null
    }
    fun stop() {
        generation++
        clear()
        car?.let { try { it.disconnect() } catch (_: Exception) { } }
        car = null
    }
}
