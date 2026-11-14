package ai.steadyride.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.steadyride.app.core.GeoPoint
import ai.steadyride.app.core.NamyangScenario
import ai.steadyride.app.core.ScenarioPhase
import ai.steadyride.app.core.ScenarioUi
import ai.steadyride.app.core.runNamyangScenario
import ai.steadyride.app.core.RouteAnalysis
import ai.steadyride.app.core.RouteCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.os.Handler
import android.os.Looper
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live data from the real vehicle:
 *  - Pleos Vehicle SDK: steering angle, brake pedal, gear, seat occupancy
 *  - AAOS Car API: vehicle speed (not exposed by the Pleos SDK)
 *  - Android sensors: acceleration (not exposed by either vehicle API)
 */
class LiveVehicleSource(private val context: Context) : VehicleSource {

    private val _state = MutableStateFlow(VehicleState())
    override val state: StateFlow<VehicleState> = _state.asStateFlow()

    private var vehicle: Vehicle? = null
    private var car: Car? = null
    private var sensorManager: SensorManager? = null

    private val seatAreas = mapOf(
        SeatPos.FRONT_PASSENGER to SeatArea(SeatValue.ROW_1_RIGHT),
        SeatPos.REAR_LEFT to SeatArea(SeatValue.ROW_2_LEFT),
        SeatPos.REAR_RIGHT to SeatArea(SeatValue.ROW_2_RIGHT),
    )

    override fun start() {
        startPleos()
        startCarApi()
        startAccelerometer()
        // The navigation connection outlives stop()/start(): keep it, just resume polling.
        val failed = naviReady.isCompleted && naviReady.getCompletionExceptionOrNull() != null
        if (navi == null || failed) {
            releaseNavi()
            startNavi()
        } else {
            mainHandler.removeCallbacks(tbtPoll)
            mainHandler.post(tbtPoll)
        }
    }

    /**
     * Probes each route option in turn: switch the option, wait for re-routing, then read
     * the remaining time/distance and the turn-by-turn + caution lists of that route.
     * The original option is restored if anything fails.
     *
     * Note: the TBT list may only cover the upcoming part of a long route; the comparison
     * then reflects the next stretch, which is where the difference matters most.
     */
    /**
     * Probes each route option for the destination being guided: request the same
     * destination with that option, wait until the navigation has computed it, then read
     * the remaining time/distance and the turn-by-turn + caution lists of that route.
     *
     * requestRoute(destination, option) is used rather than changeRouteOption(): on the Pleos
     * Connect emulator the navigation accepts changeRouteOption but keeps the old route.
     * The original option is restored if anything fails.
     *
     * Note: the TBT list may only cover the upcoming part of a long route; the comparison
     * then reflects the next stretch, which is where the difference matters most.
     */
    override suspend fun searchRoutes(onProgress: (done: Int, total: Int) -> Unit): List<RouteCandidate> {
        val n = navi ?: return emptyList()
        // Note: RouteStateInfo.isRouting means "a route search is in progress", not "guiding".
        // Whether a route is being guided is read from the destination info instead.
        val routeState = ask<RouteStateInfo>({ pendingState = it }) { n.getRouteStateInfo() } ?: return emptyList()
        val target = currentTarget(n) ?: return emptyList()
        val original = routeState.routeOption
        var current = original
        val out = mutableListOf<RouteCandidate>()
        var optionsIgnored = false
        try {
            ROUTE_OPTIONS.forEachIndexed { i, (name, label) ->
                onProgress(i, ROUTE_OPTIONS.size)
                if (optionsIgnored) return@forEachIndexed
                val opt = RouteOption.valueOf(name)
                if (opt != current) {
                    while (rerouted.tryReceive().isSuccess) Unit // drop stale events
                    withContext(Dispatchers.Main) { n.requestRoute(target.copy(routeOption = opt)) }
                    if (!waitForReroute(n, opt)) {
                        // The navigation re-routed but kept its own option (seen on the Pleos
                        // emulator): the other options would fail the same way, so stop here.
                        Log.w(TAG, "route $name: navigation kept its own route option; comparison stopped")
                        optionsIgnored = true
                        runCatching { withContext(Dispatchers.Main) { n.requestRoute(target.copy(routeOption = original)) } }
                        return@forEachIndexed
                    }
                    current = opt
                    delay(1500) // let TBT and caution points of the new route arrive
                }
                val dest = ask<DestinationInfo>({ pendingDest = it }) { n.getDestinationInfo() } ?: return@forEachIndexed
                val turns = ask<List<Maneuver>>({ pendingTbt = it }) { n.getTBTInfo() } ?: tbt
                val c = RouteCandidate(name, label, dest.remainTime, dest.remainDistance, turns + cautions)
                Log.i(TAG, "route $name: eta=${c.etaS}s dist=${c.distanceM}m turns=${c.turns} sharp=${c.sharpTurns} " +
                    "uturn=${c.uTurns} bumps=${c.bumps} index=${c.sicknessIndex}")
                out += c
            }
            onProgress(ROUTE_OPTIONS.size, ROUTE_OPTIONS.size)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            reportError("경로 비교", e)
            runCatching { withContext(Dispatchers.Main) { n.requestRoute(target.copy(routeOption = original)) } }
            return emptyList()
        }
        return out
    }

