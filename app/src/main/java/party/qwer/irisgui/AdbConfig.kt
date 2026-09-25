package party.qwer.irisgui

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import party.qwer.irisgui.backend.PathUtils
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * ADB 모드용 설정 — SharedPreferences 없이 JSON 파일로 저장/읽기.
 * Android Context가 없는 app_process 환경에서 동작.
 *
 * ISSUE-04 — 하나의 저장소를 두 세계가 동시에 보므로(앱: SharedPreferences / 데몬: JSON)
 * 아래 규칙으로 값 드리프트를 막는다:
 *  - 저장은 tmp -> rename 원자적 기록 + `.bak` 스냅샷. 읽기에 실패해도 파일을
 *    기본값으로 덮어쓰지 않는다 (중간 크래시 후 설정 전체가 조용히 사라지던 경로 차단).
 *  - 모든 setter는 잠금 하에 read-modify-write이고 `revision`을 올린다. 무엇이 언제
 *    바뀌었는지 로그로 추적할 수 있다.
 *  - 앱의 변경 사항은 앱 filesDir의 `live_config.json` 오버레이로 발행하고, 데몬은
 *    sender loop에서 mtime 변화분만 반영한다 -> 재시작 없이 rate/필터가 살아난다.
 */
object AdbConfig {
    private const val CONFIG_FILENAME = "IrisGUI.json"

    /** 데몬이 주기별로 주시하는 앱 발행 live-config 오버레이 (루트가 읽는 앱 filesDir). */
    private const val OVERLAY_FILENAME = "live_config.json"
    private const val APP_PACKAGE = "party.qwer.irisgui"

    /** 알 수 없는 키(구버전/신버전 혼용) 때문에 기본값으로 되돌아가지 않도록 느슨하게 읽는다. */
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    private val writeLock = Any()

    @Volatile
    private var config: Config = Config()

    /** 오버레이 반영에 사용한 마지막 mtime — 변화가 없으면 다시 읽지 않는다. */
    @Volatile
    private var appliedOverlayMtime: Long = -1

    /** 앱 프로세스에서 publish에 쓸 경로 (SharedPrefConfigStore.init에서 지정). */
    @Volatile
    private var overlayPath: File? = null

