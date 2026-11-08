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
        startNavi()
    }

    override fun requestComfortableRoute(): Boolean {
        val n = navi ?: return false
        return runCatching { n.changeRouteOption(RouteOption.COMFORTABLE) }
            .onFailure { reportError("편안한 경로 요청", it) }
            .isSuccess
    }

    // ---------- Pleos NaviHelper (turn-by-turn + caution points) ----------

    private var navi: NaviHelper? = null
    private var tbt: List<Maneuver> = emptyList()
    private var cautions: List<Maneuver> = emptyList()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val naviListener = object : NaviHelperEventListener {
        override fun onTBTInfo(list: List<TBTInfo>) {
            tbt = list.mapNotNull(NaviMapping::fromTbt)
            publishManeuvers()
        }

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

    private fun startNavi() {
        runCatching {
            val n = NaviHelper(context)
            n.initialize(
                { n.addListener(naviListener); mainHandler.post(tbtPoll) },
                { reportError("내비 연결", it) },
            )
            navi = n
        }.onFailure { reportError("내비 초기화", it) }
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

        mainHandler.removeCallbacks(tbtPoll)
        navi?.let { n -> runCatching { n.removeListener(naviListener); n.release() } }
        navi = null

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
        val c = runCatching { Car.createCar(context) ?: error("Car service unavailable") }
            .onFailure { reportError("Car 서비스", it) }
            .getOrNull() ?: return
        car = c

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
    }
}
