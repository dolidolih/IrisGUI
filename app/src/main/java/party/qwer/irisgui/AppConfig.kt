package party.qwer.irisgui

import android.content.Context

/**
 * AppConfig — 앱 설정 관리 (SharedPreferences / JSON 추상화)
 *
 * ADB 모드에서는 prefs가 초기화되지 않으므로 ConfigStore(JSON)로 자동 폴백.
 * A2: ConfigStoreFactory를 통해 SharedPreferences/JSON 단일 인터페이스로 통합.
 */
object AppConfig {
    private var configStore: ConfigStore? = null
    var isInitialized: Boolean = false

    fun init(context: Context) {
        configStore = SharedPrefConfigStore().apply { init(context) }
        isInitialized = true
    }

    /** ConfigStore 접근 — 초기화되지 않으면 AdbConfig(JSON) 사용 */
    private fun store(): ConfigStore {
        return configStore ?: JsonConfigStore().also { configStore = it }
    }

    // ── 공통 설정 ──────────────────────────────────────────

    var isServiceEnabled: Boolean
        get() = store().isServiceEnabled
        set(value) { store().isServiceEnabled = value }

    var webEndpoint: String
        get() = store().webEndpoint
        set(value) { store().webEndpoint = value }

    var sendRate: Long
        get() = store().sendRate
        set(value) { store().sendRate = value }

    var serverPort: Int
        get() = store().serverPort
        set(value) { store().serverPort = value }

    // ── 루팅 모드 전용 설정 ────────────────────────────────

    var botName: String
        get() = store().botName
        set(value) { store().botName = value }

    var botId: Long
        get() = store().botId
        set(value) { store().botId = value }

    var dbPollingRate: Long
        get() = store().dbPollingRate
        set(value) { store().dbPollingRate = value }

    /**
     * 메시지 전송 간격 (루팅 모드 Replier용).
     * 논루팅 모드에서는 sendRate와 동일하게 사용.
     */
    var messageSendRate: Long
        get() = store().messageSendRate
        set(value) {
            store().messageSendRate = value
            _messageSendRateCallback?.invoke()
        }

    private var _messageSendRateCallback: (() -> Unit)? = null

    /**
     * 메시지 전송 간격 변경 시 Replier 재시작을 위한 콜백 등록.
     */
    fun setMessageSendRateCallback(callback: () -> Unit) {
        _messageSendRateCallback = callback
    }

    // ── 모드 설정 ──────────────────────────────────────────

    /**
     * 현재 실행 모드 (null = 자동 감지).
     * 저장값: "ROOT_ADB", "NON_ROOT" (ROOT_MAGISK는 제거됨 — legacy 저장값은 NON_ROOT로 감지됨)
     */
    var appMode: AppMode?
        get() = store().appMode
        set(value) { store().appMode = value }
}
