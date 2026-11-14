package ai.deulleo.core.model

/** 위도·경도 좌표. */
data class GeoPoint(val lat: Double, val lng: Double)

/** 하루 중 시각을 자정 기준 분으로 표현합니다. 예: 15:00 → 900 */
typealias MinuteOfDay = Int

fun MinuteOfDay.toClock(): String = "%d:%02d".format(this / 60, this % 60)

/** 가게 주변 주차 후보. */
data class Parking(
    val name: String,
    val walkMinutes: Int,
    val note: String = "",
)

/** 장소(가게·정비소 등). */
data class Place(
    val id: String,
    val name: String,
    val category: String,
    val address: String,
    val location: GeoPoint,
    val opensAt: MinuteOfDay = 0,
    val closesAt: MinuteOfDay = 24 * 60,
    val rating: Double? = null,
    val parking: Parking? = null,
)

enum class ErrandKind {
    /** 시간이 정해진 일정 (예: 3시 미용실 예약) */
    FIXED,

    /** 언제 해도 되는 할 일 (예: 꽃 사기) */
    FLEXIBLE,

    /** 차량 데이터가 만든 관리 할 일 (예: 타이어 공기압 점검) */
    VEHICLE_CARE,
}

/** 사용자의 볼일 하나. */
data class Errand(
    val id: String,
    val title: String,
    val kind: ErrandKind,
    val place: Place,
    val stayMinutes: Int,
    /** FIXED 일정의 시작 시각 */
    val fixedStart: MinuteOfDay? = null,
    /** 생화·냉장식품처럼 차에 오래 두면 안 되는 물건을 사는 볼일 */
    val perishable: Boolean = false,
) {
    init {
        require(kind != ErrandKind.FIXED || fixedStart != null) { "FIXED 일정에는 시작 시각이 필요합니다: $title" }
        require(stayMinutes >= 0)
    }
}

/** 계획 속 정차 지점 하나. */
data class PlannedStop(
    val errand: Errand,
    val arriveAt: MinuteOfDay,
    val departAt: MinuteOfDay,
)

/** 에이전트가 만든 하루 계획. */
data class Plan(
    val startAt: MinuteOfDay,
    val stops: List<PlannedStop>,
    val homeArriveAt: MinuteOfDay,
    val score: Double,
) {
    val nextStop: PlannedStop? get() = stops.firstOrNull()
}
