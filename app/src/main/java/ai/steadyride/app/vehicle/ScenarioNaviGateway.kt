package ai.steadyride.app.vehicle

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.steadyride.app.core.GeoPoint
import ai.steadyride.app.core.NamyangScenario
import ai.steadyride.app.core.ScenarioGateway
import ai.steadyride.app.core.ScenarioRoute
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

/** All operations run on Main; callbacks are dispatched there as well. One instance per request. */
internal class ScenarioNaviGateway(private val navi: NaviHelper) : ScenarioGateway {
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    private var requestingRoute = false
    private var location = CompletableDeferred<GeoPoint>()
    private var destination = CompletableDeferred<DestinationInfo>()
    private var routeState = CompletableDeferred<RouteStateInfo>()
    private var failure: String? = null

    private fun dispatch(block: () -> Unit) {
        main.post { if (!closed) block() }
    }

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(info: CurrentLocationInfo) = dispatch {
            Log.i(TAG, "scenario: location ${info.latitude},${info.longitude}")
            location.complete(GeoPoint(info.latitude, info.longitude))
        }
        override fun onDestinationInfo(info: DestinationInfo) = dispatch {
            Log.i(TAG, "scenario: destination ${info.name} ${info.latitude},${info.longitude} ${info.remainDistance}m ${info.remainTime}s")
            destination.complete(info)
        }
        override fun onRouteStateInfo(info: RouteStateInfo) = dispatch {
            Log.i(TAG, "scenario: routeState routing=${info.isRouting} option=${info.routeOption}")
            routeState.complete(info)
        }
        override fun onError(code: NaviErrorCode) = dispatch {
            Log.i(TAG, "scenario: navi error $code")
            if (requestingRoute && code in setOf(NaviErrorCode.CAUSE_SEARCH_ROUTE,
                    NaviErrorCode.CAUSE_SEARCH_ROUTE_OVER_DISTANCE,
                    NaviErrorCode.CAUSE_SEARCH_ROUTE_TOO_SHORT_DISTANCE)) {
                failure = "내비 경로 탐색에 실패했어요 ($code). 내비의 인터넷 연결과 목적지를 확인해 주세요."
                val error = IllegalStateException(failure)
                destination.completeExceptionally(error)
                routeState.completeExceptionally(error)
            }
        }
        // Replacing an existing route may emit cancellation for the OLD route.
        // Confirm the destination/state below rather than failing that replacement.
    }

    init { navi.addListener(listener) }

    override suspend fun currentLocation(): GeoPoint = withTimeout(5_000) {
        location = CompletableDeferred()
        navi.getCurrentLocationInfo()
        location.await()
    }

    // A cold route search on Pleos can finish after 25 seconds. Allow it to confirm
    // the actual destination without prematurely reporting a failed route.
    override suspend fun requestAndConfirmRoute(): ScenarioRoute = withTimeout(45_000) {
        requestingRoute = true
        navi.requestRoute(RouteInfo(
            latitude = NamyangScenario.destination.latitude,
            longitude = NamyangScenario.destination.longitude,
            poiName = NamyangScenario.DESTINATION_NAME,
            address = NamyangScenario.DESTINATION_ADDRESS,
            poiId = "", poiSubId = "",
            routeOption = RouteOption.RECOMMENDED,
        ))
        // Provider callbacks vary. Confirm routing state AND the returned destination,
        // instead of treating requestRoute()/onRouteStarted() as successful guidance.
        while (true) {
            failure?.let { error(it) }
            routeState = CompletableDeferred()
            navi.getRouteStateInfo()
            val state = routeState.await()
            // isRouting = route search still running; the route is usable once it is false.
            if (!state.isRouting) {
                destination = CompletableDeferred()
                navi.getDestinationInfo()
                val dest = destination.await()
                failure?.let { error(it) }
                val point = GeoPoint(dest.latitude, dest.longitude)
                if (NamyangScenario.isDestination(point) && dest.remainDistance > 0 && dest.remainTime > 0) {
                    navi.getTBTInfo()
                    return@withTimeout ScenarioRoute(point, dest.remainDistance, dest.remainTime)
                }
            }
            delay(500)
        }
        @Suppress("UNREACHABLE_CODE")
        error("No route")
    }

    private companion object {
        const val TAG = "SteadyRide"
    }

    fun close() {
        closed = true
        navi.removeListener(listener)
        location.cancel()
        destination.cancel()
        routeState.cancel()
    }
}
