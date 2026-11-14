package ai.deulleo.core

import ai.deulleo.core.brief.Josa
import ai.deulleo.core.model.Errand
import ai.deulleo.core.model.ErrandKind
import ai.deulleo.core.model.GeoPoint
import ai.deulleo.core.model.MinuteOfDay
import ai.deulleo.core.model.Place
import ai.deulleo.core.model.Plan
import ai.deulleo.core.model.toClock
import ai.deulleo.core.planner.HaversineEstimator
import ai.deulleo.core.planner.ItemCategories
import ai.deulleo.core.planner.PlaceCatalog
import ai.deulleo.core.planner.RoutePlanner
import ai.deulleo.core.vehicle.VehicleCareRules
import ai.deulleo.core.vehicle.VehicleSnapshot
import ai.deulleo.core.voice.IntentParser
import ai.deulleo.core.voice.RuleBasedIntentParser
import ai.deulleo.core.voice.VoiceIntent

/** 에이전트가 앱에 요청하는 동작. */
sealed interface AgentAction {
    /** 계획대로 내비게이션 안내를 시작(또는 갱신)합니다. */
    data object StartNavigation : AgentAction
    data object None : AgentAction
}

/** 에이전트 응답: 음성으로 읽을 문장 + 갱신된 계획 + 앱이 할 동작. */
data class AgentReply(
    val speech: String,
    val plan: Plan?,
    val action: AgentAction = AgentAction.None,
)

/**
 * 들러 에이전트의 두뇌.
 *
 * 오늘의 일정·할 일과 차량 상태를 받아 계획을 세우고([briefing]), 주행 중 발화로 계획을 고칩니다([onUtterance]).
 * 안드로이드·Pleos SDK에 의존하지 않는 순수 Kotlin이라 JVM에서 단위 테스트할 수 있습니다.
 * 서비스 단계에서는 [parser]를 LLM 도구 호출로, [catalog]를 카카오 로컬 API로 교체합니다.
 */
