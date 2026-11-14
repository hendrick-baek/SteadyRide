package ai.steadyride.app.core

import kotlin.math.*

data class GeoPoint(val latitude: Double, val longitude: Double) {
    val isValid: Boolean get() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun distanceM(other: GeoPoint): Double {
        if (!isValid || !other.isValid) return Double.POSITIVE_INFINITY
        val lat = Math.toRadians(other.latitude - latitude)
        val lon = Math.toRadians(other.longitude - longitude)
        val a = sin(lat / 2).pow(2) + cos(Math.toRadians(latitude)) *
            cos(Math.toRadians(other.latitude)) * sin(lon / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }
}

/** Public campus reference point, not a surveyed vehicle entrance. */
object NamyangScenario {
    // NaviHelper uses its own current location as the origin. Never inject a start position.
    val destination = GeoPoint(37.1552771, 126.823083)
    const val DESTINATION_NAME = "현대자동차 남양연구소"
    const val DESTINATION_ADDRESS = "경기도 화성시 남양읍 현대연구소로 150"
    const val DESTINATION_RADIUS_M = 500.0

    fun isDestination(point: GeoPoint) = destination.distanceM(point) <= DESTINATION_RADIUS_M
}

enum class ScenarioPhase { IDLE, CONNECTING, CHECKING_LOCATION, ROUTING, READY, ERROR }

data class ScenarioUi(
    val phase: ScenarioPhase = ScenarioPhase.IDLE,
    val message: String = "내비의 현재 위치에서 남양연구소까지 실제 경로를 불러와요.",
    val distanceM: Int? = null,
    val durationS: Int? = null,
) {
    val busy get() = phase in setOf(ScenarioPhase.CONNECTING, ScenarioPhase.CHECKING_LOCATION, ScenarioPhase.ROUTING)
}

data class ScenarioRoute(val destination: GeoPoint, val distanceM: Int, val durationS: Int)

/** The adapter must return a confirmed active route, never merely an accepted request. */
interface ScenarioGateway {
    suspend fun currentLocation(): GeoPoint
    suspend fun requestAndConfirmRoute(): ScenarioRoute
}

suspend fun runNamyangScenario(gateway: ScenarioGateway, progress: (ScenarioUi) -> Unit): ScenarioUi {
    progress(ScenarioUi(ScenarioPhase.CHECKING_LOCATION, "내비의 현재 위치를 확인하고 있어요."))
    val location = gateway.currentLocation()
    if (!location.isValid) return ScenarioUi(ScenarioPhase.ERROR, "내비에서 유효한 현재 위치를 받지 못했어요.")
    progress(ScenarioUi(ScenarioPhase.ROUTING, "현재 위치 → 남양연구소 경로를 요청하고 있어요."))
    val route = gateway.requestAndConfirmRoute()
    check(NamyangScenario.isDestination(route.destination) && route.distanceM > 0 && route.durationS > 0) {
        "남양연구소 경로의 거리·시간을 확인하지 못했어요. 내비에서 목적지를 확인해 주세요."
    }
    return ScenarioUi(ScenarioPhase.READY, "남양연구소 경로를 확인했어요. 회전 안내를 멀미 케어에 연결해요.",
        route.distanceM, route.durationS)
}
