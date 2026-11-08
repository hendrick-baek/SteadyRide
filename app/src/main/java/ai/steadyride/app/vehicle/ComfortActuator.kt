package ai.steadyride.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.steadyride.app.core.Cue
import ai.steadyride.app.core.RiskLevel
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Turns comfort decisions into vehicle actions through the Pleos Vehicle SDK.
 * Every action is edge-triggered (only on change) to keep CPU and IPC load minimal,
 * and checked against the car's capabilities before use.
 */
class ComfortActuator(context: Context) {

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val vehicle: Vehicle? = runCatching { Vehicle(context).also { it.initialize() } }
        .onFailure { note("차량 제어 연결 실패: ${it.message}") }
        .getOrNull()

    private var ambientArea: SeatArea? = null
    private var fanArea: SeatArea? = null
    private var fanMax = 8
    private var recircArea: SeatArea? = null
    private var ventArea: SeatArea? = null
    private var ventMax = 3

    // Values saved before comfort mode so they can be restored afterwards.
    private var savedFan: Int? = null
    private var baseBrightness = 40

    private var lastCueKey: String? = null
    private var comfortOn = false

    init {
        probeCapabilities()
    }

    /** Ambient light pulse when a new cue appears; back to baseline when it ends. */
    fun onCue(cue: Cue?) {
        val key = cue?.let { "${it.source}-${it.turn}-${it.kind}" }
        if (key == lastCueKey) return
        lastCueKey = key
        val area = ambientArea ?: return
        val light = vehicle?.getLight() ?: return
        val level = if (cue != null) 100 else baseBrightness
        light.setAmbientLightBrightness(area, level, { _, _ -> }, { Log.w(TAG, "ambient", it) })
        if (cue != null) note("앰비언트 신호: ${cue.text}")
    }

    /** Comfort mode: fresh air, stronger airflow, passenger seat ventilation. */
    fun setComfortMode(on: Boolean, level: RiskLevel) {
        if (on == comfortOn) return
        comfortOn = on
        val v = vehicle ?: return
        val hvac = v.getHVAC()

        recircArea?.let { a ->
            hvac.setHvacRecirculation(a, !on, { _, _ -> note(if (on) "외기 유입으로 전환" else "내기 순환 복원") }, { fail("내·외기", it) })
        }
        fanArea?.let { a ->
            if (on) {
                hvac.getHvacFanSpeed(a, { got ->
                    val cur = got ?: 0
                    savedFan = cur
                    val target = (cur + 2).coerceIn(3, fanMax)
                    hvac.setHvacFanSpeed(a, target, { _, _ -> note("풍량 $cur → $target 단계") }, { fail("풍량", it) })
                }, { fail("풍량 조회", it) })
            } else {
                savedFan?.let { prev -> hvac.setHvacFanSpeed(a, prev, { _, _ -> note("풍량 $prev 단계로 복원") }, { fail("풍량", it) }) }
            }
        }
        ventArea?.let { a ->
            val lvl = if (on) (if (level == RiskLevel.HIGH) ventMax else 1) else 0
            v.getSeat().setHvacSeatVentilation(a, lvl, { _, _ -> note(if (on) "조수석 통풍 ${lvl}단계" else "조수석 통풍 끔") }, { fail("시트 통풍", it) })
        }
        if (on) note("멀미 완화 모드 시작") else note("멀미 완화 모드 종료")
    }

    fun release() {
        runCatching { vehicle?.release() }
    }

    private fun probeCapabilities() {
        val v = vehicle ?: return
        v.getLight().checkAmbientLightBrightnessColorCapabilities({ cfg, areas ->
            Log.i(TAG, "ambient color: access=${cfg.accessType} areas=$areas")
        }, { Log.i(TAG, "ambient color unsupported: ${it.message}") })
        v.getLight().checkAmbientLightBrightnessCapabilities({ cfg, areas ->
            Log.i(TAG, "ambient brightness: access=${cfg.accessType} areas=$areas")
            ambientArea = areas.keys.firstOrNull()
            ambientArea?.let { a -> v.getLight().getAmbientLightBrightness(a, { b -> b?.let { baseBrightness = it } }, {}) }
        }, { note("앰비언트 제어 미지원") })
        v.getHVAC().checkHvacFanSpeedCapabilities({ cfg, map ->
            Log.i(TAG, "fan: access=${cfg.accessType} $map")
            map.keys.firstOrNull()?.let { a -> fanArea = a; fanMax = map[a]?.maxValue ?: fanMax }
        }, { note("풍량 제어 미지원") })
        v.getHVAC().checkHvacRecirculationCapabilities({ cfg, areas ->
            Log.i(TAG, "recirc: access=${cfg.accessType} $areas")
            recircArea = areas.firstOrNull()
        }, { note("내·외기 제어 미지원") })
        v.getSeat().checkSeatVentilationCapabilities({ cfg, map ->
            Log.i(TAG, "vent: access=${cfg.accessType} $map")
            val passenger = map.keys.firstOrNull { it.values.contains(SeatValue.ROW_1_RIGHT) }
            ventArea = passenger
            passenger?.let { ventMax = map[it]?.maxValue ?: ventMax }
        }, { note("시트 통풍 미지원") })
    }

    private fun fail(what: String, e: Exception) {
        Log.w(TAG, what, e)
        note("$what 실패: ${e.javaClass.simpleName}")
    }

    private fun note(msg: String) {
        val time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))
        _log.update { (listOf("$time  $msg") + it).take(6) }
    }

    private companion object {
        const val TAG = "SteadyRide"
    }
}
