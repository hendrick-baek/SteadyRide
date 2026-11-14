package ai.steadyride.app.core

import ai.steadyride.app.vehicle.ManeuverKind
import ai.steadyride.app.vehicle.Turn
import ai.steadyride.app.vehicle.VehicleState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tan

enum class Phase(val label: String) { PARKED("주차"), STOPPED("정차"), DRIVING("주행") }

enum class RiskLevel(val label: String) { CALM("편안"), WATCH("주의"), HIGH("높음") }

enum class CueSource(val label: String) { NAVI("내비 예고"), STEERING("조향 감지"), BRAKE("제동 감지") }

/** A motion the passenger should be told about, [secondsAhead] before it happens (0 = now). */
data class Cue(
    val turn: Turn,
    val kind: ManeuverKind?,
    val secondsAhead: Float,
    val source: CueSource,
) {
    val text: String
        get() {
            val what = when {
                kind == ManeuverKind.SPEED_BUMP -> "과속방지턱"
                kind == ManeuverKind.DOWNHILL -> "내리막"
                source == CueSource.BRAKE -> "감속"
                turn == Turn.LEFT -> "왼쪽으로 회전"
                turn == Turn.RIGHT -> "오른쪽으로 회전"
                else -> "움직임"
            }
            return if (secondsAhead >= 0.5f) "%.0f초 뒤 %s".format(secondsAhead, what) else "지금 $what"
        }
}

data class ComfortState(
    val phase: Phase = Phase.PARKED,
    val lateralG: Float = 0f,
    val longitudinalG: Float = 0f,
    val risk: Float = 0f,              // 0..100 motion-sickness risk for the passenger
    val level: RiskLevel = RiskLevel.CALM,
    val cue: Cue? = null,
    val harshEvents: Int = 0,          // for the stop-time driver report
    val lastHarshEvent: String? = null,
) {
    val riskInt: Int get() = risk.toInt()
}

/**
 * Pure, vehicle-independent logic: turns raw signals into
 * "what is about to happen" (cue) and "how sick is the passenger likely getting" (risk).
 *
 * Risk is a simplified motion-dose model inspired by MSDV (ISO 2631-1):
 * squared horizontal acceleration is integrated over time with slow recovery,
 * then mapped to 0..100.
 */
class ComfortEngine(
    private val wheelbaseM: Float = 2.9f,
    private val steeringRatio: Float = 14f,
) {
    private var state = ComfortState()
    private var lastSpeed: Float? = null
    private var dose = 0f
    private var longFiltered = 0f
    private var harshCooldownS = 0f

    fun update(v: VehicleState, dtS: Float): ComfortState {
        val speed = v.speedMps ?: 0f
        val phase = when {
            v.isParked -> Phase.PARKED
            speed < 0.5f -> Phase.STOPPED
            else -> Phase.DRIVING
        }

        // Lateral acceleration from a kinematic bicycle model (works without an IMU).
        val wheelRad = Math.toRadians((v.steeringDeg ?: 0f).toDouble() / steeringRatio)
        val latMs2 = (speed * speed * tan(wheelRad) / wheelbaseM).toFloat()

        // Longitudinal acceleration from the speed derivative, low-pass filtered.
        val rawLong = lastSpeed?.let { (speed - it) / max(dtS, 0.01f) } ?: 0f
        lastSpeed = speed
        longFiltered += (rawLong - longFiltered) * (dtS / (dtS + 0.4f))

        // Motion dose: accumulate while moving, recover slowly (time constant ~3 min).
        val a2 = latMs2 * latMs2 + 1.4f * longFiltered * longFiltered
        dose = dose * exp(-dtS / RECOVERY_S) + a2 * dtS
        val risk = (100f * (1f - exp(-dose / DOSE_SCALE))).coerceIn(0f, 100f)
        val level = when {
            risk >= 65f -> RiskLevel.HIGH
            risk >= 35f -> RiskLevel.WATCH
            else -> RiskLevel.CALM
        }

        // Harsh events for the driver report (counted at most once per 2 s).
        harshCooldownS = max(0f, harshCooldownS - dtS)
        var harsh = state.harshEvents
        var harshText = state.lastHarshEvent
        if (harshCooldownS == 0f && phase == Phase.DRIVING) {
            val event = when {
                abs(latMs2) > HARSH_MS2 -> "급조향 (%.2f G)".format(abs(latMs2) / G)
                longFiltered < -HARSH_MS2 -> "급제동 (%.2f G)".format(-longFiltered / G)
                longFiltered > HARSH_MS2 -> "급가속 (%.2f G)".format(longFiltered / G)
                else -> null
            }
            if (event != null) {
                harsh += 1
                harshText = event
                harshCooldownS = 2f
            }
        }

        state = ComfortState(
            phase = phase,
            lateralG = latMs2 / G,
            longitudinalG = longFiltered / G,
            risk = risk,
            level = level,
            cue = predictCue(v, speed, phase),
            harshEvents = harsh,
            lastHarshEvent = harshText,
        )
        return state
    }

    /** Navigation preview first (seconds ahead), then live steering/brake (now). */
    private fun predictCue(v: VehicleState, speed: Float, phase: Phase): Cue? {
        if (phase != Phase.DRIVING) return null

        val ahead = v.maneuvers
            .map { it to (it.distanceM / max(speed, 1f)).toFloat() }
            .filter { (_, t) -> t <= CUE_HORIZON_S }
            .minByOrNull { (_, t) -> t }
        if (ahead != null) {
            val (m, t) = ahead
            return Cue(m.turn, m.kind, t, CueSource.NAVI)
        }

        val steer = v.steeringDeg ?: 0f
        if (abs(steer) > STEER_CUE_DEG && speed > 2f) {
            return Cue(if (steer < 0) Turn.LEFT else Turn.RIGHT, null, 0f, CueSource.STEERING)
        }
        if (v.brakePressed == true && speed > 3f) {
            // Braking for a maneuver the route already knows about: announce that maneuver
            // instead of a generic "slowing down", so the passenger gets one clear cue.
            val target = v.maneuvers
                .map { it to (it.distanceM / max(speed, 1f)).toFloat() }
                .filter { (_, t) -> t <= BRAKE_LINK_S }
                .minByOrNull { (_, t) -> t }
            if (target != null) return Cue(target.first.turn, target.first.kind, target.second, CueSource.NAVI)
            return Cue(Turn.NONE, null, 0f, CueSource.BRAKE)
        }
        return null
    }

    fun resetTrip() {
        dose = 0f
        state = state.copy(risk = 0f, level = RiskLevel.CALM, harshEvents = 0, lastHarshEvent = null)
    }

    private companion object {
        const val G = 9.81f
        const val RECOVERY_S = 180f
        const val DOSE_SCALE = 120f      // (m/s^2)^2 * s needed to reach ~63 risk
        const val HARSH_MS2 = 3.0f       // ~0.3 G
        const val CUE_HORIZON_S = 4f     // announce maneuvers up to 4 s ahead
        const val STEER_CUE_DEG = 25f
        const val BRAKE_LINK_S = 10f     // braking this close to a known maneuver belongs to it
    }
}
