package party.qwer.irisgui.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import party.qwer.irisgui.*
import party.qwer.irisgui.scripting.BionicRuntime
import party.qwer.irisgui.models.*
import java.io.File

/**
 * ADB 루팅 모드 전용 HTTP 서버.
 * app_process 환경에서는 Android Context가 없으므로, Replier를 직접 호출하는轻量版 서버.
 * 원본 Iris(IrisServer.kt)와 동일한 엔드포인트를 제공하며, 채팅 이벤트는 DBObserver가 전송한다.
 */
object AdbServer {
    private fun pkgStatusJson(): String {
        val m = BionicRuntime.statusJson()
        fun q(s: String) = org.json.JSONObject.quote(s)
        return "{" +
            "\"running\":" + m["running"] + "," +
            "\"current\":" + q(m["current"].toString()) + "," +
            "\"done\":" + m["done"] + "," +
            "\"total\":" + m["total"] + "," +
            "\"message\":" + q(m["message"].toString()) + "," +
            "\"error\":" + q(m["error"].toString()) + "}"
    }

    /** /pkg-install 바디: {"packages": ["a","b"]} → [a,b]. */
    private fun JsonObject.optJsonArrayStringsAdb(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    // ISSUE-17: start/stop 은 UI 스레드(startOrStop), Netty 워커(/process-command),
    // 서비스/워치독에서 동시에 도달한다 — 모든 변경부는 @Synchronized 로 직렬화하고
    // 공개 필드는 @Volatile 로 발행한다.
    @Volatile
    private var _isRunning = false
    @Volatile
    private var engineRef: Any? = null

    /**
     * ISSUE-15: SharedFlow/tryEmit 폐기는 느린 구독자 하나가 전체 손실을 만든다.
     * 연결별 bounded 채널 fanout 으로 교체.
     */
    private val wsFanout = WsFanout("AdbServer")

    /** KakaoTalk의 NotificationReferer — shared_prefs XML에서 추출 */
    private var notificationReferer: String? = null

    /** KakaoDB 싱글톤 — 매 쿼리마다 생성하지 않음 */
    private var kakaoDb: KakaoDB? = null

    /** /chat 조회 경로용 ObserverHelper — kakaoDb 변경 시에만 다시 만든다. */
    private var readHelper: Pair<KakaoDB, ObserverHelper>? = null

    private fun readHelper(db: KakaoDB): ObserverHelper =
        readHelper?.takeIf { it.first === db }?.second
            ?: ObserverHelper.forReads(db).also { readHelper = db to it }

    /**
     * AdbServer 전용 CoroutineScope — handleTextReply에서 재사용 (P7 누수 방지).
     * ISSUE-01: stopServer()가 이 scope을 cancel하므로 startServer()가 매번 새
     * scope을 만들어야 한다. 재사용하면 cancelled scope에 launch하는 것으로 되어
     * restart 이후 답장이 조용히 전부 폐기된다.
     */
    @Volatile
    private var serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** P21/ISSUE-15: WS 이벤트 전달 (non-blocking). @return 버퍼 만충으로 폐기한 건수. */
    fun broadcastToClients(message: String): Long = wsFanout.publish(message)

    /** P21: 서버 실행 상태 확인 — Android 앱에서 상태 표시용 */
    val isRunning: Boolean
        get() = _isRunning

    /** A1: 서버 시작 상태 — 외부에서 확인용 */
    val isStarted: Boolean
        get() = _isRunning && engineRef != null

    /** P21: 서버 시작/정지 — Android 앱에서 app_process 제어용 */
    @Synchronized
    fun startOrStop() {
        if (_isRunning) {
            stopServer()
        } else {
            startServer()
        }
    }

    /**
     * KakaoTalk shared_prefs XML에서 NotificationReferer 추출
     * 원본 Iris의 readNotificationReferer()와 동일한 로직.
     */
    private fun extractNotificationReferer(): String {
        try {
            val appPath = PathUtils.getAppPath()
            val prefsFile = File("${appPath}shared_prefs/KakaoTalk.hw.perferences.xml")
            if (prefsFile.exists() && prefsFile.canRead()) {
                val data = prefsFile.readText()
                val regex = Regex("""<string name="NotificationReferer">(.*?)</string>""")
                val match = regex.find(data)
                if (match != null && match.groups[1] != null) {
                    val referer = match.groups[1]!!.value
                    println("AdbServer: NotificationReferer extracted: $referer")
                    return referer
                }
            }
        } catch (e: Exception) {
            println("AdbServer: Failed to extract NotificationReferer: ${e.message}")
        }
        return "IrisGUI"
    }

    @Synchronized
    fun startServer() {
        if (engineRef != null) return
        try {
            // ISSUE-01: 이전 stopServer()가 cancel한 scope이 있으면 새 scope으로 교체한다.
            val previous = serverScope
            serverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            if (previous.coroutineContext[Job]?.isActive == true) {
                runCatching { previous.cancel() }
            }
            // 1. notificationReferer 추출
            notificationReferer = extractNotificationReferer()

            // 2. KakaoDB 싱글톤 초기화
            kakaoDb = KakaoDB()
            println("AdbServer: KakaoDB initialized, botId = ${kakaoDb!!.botUserId}")

            val lenientJson = Json { ignoreUnknownKeys = true }

            // 원상복구: 기본 바인드는 0.0.0.0(역사적 동작 — 원격 client/adoron 같은
            // WS 클라이언트가 기기 IP:포트로 접속한다). 제어 평면 루프백 유지는
            // 이 변경의 대상이 아니다(요구된 적 없는 동작 변경 = 버그로 취급).
            engineRef = embeddedServer(Netty, host = "0.0.0.0", port = AdbConfig.serverPort) {
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(lenientJson)
                }
                install(ContentNegotiation) { json(lenientJson) }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiResponse(success = false, message = cause.message ?: "unknown error")
                        )
                    }
                }

