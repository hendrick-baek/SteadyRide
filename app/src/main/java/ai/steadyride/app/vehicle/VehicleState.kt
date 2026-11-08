package ai.steadyride.app.vehicle

/** Seats we care about for passenger detection (left-hand-drive layout). */
enum class SeatPos(val label: String) {
    FRONT_PASSENGER("조수석"),
    REAR_LEFT("뒷좌석 좌"),
    REAR_RIGHT("뒷좌석 우"),
}

enum class Turn { LEFT, RIGHT, NONE }

/** What kind of upcoming motion the navigation route announces. */
enum class ManeuverKind(val label: String) {
    TURN("회전"),
    SHARP_TURN("급회전"),
    U_TURN("유턴"),
    KEEP("차선 분기"),
    ROUNDABOUT("회전교차로"),
    SPEED_BUMP("과속방지턱"),
    DOWNHILL("내리막"),
}

/** One upcoming event on the navigation route, [distanceM] ahead of the car. */
data class Maneuver(
    val kind: ManeuverKind,
    val turn: Turn,
    val distanceM: Double,
)

/**
 * One snapshot of everything the app knows about the vehicle.
 * Null means "not received yet" (or not available on this car).
 */
data class VehicleState(
    val speedMps: Float? = null,          // AAOS Car API: PERF_VEHICLE_SPEED
    val steeringDeg: Float? = null,       // Pleos SteeringWheel (negative = left)
    val brakePressed: Boolean? = null,    // Pleos Brake
    val gear: String? = null,             // Pleos Gear
    val occupied: Map<SeatPos, Boolean> = emptyMap(), // Pleos Seat
    val accel: Accel? = null,             // Android SensorManager (optional input)
    val maneuvers: List<Maneuver> = emptyList(), // Pleos NaviHelper TBT + caution points
    val drivingRestricted: Boolean = false, // AAOS CarUxRestrictions requires DO
    val errors: List<String> = emptyList(),
) {
    val speedKmh: Float? get() = speedMps?.times(3.6f)
    val hasPassenger: Boolean get() = occupied.values.any { it }
    val isParked: Boolean get() = gear?.contains("PARK") == true
}

data class Accel(val x: Float, val y: Float, val z: Float)
