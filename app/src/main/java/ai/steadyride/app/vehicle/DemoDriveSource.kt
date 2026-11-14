package ai.steadyride.app.vehicle

import ai.steadyride.app.core.RouteCandidate
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

    private val normalRoute = listOf(
        Event(220.0, ManeuverKind.TURN, Turn.RIGHT, 18.0, 180f, 6f),
        Event(400.0, ManeuverKind.SPEED_BUMP, Turn.NONE, 6.0, 0f, 4f),
        Event(560.0, ManeuverKind.TURN, Turn.LEFT, 45.0, -45f, 11f),
        Event(605.0, ManeuverKind.TURN, Turn.RIGHT, 45.0, 45f, 11f),
    )

    // What the COMFORTABLE route option stands for in the demo:
    // a slower right turn, no speed bump, one gentle curve instead of the S-curve.
    private val comfortRoute = listOf(
        Event(220.0, ManeuverKind.TURN, Turn.RIGHT, 18.0, 180f, 4.5f),
        Event(560.0, ManeuverKind.TURN, Turn.LEFT, 70.0, -18f, 11f),
    )

    @Volatile private var comfortable = false
    private val route get() = if (comfortable) comfortRoute else normalRoute

    /**
     * Demo stand-in for probing NaviHelper route options: four routes to the same
     * destination with different arrival times and different amounts of turning/bumps.
     */
    override suspend fun searchRoutes(onProgress: (done: Int, total: Int) -> Unit): List<RouteCandidate> {
        val routes = listOf(
            demoRoute("RECOMMENDED", 1080, 9_400, turns = 12, sharp = 3, uTurns = 1, bumps = 8, downhills = 1),
            demoRoute("SHORT_TIME", 1040, 9_800, turns = 10, sharp = 2, uTurns = 0, bumps = 10, downhills = 2),
            demoRoute("COMFORTABLE", 1240, 10_600, turns = 7, sharp = 1, uTurns = 0, bumps = 3, downhills = 1),
            demoRoute("HIGHWAY", 1530, 14_200, turns = 4, sharp = 1, uTurns = 0, bumps = 0, downhills = 0),
        )
        routes.forEachIndexed { i, _ ->
            onProgress(i, routes.size)
            delay(650) // what a re-route takes on the real head unit, roughly
        }
        onProgress(routes.size, routes.size)
        return routes
    }

    /** Any route other than the twisty default drives the gentle demo course. */
    override suspend fun applyRouteOption(option: String): Boolean {
        comfortable = option == "COMFORTABLE" || option == "HIGHWAY"
        return true
    }

    private fun demoRoute(option: String, etaS: Int, distM: Int, turns: Int, sharp: Int, uTurns: Int, bumps: Int, downhills: Int): RouteCandidate {
        val m = buildList {
            repeat(turns) { add(Maneuver(ManeuverKind.TURN, if (it % 2 == 0) Turn.RIGHT else Turn.LEFT, 0.0)) }
            repeat(sharp) { add(Maneuver(ManeuverKind.SHARP_TURN, Turn.LEFT, 0.0)) }
            repeat(uTurns) { add(Maneuver(ManeuverKind.U_TURN, Turn.LEFT, 0.0)) }
            repeat(bumps) { add(Maneuver(ManeuverKind.SPEED_BUMP, Turn.NONE, 0.0)) }
            repeat(downhills) { add(Maneuver(ManeuverKind.DOWNHILL, Turn.NONE, 0.0)) }
        }
        val label = ROUTE_OPTIONS.first { it.first == option }.second
        return RouteCandidate(option, label, etaS, distM, m)
    }

    fun resetRoute() {
        comfortable = false
    }
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
            val route = route
            var target = cruiseMps
            for (e in route) {
                if (s in (e.atM - 45)..(e.atM + e.lengthM)) target = min(target, e.speedMps)
            }
            // Stop-and-go traffic on the normal route: the driver keeps switching pedals.
            if (!comfortable && s in 655.0..735.0) target = if (((s - 655.0) / 14.0).toInt() % 2 == 0) 12f else 5f
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
