package ai.deulleo.core.planner

import ai.deulleo.core.model.Errand
import ai.deulleo.core.model.ErrandKind
import ai.deulleo.core.model.GeoPoint
import ai.deulleo.core.model.MinuteOfDay
import ai.deulleo.core.model.Plan
import ai.deulleo.core.model.PlannedStop
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 두 지점 사이 이동 시간(분)을 추정합니다. 실제 서비스에서는 NaviHelper/경로 API 결과로 교체합니다. */
fun interface TravelTimeEstimator {
    fun minutes(from: GeoPoint, to: GeoPoint): Int
}

/** 직선거리 × 도로 우회 계수 ÷ 평균 속도 + 주차·하차 여유 시간. 프로토타입용 추정기입니다. */
class HaversineEstimator(
    private val avgKmh: Double = 25.0,
    private val detourFactor: Double = 1.3,
    private val parkingOverheadMin: Int = 4,
) : TravelTimeEstimator {
    override fun minutes(from: GeoPoint, to: GeoPoint): Int {
        if (from == to) return 0
        val km = haversineKm(from, to) * detourFactor
        return (km / avgKmh * 60).roundToInt() + parkingOverheadMin
    }

    companion object {
        fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLng = Math.toRadians(b.lng - a.lng)
            val h = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLng / 2).let { it * it }
            return 2 * r * asin(sqrt(h))
        }
    }
}

/** 점수 가중치. 값이 작을수록 좋은 계획입니다. */
data class PlannerWeights(
    val perTravelMinute: Double = 1.0,
    val perWaitMinute: Double = 0.5,
    /** 신선식품이 차 안에 머무는 1분당 벌점 */
    val perPerishableMinute: Double = 0.8,
    /** 목표 귀가 시각을 넘긴 1분당 벌점 */
    val perLateMinute: Double = 5.0,
)

/**
 * 볼일 순서 최적화기.
 *
 * 하루 볼일은 보통 6개 이하라서 가능한 순서를 모두 시뮬레이션해도 충분히 빠릅니다(6! = 720).
 * 제약: FIXED 일정은 정해진 시각에 도착해야 하고(허용 오차 [fixedToleranceMin]),
 * 모든 볼일은 영업시간 안에 끝나야 합니다. 신선식품은 귀가까지 차에 머무는 시간만큼 벌점을 받습니다.
 */
class RoutePlanner(
    private val travel: TravelTimeEstimator = HaversineEstimator(),
    private val weights: PlannerWeights = PlannerWeights(),
    private val fixedToleranceMin: Int = 5,
    private val maxErrands: Int = 7,
) {
    fun plan(
        start: GeoPoint,
        startAt: MinuteOfDay,
        home: GeoPoint,
        errands: List<Errand>,
        homeBy: MinuteOfDay? = null,
    ): Plan? {
        require(errands.size <= maxErrands) { "볼일이 너무 많습니다(${errands.size}). 최대 $maxErrands 개까지 계획합니다." }
        if (errands.isEmpty()) {
            val arrive = startAt + travel.minutes(start, home)
            return Plan(startAt, emptyList(), arrive, 0.0)
        }
        var best: Plan? = null
        permutations(errands) { order ->
            val candidate = simulate(start, startAt, home, order, homeBy)
            if (candidate != null && (best == null || candidate.score < best!!.score)) best = candidate
        }
        return best
    }

    private fun simulate(
        start: GeoPoint,
        startAt: MinuteOfDay,
        home: GeoPoint,
        order: List<Errand>,
        homeBy: MinuteOfDay?,
    ): Plan? {
        var now = startAt
        var here = start
        var travelTotal = 0
        var waitTotal = 0
        val stops = ArrayList<PlannedStop>(order.size)

        for (errand in order) {
            val leg = travel.minutes(here, errand.place.location)
            travelTotal += leg
            val arrive = now + leg
            val begin = when (errand.kind) {
                ErrandKind.FIXED -> {
                    val fixed = errand.fixedStart!!
                    if (arrive > fixed + fixedToleranceMin) return null // 예약에 늦음
                    max(arrive, fixed)
                }
                else -> max(arrive, errand.place.opensAt)
            }
            val end = begin + errand.stayMinutes
            if (end > errand.place.closesAt) return null // 영업 종료 전에 못 끝냄
            waitTotal += begin - arrive
            stops += PlannedStop(errand, arrive, end)
            now = end
            here = errand.place.location
        }

        val lastLeg = travel.minutes(here, home)
        travelTotal += lastLeg
        val homeArrive = now + lastLeg

        val perishableMinutes = stops.filter { it.errand.perishable }.sumOf { homeArrive - it.departAt }
        val late = if (homeBy != null) max(0, homeArrive - homeBy) else 0
        val score = travelTotal * weights.perTravelMinute +
            waitTotal * weights.perWaitMinute +
            perishableMinutes * weights.perPerishableMinute +
            late * weights.perLateMinute
        return Plan(startAt, stops, homeArrive, score)
    }

    private fun <T> permutations(items: List<T>, visit: (List<T>) -> Unit) {
        val arr = items.toMutableList()
        fun go(k: Int) {
            if (k == arr.size) { visit(arr.toList()); return }
            for (i in k until arr.size) {
                arr[k] = arr[i].also { arr[i] = arr[k] }
                go(k + 1)
                arr[k] = arr[i].also { arr[i] = arr[k] }
            }
        }
        go(0)
    }
}
