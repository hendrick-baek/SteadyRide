package ai.steadyride.app

import ai.steadyride.app.audio.CueSound
import ai.steadyride.app.core.CoachTip
import ai.steadyride.app.core.ComfortEngine
import ai.steadyride.app.core.DrivingCoach
import ai.steadyride.app.core.RoutePlan
import ai.steadyride.app.core.RoutePlanner
import ai.steadyride.app.core.Cue
import ai.steadyride.app.core.CueSource
import ai.steadyride.app.core.ComfortState
import ai.steadyride.app.core.RiskLevel
import ai.steadyride.app.vehicle.AmbientMotion
import ai.steadyride.app.vehicle.ComfortActuator
import ai.steadyride.app.vehicle.DemoDriveSource
import ai.steadyride.app.vehicle.LiveVehicleSource
import ai.steadyride.app.vehicle.ROUTE_OPTIONS
import ai.steadyride.app.vehicle.Turn
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

/** State of the "least motion-sickness route within N minutes" search. */
data class RouteUi(
    val toleranceMin: Int = 5,
    val searching: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val plan: RoutePlan? = null,
    val applied: String? = null,   // RouteOption name now in use
    val message: String? = null,
)

enum class SourceMode(val label: String) { LIVE("실차 신호"), DEMO("데모 주행") }

data class UiState(
    val mode: SourceMode = SourceMode.LIVE,
    val vehicle: VehicleState = VehicleState(),
    val comfort: ComfortState = ComfortState(),
    val comfortModeOn: Boolean = false,
    val manualComfort: Boolean? = null, // null = automatic
    val cueSoundOn: Boolean = true,
    val toneRemainingS: Int? = null,   // 100 Hz tone countdown, null when idle
    val route: RouteUi = RouteUi(),
    val ambientOn: Boolean = true,
    val coach: CoachTip? = null,
)

/** Wires a vehicle source -> comfort engine -> actuators, at 10 Hz. */
class SteadyRideController(context: Context) {

    private val live = LiveVehicleSource(context)
    private val demo = DemoDriveSource()
    private var source: VehicleSource = live

    private val engine = ComfortEngine()
    private val coach = DrivingCoach()
    val actuator = ComfortActuator(context)
    private val sound = CueSound(context, actuator::note)
    val ambient = AmbientMotion(context, actuator::note)
    private var ambientOn = true

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var manual: Boolean? = null
    private var autoOn = false