    // ---------- Route analysis: score whatever route the navigation is guiding ----------

    @Volatile private var routeDirty = true

    fun markRouteChanged() {
        routeDirty = true
    }

    /** True once after the navigation's route may have changed (new route, option, destination). */
    fun consumeRouteChanged(): Boolean {
        val dirty = routeDirty
        routeDirty = false
        return dirty
    }

    /**
     * Reads the route being guided (option, remaining time/distance, the turn-by-turn list and
     * caution points) and scores it. Null when nothing is being guided.
     */
    suspend fun analyzeCurrentRoute(): RouteAnalysis? {
        // The NaviHelper object exists before its service is connected; queries sent earlier
        // are never answered. Wait for the connection.
        val n = withTimeoutOrNull(8_000) { naviReady.await() } ?: return null
        val st = ask<RouteStateInfo>({ pendingState = it }) { n.getRouteStateInfo() } ?: return null
        if (st.isRouting) return null // still computing; the caller retries
        // The provider sometimes answers 0 m / 0 s first; ask again briefly.
        var d: DestinationInfo? = null
        for (attempt in 0 until 4) {
            val got = ask<DestinationInfo>({ pendingDest = it }) { n.getDestinationInfo() }
            if (got != null && got.remainDistance > 0 && got.remainTime > 0) { d = got; break }
            delay(600)
        }
        val dest = d ?: return null
        val turns = ask<List<Maneuver>>({ pendingTbt = it }) { n.getTBTInfo() } ?: tbt
        val label = ROUTE_OPTION_LABELS[st.routeOption.name] ?: st.routeOption.name
        val c = RouteCandidate(st.routeOption.name, label, dest.remainTime, dest.remainDistance, turns + cautions)
        Log.i(TAG, "analysis ${dest.name}: ${c.label} eta=${c.etaS}s dist=${c.distanceM}m turns=${c.turns} " +
            "sharp=${c.sharpTurns} uturn=${c.uTurns} bumps=${c.bumps} index=${c.sicknessIndex}")
        return RouteAnalysis(dest.name.orEmpty(), c)
    }

    /**
     * Requests the destination being guided again, so the navigation recomputes it with its
     * current settings (e.g. toll roads avoided). Changing a navigation setting does not
     * re-route the active guidance by itself. Returns true once the new route is ready.
     */
    suspend fun reRequestCurrentRoute(): Boolean {
        val n = withTimeoutOrNull(8_000) { naviReady.await() } ?: return false
        val target = currentTarget(n) ?: return false
        while (rerouted.tryReceive().isSuccess) Unit
        withContext(Dispatchers.Main) { n.requestRoute(target) }
        delay(1500) // let the search start before polling its state
        return withTimeoutOrNull(REROUTE_TIMEOUT_MS) {
            var lastEta = -1
            var done = false
            while (!done) {
                val evented = withTimeoutOrNull(800) { rerouted.receive() } != null
                val st = ask<RouteStateInfo>({ pendingState = it }) { n.getRouteStateInfo() }
                if (st != null && !st.isRouting) {
                    val eta = ask<DestinationInfo>({ pendingDest = it }) { n.getDestinationInfo() }?.remainTime ?: -1
                    done = eta > 0 && (evented || eta == lastEta)
                    lastEta = eta
                }
            }
            true
        } ?: false
    }

    /** The destination being guided, as a [RouteInfo] that can be re-requested with another option. */
    private var lastTarget: RouteInfo? = null

