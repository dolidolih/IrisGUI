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
                                    bot_id = AdbConfig.botId
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
                                else -> throw Exception("Unknown config $name")
                            }

                            call.respond(ApiResponse(success = true, message = "success"))
                        }
                    }

                    // /dashboard
                    route("/dashboard") {
                        get {
                            val html = PageRenderer.renderDashboard()
                            call.respondText(html, ContentType.Text.Html)
                        }

                        get("status") {
                            call.respond(
                                DashboardStatusResponse(
                                    isObserving = AppState.isObserving,
                                    statusMessage = if (AppState.isObserving) "Observing database" else "Not observing database",
                                    lastLogs = AppState.lastChatLogs.toList()
                                )
                            )
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
                                message_send_rate = AdbConfig.messageSendRate
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
                // P18: JSON 이스케이프 적용
                val safeRoom = roomId?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
                val safeText = text?.replace("\\", "\\\\")?.replace("\"", "\\\"")?.replace("\n", "\\n") ?: ""
                val msg = """{"type":"reply","room":"$safeRoom","data":"$safeText","status":"sent"}"""
                broadcastToClients(msg)
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
                // P18: JSON 이스케이프 — kotlinx.serialization 사용
                val safeRoom = roomId?.replace("\\", "\\\\")?.replace("\"", "\\\"") ?: ""
                val safeImages = images.map { it.replace("\\", "\\\\")?.replace("\"", "\\\"") }
                val msg = """{"type":"reply","room":"$safeRoom","data":${safeImages}, "status":"sent"}"""
                broadcastToClients(msg)
            } catch (e: Exception) {
                System.err.println("AdbServer Replier image error: ${e.message}")
                e.printStackTrace()
            }
        }
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
