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
        private set

    fun init(context: Context) {
        // ISSUE-04: prefs 저장소를 bind하기 전에 플래그를 세운다 — ConfigStoreFactory가
        // isInitialized를 보고 구현을 고르기 때문에, init 전 초기자가 만든 JSON 바인딩에
        // 앱 수명 전체가 묶여 있더라도 여기서 되돌아온다.
        isInitialized = true
        configStore = SharedPrefConfigStore().apply { init(context) }
    }

    /**
     * ConfigStore 접근. 초기화 여부는 고정하지 않고 그때그때 factory로 택한다 —
     * init 이전의 getter 호출 하나가 프로세스 전체 수명을 JSON 저장소에 묶던
     * 드립트를 막기 위한 변경 (ISSUE-04).
     */
    private fun store(): ConfigStore {
        val current = configStore
        if (current != null && (current is SharedPrefConfigStore) == isInitialized) return current
        return ConfigStoreFactory.get().also { configStore = it }
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

    /** 브로드캐스트 이벤트 필터 (ADB 모드). null = 필터 없음. */
    var broadcastTypes: List<String>?
        get() = store().broadcastTypes
        set(value) { store().broadcastTypes = value }

    /** extension 필드 전송 여부. */
    var enableExtension: Boolean
        get() = store().enableExtension
        set(value) { store().enableExtension = value }

    /** system(origin) 이벤트를 브로드캐스트에 포함할지. */
    var includeSystemEvents: Boolean
        get() = store().includeSystemEvents
        set(value) { store().includeSystemEvents = value }
}
