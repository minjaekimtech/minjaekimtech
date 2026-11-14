package ai.deulleo.app.pleos

import ai.deulleo.core.model.GeoPoint
import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.listener.ProximityAlertListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.location.fused.model.ProximityAlertEvent
import ai.pleos.playground.location.fused.model.ProximityAlertRequest
import ai.pleos.playground.location.fused.model.ProximityTransition
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * FusedLocation SDK v1.1.3 래퍼: 현재 위치와, 볼일 장소 반경 진입·이탈(근접 알림)을 제공합니다.
 * pleos.car.permission.FUSED_LOCATION 은 dangerous 권한이라 런타임 허용이 필요합니다.
 */
class PleosLocation(context: Context) {

    private val fused = FusedLocation(context.applicationContext)

    private val _location = MutableStateFlow<GeoPoint?>(null)
    val location: StateFlow<GeoPoint?> = _location.asStateFlow()

    /** (alertId = 장소 id, 진입이면 true) */
    private val _proximity = MutableSharedFlow<Pair<String, Boolean>>(extraBufferCapacity = 8)
    val proximity: SharedFlow<Pair<String, Boolean>> = _proximity.asSharedFlow()

    private val alertIdToPlace = mutableMapOf<String, String>()

    private val listener = object : FusedLocationListener {
        override fun onChanged(data: LocationData?) {
            data?.let { _location.value = GeoPoint(it.latitude, it.longitude) }
        }
        override fun onError(e: Exception) { Log.w(TAG, "위치 오류: ${e.message}") }
    }

    fun start() {
        try {
            fused.initialize()
            if (!fused.isSupported()) { Log.w(TAG, "FusedLocation 미지원 환경"); return }
            fused.registerFusedLocationCallback(listener)
            fused.getLastKnownLocation(listener)
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation 시작 실패: ${e.message}")
        }
    }

    /** 볼일 장소 반경 [radiusM] 진입·이탈 알림 등록 */
    fun watchPlace(placeId: String, at: GeoPoint, radiusM: Float = 150f) {
        try {
            val req = ProximityAlertRequest(at.lat, at.lng, radiusM, setOf(ProximityTransition.ENTER, ProximityTransition.EXIT))
            fused.addProximityAlert(req, object : ProximityAlertListener {
                override fun onRegistered(alertId: String) { alertIdToPlace[alertId] = placeId }
                override fun onTransition(event: ProximityAlertEvent) {
                    val place = alertIdToPlace[event.alertId] ?: return
                    _proximity.tryEmit(place to (event.transition == ProximityTransition.ENTER))
                }
                override fun onError(e: Exception) { Log.w(TAG, "근접 알림 오류: ${e.message}") }
            })
        } catch (e: Exception) {
            Log.w(TAG, "근접 알림 등록 실패: ${e.message}")
        }
    }

    fun clearWatches() {
        alertIdToPlace.keys.toList().forEach { runCatching { fused.removeProximityAlert(it) } }
        alertIdToPlace.clear()
    }

    fun stop() {
        try {
            clearWatches()
            fused.unregisterFusedLocationCallback(listener)
            fused.release()
        } catch (e: Exception) {
            Log.w(TAG, "FusedLocation 해제 실패: ${e.message}")
        }
    }

    private companion object { const val TAG = "Deulleo" }
}