    private suspend fun currentTarget(n: NaviHelper): RouteInfo? {
        repeat(3) {
            val d = ask<DestinationInfo>({ pendingDest = it }) { n.getDestinationInfo() } ?: return null
            if (d.remainDistance <= 0 || d.remainTime <= 0) return null // nothing is being guided
            val known = d.name == NamyangScenario.DESTINATION_NAME
            val lat = if (d.latitude != 0.0) d.latitude else if (known) NamyangScenario.destination.latitude else null
            val lon = if (d.longitude != 0.0) d.longitude else if (known) NamyangScenario.destination.longitude else null
            if (lat != null && lon != null) {
                return RouteInfo(
                    latitude = lat,
                    longitude = lon,
                    poiName = d.name.orEmpty(),
                    address = d.address.orEmpty(),
                    poiId = d.poiId.orEmpty(),
                    poiSubId = d.poiSubId.orEmpty(),
                    routeOption = RouteOption.RECOMMENDED,
                ).also { lastTarget = it }
            }
            delay(500) // the provider sometimes answers with 0/0 coordinates first
        }
        return null
    }

    /**
     * Waits until the navigation is really guiding with [opt]. A completion callback is the
     * fast path; because providers differ (and a cold Pleos search can take 25 s+), the route
     * state is also polled: the option must match and the remaining time must be positive and
     * stable across two reads.
     */
    private suspend fun waitForReroute(n: NaviHelper, opt: RouteOption): Boolean =
        withTimeoutOrNull(REROUTE_TIMEOUT_MS) {
            var lastEta = -1
            var confirmed = false
            while (!confirmed) {
                val evented = withTimeoutOrNull(800) { rerouted.receive() } != null
                val st = ask<RouteStateInfo>({ pendingState = it }) { n.getRouteStateInfo() }
                if (st != null && !st.isRouting && st.routeOption == opt) {
                    val eta = ask<DestinationInfo>({ pendingDest = it }) { n.getDestinationInfo() }?.remainTime ?: -1
                    confirmed = eta > 0 && (evented || eta == lastEta)
                    lastEta = eta
                }
            }
            true
        } ?: false

    override suspend fun applyRouteOption(option: String): Boolean {
        val n = navi ?: return false
        val now = ask<RouteStateInfo>({ pendingState = it }) { n.getRouteStateInfo() }
        if (now?.routeOption?.name == option) return true // already guiding with it
        val target = lastTarget ?: currentTarget(n) ?: return false
        return runCatching {
            withContext(Dispatchers.Main) { n.requestRoute(target.copy(routeOption = RouteOption.valueOf(option))) }
        }.onFailure { reportError("경로 적용", it) }.isSuccess
    }

    // One-shot answers to navigation queries, completed from the listener.
    @Volatile private var pendingState: CompletableDeferred<RouteStateInfo>? = null
    @Volatile private var pendingDest: CompletableDeferred<DestinationInfo>? = null
    @Volatile private var pendingTbt: CompletableDeferred<List<Maneuver>>? = null
    private val rerouted = Channel<Unit>(Channel.CONFLATED)

    private suspend fun <T> ask(register: (CompletableDeferred<T>) -> Unit, call: () -> Unit): T? {
        val d = CompletableDeferred<T>()
        register(d)
        withContext(Dispatchers.Main) { runCatching(call) }
        return withTimeoutOrNull(3000) { d.await() }
    }

    // ---------- Pleos NaviHelper (turn-by-turn + caution points) ----------

    private var navi: NaviHelper? = null
    private var naviReady = CompletableDeferred<NaviHelper>()
    private var tbt: List<Maneuver> = emptyList()
    private var cautions: List<Maneuver> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val naviListener = object : NaviHelperEventListener {
        override fun onTBTInfo(list: List<TBTInfo>) {
            tbt = list.mapNotNull(NaviMapping::fromTbt)
            pendingTbt?.complete(tbt)
            publishManeuvers()
        }

        override fun onRouteStateInfo(info: RouteStateInfo) {
            pendingState?.complete(info)
        }

        override fun onDestinationInfo(info: DestinationInfo) {
            pendingDest?.complete(info)
        }

        override fun onRouteOptionChanged(option: RouteOption) {
            routeDirty = true
        }

        override fun onDestinationChanged() {
            routeDirty = true
        }

        override fun onRouteCancelled() {
            routeDirty = true
        }

        override fun onRoutingCompleted() {
            routeDirty = true
            rerouted.trySend(Unit)
        }

        override fun onReRouteEnded() {
            routeDirty = true
            rerouted.trySend(Unit)
        }

        // onRouteStarted means search started; it must not complete a reroute wait.

        override fun onCautionPointInfo(list: List<CautionPoint>) {
            cautions = list.mapNotNull(NaviMapping::fromCaution)
            publishManeuvers()
        }

        override fun onError(code: NaviErrorCode) {
            Log.d(TAG, "navi: $code") // no active route etc. is normal
        }
    }

