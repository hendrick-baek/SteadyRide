package ai.steadyride.app.core

import ai.steadyride.app.vehicle.Maneuver
import ai.steadyride.app.vehicle.ManeuverKind
import kotlin.math.roundToInt

/** One route the navigation produced for a given route option. */
data class RouteCandidate(
    val option: String,      // RouteOption name, e.g. "COMFORTABLE"
    val label: String,       // "편안한 경로"
    val etaS: Int,           // remaining time to destination
    val distanceM: Int,
    val maneuvers: List<Maneuver>,
) {
    private fun count(vararg k: ManeuverKind) = maneuvers.count { it.kind in k }
    val turns get() = count(ManeuverKind.TURN, ManeuverKind.KEEP)
    val sharpTurns get() = count(ManeuverKind.SHARP_TURN, ManeuverKind.ROUNDABOUT)
    val uTurns get() = count(ManeuverKind.U_TURN)
    val bumps get() = count(ManeuverKind.SPEED_BUMP)
    val downhills get() = count(ManeuverKind.DOWNHILL)

    /**
     * Expected motion-sickness load of the route. Lateral motion from turns dominates car
     * sickness (Turner & Griffin 1999), sharper turns and U-turns more so; bumps and
     * downhills add braking and vertical jolts.
     */
    val sicknessIndex: Int
        get() = (turns * 3f + sharpTurns * 7f + uTurns * 9f + bumps * 4f + downhills * 3f).roundToInt()
}

data class RoutePlan(
    val candidates: List<RouteCandidate>,
    val chosen: RouteCandidate?,
    val baselineEtaS: Int,
    val toleranceMin: Int,
) {
    fun delayMin(c: RouteCandidate) = ((c.etaS - baselineEtaS) / 60f).roundToInt()
    fun withinTolerance(c: RouteCandidate) = c.etaS - baselineEtaS <= toleranceMin * 60
}

/**
 * Picks the route with the lowest motion-sickness load among those whose arrival is no
 * more than [toleranceMin] minutes later than the fastest candidate. Ties go to the
 * earlier arrival.
 */
object RoutePlanner {
    fun choose(candidates: List<RouteCandidate>, toleranceMin: Int): RoutePlan {
        if (candidates.isEmpty()) return RoutePlan(emptyList(), null, 0, toleranceMin)
        val baseline = candidates.minOf { it.etaS }
        val allowed = candidates.filter { it.etaS - baseline <= toleranceMin * 60 }
        val best = allowed.minWithOrNull(compareBy<RouteCandidate> { it.sicknessIndex }.thenBy { it.etaS })
        return RoutePlan(candidates.sortedBy { it.etaS }, best, baseline, toleranceMin)
    }
}
