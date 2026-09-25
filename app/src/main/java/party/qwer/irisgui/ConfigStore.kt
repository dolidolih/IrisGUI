package party.qwer.irisgui

import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * ConfigStore — SharedPreferences / JSON 저장소 추상화 (A2)
 *
 * ADB 모드(Context 없음): AdbConfig(JSON) 사용
 * 일반 모드: SharedPreferences 사용
 *
 * 단일 인터페이스로 양쪽 저장소를 투명하게 접근.
 */
interface ConfigStore {
    var isServiceEnabled: Boolean
    var webEndpoint: String
    var sendRate: Long
    var serverPort: Int
    var botName: String
    var botId: Long
    var dbPollingRate: Long
    var messageSendRate: Long
    var appMode: AppMode?

    /** 브로드캐스트 이벤트 필터. null = 현행 동작 유지. */
    var broadcastTypes: List<String>?

    /** true(기본) = system(origin) 이벤트를 브로드캐스트에 포함 (현행 동작). */
    var includeSystemEvents: Boolean

    /** extension 필드 전송 여부 (false = 현행 불변). */
    var enableExtension: Boolean

    /** 저장소 초기화 (NON_ROOT 모드에서만 필요) */
    fun init(context: Context)
}

/**
 * SharedPreferences 기반 ConfigStore (NON_ROOT 모드)
 *
 * ISSUE-04: 쓰기마다 현재 값을 live_config.json 오버레이로도 발행한다. ROOT_ADB에서
 * UI(SharedPreferences)와 실행 중 데몬(JSON)이 갈라져 launch 시 port 인자만 통과하던
 * 드리프트의 통로 — 앱 filesDir는 root가 읽을 수 있어 데몬가 그대로 반영한다.
 */
class SharedPrefConfigStore : ConfigStore {
    private lateinit var prefs: SharedPreferences

    override fun init(context: Context) {
        prefs = context.getSharedPreferences("IrisGuiPrefs", Context.MODE_PRIVATE)
        AdbConfig.setOverlayPath(File(context.filesDir, "live_config.json"))
    }

    /** 저장 후 같은 값을 오버레이로 재발행 — 앱측 설정 변경을 데몬가 곧 반영한다. */
    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs.edit()
        block(editor)
        editor.apply()
        runCatching { AdbConfig.publishLiveOverlay(this) }
            .onFailure { System.err.println("SharedPrefConfigStore: overlay publish failed: $it") }
    }

    override var isServiceEnabled: Boolean
        get() = prefs.getBoolean("isServiceEnabled", false)
        set(v) = edit { putBoolean("isServiceEnabled", v).apply() }

    override var webEndpoint: String
        get() = prefs.getString("webEndpoint", "") ?: ""
        set(v) = edit { putString("webEndpoint", v).apply() }

    override var sendRate: Long
        get() = prefs.getLong("sendRate", 500L)
        set(v) = edit { putLong("sendRate", v).apply() }

    override var serverPort: Int
        get() = prefs.getInt("serverPort", 3000)
        set(v) = edit { putInt("serverPort", v).apply() }

    override var botName: String
        get() = prefs.getString("botName", "Iris") ?: "Iris"
        set(v) = edit { putString("botName", v).apply() }

    override var botId: Long
        get() = prefs.getLong("botId", 0L)
        set(v) = edit { putLong("botId", v).apply() }

    override var dbPollingRate: Long
        get() = prefs.getLong("dbPollingRate", 100L)
        set(v) = edit { putLong("dbPollingRate", v).apply() }

    override var messageSendRate: Long
        get() = prefs.getLong("messageSendRate", 50L)
        set(v) = edit { putLong("messageSendRate", v).apply() }

    override var appMode: AppMode?
        get() {
            val raw = prefs.getString("appMode", null) ?: return null
            // 알아듣지 못하는 저장값을 몰래 ROOT_ADB로 고정하지 않고 null(자동 감지)로
            // 다룬다. 자동 감지가 틀릴 수는 있어도 조용히 한 모드로 묶이는 것보다 낫다.
            return try { AppMode.valueOf(raw) } catch (_: IllegalArgumentException) {
                System.err.println("SharedPrefConfigStore: unrecognized appMode '" + raw + "' - auto-detect instead")
                null
            }
        }
        set(v) = edit { putString("appMode", v?.name).apply() }

    override var broadcastTypes: List<String>?
        get() {
            val raw = prefs.getStringSet("broadcastTypes", null) ?: return null
            return raw.toList()
        }
        set(v) = edit { putStringSet("broadcastTypes", v?.toSet()) }

    override var includeSystemEvents: Boolean
        get() = prefs.getBoolean("includeSystemEvents", true)
        set(v) = edit { putBoolean("includeSystemEvents", v).apply() }

    override var enableExtension: Boolean
        get() = prefs.getBoolean("enableExtension", false)
        set(v) = edit { putBoolean("enableExtension", v).apply() }
}

