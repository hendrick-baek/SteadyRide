package ai.steadyride.app.vehicle

import ai.steadyride.app.core.RouteCandidate
import kotlinx.coroutines.flow.StateFlow

/**
 * Anything that produces [VehicleState]: the real car (Pleos SDK + Car API + sensors),
 * or a scripted demo drive for presentations.
 */
interface VehicleSource {
    val state: StateFlow<VehicleState>
    fun start()
    fun stop()

    /**
     * Asks the navigation for a route per route option and describes each one
     * (arrival time, turns, bumps). Empty when no destination is set or unsupported.
     */
    suspend fun searchRoutes(onProgress: (done: Int, total: Int) -> Unit): List<RouteCandidate> = emptyList()

    /** Makes [option] (a RouteOption name) the active route. */
    suspend fun applyRouteOption(option: String): Boolean = false
}

/** Korean names of every navigation route option (the navigation may use any of them). */
val ROUTE_OPTION_LABELS = mapOf(
    "RECOMMENDED" to "추천 경로",
    "SHORT_DISTANCE" to "최단 거리",
    "SHORT_TIME" to "최소 시간",
    "FREE" to "무료도로 우선",
    "HIGHWAY" to "고속도로 우선",
    "COMFORTABLE" to "편안한 경로",
    "SCHOOL_ZONE" to "어린이 보호구역 회피",
)

/** Route options compared when looking for the least sickening route. */
val ROUTE_OPTIONS = listOf(
    "RECOMMENDED" to "추천 경로",
    "SHORT_TIME" to "최소 시간",
    "COMFORTABLE" to "편안한 경로",
    "HIGHWAY" to "고속도로 우선",
)
