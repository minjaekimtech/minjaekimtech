package ai.deulleo.app.pleos

import ai.deulleo.app.BuildConfig
import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.listener.EventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnServerConnectionListener as SttServerListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnServerConnectionListener as TtsServerListener

enum class VoiceState { IDLE, LISTENING, SPEAKING }

/**
 * SpeechToText SDK v3.3.1 + TextToSpeech SDK v3.3.1 래퍼.
 *
 * Pleos Playground 자격 증명(local.properties의 pleos.clientId/clientSecret)이 있으면 HYBRID 모드
 * (서버 인식 우선, 네트워크가 끊기면 On-device로 자동 전환)로, 없으면 ON_DEVICE 모드로 동작합니다.
 */
class PleosVoice(context: Context) {

    private val hasCredentials = BuildConfig.PLEOS_CLIENT_ID.isNotBlank() && BuildConfig.PLEOS_CLIENT_SECRET.isNotBlank()

    private val stt = SpeechToText(context.applicationContext, if (hasCredentials) SttMode.HYBRID else SttMode.ON_DEVICE)
    private val tts = TextToSpeech(context.applicationContext, if (hasCredentials) TtsMode.HYBRID else TtsMode.ON_DEVICE)

    private val _utterances = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** 최종 인식된 문장 */
    val utterances: SharedFlow<String> = _utterances.asSharedFlow()

    private val _partial = MutableStateFlow("")
    /** 인식 중인 문장(화면 표시용) */
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _state = MutableStateFlow(VoiceState.IDLE)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val resultListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() { _state.value = VoiceState.LISTENING }
        override fun onUpdated(text: String, completed: Boolean) {
            _partial.value = text
            if (completed && text.isNotBlank()) _utterances.tryEmit(text)
        }
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onEndedRecognition() { if (_state.value == VoiceState.LISTENING) _state.value = VoiceState.IDLE }
        override fun onError(errorType: SttError) {
            Log.w(TAG, "STT 오류: $errorType")
            _state.value = VoiceState.IDLE
        }
    }

    private val ttsListener = object : EventListener {
        override fun onReady() = Unit
        override fun onStart() { _state.value = VoiceState.SPEAKING }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onDone() { _state.value = VoiceState.IDLE }
        override fun onStop() { _state.value = VoiceState.IDLE }
        override fun onError(errorType: TtsError) {
            Log.w(TAG, "TTS 오류: $errorType")
            _state.value = VoiceState.IDLE
        }
    }

    fun start() {
        try {
            stt.initialize()
            stt.addListener(resultListener)
            stt.setLocale(Locale.KOREA)
            tts.initialize()
            tts.addEventListener(ttsListener)
            tts.setLocale(Locale.KOREA)
            if (hasCredentials) registerApps()
        } catch (e: Exception) {
            Log.w(TAG, "음성 SDK 초기화 실패: ${e.message}")
        }
    }

    /** 주행 중 응답은 짧게: 화면 대신 귀로 듣는 정보입니다. */
    fun speak(text: String) {
        try {
            stt.stop()
            tts.speak(text, true)
        } catch (e: Exception) {
            Log.w(TAG, "speak 실패: ${e.message}")
        }
    }

    /** 마이크 버튼(또는 Gleo 호출) → 한 문장 듣기 */
    fun listen() {
        try {
            tts.stop()
            _partial.value = ""
            stt.request()
        } catch (e: Exception) {
            Log.w(TAG, "listen 실패: ${e.message}")
        }
    }

    fun stop() {
        try {
            stt.removeListener(resultListener)
            stt.release()
            tts.removeEventListener(ttsListener)
            tts.release()
        } catch (e: Exception) {
            Log.w(TAG, "음성 SDK 해제 실패: ${e.message}")
        }
    }

    private fun registerApps() {
        stt.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET, object : SttServerListener {
            override fun onConnected() { Log.i(TAG, "STT 서버 연결") }
            override fun onFailed(errorType: SttError) { Log.w(TAG, "STT 서버 연결 실패: $errorType") }
        })
        tts.registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET, object : TtsServerListener {
            override fun onConnected() { Log.i(TAG, "TTS 서버 연결") }
            override fun onFailed(errorType: TtsError) { Log.w(TAG, "TTS 서버 연결 실패: $errorType") }
        })
    }

    private companion object { const val TAG = "Deulleo" }
}
