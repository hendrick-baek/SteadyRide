package ai.steadyride.app

import ai.steadyride.app.audio.CueSound
import ai.steadyride.app.core.CoachTip
import ai.steadyride.app.core.ComfortEngine
import ai.steadyride.app.core.DrivingCoach
import ai.steadyride.app.core.RouteCandidate
import ai.steadyride.app.core.RoutePlan
import ai.steadyride.app.core.RoutePlanner
import ai.steadyride.app.core.Cue
import ai.steadyride.app.core.CueSource
import ai.steadyride.app.core.ComfortState
import ai.steadyride.app.core.RiskLevel
import ai.steadyride.app.core.ScenarioPhase
import ai.steadyride.app.core.ScenarioUi
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** State of the "least motion-sickness route within N minutes" search. */
data class RouteUi(
    val toleranceMin: Int = 10,
    val searching: Boolean = false,
    val progress: Pair<Int, Int>? = null,
    val plan: RoutePlan? = null,
    val applied: String? = null,   // RouteOption name now in use
    val message: String? = null,
    // Test-vehicle mode: the route the navigation is guiding, scored, plus every route seen
    // for the same destination (the user switches routes in the navigation itself).
    val destination: String? = null,
    val analysis: RouteCandidate? = null,
    val history: List<RouteCandidate> = emptyList(),
    val analyzing: Boolean = false,
)

enum class SourceMode(val label: String) { LIVE("시험차 신호"), DEMO("데모 주행") }

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
    val scenario: ScenarioUi = ScenarioUi(),
)

/** Wires a vehicle source -> comfort engine -> actuators, at 10 Hz. */
class SteadyRideController(context: Context) {

    private val appContext = context.applicationContext

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

    // UI actions, source switching and SDK requests share Main to avoid racing the tick.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private var scenarioJob: Job? = null
    private var navigationScreenJob: Job? = null
    private var manual: Boolean? = null
    private var autoOn = false

    private var cueSoundOn = true
    private val prefs = context.getSharedPreferences("steady_ride", Context.MODE_PRIVATE)
    private var route = RouteUi(toleranceMin = prefs.getInt("route_tolerance_min", 10))
    private var routeJob: Job? = null
    private var lastSoundKey: String? = null
    private var lastSoundAtNs = 0L
    private var lastSoundTurn: Turn? = null
    private var toneDoneThisTrip = false

    fun start() {
        if (loop?.isActive == true) return
        source.start()
        resyncScenario()
        // Back in front: the user may have changed the route in the navigation meanwhile
        // (this app is frozen in the background and can miss the change events).
        if (source === live) live.markRouteChanged()
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
        loop = null
        // Navigation work (Namyang route, route comparison) keeps running in the background:
        // starting a route brings the navigation app to the front, which stops this activity.
        source.stop()
        sound.stopTone()
        ambient.restore()
    }

    fun release() {
        stop()
        cancelNavigationWork()
        live.release()
        demo.stop()
        actuator.release()
        sound.release()
        ambient.release()
        scope.cancel()
    }

    fun setMode(mode: SourceMode) {
        if (mode == _ui.value.mode) return
        val wasRunning = loop?.isActive == true
        cancelNavigationWork()
        source.stop()
        source = if (mode == SourceMode.DEMO) demo else live
        engine.resetTrip()
        coach.reset()
        demo.resetRoute()
        routeJob?.cancel()
        route = RouteUi(toleranceMin = route.toleranceMin)
        toneDoneThisTrip = false
        autoOn = false
        if (wasRunning) source.start()
        _ui.update { it.copy(mode = mode, scenario = ScenarioUi(), route = route) }
    }

    private fun cancelNavigationWork() {
        scenarioJob?.cancel()
        scenarioJob = null
        navigationScreenJob?.cancel()
        navigationScreenJob = null
        routeJob?.cancel()
        routeJob = null
        route = route.copy(searching = false, progress = null)
        _ui.update { it.copy(route = route, scenario = ScenarioUi(message = "버튼을 눌러 내비 경로를 확인해 주세요.")) }
    }

    fun startNamyangScenario() {
        if (scenarioJob?.isActive == true || routeJob?.isActive == true || loop?.isActive != true) return
        // The demo's fabricated route is never passed off as the real navigation scenario.
        if (live.state.value.drivingRestricted) {
            _ui.update { it.copy(scenario = ScenarioUi(ScenarioPhase.ERROR, "정차 후 시나리오를 설정해 주세요.")) }
            return
        }
        setMode(SourceMode.LIVE)
        route = route.copy(searching = false, progress = null, plan = null, message = null) // keep analyzed routes for comparison
        _ui.update { it.copy(route = route, scenario = ScenarioUi(ScenarioPhase.CONNECTING, "내비 서비스에 연결하고 있어요.")) }
        scenarioJob = scope.launch {
            NaviTaskService.start(appContext, "남양연구소 경로를 확인하고 있어요")
            try {
                val result = live.startNamyangScenario { status -> _ui.update { it.copy(scenario = status) } }
                _ui.update { it.copy(scenario = result) }
                live.markRouteChanged() // score the new route
                actuator.note(result.message)
            } catch (e: TimeoutCancellationException) {
                // Usually Android froze this app while the navigation was in front; the route
                // may well be set. Check the navigation once more before reporting a failure.
                val confirmed = runCatching { live.checkNamyangRoute() }.getOrNull()
                _ui.update { it.copy(scenario = confirmed ?: ScenarioUi(ScenarioPhase.ERROR,
                    "내비 응답 시간이 초과됐어요. 내비 앱 실행·위치·네트워크를 확인하고 다시 시도해 주세요.")) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(scenario = ScenarioUi(ScenarioPhase.ERROR,
                    e.message ?: "내비 연결에 실패했어요. 내비 앱을 확인해 주세요.")) }
            } finally {
                NaviTaskService.stop(appContext)
            }
        }
    }

