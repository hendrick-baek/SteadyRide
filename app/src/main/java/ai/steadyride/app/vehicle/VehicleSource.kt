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

/** Route options compared when looking for the least sickening route. */
val ROUTE_OPTIONS = listOf(
    "RECOMMENDED" to "추천 경로",
    "SHORT_TIME" to "최소 시간",
    "COMFORTABLE" to "편안한 경로",
    "HIGHWAY" to "고속도로 우선",
)
