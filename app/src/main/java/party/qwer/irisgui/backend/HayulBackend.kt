package party.qwer.irisgui.backend

import android.database.sqlite.SQLiteDatabase
import party.qwer.irisgui.AdbConfig
import java.io.File
import java.util.concurrent.TimeUnit
import android.content.Context

/**
 * HayulBackend — HAYUL(shared-uid) 모드 인프로세스 백엔드.
 *
 * HayulGUI 패치로 이 앱은 com.kakao.talk 와 같은 uid 를 공유한다 — root/데몬 없이
 * /data/data/com.kakao.talk 의 DB·파일을 앱 프로세스에서 직접 읽고 쓸 수 있다.
 * 그래서 ROOT_ADB 의 워스셋(DBObserver + Replier + AdbServer + ImageDeleter)을
 * app_process 대신 앱 안에서 그대로 띄운다 (Main.kt 의 인프로세스형). daemon 도 NLS 도 쓰지 않는다.
 *
 * 시작 전 게이트: "카톡 DB 읽기 가능 여부" (readabilityCheck). 읽기 실패면 워스셋을
 * 하나도 띄우지 않고 사유만 돌려준다 — 패치 미적용/서명 불일치 상태에서조용히 half-start
 * 되지 않도록 (상태 탭의 "미실행"과 로그의 원인이 일치해야 한다).
 *
 * 설정은 AppConfig(prefs) 가 원본 — AdbConfig.loadFromPrefs() 로 서버가 바인딩할
 * 포트/주기를 동기화한다 (JSON 없는 앱 프로세스에서의 findWritablePath 실패는 조용히 no-op).
 */
object HayulBackend {
    private val lock = Any()

    @Volatile
    private var dbObserver: DBObserver? = null

    @Volatile
    private var imageDeleter: ImageDeleter? = null

    /** 서버 + DB 관찰자가 함께 살아있는지만. watchdog 은 같은 값으로 생존을 판정한다. */
    val isRunning: Boolean
        get() = AdbServer.isStarted && dbObserver != null

    /**
     * 카톡 DB/경로 읽기 게이트.
     * @return null = 정상 기동 가능. null 이 아니면 사유 (문구를 상태/로그에 그대로 쓴다).
     */
    fun readabilityCheck(): String? {
        val appPath = PathUtils.precompute() ?: return PathUtils.describeMissing()
        val db = File(appPath, "databases/KakaoTalk.db")
        if (!db.isFile) return "KakaoTalk.db 가 없습니다 ($db) — 카카오톡 설치/패치 상태를 확인하세요"
        if (!db.canRead()) return "KakaoTalk.db 를 읽을 수 없습니다 ($db) — shared uid 미형성 (HayulGUI 로 카톡+IrisGUI 를 같은 키로 패치해 주세요)"
        return runCatching {
            SQLiteDatabase.openDatabase(
                db.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
            ).use { d ->
                // 질의 자체가 통과해야 DB 형식/WAL 접근이 Confirm 된다 (커서는 뽑지 않는다).
                d.rawQuery("SELECT 1 FROM chat_logs LIMIT 1", null).use { c -> c.moveToFirst() }
            }
            null
        }.getOrElse { "KakaoTalk.db 질의 실패 ($db): ${it.message}" }
    }

    /**
     * 워스셋을 인프로세스 기동한다 (멱등 — 이미 살아있으면 성공으로 본다).
     * [app] 은 답장 dispatch 에 쓸 앱 context — HAYUL 은 shared-uid 라
     * 일반 framework API(startService 등)로 카톡을 직접 건드릴 수 있다.
     * @return null = 성공, 실패 사유 문자열 (아무것도 남겨지지 않는다).
     */
    fun start(app: Context): String? = synchronized(lock) {
        if (isRunning) return@synchronized null
        stopWorkers() // 이전 실패/반쪽 상태 잔재 정리 — 멩등 재시작 경로

        readabilityCheck()?.let { return it }

        val failure = runCatching {
            // HAYUL 은 앱 안에서 도 round 한다: dispatch 는 binder-hack 가 아니라
            // shared-uid 로 보장된 같은-uid 일반 API 경로로 보낸다.
            Replier.dispatchContext = app.applicationContext
            // AdbServer 가 바인딩할 포트/UI-원본 설정을 prefs로부터 동기화.
            AdbConfig.loadFromPrefs()
            AdbConfig.detectBotId()

            Replier.startMessageSender()

            val kakaoDb = KakaoDB()
            val helper = ObserverHelper(kakaoDb) { msg -> AdbServer.broadcastToClients(msg) }
            val observer = DBObserver(kakaoDb, helper).also { it.startPolling() }
            dbObserver = observer

            val deleter = ImageDeleter(IMAGE_DIR_PATH, TimeUnit.HOURS.toMillis(1))
            deleter.startDeletion()
            imageDeleter = deleter

            // ISSUE-05 와 동일 규약: process-command stop 은 워스셋까지 내려야 완전 정지.
            AdbServer.workerStopHook = Runnable {
                runCatching { observer.stopPolling() }
                runCatching { deleter.stopDeletion() }
                runCatching { Replier.stop() }
            }
            AdbServer.startServer()
        }.exceptionOrNull()?.let { t ->
            stopWorkers()
            "Hayul 백엔드 기동 실패: ${t.message ?: t.javaClass.simpleName}"
        }
        failure
    }

    /** 완전 정지 (모드-무관 stopLogic 에서도 호출되는 멩등 경로). */
    fun stop() = synchronized(lock) {
        AdbServer.workerStopHook = null
        runCatching { AdbServer.stopServer() }
        stopWorkers()
        Replier.dispatchContext = null
        Unit
    }

    private fun stopWorkers() {
        runCatching { dbObserver?.stopPolling() }
        dbObserver = null
        runCatching { imageDeleter?.stopDeletion() }
        imageDeleter = null
        runCatching { Replier.stop() }
        Replier.dispatchContext = null
    }
}
