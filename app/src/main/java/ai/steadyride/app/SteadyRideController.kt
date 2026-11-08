package ai.steadyride.app

import ai.steadyride.app.core.ComfortEngine
import ai.steadyride.app.core.ComfortState
import ai.steadyride.app.core.RiskLevel
import ai.steadyride.app.vehicle.ComfortActuator
import ai.steadyride.app.vehicle.DemoDriveSource
import ai.steadyride.app.vehicle.LiveVehicleSource
import ai.steadyride.app.vehicle.VehicleSource
import ai.steadyride.app.vehicle.VehicleState
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class SourceMode(val label: String) { LIVE("실차 신호"), DEMO("데모 주행") }

data class UiState(
    val mode: SourceMode = SourceMode.LIVE,
    val vehicle: VehicleState = VehicleState(),
    val comfort: ComfortState = ComfortState(),
    val comfortModeOn: Boolean = false,
    val manualComfort: Boolean? = null, // null = automatic
)

/** Wires a vehicle source -> comfort engine -> actuators, at 10 Hz. */
class SteadyRideController(context: Context) {

    private val live = LiveVehicleSource(context)
    private val demo = DemoDriveSource()
    private var source: VehicleSource = live

    private val engine = ComfortEngine()
    val actuator = ComfortActuator(context)

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var manual: Boolean? = null
    private var autoOn = false

    fun start() {
        source.start()
        loop?.cancel()
        loop = scope.launch {
            var last = System.nanoTime()
            while (isActive) {
                val now = System.nanoTime()
                val dt = ((now - last) / 1e9f).coerceIn(0.01f, 0.5f)
                last = now
                tick(dt)
                delay(100)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        source.stop()
    }

    fun release() {
        stop()
        live.stop()
        demo.stop()
        actuator.release()
    }

    fun setMode(mode: SourceMode) {
        if (mode == _ui.value.mode) return
        source.stop()
        source = if (mode == SourceMode.DEMO) demo else live
        engine.resetTrip()
        source.start()
        _ui.value = _ui.value.copy(mode = mode)
    }

    /** Voice (Gleo) or on-screen override. null returns to automatic. */
    fun setManualComfort(on: Boolean?) {
        manual = on
    }

    fun requestComfortableRoute(): Boolean = source.requestComfortableRoute()

    private fun tick(dt: Float) {
        val v = source.state.value
        val c = engine.update(v, dt)

        // Automatic mode with hysteresis: on at HIGH risk, off once it calms down.
        autoOn = v.hasPassenger && when {
            c.level == RiskLevel.HIGH -> true
            c.risk < 30f -> false
            else -> autoOn
        }
        val on = manual ?: autoOn

        actuator.onCue(if (v.hasPassenger) c.cue else null)
        actuator.setComfortMode(on, c.level)

        _ui.value = _ui.value.copy(vehicle = v, comfort = c, comfortModeOn = on, manualComfort = manual)
    }
}
