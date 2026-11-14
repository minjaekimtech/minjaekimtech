package ai.deulleo.core

import ai.deulleo.core.model.Errand
import ai.deulleo.core.model.ErrandKind
import ai.deulleo.core.model.GeoPoint
import ai.deulleo.core.planner.SamplePlaceCatalog

/**
 * 시연용 하루: 토요일 14:10 출발, 15:00 미용실 예약, 할 일 "엄마 생신 꽃 사기".
 * 폰 앱(스크린샷 인식·캘린더 연동)이 붙기 전까지 이 데이터로 차량 앱 흐름을 시연합니다.
 */
object SampleDay {
    val home = GeoPoint(37.5505, 127.0735)
    const val START_AT = 14 * 60 + 10

    fun errands(catalog: SamplePlaceCatalog): List<Errand> {
        fun place(id: String) = catalog.places.first { it.id == id }
        return listOf(
            Errand("hair", "미용실", ErrandKind.FIXED, place("hair-1"), stayMinutes = 90, fixedStart = 15 * 60),
            Errand("flower", "엄마 생신 꽃 사기", ErrandKind.FLEXIBLE, place("flower-1"), stayMinutes = 10, perishable = true),
        )
    }
}
