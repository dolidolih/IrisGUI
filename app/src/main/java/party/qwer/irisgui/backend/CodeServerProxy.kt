package party.qwer.irisgui.backend

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import io.ktor.server.websocket.WebSocketUpgrade
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.writeFully
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import party.qwer.irisgui.RuntimeLog
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * code-server 를 IrisGUI 서빙 포트(3000) 의 `/code/` 아래 리버스 프록시로 노출한다.
 *
 * code-server 는 proot userland(host loopback) 의 127.0.0.1:[DEFAULT_PORT=13080] 에만 바인딩되어
 * 기기 LAN 에서 직접 도달 불가. 이 경로 덕분에 `http://<기기IP>:3000/code/...` 만 열리면
 * 편집·터미널·WebSocket 전부 동작한다. ROOT_ADB(AdbServer)·NON_ROOT(IrisServer) 모두 포트 3000 이라
 * 모드 무관.
 *
 * prefix-preserving — `/code/x`에서 `/code`만 제거해 포워드. code-server 의 HTML 은 전부
 * 상대("./x") 참조 + WS URL 도 `location.pathname + path` 조합이라 본문改写 불필요.
 * 상대 Location 은 /code 컨텍스트로 절대화. Origin/Host 제거(identity 업스트림) — VS Code
 * origin guard 통과.
 */
object CodeServerProxy {
    const val PATH = "/code"

    /** WS/HTTP 업스트림. 기본 code-server 게속 Port; 서버 등록 시 인자로 전달. */
    @Volatile
    var upstreamPort: Int = 13080

    private const val UPSTREAM_HOST = "127.0.0.1"

    private val nextId = AtomicLong()
    private val sockets = ConcurrentHashMap<Long, WebSocket>()

    /** code-server 종료/중지 중 남은 WS tunnel 강제 정리. */
    fun closeTunnels() {
        sockets.values.forEach { runCatching { it.close(1001, "code-server stopped") } }
        sockets.clear()
    }

    /** hop-by-hop + 프록시가 루프에서 다시 쓰는 헤더(host/origin=guard, accept-encoding=identity). */
    private val SKIP_HEADERS = setOf(
        "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
        "te", "trailers", "transfer-encoding", "upgrade",
        "host", "accept-encoding", "origin", "referer", "content-length",
    )

