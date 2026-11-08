package ai.steadyride.app.vehicle

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.TBTInfo

/** Maps Pleos navigation data to our own [Maneuver] model (null = not motion-relevant). */
object NaviMapping {

    fun fromTbt(info: TBTInfo): Maneuver? {
        val name = info.type.toString()
        val turn = when {
            name.endsWith("_L") || name.contains("LEFT") || name.endsWith("_L_") -> Turn.LEFT
            name.endsWith("_R") || name.contains("RIGHT") -> Turn.RIGHT
            else -> Turn.NONE
        }
        val kind = when (info.type) {
            TurnType.TURN_SHARP_L, TurnType.TURN_SHARP_R -> ManeuverKind.SHARP_TURN
            TurnType.TURN_UTURN -> ManeuverKind.U_TURN
            TurnType.TURN_KEEP_L, TurnType.TURN_KEEP_R -> ManeuverKind.KEEP
            TurnType.TURN_LEFT, TurnType.TURN_RIGHT -> ManeuverKind.TURN
            else -> when {
                name.contains("ROUNDABOUT") -> ManeuverKind.ROUNDABOUT
                turn != Turn.NONE -> ManeuverKind.TURN
                else -> return null // straight, arrive, toll, tunnel...
            }
        }
        return Maneuver(kind, if (kind == ManeuverKind.U_TURN) Turn.LEFT else turn, info.distance)
    }

    fun fromCaution(point: CautionPoint): Maneuver? {
        val kind = when (point.type) {
            CautionPointType.SPEED_BUMP -> ManeuverKind.SPEED_BUMP
            CautionPointType.DOWNHILL_CAUTION_AREA -> ManeuverKind.DOWNHILL
            else -> return null
        }
        return Maneuver(kind, Turn.NONE, point.remainDistance.toDouble())
    }
}