    /** Back in front after a failed/interrupted scenario: show the route if the navigation has it. */
    private fun resyncScenario() {
        val sc = _ui.value.scenario
        if (source !== live || scenarioJob?.isActive == true || sc.phase != ScenarioPhase.ERROR) return
        scenarioJob = scope.launch {
            runCatching { live.checkNamyangRoute() }.getOrNull()?.let { ok -> _ui.update { it.copy(scenario = ok) } }
        }
    }

    fun showNavigation() {
        if (_ui.value.mode != SourceMode.LIVE || navigationScreenJob?.isActive == true) return
        navigationScreenJob = scope.launch {
            try { live.showNavigation() }
            catch (e: TimeoutCancellationException) {
                _ui.update { it.copy(scenario = it.scenario.copy(message = "내비 연결 시간이 초과됐어요.")) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _ui.update { it.copy(scenario = it.scenario.copy(message = "내비 화면을 열지 못했어요. ${e.message.orEmpty()}")) }
            }
        }
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
        if (routeJob?.isActive == true || scenarioJob?.isActive == true) return false
        // Test vehicle: the navigation decides the route; analyze the one it is guiding.
        if (source === live) { analyzeRoute(); return true }
        val src = source
        routeJob = scope.launch {
            route = route.copy(searching = true, progress = 0 to ROUTE_OPTIONS.size, message = null)
            if (src === live) NaviTaskService.start(appContext, "멀미 적은 경로를 비교하고 있어요")
            try {
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
                    src === live && candidates.size == 1 ->
                        "이 내비는 앱의 경로 옵션 변경 요청을 반영하지 않아 현재 경로(${candidates[0].label})만 확인했어요. 멀미 지수 ${candidates[0].sicknessIndex}."
                    src === live && candidates.size < ROUTE_OPTIONS.size ->
                        "내비가 일부 경로 옵션 요청에 응답하지 않아 ${candidates.size}개 경로만 비교했어요. " +
                            (best?.let { "${it.label}이(가) 멀미 지수 ${it.sicknessIndex}로 가장 편해요." } ?: "")
                    best == null -> "허용 시간 안에 더 편한 경로가 없어요."
                    best.option == fastest.option -> "가장 빠른 경로가 멀미도 가장 적어요."
                    else -> "${plan.delayMin(best)}분 더 걸리는 대신 멀미 지수가 ${fastest.sicknessIndex} → ${best.sicknessIndex}로 낮아져요."
                }
                route = route.copy(searching = false, progress = null, plan = plan, applied = applied, message = msg)
                best?.let { actuator.note("멀미 최소화 경로: ${it.label} (+${plan.delayMin(it)}분, 지수 ${it.sicknessIndex})") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                route = route.copy(message = "경로 비교에 실패했어요. 다시 시도해 주세요.")
            } finally {
                route = route.copy(searching = false, progress = null)
                _ui.update { it.copy(route = route) }
                if (src === live) NaviTaskService.stop(appContext)
            }
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

    private var analysisJob: Job? = null

    /** Scores the navigation's current route and adds it to this destination's comparison. */
    fun analyzeRoute() {
        if (source !== live || analysisJob?.isActive == true) return
        analysisJob = scope.launch {
            route = route.copy(analyzing = true)
            _ui.update { it.copy(route = route) }
            val a = runCatching { live.analyzeCurrentRoute() }.getOrNull()
            if (a == null && source === live && live.state.value.maneuvers.isNotEmpty()) {
                // The navigation is guiding but did not answer yet: try again shortly.
                scope.launch { delay(4_000); live.markRouteChanged() }
            }
            route = if (a == null) {
                route.copy(analyzing = false, analysis = null)
            } else {
                val same = a.destination == route.destination
                val kept = if (same) route.history.filterNot { old ->
                    old.option == a.route.option && kotlin.math.abs(old.etaS - a.route.etaS) < 90
                } else emptyList()
                // Several routes with the same option get a running number.
                val n = kept.count { it.option == a.route.option }
                val entry = if (n == 0) a.route else a.route.copy(label = "${a.route.label} ${n + 1}")
                route.copy(analyzing = false, destination = a.destination, analysis = entry, history = (kept + entry).sortedBy { it.etaS })
            }
            _ui.update { it.copy(route = route) }
            route.analysis?.let { actuator.note("경로 분석: ${it.label} · 멀미 지수 ${it.sicknessIndex}") }
        }
    }

    private var researchJob: Job? = null

    /** Re-requests the guided destination with the navigation's current settings, then scores it. */
    fun researchRoute() {
        if (source !== live || researchJob?.isActive == true || scenarioJob?.isActive == true) return
        researchJob = scope.launch {
            route = route.copy(analyzing = true)
            _ui.update { it.copy(route = route) }
            NaviTaskService.start(appContext, "바뀐 설정으로 경로를 다시 찾고 있어요")
            try {
                val ok = runCatching { live.reRequestCurrentRoute() }.getOrDefault(false)
                if (!ok) {
                    route = route.copy(analyzing = false)
                    _ui.update { it.copy(route = route) }
                    actuator.note("다시 탐색: 안내 중인 경로가 없거나 내비 응답이 없어요")
                }
            } finally {
                NaviTaskService.stop(appContext)
                live.markRouteChanged()
            }
        }
    }

    private fun tick(dt: Float) {
        if (source === live && live.consumeRouteChanged()) analyzeRoute()
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