class DeulleoAgent(
    private val home: GeoPoint,
    private val catalog: PlaceCatalog,
    private val planner: RoutePlanner = RoutePlanner(),
    private val parser: IntentParser = RuleBasedIntentParser(),
    private val homeBy: MinuteOfDay? = null,
) {
    private val errands = mutableListOf<Errand>()
    private var careAdded = false

    var plan: Plan? = null
        private set

    val remainingErrands: List<Errand> get() = errands.toList()

    fun setErrands(list: List<Errand>) {
        errands.clear()
        errands += list
        careAdded = false
    }

    /** 탑승 브리핑: 차량 관리 할 일을 더하고 계획을 세운 뒤 제안 문장을 만듭니다. */
    fun briefing(now: MinuteOfDay, here: GeoPoint, vehicle: VehicleSnapshot): AgentReply {
        val careNotes = mutableListOf<String>()
        if (!careAdded) {
            for (need in VehicleCareRules.needsFrom(vehicle)) {
                val anchor = errands.firstOrNull { it.kind == ErrandKind.FIXED }?.place?.location ?: here
                val place = catalog.search(need.placeCategory, anchor, 1).firstOrNull() ?: continue
                errands += Errand("care-${need.name}", need.title, ErrandKind.VEHICLE_CARE, place, need.stayMinutes)
                careNotes += need.title
            }
            careAdded = true
        }
        val p = replan(now, here) ?: return AgentReply("오늘 볼일을 모두 시간 안에 처리하기 어려워요. 일정을 확인해 주세요.", null)

        val sb = StringBuilder()
        errands.firstOrNull { it.kind == ErrandKind.FIXED }?.let {
            sb.append("오늘 ${it.fixedStart!!.toClock()} ${it.title} 일정이 있어요. ")
        }
        if (careNotes.isNotEmpty()) {
            sb.append("차량 점검이 필요해서 ${Josa.eulReul(careNotes.joinToString(", "))} 경로에 넣었어요. ")
        }
        val perishable = p.stops.filter { it.errand.perishable }
        if (perishable.isNotEmpty() && perishable.last() == p.stops.last()) {
            sb.append("${Josa.eunNeun(perishable.last().errand.title)} 차에 오래 두지 않도록 마지막에 넣었어요. ")
        }
        p.nextStop?.let { first ->
            sb.append("첫 목적지 ${first.errand.place.name}에 ${first.arriveAt.toClock()} 도착 예정이에요")
            first.errand.place.parking?.let { pk -> sb.append(", 주차는 ${pk.name}${if (pk.note.isNotBlank()) "(${pk.note})" else ""}") }
            sb.append(". ")
        }
        sb.append("집에는 ${p.homeArriveAt.toClock()}쯤 도착해요. 이대로 갈까요?")
        return AgentReply(sb.toString(), p)
    }

    /** 발화 처리. */
    fun onUtterance(text: String, now: MinuteOfDay, here: GeoPoint): AgentReply =
        when (val intent = parser.parse(text)) {
            VoiceIntent.Confirm -> AgentReply("좋아요, 안내를 시작할게요.", plan, AgentAction.StartNavigation)
            VoiceIntent.Reject -> AgentReply("알겠어요. 바꾸고 싶은 걸 말씀해 주세요.", plan)
            VoiceIntent.NextStop -> {
                val next = plan?.nextStop
                if (next == null) AgentReply("남은 볼일이 없어요. 집으로 안내할게요.", plan)
                else AgentReply("다음은 ${next.errand.place.name}, ${next.arriveAt.toClock()} 도착 예정이에요.", plan)
            }
            is VoiceIntent.AddErrand -> addErrand(intent.item, now, here)
            is VoiceIntent.RemoveErrand -> removeErrand(intent.item, now, here)
            is VoiceIntent.Delay -> delay(intent.minutes, now, here)
            is VoiceIntent.Unknown -> AgentReply("죄송해요, 다시 한번 말씀해 주세요.", plan)
        }

    /** 현재(첫 번째) 볼일을 마쳤을 때 호출합니다. 예: 볼일 장소에서 출발(P→D) 감지 */
    fun completeCurrentStop(): Errand? {
        val done = plan?.nextStop?.errand ?: return null
        errands.removeAll { it.id == done.id }
        plan = plan?.let { it.copy(stops = it.stops.drop(1)) }
        return done
    }

    // ---- 내부 동작 ----

    private fun addErrand(item: String, now: MinuteOfDay, here: GeoPoint): AgentReply {
        val anchor = plan?.stops?.lastOrNull()?.errand?.place?.location ?: here
        val candidates = catalog.search(item, anchor, 3)
        if (candidates.isEmpty()) return AgentReply("주변에서 $item 파는 곳을 찾지 못했어요.", plan)

        // 후보마다 계획을 세워 보고 가장 점수가 좋은 곳을 고릅니다.
        var bestPlace: Place? = null
        var bestPlan: Plan? = null
        for (place in candidates) {
            val e = Errand("add-${place.id}", "$item 사기", ErrandKind.FLEXIBLE, place, 10, perishable = ItemCategories.isPerishable(item))
            val p = planner.plan(here, now, home, errands + e, homeBy) ?: continue
            if (bestPlan == null || p.score < bestPlan.score) { bestPlan = p; bestPlace = place }
        }
        if (bestPlace == null || bestPlan == null) return AgentReply("$item 사는 곳을 넣으면 시간이 맞지 않아요.", plan)

        val before = plan?.homeArriveAt
        errands += Errand("add-${bestPlace.id}", "$item 사기", ErrandKind.FLEXIBLE, bestPlace, 10, perishable = ItemCategories.isPerishable(item))
        plan = bestPlan

        val neighbor = errands.firstOrNull {
            it.place.id != bestPlace.id && HaversineEstimator.haversineKm(it.place.location, bestPlace.location) < 0.3
        }
        val sb = StringBuilder()
        if (neighbor != null) sb.append("${neighbor.place.category} 근처 ${Josa.eulReul(bestPlace.name)} 넣었어요. 주차는 한 번만 하시면 돼요. ")
        else sb.append("${Josa.eulReul(bestPlace.name)} 경로에 넣었어요. ")
        sb.append("집 도착은 ${bestPlan.homeArriveAt.toClock()}")
        if (before != null && bestPlan.homeArriveAt > before) sb.append("(${bestPlan.homeArriveAt - before}분 늦어짐)")
        sb.append("이에요.")
        return AgentReply(sb.toString(), plan, AgentAction.StartNavigation)
    }

    private fun removeErrand(item: String, now: MinuteOfDay, here: GeoPoint): AgentReply {
        val removed = errands.filter { it.title.contains(item) || it.place.category.contains(item) || it.place.name.contains(item) }
        if (removed.isEmpty()) return AgentReply("'$item'에 해당하는 볼일이 없어요.", plan)
        errands.removeAll(removed)
        val p = replan(now, here)
        return AgentReply("${Josa.eulReul(removed.joinToString { it.title })} 뺐어요. 집 도착은 ${p?.homeArriveAt?.toClock() ?: "-"}이에요.", p, AgentAction.StartNavigation)
    }

    private fun delay(minutes: Int, now: MinuteOfDay, here: GeoPoint): AgentReply {
        val start = now + minutes
        val beforeIds = errands.map { it.place.id }.toSet()
        val p = replan(start, here, allowSubstitutes = true)
            ?: return AgentReply("${minutes}분 늦어지면 남은 볼일을 모두 하기 어려워요. 뺄 볼일을 말씀해 주세요.", plan)
        val swapped = errands.filter { it.place.id !in beforeIds }
        val sb = StringBuilder("${minutes}분 늦어지는 걸로 다시 계산했어요. ")
        swapped.forEach { sb.append("원래 가게는 마감 시간이 맞지 않아 ${Josa.euroRo(it.place.name)} 바꿨어요. ") }
        sb.append("집 도착은 ${p.homeArriveAt.toClock()} 예정이에요.")
        return AgentReply(sb.toString(), p, AgentAction.StartNavigation)
    }

    /**
     * 계획을 다시 세웁니다. [allowSubstitutes]가 true이고 계획이 불가능하면,
     * FLEXIBLE 볼일의 가게를 같은 업종의 다른 가게로 바꿔 가며 가능한 계획을 찾습니다.
     */
    private fun replan(now: MinuteOfDay, here: GeoPoint, allowSubstitutes: Boolean = true): Plan? {
        planner.plan(here, now, home, errands, homeBy)?.let { plan = it; return it }
        if (!allowSubstitutes) return null

        var best: Pair<List<Errand>, Plan>? = null
        val flexibleIdx = errands.indices.filter { errands[it].kind == ErrandKind.FLEXIBLE }
        for (i in flexibleIdx) {
            val e = errands[i]
            for (alt in catalog.search(e.place.category, e.place.location, 5)) {
                if (alt.id == e.place.id) continue
                val trial = errands.toMutableList().also { it[i] = e.copy(id = "sub-${alt.id}", place = alt) }
                val p = planner.plan(here, now, home, trial, homeBy) ?: continue
                if (best == null || p.score < best.second.score) best = trial to p
            }
        }
        val found = best ?: return null
        errands.clear(); errands += found.first
        plan = found.second
        return found.second
    }
}
