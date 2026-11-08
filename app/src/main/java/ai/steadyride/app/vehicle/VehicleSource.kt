package ai.steadyride.app.vehicle

import kotlinx.coroutines.flow.StateFlow

/**
 * Anything that produces [VehicleState]: the real car (Pleos SDK + Car API + sensors),
 * or a scripted demo drive for presentations.
 */
interface VehicleSource {
    val state: StateFlow<VehicleState>
    fun start()
    fun stop()

    /** Ask the navigation app to re-route with the "comfortable" option. Returns false if unsupported. */
    fun requestComfortableRoute(): Boolean = false
}