    private var cueSoundOn = true
    private val prefs = context.getSharedPreferences("steady_ride", Context.MODE_PRIVATE)
    private var route = RouteUi(toleranceMin = prefs.getInt("route_tolerance_min", 5))
    private var routeJob: Job? = null
    private var lastSoundKey: String? = null
    private var lastSoundAtNs = 0L
    private var lastSoundTurn: Turn? = null
    private var toneDoneThisTrip = false

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
        sound.stopTone()
        ambient.restore()
    }

    fun release() {
        stop()
        live.stop()
        demo.stop()
        actuator.release()
        sound.release()
        ambient.release()
    }

    fun setMode(mode: SourceMode) {
        if (mode == _ui.value.mode) return
        source.stop()
        source = if (mode == SourceMode.DEMO) demo else live
        engine.resetTrip()
        coach.reset()
        demo.resetRoute()
        routeJob?.cancel()
        route = RouteUi(toleranceMin = route.toleranceMin)
        toneDoneThisTrip = false
        source.start()
        _ui.value = _ui.value.copy(mode = mode)
    }

    /** Voice (Gleo) or on-screen override. null returns to automatic. */
    fun setManualComfort(on: Boolean?) {
        manual = on
    }

    /** How many minutes later the driver accepts to arrive for a gentler route. */
    fun setRouteTolerance(min: Int) {
        route = route.copy(toleranceMin = min)
        prefs.edit().putInt("route_tolerance_min", min).apply()
    }

    /**
     * Compares the route options of the active navigation and switches to the one with
     * the lowest motion-sickness load that arrives at most [RouteUi.toleranceMin] later
     * than the fastest. Returns false if a search is already running.
     */
    fun searchComfortRoute(): Boolean {
        if (route.searching) return false
        val src = source
        routeJob = scope.launch {
            route = route.copy(searching = true, progress = 0 to ROUTE_OPTIONS.size, message = null)
            val candidates = src.searchRoutes { done, total -> route = route.copy(progress = done to total) }
            if (candidates.isEmpty()) {
                route = route.copy(searching = false, progress = null, plan = null, message = "안내 중인 경로가 없어요. 목적지를 먼저 설정해 주세요.")
                actuator.note("멀미 최소화 경로: 안내 중인 경로 없음")
                return@launch
            }
            val plan = RoutePlanner.choose(candidates, route.toleranceMin)
            val best = plan.chosen
            val fastest = candidates.minBy { it.etaS }
            val applied = if (best != null && src.applyRouteOption(best.option)) best.option else null
            val msg = when {
                best == null -> "허용 시간 안에 더 편한 경로가 없어요."
                best.option == fastest.option -> "가장 빠른 경로가 멀미도 가장 적어요."
                else -> "${plan.delayMin(best)}분 더 걸리는 대신 멀미 지수가 ${fastest.sicknessIndex} → ${best.sicknessIndex}로 낮아져요."
            }
            route = route.copy(searching = false, progress = null, plan = plan, applied = applied, message = msg)
            best?.let { actuator.note("멀미 최소화 경로: ${it.label} (+${plan.delayMin(it)}분, 지수 ${it.sicknessIndex})") }
        }
        return true
    }

    fun setAmbientMotion(on: Boolean) {
        ambientOn = on
    }

    fun setCueSound(on: Boolean) {
        cueSoundOn = on
    }

    fun previewCueSound() = sound.preview()

    /** Manual 100 Hz tone: start, or stop if it is already playing. */
    fun toggleTone() {
        if (sound.isTonePlaying) sound.stopTone() else sound.startTone()
    }

    private fun tick(dt: Float) {
        val v = source.state.value
        val c = engine.update(v, dt)
        val tip = coach.update(v, c, dt)

        // Automatic mode with hysteresis: on at HIGH risk, off once it calms down.
        autoOn = v.hasPassenger && when {
            c.level == RiskLevel.HIGH -> true
            c.risk < 30f -> false
            else -> autoOn
        }
        val on = manual ?: autoOn

        val cue = if (v.hasPassenger) c.cue else null
        ambient.update(c, cue, enabled = ambientOn && v.hasPassenger, dtS = dt)
        actuator.setComfortMode(on, c.level)
        playCueSound(cue)

        // 100 Hz tone once per trip, when a passenger is aboard and the car leaves Park
        // (the study exposed people for 1 min right before motion).
        if (v.isParked) toneDoneThisTrip = false
        if (!toneDoneThisTrip && v.hasPassenger && v.gear != null && !v.isParked) {
            toneDoneThisTrip = true
            sound.startTone()
        }

        _ui.value = _ui.value.copy(
            vehicle = v, comfort = c, comfortModeOn = on, manualComfort = manual,
            cueSoundOn = cueSoundOn, toneRemainingS = sound.toneRemainingS.value, route = route,
            coach = tip,
            ambientOn = ambientOn,
        )
    }

    /**
     * Directional sound about 1 s before a navigation maneuver. Live steering/brake cues
     * only sound if nothing played in the last 3 s (8 s for the same direction, 10 s for braking),
     * so a turn already announced by the route is not announced again while driving it.
     */
    private fun playCueSound(cue: Cue?) {
        if (cue == null) {
            lastSoundKey = null
            return
        }
        if (!cueSoundOn) return
        val key = "${cue.source}-${cue.turn}-${cue.kind}"
        if (key == lastSoundKey) return
        val now = System.nanoTime()
        val sinceS = (now - lastSoundAtNs) / 1e9
        val due = if (cue.source == CueSource.NAVI) cue.secondsAhead <= SOUND_LEAD_S
        else sinceS > when {
            cue.source == CueSource.BRAKE -> 10.0 // stop-and-go must not turn into constant beeping
            cue.turn != Turn.NONE && cue.turn == lastSoundTurn -> 8.0
            else -> 3.0
        }
        if (!due) return
        lastSoundKey = key
        lastSoundAtNs = now
        lastSoundTurn = cue.turn
        sound.playCue(cue)
    }

    private companion object {
        const val SOUND_LEAD_S = 1.2f
    }
}
