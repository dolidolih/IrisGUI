package party.qwer.irisgui.backend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Base64
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.KotlinxWebsocketSerializationConverter
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.send
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.RuntimeLog
import party.qwer.irisgui.models.*
import party.qwer.irisgui.scripting.BionicRuntime
import party.qwer.irisgui.service.ReplyManager
import java.io.File

/**
 * IrisServer — 인프로세스 HTTP 서버 (논루팅(알림) 모드 전용)
 *
 * /reply, /ws 만 제공 — 답장은 ReplyManager(PendingIntent)로 발송.
 * (ROOT_ADB 모드는 앱 프로세스에서 서버를 띄우지 않으며,
 *  app_process의 AdbServer가 전 엔드포인트를 담당한다.)
 */
object IrisServer {
    // ISSUE-17: start/stop 은 UI/서비스/워치독/Netty 어디에서나 올 수 있다 —
    // check-then-act 경쟁과 반-발행 상태를 막기 위해 synchronized + volatile.
    @Volatile
    private var activeEngine: ApplicationEngine? = null

    /** ISSUE-17: cacheDir 에 남는 iris_temp_* 잔재 정리 — 유출된 이미지 정리. */
    private fun sweepTempImages(cacheDir: File) {
        runCatching {
            val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
            cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("iris_temp_") && f.lastModified() < cutoff) {
                    f.delete()
                }
            }
        }
    }

    /** A1: 서버 시작 상태 — 외부에서 확인용 */
    val isStarted: Boolean
        get() = activeEngine != null

    /** 직전 기동 실패 원인(포트 점유 등) — 정지 시 초기화. */
    var lastError: String? = null
        private set

    /** 기동 실패 원인이 포트 점유인지 판단. */
    val isPortInUseError: Boolean
        get() = lastError?.let {
            val l = it.lowercase()
            l.contains("address already in use") || l.contains("bindexception") ||
                l.contains("already in use")
        } == true

    /**
     * ISSUE-15: SharedFlow/tryEmit 폐기는 느린 구독자 하나가 전체 손실을 만든다.
     * 연결별 bounded 채널 fanout 으로 교체 — publish 는 non-blocking.
     */
    private val wsFanout = WsFanout("IrisServer")

    /** WS 이벤트 전달 (non-blocking). @return 버퍼 만충으로 폐기한 건수. */
    fun broadcastToClients(message: String): Long = wsFanout.publish(message)

    /** ws 에 붙어있는 클라이언트 수 — NLS 이벤트가 흘러갈 좌표가 살아있는지 로그로 본다. */
    val wsSubscribers: Int get() = wsFanout.subscriberCount

    /**
     * 인프로세스 서버 기동.
     * @return 기동 성공 여부 — 실패 시 [lastError]에 원인이 남는다.
     */
    fun start(context: Context): Boolean = synchronized(this) {
        if (activeEngine != null) return true
        lastError = null
        try {
            sweepTempImages(context.cacheDir)
            val lenientJson = Json { ignoreUnknownKeys = true }

            // ISSUE-33: 논루팅 인프로세스 서버도 동일 — loopback 바인딩 기본화.
            embeddedServer(Netty, host = "0.0.0.0", port = AppConfig.serverPort) {
                install(WebSockets) {
                    contentConverter = KotlinxWebsocketSerializationConverter(lenientJson)
                }
                install(ContentNegotiation) { json(lenientJson) }
                install(StatusPages) {
                    exception<Throwable> { call, cause ->
                        call.respond(
                            HttpStatusCode.InternalServerError, ApiResponse(
                                success = false,
                                message = cause.message ?: "unknown error"
                            )
                        )
                    }
                }

                routing {
                    // ── WebSocket ──────────────────────────────
                    webSocket("/ws") {
                        // ISSUE-15: 연결별 수신 채널 — 다른 연결/publisher 무영향.
                        val sub = wsFanout.subscribe()
                        RuntimeLog.info(
                            "Server", "ws 접속 — 현재 접속 수 ${wsFanout.subscriberCount} " +
                                "(이 값 없이 이벤트만 나란하면 유입 단 문제)"
                        )
                        try {
                            for (msg in sub.channel) {
                                send(msg)
                            }
                        } finally {
                            wsFanout.unsubscribe(sub)
                            RuntimeLog.info("Server", "ws 종료 — 잔여 접속 수 ${wsFanout.subscriberCount}")
                        }
                    }


                    // ── 공통: /reply ───────────────────────────
                    post("/reply") {
                        val req = call.receive<ReplyRequest>()
                        val roomId = req.room

                        when (req.type) {
                            "text" -> {
                                val text = req.data.jsonPrimitive.content
                                handleTextReply(context, roomId, text, req.threadId)
                            }
                            "image_multiple", "image" -> {
                                val images = if (req.data is JsonArray) {
                                    req.data.jsonArray.map { it.jsonPrimitive.content }
                                } else {
                                    listOf(req.data.jsonPrimitive.content)
                                }
                                handleImageReply(context, roomId, images)
                            }
                            else -> {
                                call.respond(ApiResponse(success = false, message = "Unknown reply type: ${req.type}"))
                                return@post
                            }
                        }
                        call.respond(ApiResponse(success = true, message = "Enqueued"))
                    }

                    // ── bionic userland 패키지 설치 (게스트 iris-pk 등) ──
                    post("/pkg-install") {
                        val body = runCatching { call.receive<JsonObject>() }.getOrNull()
                        val names = body?.optJsonArrayStrings("packages") ?: emptyList()
                        if (names.isEmpty()) {
                            call.respond(mapOf("ok" to false, "message" to "packages 없음"))
                            return@post
                        }
                        val (accepted, msg) = BionicRuntime.startAsync(context, names)
                        call.respondText(
                            "{\"ok\":" + accepted + ",\"started\":true,\"message\":" +
                                org.json.JSONObject.quote(msg) + "}",
                            io.ktor.http.ContentType.Application.Json)
                    }

                    get("/pkg-install-status") {
                        val m = BionicRuntime.statusJson()
                        fun q(s: String) = org.json.JSONObject.quote(s)
                        call.respondText("{" +
                            "\"running\":" + m["running"] + "," +
                            "\"current\":" + q(m["current"].toString()) + "," +
                            "\"done\":" + m["done"] + "," +
                            "\"total\":" + m["total"] + "," +
                            "\"message\":" + q(m["message"].toString()) + "," +
                            "\"error\":" + q(m["error"].toString()) + "}",
                            io.ktor.http.ContentType.Application.Json)
                    }

                    // ── 논루팅 모드: /reply 외 엔드포인트 없음 ──

                }
            }.start(wait = false).also { activeEngine = it.engine }

            startReplyManager()
            println("IrisServer: started on port ${AppConfig.serverPort}")
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            lastError = e.message ?: e.javaClass.simpleName
            activeEngine = null
            return false
        }
    }

    /** /pkg-install 바디 헬퍼: {"packages": ["a","b"]} → [a,b]. */
    private fun JsonObject.optJsonArrayStrings(key: String): List<String> =
        (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    /** L3: ReplyManager 시작 (인프로세스 서버는 논루팅(알림) 모드 전용) */
    fun startReplyManager() {
        ReplyManager.startQueue()
    }

    fun stop(): Unit = synchronized(this) {
        // P10: ReplyManager 정지 — 코루틴 누수 방지
        ReplyManager.stopQueue()
        activeEngine?.stop(1000, 2000)
        activeEngine = null
        lastError = null
    }

    /**
     * 텍스트 답장 — PendingIntent 기반 replyAction 사용 (논루팅(알림) 모드).
     */
    private fun handleTextReply(context: Context, room: String, text: String, threadId: String?) {
        ReplyManager.enqueue {
            val action = ReplyManager.replyActions[room]
            if (action != null) {
                val intent = Intent()
                val bundle = Bundle()

                bundle.putCharSequence(action.remoteInput.resultKey, text)
                android.app.RemoteInput.addResultsToIntent(arrayOf(action.remoteInput), intent, bundle)

                try {
                    action.pendingIntent.send(context, 0, intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // P5: CoroutineScope.launch로 변경 — 함수가 suspend가 아님
                    CoroutineScope(Dispatchers.Main).launch {
                        android.widget.Toast.makeText(context, "Failed to send reply: ${e.message}", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                // P5: CoroutineScope.launch로 변경
                CoroutineScope(Dispatchers.Main).launch {
                    android.widget.Toast.makeText(context, "No ReplyAction stored for room: $room", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun handleImageReply(context: Context, room: String, base64Images: List<String>) {
        // PendingIntent 기반 replyAction 사용 (논루팅(알림) 모드)
        ReplyManager.enqueue {
            val savedFiles = mutableListOf<File>()
            val uris = base64Images.mapNotNull { base64 ->
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)

                    // ISSUE-17: millis 파일명 충돌(동일 ms 요청) 덮어쓰기 방지
                    val file = File.createTempFile("iris_temp_", ".png", context.cacheDir)

                    file.outputStream().use { it.write(bytes) }
                    savedFiles.add(file)

                    androidx.core.content.FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                } catch (e: Exception) {
                    e.printStackTrace()
                    null
                }
            }

            if (uris.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                    setPackage("com.kakao.talk")
                    type = "image/*"
                    putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                    putExtra("key_id", room.toLongOrNull() ?: 0L)
                    putExtra("key_type", 1)
                    putExtra("key_from_direct_share", true)

                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }

                try {
                    context.startActivity(intent)

                    CoroutineScope(Dispatchers.IO).launch {
                        delay(300_000)
                        savedFiles.forEach { file ->
                            if (file.exists()) {
                                val deleted = file.delete()
                                android.util.Log.d("IrisCleanup", "Deleted ${file.name}: $deleted")
                            }
                        }
                    }

                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        try {
                            val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                                addCategory(Intent.CATEGORY_HOME)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            }
                            context.startActivity(homeIntent)
                        } catch (e: Exception) { e.printStackTrace() }
                    }, 3000)

                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
