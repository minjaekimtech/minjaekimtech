package ai.deulleo.app.pleos

import ai.deulleo.core.model.GeoPoint
import ai.deulleo.core.model.Plan
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteWithWaypointsRequest
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** 내비게이션 쪽에서 오는 사건. */
sealed interface NaviEvent {
    data class Arrived(val name: String) : NaviEvent
    data class Error(val code: String) : NaviEvent
}

/**
 * NaviHelper SDK v3.0.5 래퍼.
 *
 * NaviHelper는 목적지 1곳 + 경유지 최대 2곳(WaypointIndex.FIRST/SECOND)을 지원합니다.
 * 그래서 계획의 앞쪽 최대 3곳을 한 구간으로 보내고, 남은 곳은 다음 출발 때 다시 보냅니다.
 */
class PleosNavigator(context: Context) {

    private val navi = NaviHelper(context.applicationContext)
    private val _events = MutableSharedFlow<NaviEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<NaviEvent> = _events.asSharedFlow()

    @Volatile private var connected = false
    private var pending: (() -> Unit)? = null

    private val listener = object : NaviHelperEventListener {
        override fun onWayPointArrived(info: WaypointArrivedInfo) {
            _events.tryEmit(NaviEvent.Arrived(info.name))
        }
        override fun onDestinationArrived(info: DestinationArrivedInfo) {
            _events.tryEmit(NaviEvent.Arrived(info.name))
        }
        override fun onError(errorCode: NaviErrorCode) {
            _events.tryEmit(NaviEvent.Error(errorCode.name))
        }
    }

    fun start() {
        try {
            navi.initialize(
                onConnected = {
                    connected = true
                    navi.addListener(listener)
                    pending?.invoke(); pending = null
                },
                onFailed = { t -> Log.w(TAG, "NaviHelper 연결 실패: ${t.message}") },
            )
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper 초기화 실패: ${e.message}")
        }
    }

    /** 계획의 다음 구간(최대 3곳)을 안내 요청합니다. 남은 볼일이 없으면 집으로 안내합니다. */
    fun guide(plan: Plan, home: GeoPoint) {
        val request: () -> Unit = {
            val upcoming = plan.stops.take(3).map { it.errand.place }
            try {
                if (upcoming.isEmpty()) {
                    navi.requestRoute(routeInfo(home, "집", "home"))
                } else {
                    val dest = upcoming.last()
                    val waypoints = upcoming.dropLast(1).mapIndexed { i, p ->
                        RequestWaypointInfo(
                            latitude = p.location.lat,
                            longitude = p.location.lng,
                            poiName = p.name,
                            waypointIndex = if (i == 0) WaypointIndex.FIRST else WaypointIndex.SECOND,
                            poiId = p.id,
                            address = p.address,
                            poiSubId = "0",
                        )
                    }
                    val destInfo = routeInfo(dest.location, dest.name, dest.id, dest.address)
                    if (waypoints.isEmpty()) navi.requestRoute(destInfo)
                    else navi.requestRouteWithWaypoints(RouteWithWaypointsRequest(destInfo, waypoints))
                }
            } catch (e: Exception) {
                Log.w(TAG, "경로 요청 실패: ${e.message}")
                _events.tryEmit(NaviEvent.Error(e.message ?: "unknown"))
            }
        }
        if (connected) request() else pending = request
    }

    fun stop() {
        try {
            navi.removeListener(listener)
            navi.release()
        } catch (e: Exception) {
            Log.w(TAG, "NaviHelper 해제 실패: ${e.message}")
        }
    }

    private fun routeInfo(at: GeoPoint, name: String, id: String, address: String = "") = RouteInfo(
        latitude = at.lat,
        longitude = at.lng,
        displayLatitude = null,
        displayLongitude = null,
        poiName = name,
        poiId = id,
        address = address,
        poiSubId = "0",
    )

    private companion object { const val TAG = "Deulleo" }
}
