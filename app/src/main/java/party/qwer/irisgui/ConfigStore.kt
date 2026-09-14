package party.qwer.irisgui

import android.content.Context
import android.content.SharedPreferences

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

    /** 저장소 초기화 (NON_ROOT 모드에서만 필요) */
    fun init(context: Context)
}

/**
 * SharedPreferences 기반 ConfigStore (NON_ROOT 모드)
 */
class SharedPrefConfigStore : ConfigStore {
    private lateinit var prefs: SharedPreferences

    override fun init(context: Context) {
        prefs = context.getSharedPreferences("IrisGuiPrefs", Context.MODE_PRIVATE)
    }

    override var isServiceEnabled: Boolean
        get() = prefs.getBoolean("isServiceEnabled", false)
        set(v) { prefs.edit().putBoolean("isServiceEnabled", v).apply() }

    override var webEndpoint: String
        get() = prefs.getString("webEndpoint", "") ?: ""
        set(v) { prefs.edit().putString("webEndpoint", v).apply() }

    override var sendRate: Long
        get() = prefs.getLong("sendRate", 500L)
        set(v) { prefs.edit().putLong("sendRate", v).apply() }

    override var serverPort: Int
        get() = prefs.getInt("serverPort", 3000)
        set(v) { prefs.edit().putInt("serverPort", v).apply() }

    override var botName: String
        get() = prefs.getString("botName", "Iris") ?: "Iris"
        set(v) { prefs.edit().putString("botName", v).apply() }

    override var botId: Long
        get() = prefs.getLong("botId", 0L)
        set(v) { prefs.edit().putLong("botId", v).apply() }

    override var dbPollingRate: Long
        get() = prefs.getLong("dbPollingRate", 100L)
        set(v) { prefs.edit().putLong("dbPollingRate", v).apply() }

    override var messageSendRate: Long
        get() = prefs.getLong("messageSendRate", 50L)
        set(v) { prefs.edit().putLong("messageSendRate", v).apply() }

    override var appMode: AppMode?
        get() {
            val raw = prefs.getString("appMode", null) ?: return null
            return try { AppMode.valueOf(raw) } catch (_: IllegalArgumentException) { null }
        }
        set(v) { prefs.edit().putString("appMode", v?.name).apply() }
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

    override fun init(context: Context) {
        // ADB 모드: Context 없음 → 초기화 필요 없음
        // prefs 초기화 시 JSON에 복사
        AdbConfig.loadFromPrefs()
    }
}

/**
 * ConfigStore 팩토리 — 현재 모드에 따라 적절한 구현체 반환
 */
object ConfigStoreFactory {
    @Volatile
    private var instance: ConfigStore? = null

    fun get(): ConfigStore {
        instance?.let { return it }
        synchronized(this) {
            instance?.let { return it }
            instance = if (AppConfig.isInitialized) {
                SharedPrefConfigStore()
            } else {
                JsonConfigStore()
            }
        }
        return instance!!
    }
}
