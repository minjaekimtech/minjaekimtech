package ai.deulleo.app.ui

import ai.deulleo.app.UiState
import ai.deulleo.app.pleos.VoiceState
import ai.deulleo.core.model.ErrandKind
import ai.deulleo.core.model.PlannedStop
import ai.deulleo.core.model.toClock
import ai.deulleo.core.vehicle.GearState
import ai.deulleo.core.vehicle.UiMode
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Bg = Color(0xFF0D1117)
private val Card = Color(0xFF16202B)
private val Chip = Color(0xFF1C2530)
private val Accent = Color(0xFF3B82F6)
private val Sub = Color(0xFF8B98A8)
private val Body = Color(0xFFDBE4EE)

/** 화면에서 사용자가 할 수 있는 동작 */
data class UiActions(
    val brief: () -> Unit,
    val startTrip: () -> Unit,
    val listen: () -> Unit,
    val submitText: (String) -> Unit,
    val simulateTire: (Boolean) -> Unit,
    val simulateArrival: () -> Unit,
)

@Composable
fun DeulleoApp(state: UiState, actions: UiActions) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Accent, background = Bg, surface = Card)) {
        Box(Modifier.fillMaxSize().background(Bg).padding(28.dp)) {
            when (state.mode) {
                UiMode.MANAGE -> ManageScreen(state, actions)
                UiMode.BRIEFING -> BriefingScreen(state, actions)
                UiMode.DRIVING -> DrivingScreen(state, actions)
                UiMode.ARRIVED -> ArrivedScreen(state, actions)
            }
        }
    }
}

// ---------------- 탑승 브리핑 (시동 직후 · P단): 계획 한 장 + 출발 버튼 하나 ----------------
@Composable
private fun BriefingScreen(state: UiState, a: UiActions) {
    Column(Modifier.fillMaxSize()) {
        TopLine("들러 · 오늘의 계획", state.clock.toClock())
        SpeechBubble(state.lastSpeech)
        Spacer(Modifier.height(16.dp))
        state.plan?.stops?.forEachIndexed { i, s -> StopRow(i + 1, s) }
        state.plan?.let {
            Text("집  ${it.homeArriveAt.toClock()} 도착", color = Body, fontSize = 20.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartialSpeech(state)
            Spacer(Modifier.weight(1f))
            MicButton(state.voiceState, a.listen)
            Spacer(Modifier.width(16.dp))
            Button(
                onClick = a.startTrip,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.height(64.dp),
            ) { Text(if (state.tripStarted) "계속 안내" else "출발", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold) }
        }
        DemoTools(state, a)
    }
}

// ---------------- 주행 모드: 정보 3개만, 목록·스크롤·버튼 없음(마이크 제외) ----------------
@Composable
private fun DrivingScreen(state: UiState, a: UiActions) {
    val next = state.plan?.nextStop
    Column(Modifier.fillMaxSize()) {
        TopLine("다음 목적지", progressText(state))
        Spacer(Modifier.height(12.dp))
        if (next == null) {
            BigText("집으로\n${state.plan?.homeArriveAt?.toClock() ?: ""} 도착")
        } else {
            BigText("${next.errand.place.name}\n${next.arriveAt.toClock()} 도착")
            Spacer(Modifier.height(16.dp))
            Row {
                next.errand.place.parking?.let { pk ->
                    ChipText("🅿 ${pk.name}${if (pk.note.isNotBlank()) " · ${pk.note}" else ""}")
                    Spacer(Modifier.width(10.dp))
                    if (pk.walkMinutes > 0) ChipText("도보 ${pk.walkMinutes}분")
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartialSpeech(state)
            Spacer(Modifier.weight(1f))
            MicButton(state.voiceState, a.listen)
        }
    }
}

// ---------------- 도착 모드: 가게까지 도보 안내, 다음 일정 ----------------
@Composable
private fun ArrivedScreen(state: UiState, a: UiActions) {
    val stop = state.plan?.nextStop
    val following = state.plan?.stops?.getOrNull(1)
    Column(Modifier.fillMaxSize()) {
        TopLine("${stop?.errand?.place?.name ?: ""} 도착", state.clock.toClock())
        Spacer(Modifier.height(8.dp))
        val walk = stop?.errand?.place?.parking?.walkMinutes ?: 0
        BigText(if (walk > 0) "🚶 ${stop?.errand?.place?.name}까지\n도보 ${walk}분" else "🚶 ${stop?.errand?.title ?: ""}", size = 40)
        SpeechBubble(state.lastSpeech)
        Spacer(Modifier.height(12.dp))
        following?.let { ChipText("다음: ${it.errand.place.name} · ${it.arriveAt.toClock()}") }
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PartialSpeech(state)
            Spacer(Modifier.weight(1f))
            MicButton(state.voiceState, a.listen)
        }
        DemoTools(state, a)
    }
}

// ---------------- 관리 모드: 일정·할 일 확인 ----------------
@Composable
private fun ManageScreen(state: UiState, a: UiActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopLine("들러 · 볼일 인박스", state.clock.toClock())
        Spacer(Modifier.height(12.dp))
        state.errands.forEach { e ->
            val tag = when (e.kind) { ErrandKind.FIXED -> "일정"; ErrandKind.FLEXIBLE -> "할 일"; ErrandKind.VEHICLE_CARE -> "차량" }
            val time = e.fixedStart?.toClock()?.let { " · $it" } ?: ""
            Text("[$tag] ${e.title}$time  —  ${e.place.name}", color = Body, fontSize = 20.sp, modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(20.dp))
        Button(onClick = a.brief, colors = ButtonDefaults.buttonColors(containerColor = Accent)) {
            Text("오늘 계획 세우기", fontSize = 20.sp)
        }
        Text(
            "폰 앱(스크린샷 인식·캘린더 연동)은 다음 단계에서 붙입니다. 지금은 시연용 일정을 씁니다.",
            color = Sub, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp),
        )
        DemoTools(state, a)
    }
}

// ---------------- 시연 도구: 정차 중에만 표시(에뮬레이터용) ----------------
@Composable
private fun DemoTools(state: UiState, a: UiActions) {
    if (state.vehicle.gear == GearState.DRIVE || state.vehicle.gear == GearState.REVERSE) return
    var text by remember { mutableStateOf("") }
    Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Chip).padding(12.dp)) {
        Text("시연 도구 (정차 중에만 표시)", color = Sub, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("타이어 경고", color = Body, fontSize = 15.sp)
            Spacer(Modifier.width(8.dp))
            Switch(checked = state.vehicle.lowTirePressure, onCheckedChange = a.simulateTire)
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = a.simulateArrival) { Text("도착 처리") }
            Spacer(Modifier.width(16.dp))
            Text("기어 ${state.vehicle.gear} · 연료 ${state.vehicle.fuelPercent ?: "-"}%", color = Sub, fontSize = 13.sp)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("발화 입력 (예: 케이크도 사야 해)") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { a.submitText(text); text = "" }) { Text("말하기") }
        }
    }
}

