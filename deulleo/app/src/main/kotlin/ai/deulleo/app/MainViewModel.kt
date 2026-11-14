package ai.deulleo.app

import ai.deulleo.app.pleos.NaviEvent
import ai.deulleo.app.pleos.PleosLocation
import ai.deulleo.app.pleos.PleosNavigator
import ai.deulleo.app.pleos.PleosVehicleSource
import ai.deulleo.app.pleos.PleosVoice
import ai.deulleo.app.pleos.VoiceState
import ai.deulleo.core.AgentAction
import ai.deulleo.core.AgentReply
import ai.deulleo.core.DeulleoAgent
import ai.deulleo.core.SampleDay
import ai.deulleo.core.brief.Josa
import ai.deulleo.core.model.Errand
import ai.deulleo.core.model.GeoPoint
import ai.deulleo.core.model.MinuteOfDay
import ai.deulleo.core.model.Plan
import ai.deulleo.core.planner.SamplePlaceCatalog
import ai.deulleo.core.vehicle.GearState
import ai.deulleo.core.vehicle.ModeResolver
import ai.deulleo.core.vehicle.UiMode
import ai.deulleo.core.vehicle.VehicleSnapshot
import ai.deulleo.core.voice.RuleBasedIntentParser
import ai.deulleo.core.voice.VoiceIntent
import android.app.Application
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** 화면이 그리는 상태 전부. */
data class UiState(
    val mode: UiMode = UiMode.MANAGE,
    val plan: Plan? = null,
    val errands: List<Errand> = emptyList(),
    val lastSpeech: String = "",
    val partialSpeech: String = "",
    val voiceState: VoiceState = VoiceState.IDLE,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val tripStarted: Boolean = false,
    val arrivedAtStop: Boolean = false,
    val clock: MinuteOfDay = SampleDay.START_AT,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val vehicle = PleosVehicleSource(app)
    private val navi = PleosNavigator(app)
    private val voice = PleosVoice(app)
    private val location = PleosLocation(app)

    private val catalog = SamplePlaceCatalog()
    private val agent = DeulleoAgent(SampleDay.home, catalog)
    private val parser = RuleBasedIntentParser()

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** "꽃을 실었으니 온도 낮출까요?"처럼 예/아니오를 기다리는 질문 */
    private var pendingConfirm: (() -> Unit)? = null
    private var briefed = false

    // 시연용 시계: 앱 시작 시각을 토요일 14:10으로 두고 실제 경과 시간만큼 흐릅니다.
    private val startedAtMs = SystemClock.elapsedRealtime()
    private fun now(): MinuteOfDay = SampleDay.START_AT + ((SystemClock.elapsedRealtime() - startedAtMs) / 60_000).toInt()
    private fun here(): GeoPoint = location.location.value ?: SampleDay.home

    init {
        vehicle.start(); navi.start(); voice.start(); location.start()
        agent.setErrands(SampleDay.errands(catalog))
        _ui.update { it.copy(errands = agent.remainingErrands) }

        viewModelScope.launch { vehicle.state.collect(::onVehicle) }
        viewModelScope.launch { voice.utterances.collect(::onUtterance) }
        viewModelScope.launch { voice.partial.collect { p -> _ui.update { it.copy(partialSpeech = p) } } }
        viewModelScope.launch { voice.state.collect { s -> _ui.update { it.copy(voiceState = s) } } }
        viewModelScope.launch {
            navi.events.collect { e -> if (e is NaviEvent.Arrived) markArrived() }
        }
        viewModelScope.launch {
            location.proximity.collect { (placeId, entered) ->
                if (entered && placeId == _ui.value.plan?.nextStop?.errand?.place?.id) markArrived()
            }
        }
    }

    // ---- 사용자 동작 ----

    /** 오늘 계획 세우기(탑승 브리핑). 시동 후 P단에서 자동 호출되며 관리 화면 버튼으로도 부를 수 있습니다. */
    fun brief() {
        briefed = true
        apply(agent.briefing(now(), here(), _ui.value.vehicle))
    }

    fun startTrip() = apply(AgentReply("좋아요, 안내를 시작할게요.", agent.plan, AgentAction.StartNavigation))

    fun listen() = voice.listen()

    /** 에뮬레이터에 마이크가 없을 때 시연용으로 문장을 직접 넣습니다. */
    fun submitText(text: String) = onUtterance(text)

    fun simulateTire(on: Boolean) = vehicle.simulateLowTirePressure(on)

    fun simulateArrival() = markArrived()

    // ---- 이벤트 처리 ----

    private fun onVehicle(s: VehicleSnapshot) {
        val prev = _ui.value.vehicle
        _ui.update { it.copy(vehicle = s, clock = now()) }

        // 시동 후 처음 P단을 확인하면 브리핑
        if (!briefed && s.gear == GearState.PARK) brief()

        // 볼일 장소에서 다시 출발(P → D): 현재 볼일 완료, 다음 구간 안내
        if (prev.gear == GearState.PARK && s.gear == GearState.DRIVE && _ui.value.arrivedAtStop) {
            agent.completeCurrentStop()
            _ui.update { it.copy(arrivedAtStop = false, plan = agent.plan, errands = agent.remainingErrands) }
            agent.plan?.let { if (_ui.value.tripStarted) guide(it) }
        }

        // 볼일 장소에서 트렁크를 열었다 닫음 → 신선식품이면 완료 안내 + 공조 제안
        if (prev.trunkOpen && !s.trunkOpen && _ui.value.arrivedAtStop) {
            val current = agent.plan?.nextStop?.errand
            if (current != null && current.perishable) {
                pendingConfirm = { vehicle.setCabinTemperature(22f) { ok -> say(if (ok) "실내 온도를 22도로 맞췄어요." else "온도를 바꾸지 못했어요.") } }
                say("${Josa.eunNeun(current.title)} 완료로 할게요. 실내 온도를 22도로 낮춰 둘까요?")
            }
        }
        recomputeMode()
    }

    private fun onUtterance(text: String) {
        val intent = parser.parse(text)
        pendingConfirm?.let { action ->
            pendingConfirm = null
            when (intent) {
                VoiceIntent.Confirm -> { action(); return }
                VoiceIntent.Reject -> { say("알겠어요."); return }
                else -> Unit
            }
        }
        apply(agent.onUtterance(text, now(), here()))
    }

    private fun markArrived() {
        val stop = agent.plan?.nextStop ?: return
        if (_ui.value.arrivedAtStop) return
        _ui.update { it.copy(arrivedAtStop = true) }
        val pk = stop.errand.place.parking
        say(
            if (pk != null && pk.walkMinutes > 0) "${pk.name}에 주차하세요. ${stop.errand.place.name}까지 걸어서 ${pk.walkMinutes}분이에요."
            else "${Josa.euroRo(stop.errand.place.name)} 도착했어요."
        )
        recomputeMode()
    }

    private fun apply(reply: AgentReply) {
        _ui.update { it.copy(plan = reply.plan ?: it.plan, errands = agent.remainingErrands) }
        say(reply.speech)
        if (reply.action == AgentAction.StartNavigation) {
            _ui.update { it.copy(tripStarted = true) }
            agent.plan?.let(::guide)
        }
        recomputeMode()
    }

    private fun guide(plan: Plan) {
        navi.guide(plan, SampleDay.home)
        location.clearWatches()
        plan.stops.take(3).forEach { location.watchPlace(it.errand.place.id, it.errand.place.location) }
    }

    private fun say(text: String) {
        _ui.update { it.copy(lastSpeech = text) }
        voice.speak(text)
    }

    private fun recomputeMode() = _ui.update {
        it.copy(mode = ModeResolver.resolve(it.vehicle.gear, hasPlan = it.plan != null, arrivedAtStop = it.arrivedAtStop))
    }

    override fun onCleared() {
        vehicle.stop(); navi.stop(); voice.stop(); location.stop()
    }
}
