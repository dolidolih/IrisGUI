package party.qwer.irisgui.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.models.*

/**
 * AdbProcessClient — Android 앱 ↔ app_process(HTTP) 통신 클라이언트
 *
 * ADB 모드에서 Android 앱 UI가 백그라운드 app_process(AdbServer)의
 * 실제 상태를 조회하고 제어하는 역할을 한다.
 *
 * 주의: 모든 함수는 suspend + withContext(Dispatchers.IO) 로 동작한다.
 * 동기 OkHttp 호출을 메인 스레드(LaunchedEffect/rememberCoroutineScope의 기본
 * Main 디스패처)에서 실행하면 NetworkOnMainThreadException 이 발생하고
 * try/catch 에 삼켜져 항상 null 을 반환(대시보드 "미실행")하는 문제가 있었다.
 */
object AdbProcessClient {
    // 커넥션 풀을 쓰지 않는다: 데몬이 내려간 뒤 같은 포트를 다른 프로세스가 재사용하면
    // 커넥션 풀에 남은 stale 커넥션에 응답하려 "unexpected end of stream" 이 발생한다.
    // 조회 실패가 곧 '정지'로 오인되는 원인. 커넥션마다 새로 연결한다.
    private val client = OkHttpClient.Builder()
        .connectionPool(okhttp3.ConnectionPool(0, 1, java.util.concurrent.TimeUnit.NANOSECONDS))
        .connectTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(3, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()
    // ISSUE-13: 관대한 디코딩 — 모르는 키 무시 + 입력 문자열 허용 + null/부결 필드는
    // 기본값으로 치환. 데몬 JSON 이 진화해도(신규/삭제 필드) 상태 조회가 브레이크되지 않는다.
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    /** ISSUE-13: HTTP 응답은 받았으나 status 를 해석할 수 없는 경우의 "살아있되 불명" 값. */
    private fun aliveUnknown(): AdbProcessStatusResponse = AdbProcessStatusResponse(
        server_running = true,
        port = AppConfig.serverPort,
        bot_http_port = AppConfig.serverPort
    )

    private val baseUrl: String
        get() = "http://127.0.0.1:${AppConfig.serverPort}"

    // ── Process Status / Control ──────────────────────────

    /**
     * app_process의 현재 상태 조회.
     *
     * ISSUE-13 — "not running"(null) 은 접속 실패/타임아웃 에 대해서만 내린다:
     *   - HTTP 응답(2xx 여부 무관)을 수령했다 = 접속 성공 ⇒ 살아있음.
     *     본문이 부분적이거나 error-shape 이어도 aliveUnknown(기본값 상태) 반환.
     *   - 커넥션 거부/타임아웃은 1회 재시도 후 null (StatusScreen 의 포트 오픈 확인과
     *     합쳐 최종 판단 — 유령 Down 방지).
     */
    suspend fun queryStatus(): AdbProcessStatusResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/process-status").get().build()
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = runCatching { response.body?.string() }.getOrNull()
                        val decoded = body?.let {
                            runCatching { json.decodeFromString<AdbProcessStatusResponse>(it) }.getOrNull()
                        }
                        if (decoded != null) return@withContext decoded
                        // HTTP 200 + 해석 불가 ⇒ 살아있음(파트셜/부분 기동/포맷 변화) — Down 판정 금지.
                        println("AdbProcessClient: /process-status 응답은 받았으나 페이로드 불완전 (alive-unknown)")
                        return@withContext aliveUnknown()
                    }
                    // HTTP 오류 응답 — 구별 필요:
                    //  * 404/405 는 「포트에 무언가는 응답하지만 daemon 라우터가 없다」 뜻.
                    //    NON_ROOT 에서 앱 IrisServer 가 같은 포트(3000) 를 차지하고
                    //    자기 자신에게 /process-status 를 물어볼 때 이 응답이 나온다 —
                    //    이를 생존 취급하면 NLS 가 “이벤트는 daemon 차지”라며 의도적으로
                    //    브로드캐스트를 끊어버려 ws 가 완전정지한다. (2026-09 실기기
                    //    로그로 확인: 이벤트 유입·ws 접속 정상인데 응답만 없는 현상의 원인)
                    //  * 5xx 등은 StatusPages 를 탄 실제 daemon 후보 — 기존대로 재시도.
                    if (response.code == 404 || response.code == 405) {
                        println("AdbProcessClient: /process-status HTTP ${response.code} — daemon 이 아닌 응답(자기도시 포함), 정지 판정")
                        return@withContext null
                    }
                    if (attempt == 0) {
                        delay(300)
                    } else {
                        println("AdbProcessClient: /process-status HTTP ${response.code} — 응답 수신했으므로 생존 취급")
                        return@withContext aliveUnknown()
                    }
                }
            } catch (e: Exception) {
                // 접속 불가/타임아웃 — 아래 재시작 후에도 같으면 진짜 "정지" 후보.
                if (attempt == 0) delay(300)
            }
        }
        null
    }

    /**
     * 서버 포트가 TCP 로 열려있는지 — HTTP 응답은 없지만 프로세스가 떠있는지 구분해야 할 때 쓴다.
     * status 조회가 null 을 반환해도 포트가 열려 있으면 '막 기동 중/한창 바쁨'이고,
     * 연결이 거절되면 정말로 내려간 상태다.
     */
    suspend fun isHttpPortOpen(timeoutMs: Int = 300): Boolean = withContext(Dispatchers.IO) {
        try {
            java.net.Socket().use { s ->
                s.connect(java.net.InetSocketAddress("127.0.0.1", AppConfig.serverPort), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    /** app_process 정지 */
    suspend fun stopProcess(): Boolean = executeCommand("stop")

    /** app_process 재시작 */
    suspend fun restartProcess(): Boolean = executeCommand("restart")

    private suspend fun executeCommand(command: String): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(AdbProcessCommandRequest(command))
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/process-command").post(body).build()
        try {
            client.newCall(request).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    // ── Config ────────────────────────────────────────────

    /** 서버 설정 조회 (응답 파싱 실패 시 null) */
    suspend fun fetchConfig(): ConfigResponse? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/config").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string() ?: return@use null
                json.decodeFromString<ConfigResponse>(body)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * 서버 설정 업데이트
     * @param name 설정 항목 이름 (endpoint, botname, dbrate, sendrate, botport)
     */
    suspend fun updateConfig(name: String, request: ConfigRequest): Boolean = withContext(Dispatchers.IO) {
        val body = json.encodeToString(request)
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder().url("$baseUrl/config/$name").post(body).build()
        try {
            client.newCall(httpRequest).execute().use { response -> response.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    // ── Query ─────────────────────────────────────────────

    /** KakaoDB 쿼리 실행 (실패 시 null) */
    suspend fun executeQuery(query: String, bind: List<String>? = null): QueryResponse? = withContext(Dispatchers.IO) {
        val req = QueryRequest(query = query, bind = bind?.map { JsonPrimitive(it) })
        val body = json.encodeToString(req)
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder().url("$baseUrl/query").post(body).build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bodyText = response.body?.string() ?: return@use null
                json.decodeFromString<QueryResponse>(bodyText)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Reply (답장 테스트) ───────────────────────────────

    /**
     * /reply 로 텍스트 메시지 전송 (답장 테스트용).
     * @return HTTP 상태 코드(200대=성공), 연결 실패 시 -1
     */
    suspend fun sendReply(room: String, message: String): Int = withContext(Dispatchers.IO) {
        val safeRoom = room.replace("\\", "\\\\").replace("\"", "\\\"")
        val safeMsg = message.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val jsonString = """{"type":"text","room":"$safeRoom","data":"$safeMsg"}"""
        val body = jsonString.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("$baseUrl/reply").post(body).build()
        try {
            client.newCall(request).execute().use { response -> response.code }
        } catch (e: Exception) {
            -1
        }
    }

    // ── Decrypt ───────────────────────────────────────────

    /** 메시지 복호화 */
    suspend fun decryptMessage(enc: Int, b64Ciphertext: String, userId: Long? = null): DecryptResponse? = withContext(Dispatchers.IO) {
        val req = DecryptRequest(enc = enc, b64_ciphertext = b64Ciphertext, user_id = userId)
        val body = json.encodeToString(req)
            .toRequestBody("application/json".toMediaType())
        val httpRequest = Request.Builder().url("$baseUrl/decrypt").post(body).build()
        try {
            client.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val bodyText = response.body?.string() ?: return@use null
                json.decodeFromString<DecryptResponse>(bodyText)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ── Rooms (방 목록) ───────────────────────────────────

    /** 최근 채팅방 목록 조회 (실패 시 빈 리스트) */
    suspend fun fetchRooms(): List<RoomInfo> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$baseUrl/rooms").get().build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string() ?: return@use emptyList()
                json.decodeFromString<RoomListResponse>(body).rooms
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
