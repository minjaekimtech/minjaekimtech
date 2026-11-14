package ai.deulleo.core.vehicle

/** 기어 상태(Vehicle SDK Gear 도메인을 단순화). */
enum class GearState { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/** 앱이 판단에 쓰는 차량 상태 스냅숏. 값이 null이면 해당 차종/에뮬레이터에서 지원하지 않거나 아직 모르는 것입니다. */
data class VehicleSnapshot(
    val gear: GearState = GearState.UNKNOWN,
    val fuelPercent: Int? = null,
    val evSocPercent: Int? = null,
    val lowTirePressure: Boolean = false,
    val engineOilLow: Boolean = false,
    val trunkOpen: Boolean = false,
    val cabinTempC: Float? = null,
)

/** 차량 상태에서 생겨나는 '차량 관리 할 일'. */
enum class VehicleCareNeed(val title: String, val placeCategory: String, val stayMinutes: Int) {
    LOW_FUEL("주유", "주유소", 10),
    LOW_BATTERY("충전", "전기차충전소", 30),
    TIRE_PRESSURE("타이어 공기압 점검", "정비소", 30),
    ENGINE_OIL("엔진오일 점검", "정비소", 40),
}

object VehicleCareRules {
    const val LOW_FUEL_PERCENT = 15
    const val LOW_SOC_PERCENT = 20

    fun needsFrom(s: VehicleSnapshot): List<VehicleCareNeed> = buildList {
        s.fuelPercent?.let { if (it <= LOW_FUEL_PERCENT) add(VehicleCareNeed.LOW_FUEL) }
        s.evSocPercent?.let { if (it <= LOW_SOC_PERCENT) add(VehicleCareNeed.LOW_BATTERY) }
        if (s.lowTirePressure) add(VehicleCareNeed.TIRE_PRESSURE)
        if (s.engineOilLow) add(VehicleCareNeed.ENGINE_OIL)
    }
}

/** 차량 상태로 정하는 화면 모드. Pleos 디자인 가이드의 즉시성(1~2초 내 파악) 원칙을 따릅니다. */
enum class UiMode {
    /** 일정·할 일 편집 (계획 없음 또는 정차 중 관리) */
    MANAGE,

    /** 시동 직후·P단: 오늘 계획 한 장 + 출발 버튼 */
    BRIEFING,

    /** 주행 중: 정보 3개, 음성 우선, 상세 화면 차단 */
    DRIVING,

    /** 목적지 정차: 가게까지 도보 안내, 다음 일정 */
    ARRIVED,
}

object ModeResolver {
    /**
     * 주행 중에는 계획 확정 여부와 관계없이 항상 주행 모드입니다(버튼이 있는 브리핑 화면을 주행 중에 띄우지 않음).
     *
     * @param hasPlan 오늘 계획이 있는지
     * @param arrivedAtStop 현재 정차 지점이 계획된 목적지 근처인지(근접 알림·내비 도착 이벤트)
     */
    fun resolve(gear: GearState, hasPlan: Boolean, arrivedAtStop: Boolean): UiMode = when {
        !hasPlan -> UiMode.MANAGE
        gear == GearState.DRIVE || gear == GearState.REVERSE -> UiMode.DRIVING
        arrivedAtStop -> UiMode.ARRIVED
        else -> UiMode.BRIEFING
    }
}
