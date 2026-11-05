package com.drivesoundtrack.app.vehicle

import com.drivesoundtrack.app.audio.SoundModel
import android.content.Context
import android.util.Log
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.Brake
import ai.pleos.playground.vehicle.api.SteeringWheel
import ai.pleos.playground.vehicle.api.TurnSignal
import ai.pleos.playground.vehicle.api.Wiper
import ai.pleos.playground.vehicle.api.model.area.WindowArea
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.area.WindowValue
import ai.pleos.playground.vehicle.constant.control.WiperState
import ai.pleos.playground.vehicle.constant.control.WiperSwitch
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.WiperStateListener

/** Read-only Pleos signals for the musical ensemble. Each channel fails independently. */
class PleosBandReader {
    private val frontGlass = WindowArea(WindowValue.FRONT_WINDSHIELD)
    private var vehicle: Vehicle? = null
    private var brakeApi: Brake? = null
    private var steeringApi: SteeringWheel? = null
    private var turnApi: TurnSignal? = null
    private var wiperApi: Wiper? = null
    private var brakeListener: BrakePedalStatusListener? = null
    private var steeringListener: SteeringWheelAngleListener? = null
    private var turnListener: TurnSignalStateListener? = null
    private var wiperListener: WiperStateListener? = null
    @Volatile private var closed = false

    fun start(context: Context, model: SoundModel, onUpdate: () -> Unit) {
        stop()
        closed = false
        try {
            val connected = Vehicle(context).also { it.initialize() }
            vehicle = connected
            readBrake(connected, model, onUpdate)
            readSteering(connected, model, onUpdate)
            readTurn(connected, model, onUpdate)
            readWiper(connected, model, onUpdate)
        } catch (ex: Exception) {
            Log.w("DriveSoundtrack", "Pleos band signals unavailable", ex)
            stop()
        } catch (ex: LinkageError) {
            Log.w("DriveSoundtrack", "Pleos band signals unavailable", ex)
            stop()
        }
    }

    private fun readBrake(v: Vehicle, model: SoundModel, update: () -> Unit) {
        try {
            val api = v.getBrake().also { brakeApi = it }
            val listener = object : BrakePedalStatusListener {
                override fun onBrakePedalStatusUpdated(value: Boolean?) { if (!closed) { model.setBrake(value); update() } }
                override fun onFailed(e: Exception) { if (!closed) { model.setBrake(null); update() } }
            }
            brakeListener = listener
            api.registerBrakePedalStatus(listener)
            api.getCurrentBrakePedalStatus({ if (!closed) { model.setBrake(it); update() } },
                { if (!closed) { model.setBrake(null); update() } })
        } catch (ex: Exception) { Log.w("DriveSoundtrack", "Brake signal unavailable", ex) }
    }

    private fun readSteering(v: Vehicle, model: SoundModel, update: () -> Unit) {
        try {
            val api = v.getSteeringWheel().also { steeringApi = it }
            val listener = object : SteeringWheelAngleListener {
                override fun onSteeringWheelAngleUpdated(value: Float?) { if (!closed) { model.setSteering(value?.toDouble()); update() } }
                override fun onFailed(e: Exception) { if (!closed) { model.setSteering(null); update() } }
            }
            steeringListener = listener
            api.registerSteeringWheelAngle(listener)
            api.getCurrentSteeringWheelAngle({ if (!closed) { model.setSteering(it?.toDouble()); update() } },
                { if (!closed) { model.setSteering(null); update() } })
        } catch (ex: Exception) { Log.w("DriveSoundtrack", "Steering signal unavailable", ex) }
    }

    private fun readTurn(v: Vehicle, model: SoundModel, update: () -> Unit) {
        try {
            val api = v.getTurnSignal().also { turnApi = it }
            fun apply(value: TurnSignalValue?) {
                if (!closed) {
                    model.setTurn(when (value) { TurnSignalValue.LEFT -> -1; TurnSignalValue.RIGHT -> 1; TurnSignalValue.OFF -> 0; else -> null })
                    update()
                }
            }
            val listener = object : TurnSignalStateListener {
                override fun onTurnSignalStateUpdated(value: TurnSignalValue?) = apply(value)
                override fun onFailed(e: Exception) = apply(null)
            }
            turnListener = listener
            api.registerTurnSignalState(listener)
            api.getCurrentTurnSignalState({ apply(it) }, { apply(null) })
        } catch (ex: Exception) { Log.w("DriveSoundtrack", "Turn signal unavailable", ex) }
    }

    private fun readWiper(v: Vehicle, model: SoundModel, update: () -> Unit) {
        try {
            val api = v.getWiper().also { wiperApi = it }
            fun apply(value: WiperState?) {
                if (!closed) { model.setWiper(when (value) { WiperState.ON -> true; WiperState.OFF -> false; else -> null }); update() }
            }
            val listener = object : WiperStateListener {
                override fun onWiperStateUpdated(value: WiperState?) = apply(value)
                override fun onFailed(e: Exception) = apply(null)
            }
            wiperListener = listener
            api.registerWiperState(frontGlass, listener)
            api.getCurrentWiperState(frontGlass, { apply(it) }, { apply(null) })
            pollWiperSwitch(model)
        } catch (ex: Exception) { Log.w("DriveSoundtrack", "Wiper signal unavailable", ex) }
    }

    /** The virtual console exposes a switch even where its actual-state callback is missing. */
    fun pollWiperSwitch(model: SoundModel) {
        if (closed) return
        try {
            wiperApi?.getCurrentWiperSwitch(frontGlass, { switch ->
                if (!closed) model.setWiper(when (switch) {
                    WiperSwitch.OFF -> false
                    WiperSwitch.AUTO, WiperSwitch.OTHER, WiperSwitch.SERVICE, null -> null
                    else -> true
                })
            }, { })
        } catch (_: Exception) { }
    }

    fun stop() {
        closed = true
        brakeListener?.let { try { brakeApi?.unregisterBrakePedalStatus(it) } catch (_: Exception) { } }
        steeringListener?.let { try { steeringApi?.unregisterSteeringWheelAngle(it) } catch (_: Exception) { } }
        turnListener?.let { try { turnApi?.unregisterTurnSignalState(it) } catch (_: Exception) { } }
        wiperListener?.let { try { wiperApi?.unregisterWiperState(frontGlass, it) } catch (_: Exception) { } }
        vehicle?.let { try { it.release() } catch (_: Exception) { } }
        vehicle = null
        brakeApi = null; steeringApi = null; turnApi = null; wiperApi = null
        brakeListener = null; steeringListener = null; turnListener = null; wiperListener = null
    }
}