    // TBT is also requested once per second so the distance stays fresh.
    private val tbtPoll = object : Runnable {
        override fun run() {
            navi?.let { runCatching { it.getTBTInfo() } }
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun publishManeuvers() = _state.update { it.copy(maneuvers = tbt + cautions) }

    /** Final shutdown: disconnects from the navigation. */
    fun release() {
        stop()
        releaseNavi()
    }

    private fun releaseNavi() {
        mainHandler.removeCallbacks(tbtPoll)
        naviReady.cancel()
        navi?.let { n -> runCatching { n.removeListener(naviListener); n.release() } }
        navi = null
    }

    private fun startNavi() {
        val ready = CompletableDeferred<NaviHelper>()
        naviReady = ready
        runCatching {
            val n = NaviHelper(context)
            navi = n
            n.initialize(
                { mainHandler.post {
                    if (navi === n && naviReady === ready) {
                        n.addListener(naviListener)
                        mainHandler.post(tbtPoll)
                        ready.complete(n)
                    }
                } },
                { error ->
                    ready.completeExceptionally(error)
                    reportError("내비 연결", error)
                },
            )
        }.onFailure { ready.completeExceptionally(it); reportError("내비 초기화", it) }
    }

    suspend fun startNamyangScenario(progress: (ScenarioUi) -> Unit): ScenarioUi =
        withContext(Dispatchers.Main.immediate) {
            val n = withTimeout(8_000) { naviReady.await() }
            val gateway = ScenarioNaviGateway(n)
            try { runNamyangScenario(gateway, progress) } finally { gateway.close() }
        }

    /**
     * Reads the route the navigation is guiding right now and reports it as the Namyang
     * scenario if it leads there. Used when the app comes back to the front: while the
     * navigation was in front, Android froze this app and the original wait timed out.
     */
    suspend fun checkNamyangRoute(): ScenarioUi? = withContext(Dispatchers.Main.immediate) {
        val n = withTimeoutOrNull(8_000) { naviReady.await() } ?: return@withContext null
        val d = ask<DestinationInfo>({ pendingDest = it }) { n.getDestinationInfo() } ?: return@withContext null
        val onRoute = NamyangScenario.isDestination(GeoPoint(d.latitude, d.longitude)) && d.remainDistance > 0 && d.remainTime > 0
        if (!onRoute) return@withContext null
        ScenarioUi(ScenarioPhase.READY, "남양연구소 경로를 확인했어요. 회전 안내를 멀미 케어에 연결해요.", d.remainDistance, d.remainTime)
    }

    suspend fun showNavigation() = withContext(Dispatchers.Main.immediate) {
        val n = withTimeout(8_000) { naviReady.await() }
        n.requestNavigationScreen(NavigationScreenRequest.Foreground)
    }

    override fun stop() {
        vehicle?.let { v ->
            runCatching {
                v.getSteeringWheel().unregisterSteeringWheelAngle(steeringListener)
                v.getBrake().unregisterBrakePedalStatus(brakeListener)
                v.getGear().unregisterGearSelection(gearListener)
                seatListeners.forEach { (pos, l) -> v.getSeat().unregisterSeatOccupancy(seatAreas.getValue(pos), l) }
            }
            v.release()
        }
        vehicle = null

        car?.let { c ->
            runCatching {
                (c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager).unregisterCallback(speedCallback)
                (c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager).unregisterListener()
            }
            c.disconnect()
        }
        car = null

        // Only pause TBT polling. Starting a route or comparing route options brings the
        // navigation app to the front and sends this app to the background; releasing the
        // NaviHelper here would abort the very request the user just made.
        mainHandler.removeCallbacks(tbtPoll)

        sensorManager?.unregisterListener(accelListener)
        sensorManager = null
    }

    // ---------- Pleos Vehicle SDK ----------

    private val steeringListener = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) = _state.update { it.copy(steeringDeg = angle) }
        override fun onFailed(e: Exception) = reportError("조향각", e)
    }

