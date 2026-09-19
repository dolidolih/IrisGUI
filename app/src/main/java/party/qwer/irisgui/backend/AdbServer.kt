package party.qwer.irisgui.backend

import io.ktor.http.ContentType
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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import party.qwer.irisgui.*
import party.qwer.irisgui.models.*
import java.io.File

/**
 * ADB 루팅 모드 전용 HTTP 서버.
 * app_process 환경에서는 Android Context가 없으므로, Replier를 직접 호출하는轻量版 서버.
 * 원본 Iris(IrisServer.kt)와 동일한 엔드포인트를 제공하며, 채팅 이벤트는 DBObserver가 전송한다.
 */
object AdbServer {
    @Volatile
    private var _isRunning = false
    private var engineRef: Any? = null

    private val wsBroadcastFlow = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val sharedFlow = wsBroadcastFlow.asSharedFlow()

    /** KakaoTalk의 NotificationReferer — shared_prefs XML에서 추출 */
    private var notificationReferer: String? = null

    /** KakaoDB 싱글톤 — 매 쿼리마다 생성하지 않음 */
    private var kakaoDb: KakaoDB? = null

    /** /chat 조회 경로용 ObserverHelper — kakaoDb 변경 시에만 다시 만든다. */
    private var readHelper: Pair<KakaoDB, ObserverHelper>? = null

    private fun readHelper(db: KakaoDB): ObserverHelper =
        readHelper?.takeIf { it.first === db }?.second
            ?: ObserverHelper.forReads(db).also { readHelper = db to it }

    /** AdbServer 전용 CoroutineScope — handleTextReply에서 재사용 (P7 누수 방지) */
    private val serverScope = CoroutineScope(Dispatchers.IO)

    fun broadcastToClients(message: String) {
        wsBroadcastFlow.tryEmit(message)
    }

    /** P21: 서버 실행 상태 확인 — Android 앱에서 상태 표시용 */
    val isRunning: Boolean
        get() = _isRunning

    /** A1: 서버 시작 상태 — 외부에서 확인용 */
    val isStarted: Boolean
        get() = _isRunning && engineRef != null

    /** P21: 서버 시작/정지 — Android 앱에서 app_process 제어용 */
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

    fun startServer() {
        if (engineRef != null) return
        try {
            // 1. notificationReferer 추출
            notificationReferer = extractNotificationReferer()

            // 2. KakaoDB 싱글톤 초기화
            kakaoDb = KakaoDB()
            println("AdbServer: KakaoDB initialized, botId = ${kakaoDb!!.botUserId}")

            val lenientJson = Json { ignoreUnknownKeys = true }

            engineRef = embeddedServer(Netty, port = AdbConfig.serverPort) {
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
                        sharedFlow.collect { msg ->
                            send(msg)
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
                            val rooms = db.getRecentRooms(30).map {
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
                                val hits = CryptoDatabaseReader.searchMessages(req.query, req.limit)
                                    .map { SearchHit(id = it.first, preview = it.second) }
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
                            OpenApiSpec.render(AdbConfig.serverPort),
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
                                stopServer()
                                call.respond(ApiResponse(success = true, message = "Process stopped"))
                            }
                            "restart" -> {
                                stopServer()
                                startServer()
                                call.respond(ApiResponse(success = true, message = "Process restarted"))
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
        }
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
    fun stopServer() {
        // serverScope 정지 — handleTextReply/handleImageReply 코루틴 정리
        serverScope.cancel()
        kakaoDb?.closeConnection()
        kakaoDb = null
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
    }
}