    init {
        config = loadConfig()
    }

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
        val enableExtension: Boolean = false,
        /** ISSUE-04: 바꿀 때마다 올라가는 이력 번호 - 저장소 드리프트 진단용. */
        val revision: Long = 0
    )

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
                        mutate { it.copy(botId = detectedId) }
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

    /** config 파일이 실제로 존재하는가 - 빈 저장소 기본값을 prefs에 복사하지 않으려는 판정. */
    private fun configFileExists(): Boolean =
        findWritablePath()?.let { File(it).isFile } == true

    private fun loadConfig(): Config {
        val path = findWritablePath() ?: return Config()
        val file = File(path)
        if (!file.isFile) {
            // 파일이 없을 때만 기본값 파일을 만든다. 읽기에 실패한 경우에는 절대
            // 기존 파일을 기본값으로 덮어쓰지 않는다 (크래시 후 설정 유실 방지 - ISSUE-04).
            val fresh = Config(revision = 1)
            config = fresh
            persist(file.absolutePath)
            println("AdbConfig: $path not found, creating default config")
            return fresh
        }
        return try {
            json.decodeFromString(Config.serializer(), file.readText())
        } catch (e: Exception) {
            val bak = File("$path.bak")
            if (bak.isFile) {
                runCatching { json.decodeFromString(Config.serializer(), bak.readText()) }
                    .onSuccess {
                        println("AdbConfig: main config 읽기 실패 ($e) - ${bak}로 되돌림")
                        return it
                    }
            }
            println("AdbConfig: Failed to read $path: ${e.message} — keeping file as-is, using defaults")
            Config()
        }
    }

    /** tmp → rename 원자적 저장 + `.bak` 스냅샷. 성공 시 새 mtime을 반영한다. */
    private fun persist(path: String) {
        val payload = json.encodeToString(Config.serializer(), config)
        val target = File(path)
        val tmp = File("$path.tmp")
        tmp.writeText(payload)
        runCatching {
            tmp.setReadable(false, false)
            tmp.setReadable(true, true)
            tmp.setWritable(false, false)
            tmp.setWritable(true, true)
        }
        if (target.isFile) {
            runCatching { target.copyTo(File("$path.bak"), overwrite = true) }
        }
        if (!tmp.renameTo(target)) {
            // rename 불가(크로스 파일시스템/퍼미션)일 때만 인플레이스 — 어차피 원자적이
            // 아니어도 되는 예외 경로지만 차라리 이 편이 아무것도 못 쓰는 것보다 낫다.
            target.writeText(payload)
            tmp.delete()
        }
    }

    private fun saveConfig() {
        val path = findWritablePath()
        if (path == null) {
            println("AdbConfig: 쓸 수 있는 config 경로가 없어 설정 변경이 저장되지 않음")
            return
        }
        try {
            persist(path)
        } catch (e: Exception) {
            System.err.println("AdbConfig: Failed to write $path: ${e.message}")
        }
    }

    /** 모든 변경의 단일 입구 - 잠금 하에 read-modify-write, revision 증가. */
    private inline fun mutate(block: (Config) -> Config) {
        val changed: Config
        synchronized(writeLock) {
            changed = block(config).copy(revision = config.revision + 1)
            config = changed
        }
        saveConfig()
        println("AdbConfig: config 변경 (revision ${changed.revision})")
    }

    /** 데몬/앱 공통: 현재 config의 이력 번호 (저장소 드리프트 진단/로그용). */
    val revision: Long get() = config.revision

    /** 현재 값을 한 줄로 - "UI에서 바꿨는데 데몬이 그대로" 추적의 발판. */
    fun describe(): String =
        "AdbConfig{rev=$revision mode=${config.appMode} port=${config.serverPort} " +
            "sendRate=${config.sendRate} msgRate=${config.messageSendRate} dbRate=${config.dbPollingRate} " +
            "botId=${config.botId} botName=${config.botName}}"

    // ── 접근자 ────────────────────────────────────────────

    var appMode: String
        get() = config.appMode
        set(value) { mutate { it.copy(appMode = value) } }

    var isServiceEnabled: Boolean
        get() = config.isServiceEnabled
        set(value) { mutate { it.copy(isServiceEnabled = value) } }

    var webEndpoint: String
        get() = config.webEndpoint
        set(value) { mutate { it.copy(webEndpoint = value) } }

    var sendRate: Long
        get() = config.sendRate
        set(value) { mutate { it.copy(sendRate = value) } }

    var serverPort: Int
        get() = config.serverPort
        set(value) { mutate { it.copy(serverPort = value) } }

    var botName: String
        get() = config.botName
        set(value) { mutate { it.copy(botName = value) } }

    var botId: Long
        get() = config.botId
        set(value) { mutate { it.copy(botId = value) } }

    var dbPollingRate: Long
        get() = config.dbPollingRate
        set(value) { mutate { it.copy(dbPollingRate = value) } }

    var messageSendRate: Long
        get() = config.messageSendRate
        set(value) { mutate { it.copy(messageSendRate = value) } }

    var broadcastTypes: List<String>?
        get() = config.broadcastTypes
        set(value) { mutate { it.copy(broadcastTypes = value) } }

    var enableExtension: Boolean
        get() = config.enableExtension
        set(value) { mutate { it.copy(enableExtension = value) } }

    var includeSystemEvents: Boolean
        get() = config.includeSystemEvents
        set(value) { mutate { it.copy(includeSystemEvents = value) } }

    /** SharedPreferences → AdbConfig(JSON) — prefs 초기화 시 JSON 동기화 (L1 명명 수정) */
    fun loadFromPrefs() {
        val store = ConfigStoreFactory.get()
        if (store !is SharedPrefConfigStore) return // 데몬은 prefs 저장소가 없어 자기 JSON이 곧 원본
        val snapshot = Config(
            appMode = store.appMode?.name ?: "ROOT_ADB",
            isServiceEnabled = store.isServiceEnabled,
            webEndpoint = store.webEndpoint,
            sendRate = store.sendRate,
            serverPort = store.serverPort,
            botName = store.botName,
            botId = store.botId,
            dbPollingRate = store.dbPollingRate,
            messageSendRate = store.messageSendRate,
            broadcastTypes = store.broadcastTypes,
            includeSystemEvents = store.includeSystemEvents,
            enableExtension = store.enableExtension,
            revision = config.revision + 1
        )
        synchronized(writeLock) { config = snapshot }
        saveConfig()
        println("AdbConfig: loaded from SharedPreferences (revision ${snapshot.revision})")
    }

    /**
     * AdbConfig(JSON) → SharedPreferences — UI에서 설정 표시용 (L1 명명 수정).
     * ISSUE-04: JSON 파일이 없는데 기본값을 prefs에 흘려 넣어 UI 설정을 초기화하는 일을 막는다.
     */
    @JvmStatic
    fun saveToPrefs() {
        val store = ConfigStoreFactory.get()
        if (store !is SharedPrefConfigStore) {
            println("AdbConfig: prefs 저장소 없음 — saveToPrefs 생략 (daemon JSON가 원본)")
            return
        }
        if (!configFileExists()) {
            println("AdbConfig: config 파일 없음 — prefs 보존 (복사 안 함)")
            return
        }
        val from = describe()
        store.appMode = runCatching { AppMode.valueOf(config.appMode) }.getOrNull()
        store.isServiceEnabled = config.isServiceEnabled
        store.webEndpoint = config.webEndpoint
        store.sendRate = config.sendRate
        store.serverPort = config.serverPort
        store.botName = config.botName
        store.botId = config.botId
        store.dbPollingRate = config.dbPollingRate
        store.messageSendRate = config.messageSendRate
        store.broadcastTypes = config.broadcastTypes
        store.includeSystemEvents = config.includeSystemEvents
        store.enableExtension = config.enableExtension
        println("AdbConfig: applied into prefs ← $from")
    }

    // ── live-config 오버레이 (ISSUE-04: 재시작 없는 앱→데몬 반영) ──────────

    /** 앱 프로세스: 데몬이 읽을 오버레이 경로를 지정한다 (SharedPrefConfigStore.init에서 호출). */
    fun setOverlayPath(path: File?) {
        overlayPath = path
    }

    /** 데몬이 읽어갈 오버레이 후보 경로 (앱 filesDir = root 읽기 가능, /data/local/tmp 와 달리 비공개). */
    private fun overlayCandidates(): List<File> {
        val env = System.getenv("IRISGUI_LIVE_CONFIG")?.takeIf { it.isNotBlank() }?.let { listOf(File(it)) }
            ?: emptyList()
        return env + listOf(
            File("/data/user/0/$APP_PACKAGE/files/$OVERLAY_FILENAME"),
            File("/data/data/$APP_PACKAGE/files/$OVERLAY_FILENAME")
        )
    }

    /**
     * 현재 prefs-저장소 값을 데몬용 오버레이로 발행한다. 데몬 기동 직전(IrisService)과
     * prefs 쓸 때마다 호출된다. 실패해도 예외를 던지지 않는다 — 설정 경로가 막혀 본 기능을
     * 못 쓰는 일은 없어야 한다.
     */
    fun publishLiveOverlay(store: ConfigStore) {
        if (!AppConfig.isInitialized) return // 데몬: 자기가 저장소 원본 — 쓸 필요 없음
        val target = overlayPath
            ?: overlayCandidates().firstOrNull { it.parentFile?.isDirectory == true }
            ?: return
        val snapshot = try {
            Config(
                appMode = store.appMode?.name ?: config.appMode,
                isServiceEnabled = store.isServiceEnabled,
                webEndpoint = store.webEndpoint,
                sendRate = store.sendRate,
                serverPort = store.serverPort,
                botName = store.botName,
                botId = store.botId,
                dbPollingRate = store.dbPollingRate,
                messageSendRate = store.messageSendRate,
                broadcastTypes = store.broadcastTypes,
                includeSystemEvents = store.includeSystemEvents,
                enableExtension = store.enableExtension,
                revision = storeRevision(store)
            )
        } catch (e: Exception) {
            System.err.println("AdbConfig: overlay snapshot failed: $e")
            return
        }
        try {
            val tmp = File(target.absolutePath + ".tmp")
            tmp.writeText(json.encodeToString(Config.serializer(), snapshot))
            runCatching {
                tmp.setReadable(false, false); tmp.setReadable(true, true)
                tmp.setWritable(false, false); tmp.setWritable(true, true)
            }
            if (!tmp.renameTo(target)) {
                target.writeText(json.encodeToString(Config.serializer(), snapshot))
                tmp.delete()
            }
        } catch (e: Exception) {
            System.err.println("AdbConfig: live overlay write failed ($target): $e")
        }
    }

    private fun storeRevision(store: ConfigStore): Long = config.revision + 1

    /**
     * 현재 유효한 설정(AppConfig가 보는 저장소)을 오버레이로 다시 쓴다. 데몬 기동
     * 직전(IrisService)처럼 "설정 변경이 없었어도 지금 상태를 확실히" 넘기고 싶을
     * 때 쓰는 입구.
     */
    fun publishCurrentConfig() = publishLiveOverlay(ConfigStoreFactory.get())

    /** 데몬 사이드: 앱이 발행한 오버레이를 mtime 변화시에만 반영한다.
     * Replier sender loop에서 매 반복마다 호출되므로 rate/filter 변경이 재시작 없이 살아난다.
     * @return 반영이 있었던 경우 true
     */
    fun reloadOverlayIfNeeded(): Boolean {
        val newest = overlayCandidates()
            .mapNotNull { f -> if (f.isFile) f to f.lastModified() else null }
            .maxByOrNull { it.second }
            ?: return false
        if (newest.second <= appliedOverlayMtime) return false
        appliedOverlayMtime = newest.second
        val overlay = try {
            json.decodeFromString(Config.serializer(), newest.first.readText())
        } catch (e: Exception) {
            System.err.println("AdbConfig: overlay unreadable ($newest): $e")
            return false
        }
        val changed = describeChanges(config, overlay)
        if (changed.isEmpty()) return true
        synchronized(writeLock) { config = mergeOverlay(config, overlay) }
        saveConfig()
        println("AdbConfig: live overlay 반영 (${newest.first}) ${describe()} - 바뀐 항목 $changed")
        return true
    }

    /**
     * 병합 규칙:
     *  - botId는 데몬 소유(자동 감지 값). 앱측 0으로 살아있는 id를 덮어쓰면 복호화가
     *    통째로 깨지므로 데몬 값이 0일 때만 오버레이를 신뢰한다 (ISSUE-34 연계).
     *  - 나머지 필드는 앱 UI가 원본 — 오버레이 값을 따른다.
     */
    private fun mergeOverlay(current: Config, overlay: Config): Config = current.copy(
        appMode = overlay.appMode,
        isServiceEnabled = overlay.isServiceEnabled,
        webEndpoint = overlay.webEndpoint,
        sendRate = overlay.sendRate,
        serverPort = overlay.serverPort,
        botName = overlay.botName,
        botId = if (current.botId != 0L) current.botId else overlay.botId,
        dbPollingRate = overlay.dbPollingRate,
        messageSendRate = overlay.messageSendRate,
        broadcastTypes = overlay.broadcastTypes,
        includeSystemEvents = overlay.includeSystemEvents,
        enableExtension = overlay.enableExtension,
        revision = current.revision + 1
    )

    private fun describeChanges(current: Config, overlay: Config): List<String> {
        val diff = mutableListOf<String>()
        if (current.appMode != overlay.appMode) diff += "appMode=${overlay.appMode}"
        if (current.isServiceEnabled != overlay.isServiceEnabled) diff += "isServiceEnabled=${overlay.isServiceEnabled}"
        if (current.webEndpoint != overlay.webEndpoint) diff += "webEndpoint=${overlay.webEndpoint}"
        if (current.sendRate != overlay.sendRate) diff += "sendRate=${overlay.sendRate}"
        if (current.serverPort != overlay.serverPort) diff += "serverPort=${overlay.serverPort}"
        if (current.botName != overlay.botName) diff += "botName=${overlay.botName}"
        if (current.botId != overlay.botId && current.botId != 0L) diff += "botId(kept ${current.botId})"
        if (current.dbPollingRate != overlay.dbPollingRate) diff += "dbPollingRate=${overlay.dbPollingRate}"
        if (current.messageSendRate != overlay.messageSendRate) diff += "messageSendRate=${overlay.messageSendRate}"
        if (current.broadcastTypes != overlay.broadcastTypes) diff += "broadcastTypes=${overlay.broadcastTypes}"
        if (current.includeSystemEvents != overlay.includeSystemEvents) diff += "includeSystemEvents=${overlay.includeSystemEvents}"
        if (current.enableExtension != overlay.enableExtension) diff += "enableExtension=${overlay.enableExtension}"
        return diff
    }
}
