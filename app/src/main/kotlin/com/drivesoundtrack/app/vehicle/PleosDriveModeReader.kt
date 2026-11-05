package com.drivesoundtrack.app.vehicle

import com.drivesoundtrack.app.audio.SoundModel
import android.content.Context
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.DrivingMode
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.constant.control.DrivingModeType
import ai.pleos.playground.vehicle.listener.DrivingModeStateListener

/** Pleos Vehicle SDK read-only integration; unavailable signals leave the neutral groove. */
class PleosDriveModeReader {
    private var vehicle: Vehicle? = null
    private var drivingMode: DrivingMode? = null
    private var listener: DrivingModeStateListener? = null
    @Volatile private var closed = false

    fun start(context: Context, model: SoundModel) {
        try {
            val newVehicle = Vehicle(context)
            vehicle = newVehicle
            newVehicle.initialize()
            val modeApi = newVehicle.getDrivingMode()
            drivingMode = modeApi
            modeApi.checkDrivingModeStateCapabilities({ config ->
                if (!closed) {
                    if (config.accessType != AccessType.READ && config.accessType != AccessType.READ_WRITE) {
                        model.setDriveMode(0)
                    } else {
                        val modeListener = object : DrivingModeStateListener {
                            override fun onDrivingModeStateUpdated(state: DrivingModeType?) { model.setDriveMode(asInt(state)) }
                            override fun onFailed(e: Exception) { model.setDriveMode(0) }
                        }
                        listener = modeListener
                        modeApi.registerDrivingModeState(modeListener)
                        modeApi.getCurrentDrivingModeState(
                            { mode -> model.setDriveMode(asInt(mode)) },
                            { model.setDriveMode(0) }
                        )
                    }
                }
            }, { model.setDriveMode(0) })
        } catch (_: Exception) { stop() } catch (_: LinkageError) { stop() }
    }

    private fun asInt(mode: DrivingModeType?): Int = when (mode) {
        DrivingModeType.ECO -> 1
        DrivingModeType.SPORT -> 2
        else -> 0
    }

    fun stop() {
        closed = true
        val modeListener = listener
        if (modeListener != null) drivingMode?.let {
            try { it.unregisterDrivingModeState(modeListener) } catch (_: Exception) { }
        }
        vehicle?.let { try { it.release() } catch (_: Exception) { } }
        vehicle = null
        drivingMode = null
        listener = null
    }
}
