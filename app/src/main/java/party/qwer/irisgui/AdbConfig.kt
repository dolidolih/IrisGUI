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
        val messageSendRate: Long = 50L,
        /**
         * 브로드캐스트할 이벤트 종류 필터. null/비워둠 = 필터 없음(현행 동작 유지).
         * 값: text, photo, video, audio, file, contact, photo_animation, gif, emoticon, list,
         * app, app_feed, feed, feed_share, talk_memo, long_app, current_user, mchatlog, syncmsg, system,
         * unknown, deleted. ("deleted" = 메시지 삭제 마크 이벤트 — SYNCDLMSG(작성자)/SYNCMODMSG(방장).)
         */
        val broadcastTypes: List<String>? = null,
        /** true(기본) = 현행대로 system(origin) 이벤트를 브로드캐스트에 포함. false = 시스템 이벤트 제외. */
        val includeSystemEvents: Boolean = true,
        /** true면 /ws 브로드캐스트 프레임에 `extension` 필드(타입/리액션/OpenChat비트) 추가. 기본 false = 현행 불변. */
        val enableExtension: Boolean = false
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

    /**
     * 설정을 저장할 경로를 택한다. 파일이 아직 없다는 이유로 경로를 Reject()하지
     * 않도록, 디렉터리에 써서 신규 생성할 수 있는지까지 본다.
     * (File.canWrite()는 존재하지 않는 경로에 대해 false를 반환하므로, 파일 유무와
     *  무관하게 디렉터리 쓰기 가능 여부로 판정해야 신규 설치 후 첫 설정 변경이 보존된다.)
     */
    private fun findWritablePath(): String? {
        val candidates = listOf(
            // /data/local/tmp/를 우선 사용 (원본 Iris와 호환)
            "/data/local/tmp/$CONFIG_FILENAME",
            "/data/$CONFIG_FILENAME",
        )
        for (path in candidates) {
            val file = File(path)
            if (file.isFile) {
                if (file.canWrite()) return path
            } else {
                val dir = file.parentFile ?: continue
                if (dir.isDirectory && dir.canWrite()) return path
            }
        }
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
        val path = findWritablePath()
        if (path == null) {
            println("AdbConfig: 쓰기 가능한 config 경로를 찾지 못해 설정 변경이 저장되지 않음")
            return
        }
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

    var broadcastTypes: List<String>?
        get() = config.broadcastTypes
        set(value) { config = config.copy(broadcastTypes = value); saveConfig() }

    var enableExtension: Boolean
        get() = config.enableExtension
        set(value) { config = config.copy(enableExtension = value); saveConfig() }

    var includeSystemEvents: Boolean
        get() = config.includeSystemEvents
        set(value) { config = config.copy(includeSystemEvents = value); saveConfig() }

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