/**
 * JSON 파일 기반 ConfigStore (ADB 모드)
 * AdbConfig의 접근자를 Delegated로 제공.
 */
class JsonConfigStore : ConfigStore {
    override var isServiceEnabled: Boolean
        get() = AdbConfig.isServiceEnabled
        set(v) { AdbConfig.isServiceEnabled = v }

    override var webEndpoint: String
        get() = AdbConfig.webEndpoint
        set(v) { AdbConfig.webEndpoint = v }

    override var sendRate: Long
        get() = AdbConfig.sendRate
        set(v) { AdbConfig.sendRate = v }

    override var serverPort: Int
        get() = AdbConfig.serverPort
        set(v) { AdbConfig.serverPort = v }

    override var botName: String
        get() = AdbConfig.botName
        set(v) { AdbConfig.botName = v }

    override var botId: Long
        get() = AdbConfig.botId
        set(v) { AdbConfig.botId = v }

    override var dbPollingRate: Long
        get() = AdbConfig.dbPollingRate
        set(v) { AdbConfig.dbPollingRate = v }

    override var messageSendRate: Long
        get() = AdbConfig.messageSendRate
        set(v) { AdbConfig.messageSendRate = v }

    override var appMode: AppMode?
        get() = runCatching { AppMode.valueOf(AdbConfig.appMode) }.getOrNull()
        set(v) { AdbConfig.appMode = v?.name ?: "ROOT_ADB" }

    override var broadcastTypes: List<String>?
        get() = AdbConfig.broadcastTypes
        set(v) { AdbConfig.broadcastTypes = v }

    override var includeSystemEvents: Boolean
        get() = AdbConfig.includeSystemEvents
        set(v) { AdbConfig.includeSystemEvents = v }

    override var enableExtension: Boolean
        get() = AdbConfig.enableExtension
        set(v) { AdbConfig.enableExtension = v }

    override fun init(context: Context) {
        // ADB 모드: Context 없음 → 초기화 필요 없음
        // prefs 초기화 시 JSON에 복사
        AdbConfig.loadFromPrefs()
    }

    /**
     * JSON 저장소는 그 자체가 원본(데몬)이라 오버레이 발행할 것이 없다.
     * 앱 프로세스(prefs 원본)에서만 SharedPrefConfigStore가 publishLiveOverlay를 탄다.
     */
    fun publishOverlay() = Unit

}

/**
 * ConfigStore 팩토리 — 저장 상태(isInitialized)에 맞는 구현을 고른다.
 *
 * ISSUE-04: 예전에는 최초 호출 시점의 AppConfig.isInitialized 하나로 영구 바인딩해서,
 * init 이전 getter 호출 하나가 프로세스 수명 전체를 JSON 저장소에 묶었다
 * (=UI는 SharedPreferences에 쓰는데 데몬은 JSON만 보는 드리프트). 초기화 여부가
 * 바뀌면 바인딩도 따라 바뀌도록 재평가한다.
 */
object ConfigStoreFactory {
    @Volatile
    private var instance: ConfigStore? = null

    fun get(): ConfigStore {
        val wantPrefs = AppConfig.isInitialized
        instance?.let { if ((it is SharedPrefConfigStore) == wantPrefs) return it }
        return synchronized(this) {
            instance?.takeIf { (it is SharedPrefConfigStore) == wantPrefs } ?: run {
                val created: ConfigStore = if (wantPrefs) initializedPrefsStore() else JsonConfigStore()
                instance = created
                created
            }
        }
    }

    /**
     * 팩토리가 lazy하게 만드는 SharedPreferences 저장소는 init(context)를 받지 못한 채로
     * 태어날 수 있다 (A2 이후 이 경로가 사실상 데몬 전용이었기 때문). lateinit prefs를
     * 읽고 터지는 일이 없도록 앱 Context를 찾아 붙이고, 못 찾으면 JSON으로 폴백한다.
     */
    private fun initializedPrefsStore(): ConfigStore {
        val store = SharedPrefConfigStore()
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null)
        }.getOrNull() as? Context ?: run {
            System.err.println("ConfigStoreFactory: app Context 없음 - JSON 저장소로 폴백")
            return JsonConfigStore()
        }
        store.init(app)
        return store
    }
}
