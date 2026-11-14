package ai.deulleo.core.planner

import ai.deulleo.core.model.GeoPoint
import ai.deulleo.core.model.Parking
import ai.deulleo.core.model.Place

/** 장소 검색. 서비스 단계에서는 카카오 로컬 API(키워드·카테고리, 주차장 PK6)로 구현합니다. */
interface PlaceCatalog {
    /** [near] 주변에서 [query](품목 또는 업종)에 맞는 장소를 가까운 순으로 돌려줍니다. */
    fun search(query: String, near: GeoPoint, limit: Int = 5): List<Place>
}

/** 품목 → 업종 매핑. "케이크" → "빵집". */
object ItemCategories {
    private val map = linkedMapOf(
        "꽃" to "꽃집", "꽃다발" to "꽃집", "화분" to "꽃집",
        "케이크" to "빵집", "빵" to "빵집", "케익" to "빵집",
        "커피" to "카페", "카페" to "카페",
        "약" to "약국", "세탁물" to "세탁소", "세탁" to "세탁소",
        "장" to "마트", "장보기" to "마트", "우유" to "마트", "과일" to "마트",
        "기름" to "주유소", "주유" to "주유소", "충전" to "전기차충전소",
        "타이어" to "정비소", "정비" to "정비소",
    )

    fun categoryOf(item: String): String =
        map.entries.firstOrNull { item.contains(it.key) }?.value ?: item

    /** 차에 오래 두면 안 되는 물건인지(꽃, 케이크, 냉장식품 등) */
    fun isPerishable(item: String): Boolean =
        listOf("꽃", "케이크", "케익", "아이스", "우유", "회", "고기", "생선").any { item.contains(it) }
}

/** 시연용 샘플 카탈로그(서울 성수동 일대 가상 가게). 실제 상호가 아닙니다. */
class SamplePlaceCatalog : PlaceCatalog {
    val places: List<Place> = listOf(
        Place("hair-1", "들러헤어 성수점", "미용실", "서울 성동구 성수이로 (가상)", GeoPoint(37.5446, 127.0557),
            opensAt = 10 * 60, closesAt = 20 * 60, rating = 4.8,
            parking = Parking("건물 지하주차장", 1, "2시간 무료")),
        Place("flower-1", "봄날 꽃집", "꽃집", "서울 성동구 왕십리로 (가상)", GeoPoint(37.5472, 127.0470),
            opensAt = 10 * 60, closesAt = 18 * 60, rating = 4.7,
            parking = Parking("성수1가 공영주차장", 3, "10분 500원")),
        Place("flower-2", "늦은꽃 플라워", "꽃집", "서울 광진구 자양로 (가상)", GeoPoint(37.5380, 127.0820),
            opensAt = 11 * 60, closesAt = 20 * 60, rating = 4.5,
            parking = Parking("매장 앞 전용주차", 0, "구매 시 무료")),
        Place("bakery-1", "모퉁이 베이커리", "빵집", "서울 광진구 자양로 (가상)", GeoPoint(37.5392, 127.0811),
            opensAt = 9 * 60, closesAt = 21 * 60, rating = 4.6,
            parking = Parking("매장 앞 전용주차", 1, "꽃집과 같은 주차장")),
        Place("garage-1", "성수 타이어·정비", "정비소", "서울 성동구 성수일로 (가상)", GeoPoint(37.5430, 127.0540),
            opensAt = 9 * 60, closesAt = 19 * 60, rating = 4.4,
            parking = Parking("정비소 작업장", 0, "")),
        Place("gas-1", "성수 셀프주유소", "주유소", "서울 성동구 아차산로 (가상)", GeoPoint(37.5455, 127.0600),
            rating = 4.1),
        Place("cafe-1", "쉼표 카페", "카페", "서울 광진구 능동로 (가상)", GeoPoint(37.5420, 127.0700),
            opensAt = 8 * 60, closesAt = 22 * 60, rating = 4.5,
            parking = Parking("카페 주차장", 0, "1시간 무료")),
    )

    override fun search(query: String, near: GeoPoint, limit: Int): List<Place> {
        val category = ItemCategories.categoryOf(query)
        return places
            .filter { it.category == category || it.name.contains(query) }
            .sortedBy { HaversineEstimator.haversineKm(near, it.location) }
            .take(limit)
    }
}