                routing {
                    // ── WebSocket ──────────────────────────────
                    webSocket("/ws") {
                        // ISSUE-15: 연결별 수신 채널 — 느린 클라이언트 하나가
                        // 다른 connection/polling thread 를 막지 못한다.
                        val sub = wsFanout.subscribe()
                        try {
                            for (msg in sub.channel) {
                                send(msg)
                            }
                        } finally {
                            wsFanout.unsubscribe(sub)
                        }
                    }


                    // ── 공통: /reply ───────────────────────────
                    post("/reply") {
                        val req = call.receive<ReplyRequest>()
                        val roomId = req.room

                        when (req.type) {
                            "text" -> {
                                val text = req.data.jsonPrimitive.content
                                handleTextReply(roomId, text, req.threadId)
                            }
                            "image_multiple", "image" -> {
                                val images = if (req.data is JsonArray) {
                                    req.data.jsonArray.map { it.jsonPrimitive.content }
                                } else {
                                    listOf(req.data.jsonPrimitive.content)
                                }
                                handleImageReply(roomId, images)
                            }
                            else -> {
                                call.respond(ApiResponse(success = false, message = "Unknown reply type: ${req.type}"))
                                return@post
                            }
                        }
                        call.respond(ApiResponse(success = true, message = "Enqueued"))
                    }

                    // ── bionic userland 패키지 설치 (게스트 iris-pk) ──
                    post("/pkg-install") {
                        val body = runCatching { call.receive<JsonObject>() }.getOrNull()
                        val names = body?.optJsonArrayStringsAdb("packages") ?: emptyList()
                        if (names.isEmpty()) {
                            call.respond(ApiResponse(success = false, message = "packages 없음"))
                            return@post
                        }
                        // 접수 즉시 응답 — 설치는 백그라운드 스레드, 클라이언트는 GET
                        // /pkg-install-status 로 넘겨받는다 (짧은 왕복 유지).
                        val (accepted, msg) = BionicRuntime.startAsync(null, names)
                        call.respondText(
                            "{\"success\":" + accepted + ",\"started\":true,\"message\":" +
                                org.json.JSONObject.quote(msg) + "}",
                            ContentType.Application.Json)
                    }

                    get("/pkg-install-status") {
                        call.respondText(pkgStatusJson(), ContentType.Application.Json)
                    }

                    // ── 루팅 모드 전용 엔드포인트 ─────────────
                    // /config
                    route("/config") {
                        get {
                            call.respond(
                                ConfigResponse(
                                    bot_name = AdbConfig.botName,
                                    bot_http_port = AdbConfig.serverPort,
                                    web_server_endpoint = AdbConfig.webEndpoint,
                                    db_polling_rate = AdbConfig.dbPollingRate,
                                    message_send_rate = AdbConfig.messageSendRate,
                                    bot_id = AdbConfig.botId,
                                    broadcast_types = AdbConfig.broadcastTypes
                                )
                            )
                        }

                        post("{name}") {
                            val name = call.parameters["name"]
                            val req = call.receive<ConfigRequest>()

                            when (name) {
                                "endpoint" -> {
                                    val value = req.endpoint ?: ""
                                    AdbConfig.webEndpoint = value
                                }
                                "botname" -> {
                                    val value = req.botname
                                    if (value.isNullOrBlank()) throw Exception("missing or empty value")
                                    AdbConfig.botName = value
                                }
                                "dbrate" -> {
                                    val value = req.rate ?: throw Exception("missing or invalid value")
                                    AdbConfig.dbPollingRate = value
                                }
                                "sendrate" -> {
                                    val value = req.rate ?: throw Exception("missing or invalid value")
                                    AdbConfig.messageSendRate = value
                                }
                                "botport" -> {
                                    val value = req.port ?: throw Exception("missing or invalid value")
                                    if (value < 1 || value > 65535) throw Exception("Invalid port number")
                                    AdbConfig.serverPort = value
                                    // ISSUE-17: 실행 중인 엔진은 이전 포트 바인딩을 유지한다.
                                    // 조용히 넘어가는 "성공" 응답 대신 재시작 필요를 명시 —
                                    // 상태는 /process-status 의 port 필드로 계속 확인 가능하다.
                                    return@post call.respond(ApiResponse(
                                        success = true,
                                        message = "saved; engine keeps port ${AdbConfig.serverPort} until restart"
                                    ))
                                }
                                "types" -> {
                                    val value = req.types ?: throw Exception("missing value")
                                    if (value.isNotEmpty()) {
                                        val invalid = value.filter { it !in KakaoMessageType.VALID_FILTERS }
                                        if (invalid.isNotEmpty())
                                            throw Exception("Invalid broadcast type(s): $invalid")
                                    }
                                    // 빈 list = 필터 해제(null, 현행 동작)
                                    AdbConfig.broadcastTypes = value.ifEmpty { null }
                                }

                                "extension" -> {
                                    val value = req.enable
                                        ?: throw Exception("missing or invalid value")
                                    AdbConfig.enableExtension = value
                                }
                                else -> throw Exception("Unknown config $name")
                            }

                            call.respond(ApiResponse(success = true, message = "success"))
                        }
                    }

                    // /rooms — 최근 채팅방 목록 (ADB 모드 전용, 한글 방이름)
                    get("/rooms") {
                        try {
                            val db = kakaoDb ?: throw Exception("KakaoDB not initialized")
                            // limit 기본값은 기존 동작과 동일한 30 유지 — 미지정 응답은 그대로.
                            val limit = (call.request.queryParameters["limit"]?.toIntOrNull()
                                ?: 30).coerceIn(1, 100)
                            val rooms = db.getRecentRooms(limit).map {
                                RoomInfo(
                                    id = it["id"] ?: "",
                                    name = it["name"],
                                    updated_at = it["updated_at"]
                                )
                            }
                            call.respond(RoomListResponse(rooms = rooms))
                        } catch (e: Exception) {
                            call.respond(RoomListResponse(rooms = emptyList()))
                        }
                    }

                    // ── path 기반 read-only 조회 API (info provision) ───
                    route("/user") {
                        get("/name/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid user id"))
                            try {
                                val name = kakaoDb?.getUserProfile(id)?.first
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid user id"))
                            try {
                                val (name, url) = kakaoDb!!.getUserProfile(id)
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("id", JsonPrimitive(id))
                                    put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
                                    put("profile_image_url", url?.let { JsonPrimitive(it) } ?: JsonNull)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                    }

                    route("/room") {
                        get("/name/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                val name = kakaoDb?.getChatRoomMeta(id)?.get("name") as? String
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("name", name?.let { JsonPrimitive(it) } ?: JsonNull)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                call.respond(JsonPayloadResponse(payload = toJsonElement(kakaoDb?.getChatRoomMeta(id))))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}/members") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                val members = kakaoDb?.getRoomMembers(id) ?: emptyList()
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("members", toJsonElement(members))
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}/links") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            try {
                                val db = kakaoDb!!
                                val linkId = db.linkIdOfChat(id)
                                call.respond(JsonPayloadResponse(payload = toJsonElement(linkId?.let { db.getOpenLink(it) })))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                    }

                    get("/reactions/{id}") {
                        val id = call.parameters["id"]?.toLongOrNull()
                            ?: return@get call.respond(ApiResponse(false, "invalid log id"))
                        try {
                            val rx = kakaoDb?.getReactions(id) ?: emptyList()
                            call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                put("reactions", toJsonElement(rx))
                            }))
                        } catch (e: Exception) {
                            call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                        }
                    }

                    // ── /chat — 채팅 로그 조회 (live /ws 프레임과 동일한 형식) ──────────
                    // id 는 확장자의 log_id(스노우플레이크)와 같은 계열. /reply 나 /ws 와 동일 포맷이라
                    // 기존 클라이언트가 그대로 파싱할 수 있다.
                    route("/chat") {
                        // 방(chat_id) 기준 메시지 페이지네이션. item[] 는 `/ws` 프레임과 동일.
                        // 커서는 log_id 스노우플레이크(단조 증가) 기준이고 before/after/around 는
                        // 상호배타. `around` 는 anchor 포함(older 방향), before/after 는 exclusive.
                        get("/{room}/messages") {
                            val db = kakaoDb
                                ?: return@get call.respond(ApiResponse(false, "db not ready"))
                            val room = call.parameters["room"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid room id"))
                            val q = call.request.queryParameters
                            for (name in listOf("limit", "before", "after", "around", "user_id",
                                                "from_created_at", "to_created_at")) {
                                if (q[name] != null && q[name]!!.toLongOrNull() == null)
                                    return@get call.respond(ApiResponse(false, "invalid '$name'"))
                            }
                            val anchor = listOfNotNull(
                                q["before"]?.toLongOrNull()?.let { "before" to it },
                                q["after"]?.toLongOrNull()?.let { "after" to it },
                                q["around"]?.toLongOrNull()?.let { "around" to it }
                            )
                            if (anchor.size > 1) return@get call.respond(
                                ApiResponse(false, "before/after/around are mutually exclusive")
                            )
                            val (cursorVal, cursorOp, orderDesc) = when (anchor.firstOrNull()?.first) {
                                "before" -> Triple(anchor.first().second, "<", true)
                                "after" -> Triple(anchor.first().second, ">", false)
                                "around" -> Triple(anchor.first().second, "<=", true)
                                else -> Triple(Long.MAX_VALUE, "<", true)
                            }
                            val types = q["types"]?.split(",")?.map { it.trim() }
                                ?.filter { it.isNotEmpty() }
                            if (types != null) {
                                val invalid = types.filter { it !in KakaoMessageType.VALID_FILTERS }
                                if (invalid.isNotEmpty())
                                    return@get call.respond(
                                        ApiResponse(false, "Invalid type(s): $invalid")
                                    )
                            }
                            val limit = (q["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 100)
                            try {
                                val (items, nextCursor, hasMore) = readHelper(db).messageFramesPage(
                                    room, cursorVal, cursorOp, limit, orderDesc,
                                    q["user_id"]?.toLongOrNull(), types,
                                    q["from_created_at"]?.toLongOrNull(),
                                    q["to_created_at"]?.toLongOrNull()
                                )
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    putJsonArray("items") { items.forEach { add(toJsonElement(it)) } }
                                    put("next_cursor",
                                        nextCursor?.let { JsonPrimitive(it.toString()) } ?: JsonNull)
                                    put("has_more", hasMore)
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        get("/{id}") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid log id"))
                            try {
                                val db = kakaoDb ?: return@get call.respond(ApiResponse(false, "db not ready"))
                                val frame = readHelper(db).frameForLogId(id)
                                    ?: return@get call.respond(ApiResponse(false, "log not found"))
                                call.respond(JsonPayloadResponse(payload = toJsonElement(frame)))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        // 삭제된/original 메시지 회수 조회. id 는 삭제 마크 id 또는 원본 id 어느쪽을
                        // 넣어도 동일하게 동작한다.
                        get("/{id}/deleted") {
                            val id = call.parameters["id"]?.toLongOrNull()
                                ?: return@get call.respond(ApiResponse(false, "invalid log id"))
                            try {
                                val db = kakaoDb ?: return@get call.respond(ApiResponse(false, "db not ready"))
                                val out = readHelper(db).deletedRecoveryFor(id)
                                    ?: return@get call.respond(ApiResponse(false, "not a deleted message"))
                                call.respond(JsonPayloadResponse(payload = toJsonElement(out)))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                        // n번째 이전 메시지
                        get("/{id}/prev/{n}") {
                            val (id, n) = parseChatOffset(call) ?: return@get call.respond(
                                ApiResponse(false, "invalid id or offset")
                            )
                            respondRelative(call, id, -n)
                        }
                        get("/{id}/before/{n}") {
                            val (id, n) = parseChatOffset(call) ?: return@get call.respond(
                                ApiResponse(false, "invalid id or offset")
                            )
                            respondRelative(call, id, -n)
                        }
                        // n번째 다음 메시지
                        get("/{id}/next/{n}") {
                            val (id, n) = parseChatOffset(call) ?: return@get call.respond(
                                ApiResponse(false, "invalid id or offset")
                            )
                            respondRelative(call, id, n)
                        }
                        get("/{id}/after/{n}") {
                            val (id, n) = parseChatOffset(call) ?: return@get call.respond(
                                ApiResponse(false, "invalid id or offset")
                            )
                            respondRelative(call, id, n)
                        }
                        // days: 소수 허용(0.5 == 12시간). days보다 오래된 chat_logs 삭제.
                        delete("/logs") {
                            try {
                                val db = kakaoDb ?: return@delete call.respond(ApiResponse(false, "db not ready"))
                                val raw = call.request.queryParameters["days"]
                                val days = raw?.toDoubleOrNull()
                                    ?: return@delete call.respond(ApiResponse(false, "missing 'days'"))
                                if (days <= 0.0) return@delete call.respond(
                                    ApiResponse(false, "'days' must be positive")
                                )
                                val deleted = readHelper(db).purgeChatLogsOlderThan(days)
                                call.respond(JsonPayloadResponse(payload = buildJsonObject {
                                    put("deleted", JsonPrimitive(deleted))
                                    put("cutoff_days", JsonPrimitive(days))
                                }))
                            } catch (e: Exception) {
                                call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
                            }
                        }
                    }

                    // /query — KakaoDB 쿼리 (싱글톤 사용)
                    route("/query") {
                        post {
                            val req = call.receive<QueryRequest>()
                            try {
                                val db = kakaoDb ?: throw Exception("KakaoDB not initialized")
                                val sql = req.query ?: throw Exception("missing query")
                                val bindArgs: Array<String?>? = req.bind?.map { it.content as String? }?.toTypedArray()
                                val rows = db.executeQuery(sql, bindArgs ?: emptyArray())
                                // decryptRow 적용 (원본 Iris와 동일)
                                val decryptedRows = rows.map { KakaoDB.decryptRow(it) }
                                call.respond(QueryResponse(data = decryptedRows))
                            } catch (e: Exception) {
                                // P13: 에러 정보를 응답에 포함 — 클라이언트가 원인 파악 가능
                                call.respond(QueryResponse(data = emptyList(), error = e.message))
                            }
                        }
                    }

                    // /search — crypto_database全文 검색 (chat_log_search.searchable_text)
                    route("/search") {
                        post {
                            val req = call.receive<SearchRequest>()
                            try {
                                val hasFilter = req.room != null || req.user_id != null ||
                                    !req.types.isNullOrEmpty() || req.from_created_at != null ||
                                    req.to_created_at != null
                                val db = kakaoDb
                                val window = if (hasFilter && db != null)
                                    minOf(req.limit * 8, 500) else req.limit
                                // chat_log_search(crypto_database) 에는 방/보낸이/시각 컬럼이 없고
                                // db1 SQL 로 조인도 불가 → id 만 뽑아 db1.chat_logs 에서 되읽는다.
                                val raw = CryptoDatabaseReader.searchMessages(req.query, window)
                                val hits = if (!hasFilter || db == null) {
                                    raw.map { SearchHit(id = it.first, preview = it.second) }
                                } else {
                                    readHelper(db).enrichChatHits(raw)
                                        .filter { row ->
                                            val ts = (row["created_at"] as? String)?.toLongOrNull() ?: 0L
                                            (req.room == null || row["chat_id"] == req.room.toString()) &&
                                                (req.user_id == null || row["user_id"] == req.user_id.toString()) &&
                                                (req.types.isNullOrEmpty() || row["type_name"] in req.types!!) &&
                                                (req.from_created_at == null || ts >= req.from_created_at!!) &&
                                                (req.to_created_at == null || ts <= req.to_created_at!!)
                                        }
                                        .take(req.limit)
                                        .map { row ->
                                            SearchHit(
                                                id = row["id"] as Long,
                                                preview = row["preview"] as String,
                                                chat_id = row["chat_id"] as String?,
                                                user_id = row["user_id"] as String?,
                                                created_at = row["created_at"] as String?,
                                                type_name = row["type_name"] as String?,
                                                room_name = row["room_name"] as String?,
                                                sender_name = row["sender_name"] as String?
                                            )
                                        }
                                }
                                call.respond(SearchResponse(hits = hits))
                            } catch (e: Exception) {
                                call.respond(SearchResponse(hits = emptyList(), error = e.message))
                            }
                        }
                    }

                    // /decrypt — 메시지 복호화
                    route("/decrypt") {
                        post {
                            val req = call.receive<DecryptRequest>()
                            try {
                                val plain = KakaoDecrypt.decrypt(
                                    req.enc,
                                    req.b64_ciphertext,
                                    req.user_id ?: AdbConfig.botId
                                )
                                call.respond(DecryptResponse(plain_text = plain))
                            } catch (e: Exception) {
                                call.respond(DecryptResponse(plain_text = "Error: ${e.message}"))
                            }
                        }
                    }

                    // ── API 명세 (Swagger UI / OpenAPI JSON) ────────────
                    // 명세는 OpenApiSpec 에서 코드로 생성 → 리소스 로더 없이 동작.
                    // UI 번들만 브라우저가 CDN 에서 읽고, 없으면 JSON 링크로 대체된다.
                    get("/openapi.json") {
                        call.respondText(
                            OpenApiSpec.render(
                                AdbConfig.serverPort,
                                host = call.request.headers[HttpHeaders.Host]
                            ),
                            ContentType.Application.Json
                        )
                    }
                    get("/swagger") {
                        call.respondText(OpenApiSpec.html(), ContentType.Text.Html)
                    }

                    // /aot — AOT 토큰
                    get("/aot") {
                        try {
                            val aot = AuthProvider.getToken()
                            call.respond(AotResponse(success = true, aot = Json.parseToJsonElement(aot.toString())))
                        } catch (e: Exception) {
                            call.respond(AotResponse(success = false, aot = JsonNull))
                        }
                    }

                    // ── P21: 프로세스 상태/제어 (Android 앱 ↔ app_process 통신) ──
                    get("/process-status") {
                        // AdbServer가 실행 중인지, DB 관찰 중인지 등 전체 상태 반환
                        call.respond(
                            AdbProcessStatusResponse(
                                server_running = _isRunning,
                                port = AdbConfig.serverPort,
                                db_observing = AppState.isObserving,
                                bot_id = AdbConfig.botId,
                                bot_name = AdbConfig.botName,
                                bot_http_port = AdbConfig.serverPort,
                                web_server_endpoint = AdbConfig.webEndpoint,
                                db_polling_rate = AdbConfig.dbPollingRate,
                                message_send_rate = AdbConfig.messageSendRate,
                                logs = RuntimeLog.snapshot(limit = 60),
                                last_logs = AppState.lastChatLogs.toList()
                            )
                        )
                    }

                    // P23: Android 앱에서 app_process 시작/정지 제어
                    post("/process-command") {
                        val req = call.receive<AdbProcessCommandRequest>()
                        when (req.command) {
                            "stop" -> {
                                // ISSUE-05: 자기 엔진의 요청 스레드에서 stopServer() 하면
                                // in-flight(자신 포함) 대기 + 응답 미전달 → 클라이언트가
                                // "실패"로 오인해 pkill 로 직행한다. 먼저 응답하고 내려온다.
                                call.respond(ApiResponse(success = true, message = "Process stopping"))
                                scheduleLifecycle { stopServer() }
                            }
                            "restart" -> {
                                // stop → port 반환 확인 후 start: 같은 스레드에서 직렬 실행.
                                call.respond(ApiResponse(success = true, message = "Process restarting"))
                                scheduleLifecycle {
                                    stopServer()
                                    // engine.stop(...) 내부 join 으로 포트는 반납되지만,
                                    // SO_REUSE 편승을 기다리기 위해 짧게 대기한다.
                                    runCatching { Thread.sleep(300) }
                                    startServer()
                                }
                            }
                            else -> {
                                call.respond(ApiResponse(success = false, message = "Unknown command: ${req.command}"))
                            }
                        }
                    }
                }
            }.start(false)

            println("AdbServer: Started on port ${AdbConfig.serverPort}, referer=$notificationReferer")
            _isRunning = true
        } catch (e: Exception) {
            System.err.println("AdbServer failed to start: ${e.message}")
            e.printStackTrace()
            // ISSUE-17: BindException 등으로 실패해도 이미 만든 KakaoDB 핸들은 열어둔
            // 채로 남는다 — 반복 재시작마다 fd 누수. 어떤 실패 경로든 핸들을 닫는다.
            engineRef = null
            _isRunning = false
            runCatching { kakaoDb?.closeConnection() }
            kakaoDb = null
            readHelper = null
        }
    }

    /**
     * ISSUE-05: 정지 시 데몬 워스(스레드/폴러)까지 함께 내리기 위한 훅 —
     * Main.kt가 DBObserver/ImageDeleter 를 등록한다. (hook 이 null 이면 기존 동작과 동일)
     */
    @JvmStatic
    var workerStopHook: Runnable? = null

    /**
     * ISSUE-05: stop/restart 는 엔진 자신의 요청 스레드에서 수행하면 자기 엔진의
     * grace timeout 을 태우고 응답 자체가 미전달된다. 응답을 먼저 내보낸 뒤
     * detached 스레드에서 지연 실행한다.
     */
    private fun scheduleLifecycle(action: () -> Unit) {
        Thread {
            // 응답 플러시를 위한 짧은 유예 — 값은 응답 지연(100ms대)에만 영향,
            // 정지/재시작 결과에는 영향이 없다.
            runCatching { Thread.sleep(150) }
            runCatching { action() }
                .onFailure { println("AdbServer: deferred lifecycle action failed: ${it.message}") }
        }.apply { isDaemon = true; name = "AdbServer-lifecycle" }.start()
    }

    /**
     * 텍스트 답장 — notificationReferer를 Replier에 전달
     * 원본 Iris의 Replier.sendMessage(referer, chatId, msg, threadId)와 동일.
     * P7: serverScope 재사용 — 매 호출마다 새 CoroutineScope 생성 방지.
     */
    private fun handleTextReply(roomId: String, text: String, threadId: String?) {
        val referer = notificationReferer ?: "IrisGUI"
        serverScope.launch {
            try {
                val chatId = roomId.toLongOrNull()
                if (chatId != null) {
                    val threadIdLong = threadId?.toLongOrNull()
                    Replier.sendMessage(referer, chatId, text, threadIdLong)
                }
                // irispy-client 규약상 /ws 는 반드시 `{msg, room, sender, json}` 형태만 허용한다.
                // (클라이언트 쪽에서 `del data["json"]` 을 수행하므로 `json` 키가 없는 페이로드는
                //  KeyError('json') → "이벤트를 처리 중 오류" 가 된다.)
                // 원본 Iris 도 답장 ACK 를 /ws 로 브로드캐스트하지 않으므로 로그만 남긴다.
                val safeText = text?.replace("\n", "\\n") ?: ""
                println("AdbServer: text reply sent room=$roomId len=${safeText.length}")
            } catch (e: Exception) {
                System.err.println("AdbServer Replier error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun handleImageReply(roomId: String, images: List<String>) {
        serverScope.launch {
            try {
                val chatId = roomId.toLongOrNull()
                if (chatId != null) {
                    if (images.size == 1) {
                        Replier.sendPhoto(chatId, images[0])
                    } else {
                        Replier.sendMultiplePhotos(chatId, images)
                    }
                }
                // 답장 ACK 를 /ws 로 브로드캐스트하면 irispy-client 에서는 'json' 키가
                // 없어 KeyError 가 발생하므로 로그만 남긴다.
                println("AdbServer: image reply sent room=$roomId count=${images.size}")
            } catch (e: Exception) {
                System.err.println("AdbServer Replier image error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    /** `/chat/{id}/prev/{n}` 류 경로 파라미터 파싱. 실패 시 null. */
    private fun parseChatOffset(
        call: io.ktor.server.application.ApplicationCall
    ): Pair<Long, Int>? {
        val id = call.parameters["id"]?.toLongOrNull() ?: return null
        val n = call.parameters["n"]?.toIntOrNull() ?: return null
        if (n <= 0) return null
        return id to n
    }

    /** 동일 채팅방 내 n번째 전후(offset 음수=이전) 메시지를 /ws 프레임 포맷으로 응답. */
    private suspend fun respondRelative(
        call: io.ktor.server.application.ApplicationCall, id: Long, offset: Int
    ) {
        try {
            val db = kakaoDb ?: return call.respond(ApiResponse(false, "db not ready"))
            val frame = readHelper(db).frameRelative(id, offset)
            if (frame == null) {
                call.respond(ApiResponse(false, "message not found"))
            } else {
                call.respond(JsonPayloadResponse(payload = toJsonElement(frame)))
            }
        } catch (e: Exception) {
            call.respond(JsonPayloadResponse(payload = JsonNull, error = e.message))
        }
    }

    private fun toJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Int -> JsonPrimitive(value)
        is Long -> JsonPrimitive(value)
        is Short -> JsonPrimitive(value.toInt())
        is Double -> JsonPrimitive(value)
        is Float -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            for ((k, v) in value) put(k.toString(), toJsonElement(v))
        }
        is List<*> -> buildJsonArray {
            for (item in value) add(toJsonElement(item))
        }
        else -> JsonPrimitive(value.toString())
    }

    /**
     * 리소스 정리
     */
    @Synchronized
    fun stopServer() {
        // ISSUE-05/17: 먼저 수접을 끊는다(new requests 차단, in-flight join) —
        // handler 가 DB/scope 에 접근하지 않는 상태가 된 뒤에 워스를 정리한다.
        engineRef?.let {
            try {
                val stopMethod = it.javaClass.getMethod("stop", Long::class.java, Long::class.java)
                stopMethod.invoke(it, 1000L, 2000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        engineRef = null
        _isRunning = false
        // serverScope 정지 — handleTextReply/handleImageReply 코루틴 정리
        // (ISSUE-01: scope은 startServer에서 매번 새로 만들어지므로 재사용 걱정 없음)
        serverScope.cancel()
        // ISSUE-05: 데몬 워스(DB poller, 이미지 정리)까지 truly stop — 그래야
        // "stop" 이후에도 답장/관측을 하는 반-정지(deamon은 살지만 포트만 반납) 상태가 남지 않는다.
        runCatching { workerStopHook?.run() }
            .onFailure { println("AdbServer: worker stop hook failed: ${it.message}") }
        kakaoDb?.closeConnection()
        kakaoDb = null
        readHelper = null
    }
}
