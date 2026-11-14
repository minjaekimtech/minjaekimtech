package ai.deulleo.core.voice

/** 운전자 발화에서 뽑아낸 의도. */
sealed interface VoiceIntent {
    data object Confirm : VoiceIntent
    data object Reject : VoiceIntent
    data object NextStop : VoiceIntent
    data class AddErrand(val item: String) : VoiceIntent
    data class RemoveErrand(val item: String) : VoiceIntent
    data class Delay(val minutes: Int) : VoiceIntent
    data class Unknown(val text: String) : VoiceIntent
}

/** 발화 → 의도. 프로토타입은 규칙 기반이며, 서비스 단계에서는 LLM 도구 호출로 교체합니다. */
fun interface IntentParser {
    fun parse(utterance: String): VoiceIntent
}

/**
 * 오프라인에서도 동작하는 한국어 규칙 기반 파서.
 * 시연 시나리오의 핵심 발화("응", "케이크도 사야 해", "30분 늦을 것 같아", "꽃집 빼 줘", "다음 어디야")를 처리합니다.
 */
class RuleBasedIntentParser : IntentParser {

    private val confirmWords = listOf("응", "그래", "좋아", "출발", "네", "예", "ㅇㅇ", "오케이", "그렇게 해")
    private val rejectWords = listOf("아니", "싫어", "취소", "됐어", "그만")

    private val addRegex = Regex("""([가-힣A-Za-z]+?)(?:도|를|을|좀)?\s*(?:사야|사러|사고|들러야|들러|가야|맡겨야|넣어)""")
    private val removeRegex = Regex("""([가-힣A-Za-z]+?)(?:은|는|을|를)?\s*(?:빼|취소|안 가|안가|건너뛰)""")
    private val delayDigits = Regex("""(\d+)\s*분\s*(?:정도\s*)?(?:늦|지연|더 걸)""")
    private val delayWords = Regex("""(한\s*시간|반\s*시간|[일이삼사오육]?십)\s*분?\s*(?:정도\s*)?(?:늦|지연|더 걸)""")

    override fun parse(utterance: String): VoiceIntent {
        val text = utterance.trim().trimEnd('.', '!', '?', '요').trim()
        if (text.isEmpty()) return VoiceIntent.Unknown(utterance)

        parseDelay(text)?.let { return VoiceIntent.Delay(it) }
        if (text.contains("다음") && (text.contains("어디") || text.contains("뭐"))) return VoiceIntent.NextStop

        removeRegex.find(text)?.let { m ->
            val item = cleanItem(m.groupValues[1])
            if (item.isNotEmpty()) return VoiceIntent.RemoveErrand(item)
        }
        addRegex.find(text)?.let { m ->
            val item = cleanItem(m.groupValues[1])
            if (item.isNotEmpty() && item !in setOf("어디", "집", "빨리")) return VoiceIntent.AddErrand(item)
        }
        if (rejectWords.any { text.startsWith(it) }) return VoiceIntent.Reject
        if (confirmWords.any { text == it || text.startsWith("$it ") || text.startsWith(it) && text.length <= it.length + 2 }) {
            return VoiceIntent.Confirm
        }
        return VoiceIntent.Unknown(utterance)
    }

    private fun parseDelay(text: String): Int? {
        delayDigits.find(text)?.let { return it.groupValues[1].toInt() }
        delayWords.find(text)?.let { m ->
            val w = m.groupValues[1].replace(" ", "")
            return when {
                w == "한시간" -> 60
                w == "반시간" -> 30
                w == "십" -> 10
                w.endsWith("십") -> (koreanDigit(w[0]) ?: 1) * 10
                else -> null
            }
        }
        if (text.contains("늦을 것 같") || text.contains("늦어질 것 같") || text.contains("늦겠")) return 30
        return null
    }

    private fun koreanDigit(c: Char): Int? = "일이삼사오육".indexOf(c).takeIf { it >= 0 }?.plus(1)

    private fun cleanItem(raw: String): String =
        raw.removePrefix("아").removePrefix("참").trim()
            .removeSuffix("도").removeSuffix("이랑").removeSuffix("하고").trim()
}
