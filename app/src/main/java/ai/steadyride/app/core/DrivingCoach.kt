package ai.steadyride.app.core

import ai.steadyride.app.vehicle.VehicleState
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

/** What the driver can do differently, most important first when several apply. */
enum class CoachTopic { HARSH_BRAKE, PEDAL_SWITCHING, HARSH_START, FAST_CORNER, SMOOTH, LEARNING }

data class CoachTip(
    val topic: CoachTopic,
    val title: String,
    val body: String,
    val evidence: String? = null, // e.g. "최근 3분 급제동 4회"
)

/**
 * Watches how the car is driven and turns it into one short, personal tip for the driver.
 *
 * Signals (from speed and steering only, so it works without an IMU):
 *  - harsh braking: deceleration beyond ~0.25 G, or a sudden onset (high jerk) of braking
 *  - harsh start:   acceleration beyond ~0.2 G
 *  - pedal switching: accelerate <-> brake reversals within a few seconds (stop-and-go "pumping")
 *  - fast cornering: lateral acceleration beyond ~0.25 G
 *
 * Counts decay with a ~3 minute time constant, so the tip reflects recent driving and
 * improves on its own when the driver changes habits. A tip is held for at least
 * [HOLD_S] so the text on screen never flickers.
 */
class DrivingCoach {

    private var harshBrake = 0f
    private var harshStart = 0f
    private var switching = 0f
    private var fastCorner = 0f
    private var drivenS = 0f

    private var lastLong = 0f
    private var phase = 0             // -1 braking, +1 accelerating, 0 neither
    private var phaseFlipAgoS = 99f
    // Each hard brake/start/corner counts once: re-armed only after the signal eases off.
    private var brakeArmed = true
    private var startArmed = true
    private var cornerArmed = true

    private var shown: CoachTip = learning()
    private var shownForS = 0f

    fun update(v: VehicleState, c: ComfortState, dtS: Float): CoachTip {
        val decay = exp(-dtS / MEMORY_S)
        harshBrake *= decay; harshStart *= decay; switching *= decay; fastCorner *= decay
        phaseFlipAgoS += dtS

        if (c.phase == Phase.DRIVING) {
            drivenS += dtS
            val long = c.longitudinalG * G
            val lat = c.lateralG * G
            val jerk = (long - lastLong) / max(dtS, 0.01f)

            // Harsh braking: hard, or applied abruptly.
            if (brakeArmed && (long < -BRAKE_MS2 || (long < -1.0f && jerk < -JERK_MS3))) {
                harshBrake += 1f
                brakeArmed = false
            } else if (long > -0.5f) brakeArmed = true

            if (startArmed && long > START_MS2) {
                harshStart += 1f
                startArmed = false
            } else if (long < 0.5f) startArmed = true

            if (cornerArmed && abs(lat) > CORNER_MS2) {
                fastCorner += 1f
                cornerArmed = false
            } else if (abs(lat) < 1.0f) cornerArmed = true

            // Accelerate <-> brake reversals close together.
            val now = when {
                long > 0.6f -> 1
                long < -0.6f -> -1
                else -> 0
            }
            if (now != 0 && now != phase) {
                if (phase != 0 && phaseFlipAgoS < SWITCH_WINDOW_S) switching += 1f
                phase = now
                phaseFlipAgoS = 0f
            }
            lastLong = long
        }

        val next = choose(c)
        shownForS += dtS
        if (next.topic != shown.topic && shownForS >= HOLD_S) {
            shown = next
            shownForS = 0f
        } else if (next.topic == shown.topic) {
            shown = next // keep the counts in the evidence line fresh
        }
        return shown
    }

    private fun choose(c: ComfortState): CoachTip {
        if (drivenS < 20f) return learning()
        val n = { x: Float -> (x + 0.5f).toInt() }
        // Pick the habit that is furthest past its own threshold.
        val ratios = mapOf(
            CoachTopic.HARSH_BRAKE to harshBrake / 2f,
            CoachTopic.PEDAL_SWITCHING to switching / 3f,
            CoachTopic.HARSH_START to harshStart / 2f,
            CoachTopic.FAST_CORNER to fastCorner / 2f,
        )
        val worst = ratios.maxBy { it.value }.takeIf { it.value >= 1f }?.key
        return when (worst) {
            CoachTopic.HARSH_BRAKE -> CoachTip(
                CoachTopic.HARSH_BRAKE,
                "브레이크를 조금 더 부드럽게 밟아 주세요",
                "정지 2~3초 전부터 나눠서 밟으면 동승자의 몸이 앞으로 덜 쏠려요.",
                "최근 급제동 ${n(harshBrake)}회",
            )
            CoachTopic.PEDAL_SWITCHING -> CoachTip(
                CoachTopic.PEDAL_SWITCHING,
                "가속과 제동을 자주 번갈아 하고 있어요",
                "앞차와 거리를 넉넉히 두고, 브레이크 대신 가속 페달에서 발을 떼 속도를 맞춰 보세요.",
                "최근 페달 전환 ${n(switching)}회",
            )
            CoachTopic.HARSH_START -> CoachTip(
                CoachTopic.HARSH_START,
                "출발을 조금 더 천천히 해 주세요",
                "처음 2초는 가볍게 밟고, 속도가 붙은 뒤에 더 밟으면 뒷좌석이 편해져요.",
                "최근 급가속 ${n(harshStart)}회",
            )
            CoachTopic.FAST_CORNER -> CoachTip(
                CoachTopic.FAST_CORNER,
                "커브 전에 미리 속도를 줄여 주세요",
                "회전 중에는 가속을 줄이고 진입 전에 감속하면 좌우 쏠림이 크게 줄어요.",
                "최근 빠른 회전 ${n(fastCorner)}회",
            )
            else -> CoachTip(
                CoachTopic.SMOOTH,
                "부드럽게 운전하고 계세요",
                "지금처럼 일정한 속도를 유지하면 동승자가 편안하게 갈 수 있어요.",
                "동승자 멀미 위험 ${c.riskInt}",
            )
        }
    }

    private fun learning() = CoachTip(CoachTopic.LEARNING, "운전 패턴을 살펴보는 중이에요", "조금만 더 달리면 맞춤 조언을 드릴게요.")

    fun reset() {
        harshBrake = 0f; harshStart = 0f; switching = 0f; fastCorner = 0f; drivenS = 0f
        phase = 0; phaseFlipAgoS = 99f
        brakeArmed = true; startArmed = true; cornerArmed = true
        shown = learning(); shownForS = 0f
    }

    private companion object {
        const val G = 9.81f
        const val MEMORY_S = 180f
        const val BRAKE_MS2 = 2.5f
        const val JERK_MS3 = 6f
        const val START_MS2 = 2.0f
        const val CORNER_MS2 = 2.5f
        const val SWITCH_WINDOW_S = 4f
        const val HOLD_S = 12f
    }
}
