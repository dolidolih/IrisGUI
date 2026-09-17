package party.qwer.irisgui

import android.database.sqlite.SQLiteDatabase
import party.qwer.irisgui.backend.PathUtils
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
/**
 * ADB 모드용 설정 — SharedPreferences 없이 JSON 파일로 저장/읽기.
 * Android Context가 없는 app_process 환경에서 동작.
 */
object AdbConfig {
    private const val CONFIG_FILENAME = "IrisGUI.json"

    private val json = Json { encodeDefaults = true }

    @Serializable
    data class Config(
        val appMode: String = "ROOT_ADB",
        val isServiceEnabled: Boolean = false,
        val webEndpoint: String = "",
        val sendRate: Long = 500L,
        val serverPort: Int = 3000,
        val botName: String = "Iris",
        val botId: Long = 0L,
        val dbPollingRate: Long = 100L,
        val messageSendRate: Long = 50L
    )

    private var config: Config = loadConfig()

    /**
     * KakaoTalk DB에서 botId 자동 감지
     * chat_logs에서 isMine:true인 사용자의 user_id를 찾는다.
     * AdbServer.kt에서 KakaoDB를 초기화하면 자동으로 botId가 설정됨.
     */
    fun detectBotId() {
        try {
            val appPath = PathUtils.getAppPathOrNull()
            if (appPath == null) {
                println("AdbConfig: KakaoTalk 이 설치되지 않음. botId=${config.botId} 유지.")
                return
            }
            val dbPath = "${appPath}databases/KakaoTalk.db"
            if (!File(dbPath).exists()) {
                println("AdbConfig: KakaoTalk 은 설치되어 있나 DB 파일이 없음. path=$dbPath")
                return
            }
            val db = SQLiteDatabase.openDatabase(dbPath, null, SQLiteDatabase.OPEN_READONLY)
            db.rawQuery(
                "SELECT user_id FROM chat_logs WHERE v LIKE '%\"isMine\":true%' ORDER BY _id DESC LIMIT 1",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val detectedId = cursor.getLong(0)
                    if (detectedId != config.botId) {
                        config = config.copy(botId = detectedId)
                        saveConfig()
                        println("AdbConfig: botId auto-detected: $detectedId")
                    }
                } else {
                    println("AdbConfig: KakaoTalk DB를 찾았지만 내 계정의 isMine 레코드가 없어 botId 미파악. botId=${config.botId} 유지.")
                }
            }
            db.close()
        } catch (e: Exception) {
            println("AdbConfig: Failed to detect botId: ${e.message}")
        }
    }

    private fun findWritablePath(): String? {
        // /data/local/tmp/가 있으면 사용 (원본 Iris와 호환)
        val tmp = "/data/local/tmp/$CONFIG_FILENAME"
        if (File(tmp).canWrite()) return tmp

        // /data/local/tmp/가 없으면 /data/에 직접 저장
        val data = "/data/$CONFIG_FILENAME"
        if (File(data).canWrite()) return data

        return null
    }

    private fun loadConfig(): Config {
        val path = findWritablePath() ?: return Config()
        val file = File(path)
        return if (file.exists()) {
            try {
                json.decodeFromString(Config.serializer(), file.readText())
            } catch (e: Exception) {
                println("AdbConfig: Failed to read $path: ${e.message}, using defaults")
                Config()
            }
        } else {
            println("AdbConfig: $path not found, creating default config")
            saveConfig()
            Config()
        }
    }

    private fun saveConfig() {
        val path = findWritablePath() ?: return
        try {
            File(path).writeText(json.encodeToString(Config.serializer(), config))
        } catch (e: Exception) {
            System.err.println("AdbConfig: Failed to write $path: ${e.message}")
        }
    }

    // ── 접근자 ────────────────────────────────────────────

    var appMode: String
        get() = config.appMode
        set(value) { config = config.copy(appMode = value); saveConfig() }

    var isServiceEnabled: Boolean
        get() = config.isServiceEnabled
        set(value) { config = config.copy(isServiceEnabled = value); saveConfig() }

    var webEndpoint: String
        get() = config.webEndpoint
        set(value) { config = config.copy(webEndpoint = value); saveConfig() }

    var sendRate: Long
        get() = config.sendRate
        set(value) { config = config.copy(sendRate = value); saveConfig() }

    var serverPort: Int
        get() = config.serverPort
        set(value) { config = config.copy(serverPort = value); saveConfig() }

    var botName: String
        get() = config.botName
        set(value) { config = config.copy(botName = value); saveConfig() }

    var botId: Long
        get() = config.botId
        set(value) { config = config.copy(botId = value); saveConfig() }

    var dbPollingRate: Long
        get() = config.dbPollingRate
        set(value) { config = config.copy(dbPollingRate = value); saveConfig() }

    var messageSendRate: Long
        get() = config.messageSendRate
        set(value) { config = config.copy(messageSendRate = value); saveConfig() }

    /**
     * SharedPreferences → AdbConfig(JSON) — prefs 초기화 시 JSON 동기화 (L1 명명 수정)
     */
    fun loadFromPrefs() {
        val store = ConfigStoreFactory.get()
        config = Config(
            appMode = store.appMode?.name ?: "ROOT_ADB",
            isServiceEnabled = store.isServiceEnabled,
            webEndpoint = store.webEndpoint,
            sendRate = store.sendRate,
            serverPort = store.serverPort,
            botName = store.botName,
            botId = store.botId,
            dbPollingRate = store.dbPollingRate,
            messageSendRate = store.messageSendRate
        )
        saveConfig()
    }

    /**
     * AdbConfig(JSON) → SharedPreferences — UI에서 설정 표시용 (L1 명명 수정)
     */
    @JvmStatic
    fun saveToPrefs() {
        val store = ConfigStoreFactory.get()
        store.appMode = runCatching { AppMode.valueOf(config.appMode) }.getOrNull()
        store.isServiceEnabled = config.isServiceEnabled
        store.webEndpoint = config.webEndpoint
        store.sendRate = config.sendRate
        store.serverPort = config.serverPort
        store.botName = config.botName
        store.botId = config.botId
        store.dbPollingRate = config.dbPollingRate
        store.messageSendRate = config.messageSendRate
    }
}
