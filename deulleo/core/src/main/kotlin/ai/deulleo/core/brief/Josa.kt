package ai.deulleo.core.brief

/** 마지막 글자의 받침에 맞춰 조사를 붙입니다. "베이커리" + 을/를 → "베이커리를" */
object Josa {
    private fun lastHangul(word: String): Char? = word.trim().lastOrNull { it in '가'..'힣' }

    private fun hasFinal(word: String): Boolean {
        val c = lastHangul(word) ?: return false
        return (c - '가') % 28 != 0
    }

    /** 받침이 ㄹ이면 '로'를 씁니다(예: 서울로). */
    private fun finalIsRieul(word: String): Boolean {
        val c = lastHangul(word) ?: return false
        return (c - '가') % 28 == 8
    }

    fun eulReul(word: String) = word + if (hasFinal(word)) "을" else "를"
    fun iGa(word: String) = word + if (hasFinal(word)) "이" else "가"
    fun eunNeun(word: String) = word + if (hasFinal(word)) "은" else "는"
    fun euroRo(word: String) = word + if (hasFinal(word) && !finalIsRieul(word)) "으로" else "로"
}
