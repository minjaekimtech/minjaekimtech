package ai.deulleo.core

import ai.deulleo.core.brief.Josa
import ai.deulleo.core.model.Errand
import ai.deulleo.core.model.ErrandKind
import ai.deulleo.core.model.GeoPoint
import ai.deulleo.core.model.Place
import ai.deulleo.core.planner.RoutePlanner
import ai.deulleo.core.planner.SamplePlaceCatalog
import ai.deulleo.core.planner.TravelTimeEstimator
import ai.deulleo.core.vehicle.GearState
import ai.deulleo.core.vehicle.ModeResolver
import ai.deulleo.core.vehicle.UiMode
import ai.deulleo.core.vehicle.VehicleSnapshot
import ai.deulleo.core.voice.RuleBasedIntentParser
import ai.deulleo.core.voice.VoiceIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoutePlannerTest {
    private val a = GeoPoint(0.0, 0.0)
    private val b = GeoPoint(0.0, 0.1)
    private val c = GeoPoint(0.0, 0.2)
    private val tenMin = TravelTimeEstimator { x, y -> if (x == y) 0 else 10 }

    private fun place(id: String, at: GeoPoint, closes: Int = 24 * 60) = Place(id, id, "test", "", at, closesAt = closes)

    @Test
    fun `perishable errand goes last`() {
        val planner = RoutePlanner(tenMin)
        val flowers = Errand("f", "꽃", ErrandKind.FLEXIBLE, place("f", b), 10, perishable = true)
        val laundry = Errand("l", "세탁물", ErrandKind.FLEXIBLE, place("l", c), 10)
        val plan = assertNotNull(planner.plan(a, 600, a, listOf(flowers, laundry)))
        assertEquals(listOf("l", "f"), plan.stops.map { it.errand.id })
    }

    @Test
    fun `fixed appointment is respected`() {
        val planner = RoutePlanner(tenMin)
        val hair = Errand("h", "미용실", ErrandKind.FIXED, place("h", b), 60, fixedStart = 620)
        val shop = Errand("s", "마트", ErrandKind.FLEXIBLE, place("s", c), 30)
        val plan = assertNotNull(planner.plan(a, 600, a, listOf(shop, hair)))
        // 마트를 먼저 가면 예약에 늦으므로 미용실이 먼저입니다.
        assertEquals("h", plan.stops.first().errand.id)
        assertEquals(620, plan.stops.first().departAt - 60)
    }

    @Test
    fun `closed shop makes plan infeasible`() {
        val planner = RoutePlanner(tenMin)
        val late = Errand("x", "꽃", ErrandKind.FLEXIBLE, place("x", b, closes = 605), 10)
        assertNull(planner.plan(a, 600, a, listOf(late)))
    }
}

class IntentParserTest {
    private val p = RuleBasedIntentParser()

    @Test
    fun `scenario utterances`() {
        assertEquals(VoiceIntent.Confirm, p.parse("응"))
        assertEquals(VoiceIntent.Confirm, p.parse("좋아 출발하자"))
        assertEquals(VoiceIntent.AddErrand("케이크"), p.parse("아, 케이크도 사야 해"))
        assertEquals(VoiceIntent.Delay(30), p.parse("30분 늦을 것 같아"))
        assertEquals(VoiceIntent.Delay(40), p.parse("사십 분 정도 늦어"))
        assertEquals(VoiceIntent.Delay(30), p.parse("좀 늦을 것 같아"))
        assertEquals(VoiceIntent.RemoveErrand("꽃집"), p.parse("꽃집은 빼 줘"))
        assertEquals(VoiceIntent.NextStop, p.parse("다음 어디야?"))
        assertEquals(VoiceIntent.Reject, p.parse("아니 잠깐만"))
    }
}

class ModeResolverTest {
    @Test
    fun modes() {
        assertEquals(UiMode.MANAGE, ModeResolver.resolve(GearState.PARK, hasPlan = false, arrivedAtStop = false))
        assertEquals(UiMode.BRIEFING, ModeResolver.resolve(GearState.PARK, hasPlan = true, arrivedAtStop = false))
        assertEquals(UiMode.DRIVING, ModeResolver.resolve(GearState.DRIVE, hasPlan = true, arrivedAtStop = false))
        assertEquals(UiMode.ARRIVED, ModeResolver.resolve(GearState.PARK, hasPlan = true, arrivedAtStop = true))
    }
}

class JosaTest {
    @Test
    fun particles() {
        assertEquals("베이커리를", Josa.eulReul("모퉁이 베이커리").substringAfter("모퉁이 "))
        assertEquals("꽃집을", Josa.eulReul("꽃집"))
        assertEquals("플라워로", Josa.euroRo("플라워"))
        assertEquals("꽃집으로", Josa.euroRo("꽃집"))
        assertEquals("서울로", Josa.euroRo("서울"))
    }
}

/** 기획서 4번 시나리오를 처음부터 끝까지 재현합니다. */
class ScenarioTest {
    private val catalog = SamplePlaceCatalog()

    @Test
    fun `saturday errands scenario`() {
        val agent = DeulleoAgent(SampleDay.home, catalog)
        agent.setErrands(SampleDay.errands(catalog))

        // 1) 탑승 브리핑: 타이어 저압 → 정비소가 추가되고, 미용실 예약 전에 배치됩니다. 꽃은 마지막.
        val brief = agent.briefing(SampleDay.START_AT, SampleDay.home, VehicleSnapshot(gear = GearState.PARK, lowTirePressure = true))
        println("브리핑: ${brief.speech}")
        val plan = assertNotNull(brief.plan)
        assertEquals(listOf("care-TIRE_PRESSURE", "hair", "flower"), plan.stops.map { it.errand.id })
        assertTrue(brief.speech.contains("타이어 공기압 점검"))

        // 2) "응" → 안내 시작
        assertEquals(AgentAction.StartNavigation, agent.onUtterance("응", SampleDay.START_AT, SampleDay.home).action)

        // 3) 정비소·미용실 완료
        agent.completeCurrentStop()
        agent.completeCurrentStop()
        val salon = catalog.places.first { it.id == "hair-1" }.location

        // 4) 미용이 늦게 끝남 → 18시 마감 꽃집 대신 20시까지 하는 꽃집으로 교체
        val delayed = agent.onUtterance("40분 늦을 것 같아", 17 * 60 + 10, salon)
        println("지연: ${delayed.speech}")
        val afterDelay = assertNotNull(delayed.plan)
        assertEquals("flower-2", afterDelay.stops.single().errand.place.id)

        // 5) "케이크도 사야 해" → 꽃집 옆 빵집, 한 번 주차
        val cake = agent.onUtterance("아, 케이크도 사야 해", 17 * 60 + 50, salon)
        println("추가: ${cake.speech}")
        val withCake = assertNotNull(cake.plan)
        assertTrue(withCake.stops.any { it.errand.place.id == "bakery-1" })
        assertTrue(cake.speech.contains("주차는 한 번만"))
    }
}