// ---------------- 공통 요소 ----------------
@Composable
private fun TopLine(left: String, right: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(left, color = Sub, fontSize = 18.sp)
        Spacer(Modifier.weight(1f))
        Text(right, color = Sub, fontSize = 18.sp)
    }
}

@Composable
private fun SpeechBubble(text: String) {
    if (text.isBlank()) return
    Row(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Card)) {
        Box(Modifier.width(4.dp).height(64.dp).background(Accent))
        Text("\"$text\"", color = Body, fontSize = 18.sp, lineHeight = 26.sp, modifier = Modifier.padding(12.dp))
    }
}

@Composable
private fun StopRow(n: Int, s: PlannedStop) {
    Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("$n", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Text("${s.errand.place.name}  ${s.arriveAt.toClock()}", color = Body, fontSize = 20.sp)
        s.errand.place.parking?.let { Text("   🅿 ${it.name}", color = Color(0xFF7FB0FF), fontSize = 17.sp) }
    }
}

@Composable
private fun BigText(text: String, size: Int = 48) =
    Text(text, color = Color.White, fontSize = size.sp, fontWeight = FontWeight.ExtraBold, lineHeight = (size * 1.2).sp)

@Composable
private fun ChipText(text: String) =
    Text(text, color = Body, fontSize = 18.sp, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Chip).padding(horizontal = 12.dp, vertical = 8.dp))

@Composable
private fun PartialSpeech(state: UiState) {
    val label = when (state.voiceState) {
        VoiceState.LISTENING -> "🗣 ${state.partialSpeech.ifBlank { "듣는 중..." }}"
        VoiceState.SPEAKING -> "🔊 말하는 중"
        VoiceState.IDLE -> ""
    }
    Text(label, color = Sub, fontSize = 16.sp)
}

@Composable
private fun MicButton(voice: VoiceState, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = if (voice == VoiceState.LISTENING) Color(0xFFEF4444) else Accent),
        modifier = Modifier.size(72.dp),
    ) { Text("🎤", fontSize = 26.sp) }
}

private fun progressText(state: UiState): String {
    val left = state.plan?.stops?.size ?: 0
    return if (left == 0) "남은 볼일 없음" else "남은 볼일 ${left}곳"
}