    private val brakeListener = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(pressed: Boolean?) = _state.update { it.copy(brakePressed = pressed) }
        override fun onFailed(e: Exception) = reportError("브레이크", e)
    }

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) = _state.update { it.copy(gear = gear?.toString()) }
        override fun onFailed(e: Exception) = reportError("기어", e)
    }

    private val seatListeners: Map<SeatPos, SeatOccupancyListener> = SeatPos.entries.associateWith { pos ->
        object : SeatOccupancyListener {
            override fun onSeatOccupancyUpdated(occupied: Boolean?) = setSeat(pos, occupied)
            override fun onFailed(e: Exception) = reportError("좌석(${pos.label})", e)
        }
    }

    private fun setSeat(pos: SeatPos, occupied: Boolean?) {
        _state.update { it.copy(occupied = it.occupied + (pos to (occupied == true))) }
    }

    private fun startPleos() {
        val v = runCatching { Vehicle(context).also { it.initialize() } }
            .onFailure { reportError("Pleos 초기화", it) }
            .getOrNull() ?: return
        vehicle = v

        // Read current values once, then subscribe to changes.
        v.getSteeringWheel().getCurrentSteeringWheelAngle(
            { a -> _state.update { it.copy(steeringDeg = a) } }, { reportError("조향각", it) })
        v.getBrake().getCurrentBrakePedalStatus(
            { b -> _state.update { it.copy(brakePressed = b) } }, { reportError("브레이크", it) })
        v.getGear().getCurrentGearSelectionState(
            { g -> _state.update { it.copy(gear = g.toString()) } }, { reportError("기어", it) })

        v.getSteeringWheel().registerSteeringWheelAngle(steeringListener)
        v.getBrake().registerBrakePedalStatus(brakeListener)
        v.getGear().registerGearSelection(gearListener)

        seatAreas.forEach { (pos, area) ->
            v.getSeat().getCurrentSeatOccupancy(area, { setSeat(pos, it) }, { reportError("좌석(${pos.label})", it) })
            v.getSeat().registerSeatOccupancy(area, seatListeners.getValue(pos))
        }
    }

    // ---------- AAOS Car API (speed) ----------

    private val speedCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            val mps = value.value as? Float ?: return
            _state.update { it.copy(speedMps = mps) }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            reportError("차속", RuntimeException("property error $propertyId"))
        }
    }

    private fun startCarApi() {
        // The lifecycle listener (re)registers whenever the car service becomes ready, so a
        // car service restart does not leave speed and driving restrictions disconnected.
        val c = runCatching {
            Car.createCar(context, mainHandler, Car.CAR_WAIT_TIMEOUT_DO_NOT_WAIT) { connected, ready ->
                if (ready) registerCarCallbacks(connected)
            }
        }.onFailure { reportError("Car 서비스", it) }.getOrNull() ?: return
        car = c
    }

    private fun registerCarCallbacks(c: Car) {
        _state.update { st -> st.copy(errors = st.errors.filterNot { it.startsWith("Car 서비스") }) }
        runCatching {
            val pm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            pm.registerCallback(speedCallback, VehiclePropertyIds.PERF_VEHICLE_SPEED, 10f)
        }.onFailure { reportError("차속(Car API)", it) }

        // Driving restriction: the platform tells us when distraction optimization is required.
        runCatching {
            val um = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            val apply = { r: CarUxRestrictions? ->
                _state.update { it.copy(drivingRestricted = r?.isRequiresDistractionOptimization == true) }
            }
            apply(um.currentCarUxRestrictions)
            um.registerListener { apply(it) }
        }.onFailure { reportError("주행 규제", it) }
    }

    // ---------- Android accelerometer ----------

    private val accelListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            _state.update { it.copy(accel = Accel(x, y, z)) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private fun startAccelerometer() {
        val sm = context.getSystemService(SensorManager::class.java) ?: return
        val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER_UNCALIBRATED)
        if (sensor == null) {
            reportError("가속도 센서", RuntimeException("no accelerometer"))
            return
        }
        sensorManager = sm
        sm.registerListener(accelListener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    private fun reportError(what: String, e: Throwable) {
        Log.w(TAG, "$what failed", e)
        val msg = "$what: ${e.javaClass.simpleName} ${e.message.orEmpty()}".trim()
        _state.update { s -> if (msg in s.errors) s else s.copy(errors = (s.errors + msg).takeLast(8)) }
    }

    private companion object {
        const val TAG = "SteadyRide"
        const val REROUTE_TIMEOUT_MS = 25_000L // a cold Pleos route search can take 25 s
    }
}