    /** WS idle/read timeout 은 0. HTTP 도 같은 client — code-server 응답은 빠르다. */
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(0, TimeUnit.MILLISECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    /** `routing { CodeServerProxy.attach(this) }` — `{tail...}` 가 `/code` 포함 하위 전체. */
    fun attach(r: Route) {
        r.route("$PATH/{tail...}") {
            handle { if (isUpgrade(call)) ws(call) else http(call) }
        }
    }

    // ── HTTP ────────────────────────────────────────────────────────────────

    private suspend fun http(call: ApplicationCall) {
        val uri = call.request.local.uri
        val path = stripPrefix(uri.substringBefore('?'))
        val query = uri.substringAfter('?', "")
        val url = "http://$UPSTREAM_HOST:$upstreamPort$path" + if (query.isNotEmpty()) "?$query" else ""
        val method = call.request.local.method
        val body = if (method == HttpMethod.Get || method == HttpMethod.Head) {
            null
        } else {
            withContext(Dispatchers.IO) { call.receiveStream()?.readBytes() }
                ?.toRequestBody(null)
        }
        val req = Request.Builder().url(url).apply {
            call.request.headers.forEach { k, vs ->
                if (k.lowercase() !in SKIP_HEADERS) vs.forEach { addHeader(k, it) }
            }
        }.method(method.value, body).build()

        val resp = try {
            withContext(Dispatchers.IO) { client.newCall(req).execute() }
        } catch (t: Throwable) {
            call.respond(HttpStatusCode.BadGateway,
                "code-server($UPSTREAM_HOST:$upstreamPort) 에 연결할 수 없음 — 편집 세션/재설치를 확인하세요 (${t.message})")
            return
        }
        resp.use { r ->
            val status = HttpStatusCode.fromValue(r.code)
            val loc = r.headers[HttpHeaders.Location]?.let { absolutize(it, path) }
            r.headers.names().forEach { n ->
                if (n.lowercase() !in SKIP_HEADERS && !n.equals(HttpHeaders.Location, true)) {
                    call.response.headers.append(n, r.headers[n]!!)
                }
            }
            loc?.let { call.response.headers.append(HttpHeaders.Location, it) }
            val src = r.body
            if (src == null || method == HttpMethod.Head) {
                call.respond(status)
            } else {
                src.use { b ->
                    val ct = b.contentType()?.toString()?.let {
                        runCatching { ContentType.parse(it) }.getOrNull()
                    }
                    call.respondBytesWriter(contentType = ct, status = status) {
                        stream(b.byteStream(), this)
                    }
                }
            }
        }
    }

    private suspend fun stream(ins: java.io.InputStream, out: ByteWriteChannel) {
        try {
            val buf = ByteArray(64 * 1024)
            ins.use {
                while (true) {
                    val n = withContext(Dispatchers.IO) { it.read(buf) }
                    if (n < 0) break
                    if (n > 0) out.writeFully(buf, 0, n)
                }
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { out.flush() }
        }
    }

    /** `./x`→<현재 dir>/x · `/x`→/code/x · 절대 URL 그대. */
    private fun absolutize(loc: String, requestPath: String): String = when {
        loc.startsWith("http://") || loc.startsWith("https://") -> loc
        loc.startsWith("/") -> PATH + loc
        else -> requestPath.substringBeforeLast('/', "") + "/" +
            loc.removePrefix("./").removePrefix(".")
    }

    // ── WebSocket ────────────────────────────────────────────────────────────

    private suspend fun ws(call: ApplicationCall) {
        val handle: suspend (WebSocketSession) -> Unit = { session -> tunnel(session, call) }
        call.respond(WebSocketUpgrade(call, protocol = null, handle = handle))
    }

    /** ktor 세션 ↔ okhttp 업스트림 왕복. 터널당 업스트림 소켓 1. */
    private suspend fun tunnel(session: WebSocketSession, call: ApplicationCall) = coroutineScope {
        val id = nextId.incrementAndGet()
        val uri = call.request.local.uri
        val path = stripPrefix(uri.substringBefore('?'))
        val query = uri.substringAfter('?', "")
        val url = "ws://$UPSTREAM_HOST:$upstreamPort$path" + if (query.isNotEmpty()) "?$query" else ""
        val frames = Channel<Frame>(Channel.UNLIMITED)

        val upstream: WebSocket = suspendCancellableCoroutine { cont ->
            val ws = client.newWebSocket(Request.Builder().url(url).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        RuntimeLog.info("CodeServerProxy", "ws upstream open #" + id)
                        sockets[id] = webSocket
                        cont.resume(webSocket)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        frames.trySend(Frame.Text(text))
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        RuntimeLog.info("CodeServerProxy", "ws bin< #$id len=" + bytes.size)
                        frames.trySend(Frame.Binary(true, bytes.toByteArray()))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        frames.trySend(Frame.Close(CloseReason(code.toShort(), reason.take(120))))
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        frames.trySend(Frame.Close(CloseReason(code.toShort(), reason.take(120))))
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        RuntimeLog.info("CodeServerProxy", "ws upstream fail #$id: " + t.message + " " + response?.code)
                        frames.trySend(Frame.Close(CloseReason(1011,
                            ("upstream: " + (t.message ?: t.javaClass.simpleName)).take(100))))
                        if (!cont.isCompleted) {
                            cont.resumeWithException(IOException("code-server ws: ${t.message}", t))
                        }
                    }
                })
            cont.invokeOnCancellation { runCatching { ws.cancel() } }
        }

        try {
            val up = launch {
                runCatching {
                    for (f in session.incoming) {
                        val sent = when (f) {
                            is Frame.Text -> upstream.send(f.readText())
                            is Frame.Binary -> upstream.send(f.data.toByteString())
                            is Frame.Close -> { upstream.close(1000, "client"); break }
                            else -> true // ping/pong: okhttp 자동
                        }
                        if (!sent) break
                    }
                }
            }
            val down = launch {
                runCatching {
                    while (true) {
                        val f = frames.receive()
                        session.outgoing.send(f)
                        if (f is Frame.Close) break
                    }
                }
            }
            up.join()
            runCatching { upstream.close(1001, "proxy end") }
            runCatching { session.close(CloseReason(1001, "proxy end")) }
            down.cancel()
        } finally {
            sockets.remove(id)
            runCatching { upstream.cancel() }
        }
    }

    private fun isUpgrade(call: ApplicationCall): Boolean {
        val up = call.request.headers[HttpHeaders.Upgrade]?.lowercase().orEmpty()
        return "websocket" in up && call.request.headers[HttpHeaders.SecWebSocketKey] != null
    }

    private fun stripPrefix(path: String): String =
        if (path.startsWith("$PATH/")) path.substring(PATH.length)
        else if (path == PATH) "/"
        else path
}
