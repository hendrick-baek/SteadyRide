import ai.steadyride.app.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

/** SDK-independent checks, run with the project's Kotlin/coroutines classpath. */
fun main() = runBlocking {
    class FakeGateway(
        val location: GeoPoint,
        val route: ScenarioRoute = ScenarioRoute(NamyangScenario.destination, 55_000, 4_200),
        val fail: Boolean = false,
    ) : ScenarioGateway {
        var requests = 0
        override suspend fun currentLocation() = location
        override suspend fun requestAndConfirmRoute(): ScenarioRoute {
            requests++
            if (fail) throw CancellationException("Stopped")
            return route
        }
    }
    val phases = mutableListOf<ScenarioPhase>()
    // This is the location returned by Pleos even when Android GPS was moved elsewhere.
    val pleosLocation = GeoPoint(37.4127212, 127.094681)
    val formerDestination = GeoPoint(37.46441, 127.04273)
    val good = FakeGateway(pleosLocation)
    val result = runNamyangScenario(good) { phases += it.phase }
    check(result.phase == ScenarioPhase.READY && result.distanceM == 55_000 && result.durationS == 4_200)
    check(good.requests == 1 && phases == listOf(ScenarioPhase.CHECKING_LOCATION, ScenarioPhase.ROUTING))

    // No geographic gate on the start: the navigation provider decides route availability.
    for (start in listOf(formerDestination, GeoPoint(37.421998, -122.084))) {
        val anywhere = FakeGateway(start)
        check(runNamyangScenario(anywhere) {}.phase == ScenarioPhase.READY)
        check(anywhere.requests == 1)
    }
    for (point in listOf(GeoPoint(Double.NaN, 127.0), GeoPoint(91.0, 127.0), GeoPoint(37.0, Double.POSITIVE_INFINITY))) {
        val invalid = FakeGateway(point)
        check(runNamyangScenario(invalid) {}.phase == ScenarioPhase.ERROR && invalid.requests == 0)
    }
    check(NamyangScenario.isDestination(GeoPoint(37.1553, 126.8231)))
    check(!NamyangScenario.isDestination(GeoPoint(37.421998, -122.084)))

    for (bad in listOf(
        ScenarioRoute(formerDestination, 55_000, 4_200),
        ScenarioRoute(NamyangScenario.destination, 0, 4_200),
        ScenarioRoute(NamyangScenario.destination, 55_000, -1),
    )) {
        check(runCatching { runNamyangScenario(FakeGateway(pleosLocation, bad)) {} }
            .exceptionOrNull() is IllegalStateException)
    }
    check(runCatching { runNamyangScenario(FakeGateway(pleosLocation, fail = true)) {} }
        .exceptionOrNull() is CancellationException)
    check(ScenarioUi(ScenarioPhase.ROUTING).busy && !ScenarioUi(ScenarioPhase.ERROR).busy)
    println("PASS: Pleos current location, unrestricted origin, invalid GPS, Namyang destination, old destination rejected, invalid ETA/distance, cancellation, busy state")
}
