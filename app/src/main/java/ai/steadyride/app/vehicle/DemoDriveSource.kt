package ai.steadyride.app.vehicle

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
import kotlin.math.min

/**
 * Scripted demo drive for presentations and the submission video.
 * The emulator cannot simulate an active navigation route, so this source
 * plays a ~75 s loop with announced maneuvers:
 * right turn -> speed bump -> S-curve -> stop-and-go -> loop.
 */
class DemoDriveSource : VehicleSource {

    private data class Event(val atM: Double, val kind: ManeuverKind, val turn: Turn, val lengthM: Double, val steerDeg: Float, val speedMps: Float)

    private val route = listOf(
        Event(220.0, ManeuverKind.TURN, Turn.RIGHT, 18.0, 180f, 6f),
        Event(400.0, ManeuverKind.SPEED_BUMP, Turn.NONE, 6.0, 0f, 4f),
        Event(560.0, ManeuverKind.TURN, Turn.LEFT, 45.0, -45f, 11f),
        Event(605.0, ManeuverKind.TURN, Turn.RIGHT, 45.0, 45f, 11f),
    )
    private val stopAtM = 800.0
    private val cruiseMps = 14f

    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    override fun start() {
        job?.cancel()
        job = scope.launch { run() }
    }

    override fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun run() {
        val dt = 0.05f
        var s = 0.0
        var v = 0f
        var stoppedFor = 0f
        while (scope.isActive) {
            // Target speed: slow down before events, stop at the end, then loop.
            var target = cruiseMps
            for (e in route) {
                if (s in (e.atM - 45)..(e.atM + e.lengthM)) target = min(target, e.speedMps)
            }
            if (s >= stopAtM - 60) target = 0f

            val accel = (target - v).coerceIn(-3.5f * dt, 2.2f * dt)
            v = (v + accel).coerceAtLeast(0f)
            s += v * dt

            if (s >= stopAtM - 60 && v < 0.05f) {
                stoppedFor += dt
                if (stoppedFor > 4f) { s = 0.0; stoppedFor = 0f }
            }

            val active = route.firstOrNull { s in it.atM..(it.atM + it.lengthM) }
            val steer = active?.steerDeg ?: 0f

            val ahead = route
                .filter { it.atM - s in 0.0..300.0 }
                .map { Maneuver(it.kind, it.turn, it.atM - s) }

            _state.value = VehicleState(
                speedMps = v,
                steeringDeg = steer,
                brakePressed = accel < -1.0f * dt,
                gear = "GEAR_DRIVE",
                occupied = mapOf(SeatPos.FRONT_PASSENGER to true, SeatPos.REAR_LEFT to false, SeatPos.REAR_RIGHT to true),
                maneuvers = ahead,
                drivingRestricted = v > 0.1f,
            )
            delay((dt * 1000).toLong())
        }
    }
}
