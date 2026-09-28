package party.qwer.irisgui.backend

import android.content.Context
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.concurrent.ConcurrentHashMap
import okhttp3.Request
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.IrisGUIApplication
import party.qwer.irisgui.RuntimeLog
import party.qwer.irisgui.ui.CodeEditorBridge

/**
 * /editor — 앱에 내장된 Monaco 편집기를 LAN 의 다른 기기(평범한 브라우저)에서도
 * 제어할 수 있게 하는 웹 리모트 평면.
 *
 * 설계: 코드/저장/실행/터미널 로직은 ui.CodeEditorBridge 를 세션 단위로 그대로
 * 재사용한다(WebView 편집 화면과 동일한 코드경로 → 동작 차이 없음). WebView 의
 * `window.AndroidBridge` 시그니처를 (동기 XHR RPC + WS 제어/푸시로 구현한)
 * JS WebBridge 가 same-origin 에서 구현한다. 브릿지가 뱉는 emit js 스니펫은
 * WS text frame 으로 브로드캐스트되고, 클라이언트가 new Function() 으로 돌려
 * 실행한다(WebView 의 evaluateJavascript 와 동격의 신뢰 수준).
 *
 * 보안: 의도적으로 미인증 — /ws, /reply, /pkg-install 와 같은 "신뢰하는
 * 내부 네트워크 전용" 전제의 연속이며, 바인드도 리스너(serverPort)를 따른다.
 *
 * 배속 — 리스너가 서는 곳이 곧 모드:
 * - NON_ROOT / HAYUL: IrisServer/AdbServer 가 앱 프로세스에 선다 → registerLocal.
 *   그 라우팅 안에서 세션/브릿지를 직접 서빙하므로 서비스 포트가 그대로 /editor 의
 *   포트다.
 * - ROOT_ADB: 리스너(서버 포트)는 데몬(app_process)에 서고 편집기/userland 는 앱에
 *   산다 → registerRelay. 데몬 라우팅은 받은 요청을 루프백으로 앱 안쪽 internal
 *   서버(AppConfig.editorPort, 기본 3100 — 서비스가 도는 동안 앱 프로세스가
 *   127.0.0.1 만에 유지)에 그대로 붙여 넘긴다. 상태는 앱에 있고 데몬은 pipe 만 한다.
 * 따라서 사용자 URL 은 모드 무관 `http://ip:<서비스포트>/editor` 하나로 통일된다.
 */
object EditorWeb {

    @Volatile private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /**
     * internal 서버 — 서비스가 도는 동안 bridge/userland 의 거주지(앱 프로세스)에
     * 상주한다. 두 용도: (1) ROOT_ADB 에서 데몬 relay 의 수신점, (2) 그 외 모드에서도
     * 로컬에서 열려 있는 백업창. 사용자-facing URL 은 언제나 서비스 포트(registerLocal)이
     * 맡고, 이건 127.0.0.1 만 bind 한다. 이미 떠 있으면 멱등 no-op.
     */
    @Synchronized
    fun start(context: Context, port: Int = AppConfig.editorPort) {
        if (engine != null) return
        try {
            engine = embeddedServer(Netty, host = "127.0.0.1", port = port) {
                install(WebSockets)
                routing { EditorWeb.register(this) }
            }.start(wait = false)
            RuntimeLog.info("Editor", "editor server on :$port")
        } catch (e: Exception) {
            engine = null
            RuntimeLog.warn("Editor", "editor server bind :$port failed: ${e.message}")
        }
    }

    @Synchronized
    fun stop() {
        runCatching { engine?.stop(500, 1000) }
        engine = null
        closeAll()
    }

    val isStarted: Boolean get() = engine != null

    /** LinuxScripts 프로젝트명 규칙과 동일. */
    private val ProjectName = Regex("^[A-Za-z0-9_-]{1,64}$")

    private class Session(val project: String, val bridge: CodeEditorBridge) {
        @Volatile var lastSeen = System.currentTimeMillis()
        private val chans = ConcurrentHashMap.newKeySet<Channel<String>>()

        fun touch() { lastSeen = System.currentTimeMillis() }

        fun bcast(s: String) { chans.forEach { runCatching { it.trySend(s) } } }

        fun subscribe(): Channel<String> = Channel<String>(64).also { chans += it }
        fun unsubscribe(c: Channel<String>) { chans -= c; c.close() }
        fun idle(deadMs: Long) =
            chans.isEmpty() && System.currentTimeMillis() - lastSeen > deadMs

        fun close() { runCatching { bridge.close() } }
    }

    @Volatile private var reaper: Thread? = null
    private val sessions = ConcurrentHashMap<String, Session>()

    /** 앱 context 집탄: in-process(HAYUL/adb 앱 프로세스) → 데몬용 currentApplication 폴백. */
    private fun appContext(): Context? =
        IrisGUIApplication.instance
            ?: runCatching {
                Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null) as? Context
            }.getOrNull()

    private fun session(project: String): Session? {
        if (!ProjectName.matches(project)) return null
        sessions[project]?.let { return it.also { s -> s.touch() } }
        val ctx = appContext() ?: return null
        return sessions.computeIfAbsent(project) { name ->
            ensureReaper()
            Session(name, CodeEditorBridge(
                context = ctx,
                project = name,
                emit = { js -> sessions[name]?.bcast(js) },
                requestClose = { /* 웹 화면은 닫기가 없다 — WS 종료/유휴 타임아웃이 회수 */ }
            ))
        }
    }

    /** 클라이언트 없는 유휴 세션(15분) 정리 — terminal proot 누수를 막는다. */
    private fun ensureReaper() {
        if (reaper?.isAlive == true) return
        synchronized(this) {
            if (reaper?.isAlive == true) return
            reaper = Thread {
                while (sessions.isNotEmpty()) {
                    runCatching { Thread.sleep(30_000) }
                    sessions.entries.filter { it.value.idle(15 * 60_000L) }
                        .map { it.key }.forEach { k ->
                            runCatching { sessions.remove(k)?.close() }
                            RuntimeLog.info("Editor", "web session idle-closed: $k")
                        }
                }
            }.apply { isDaemon = true; name = "editor-idle" }
            reaper!!.start()
        }
    }

    /** AdbServer.stopServer() — 남은 세션 terminal(proot) 을 즉시 내린다. */
    fun closeAll() {
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
    }

    /** AdbServer/IrisServer routing{} 에서 이것만 부른다. */
    fun register(r: Route) {
        // 라우팅을 어느 프로세스가 받느냐로 갈린다: 앱(인스턴스 설정됨)은 직접 서빙,
        // 데몬(app_process)은 앱 내부를 붙여 넘기는 relay 라우팅만 얹는다.
        if (party.qwer.irisgui.IrisGUIApplication.instance != null) registerLocal(r)
        else registerRelay(r)
    }

    // ── relay (ROOT_ADB: 데몬 라우팅 → 앱 internal) ───────────────────────────
    // 데몬의 서비스 포트(/editor*, /assets/editor/*, /editor/ws)로 들어온 요청을
    // 루프백의 앱 internal 서버(editorPort, 기본 3100)에 그대로 전달한다. 상태(세션,
    // bridge, terminal proot)는 전부 앱에 있고, 데몬은 바이트를 pipe 만 한다.

    private val relayClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
    }

    private fun relayBase() = "http://127.0.0.1:${AppConfig.editorPort}"

    private fun registerRelay(r: Route) = with(r) {
        get("/editor") { relayHttp(call, null) }
        get("/editor/") { relayHttp(call, null) }
        route("/assets/editor/{path...}") {
            get { relayHttp(call, null) }
        }
        route("/editor/api/{method}") {
            get { relayHttp(call, null) }
            post { relayHttp(call, call.receiveText()) }
        }
        webSocket("/editor/ws") { relayWs(call) }
    }

    private suspend fun relayHttp(call: ApplicationCall, body: String?) {
        val target = relayBase() + call.request.local.uri
        val req = okhttp3.Request.Builder().url(target).let { b ->
            if (body != null) b.post(body.toRequestBody("application/json".toMediaType()))
            else b.get()
        }.build()
        val resp = runCatching {
            withContext(Dispatchers.IO) { relayClient.newCall(req).execute() }
        }.getOrElse { e ->
            return call.respondText(
                "{\"ok\":false,\"message\":\"editor relay: 앱 internal 서버(:" +
                    "${AppConfig.editorPort}) 접근 불가 — 서비스가 도는 중인지 확인 " +
                    "(${e.javaClass.simpleName}: ${e.message?.replace('"', '\'')})\"}",
                ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
        }
        resp.use { r ->
            val bytes = runCatching { r.body?.bytes() }.getOrNull() ?: ByteArray(0)
            val code = HttpStatusCode.fromValue(r.code)
            if (bytes.isEmpty()) call.respond(code)
            else call.respondBytes(
                bytes,
                r.header("Content-Type")?.let { runCatching { ContentType.parse(it) }.getOrNull() }
                    ?: ContentType.Application.OctetStream,
                status = code)
        }
    }

    /** 데몬 WS → 앱 internal WS: frame 을 양방향 pipe 한다. */
    private suspend fun io.ktor.server.websocket.WebSocketServerSession.relayWs(
        call: ApplicationCall
    ) {
        val target = "ws://127.0.0.1:${AppConfig.editorPort}" + call.request.local.uri
        val down = Channel<Frame>(64)
        val remote = relayClient.newWebSocket(
            okhttp3.Request.Builder().url(target).build(),
            object : okhttp3.WebSocketListener() {
                override fun onMessage(ws: okhttp3.WebSocket, text: String) {
                    down.trySend(Frame.Text(text))
                }

                override fun onMessage(ws: okhttp3.WebSocket, bytes: okio.ByteString) {
                    down.trySend(Frame.Binary(true, bytes.toByteArray()))
                }

                override fun onClosed(ws: okhttp3.WebSocket, code: Int, reason: String) {
                    runCatching { down.close() }
                }

                override fun onFailure(ws: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                    runCatching {
                        down.trySend(Frame.Text(
                            "{\"err\":\"editor relay: downlink 실패 " +
                                "(${t.javaClass.simpleName}: ${t.message?.replace('"', '\'')})\"}"))
                    }
                    runCatching { down.close() }
                }
            })
        val pump = launch {
            for (msg in down) runCatching { send(msg) }
        }
        try {
            for (f in incoming) {
                when (f) {
                    is Frame.Close -> { remote.close(1001, "bye"); break }
                    is Frame.Text -> runCatching { remote.send(f.readText()) }
                    is Frame.Binary ->
                        runCatching { remote.send(okio.ByteString.of(*f.data)) }
                    else -> Unit // ping/pong — engine 단에서 자동 처리
                }
            }
        } finally {
            pump.cancel()
            runCatching { remote.close(1001, "client gone") }
            runCatching { down.close() }
        }
    }

    /** 편집기를 직접 서빙하는(앱 프로세스 전용) 라우팅 — 세션/브릿지가 여기에 산다. */
    private fun registerLocal(r: Route) = with(r) {
        get("/editor") { call.page() }
        get("/editor/") { call.page() }

        // html 의 base/href 가 절대경로(/assets/editor/...)라서 그대로 재사용 가능.
        route("/assets/editor/{path...}") {
            get {
                val rel = call.parameters.getAll("path")?.joinToString("/") ?: return@get
                call.serveAsset(rel)
            }
        }

        // window.AndroidBridge 메서드 대응 RPC. GET 은 query, 큰 덩어리(text/b64)는 body.
        route("/editor/api/{method}") {
            get { call.rpc(call.parameters["method"] ?: "listDir", null) }
            post { call.rpc(call.parameters["method"] ?: "", call.receiveText()) }
        }

        // emit(터미널 출력 등 브릿지 푸시) + 터미널 제어(입력은 WS 로 보낸다).
        webSocket("/editor/ws") {
            val s = session(call.request.queryParameters["project"] ?: "main")
            if (s == null) {
                send(Frame.Text(
                    "{\"err\":\"editor session unavailable (daemon-hosted server or bad project name)\"}"))
                return@webSocket
            }
            val ch = s.subscribe()
            try {
                val writer = launch { for (msg in ch) send(Frame.Text(msg)) }
                for (f in incoming) {
                    if (f is Frame.Close) break
                    if (f is Frame.Text) {
                        s.touch()
                        handleControl(s, f.readText())
                    }
                }
                writer.cancel()
            } finally {
                s.unsubscribe(ch)
            }
        }
    }

    private fun handleControl(s: Session, json: String) = runCatching {
        val o = org.json.JSONObject(json)
        when (o.optString("op")) {
            "input" -> s.bridge.termInput(o.optInt("id"), o.optString("b64"))
            "resize" -> s.bridge.termResize(o.optInt("id"), o.optInt("cols"), o.optInt("rows"))
            "stop" -> s.bridge.termStop(o.optInt("id"))
        }
    }

    private suspend fun ApplicationCall.rpc(method: String, body: String?) {
        val s = session(request.queryParameters["project"] ?: "main")
            ?: return respondText(
                "{\"ok\":false,\"message\":\"editor needs the app process / valid project\"}",
                status = HttpStatusCode.ServiceUnavailable)
        s.touch()
        val q = request.queryParameters
        val out: String? = try {
            when (method) {
                "listDir" -> s.bridge.listDir()
                "read" -> s.bridge.read(q["rel"] ?: "")
                "write" -> s.bridge.write(q["rel"] ?: "", body ?: "")
                "create" -> s.bridge.create(q["rel"] ?: "", q["dir"] == "1")
                "remove" -> s.bridge.remove(q["rel"] ?: "")
                "rename" -> s.bridge.rename(q["from"] ?: "", q["to"] ?: "")
                "projectInfo" -> s.bridge.projectInfo()
                "pipPackages" -> s.bridge.pipPackages()
                "members" -> s.bridge.members(q["module"] ?: "")
                "runScript" -> s.bridge.runScript()
                "runScriptAsync" -> s.bridge.runScriptAsync()
                "stopScript" -> s.bridge.stopScript()
                "scriptRunning" -> s.bridge.scriptRunning()
                "termStart" -> s.bridge.termStart(
                    q["cols"]?.toIntOrNull() ?: 80, q["rows"]?.toIntOrNull() ?: 24)
                "termInput" -> { s.bridge.termInput(q["id"]?.toIntOrNull() ?: 0, body ?: ""); "ok" }
                "termResize" -> {
                    s.bridge.termResize(q["id"]?.toIntOrNull() ?: 0,
                        q["cols"]?.toIntOrNull() ?: 80, q["rows"]?.toIntOrNull() ?: 24); "ok"
                }
                "termStop" -> { s.bridge.termStop(q["id"]?.toIntOrNull() ?: 0); "ok" }
                "clipboardGet" -> s.bridge.clipboardGet()
                "clipboardSet" -> s.bridge.clipboardSet(body)
                "log" -> { RuntimeLog.info("Editor", "web: " + (body ?: "")); "ok" }
                "closeEditor" -> "ok"
                else -> return respondText(
                    "{\"ok\":false,\"message\":\"unknown method: $method\"}",
                    status = HttpStatusCode.NotFound)
            }
        } catch (e: Exception) {
            return respondText(
                "{\"ok\":false,\"message\":\"${e.message?.replace('"', '\'') ?: "rpc failed"}\"}",
                status = HttpStatusCode.InternalServerError)
        }
        if (out == null) respond(HttpStatusCode.NoContent) // read 실패(null) 등
        else respondBytes(out.toByteArray(Charsets.UTF_8), ContentType.Application.Json)
    }

    // ── html / asset ─────────────────────────────────────────────────────

    private suspend fun ApplicationCall.page() {
        val ctx = appContext()
            ?: return respondText("editor needs the app process",
                status = HttpStatusCode.ServiceUnavailable)
        val bytes = runCatching {
            ctx.assets.open("editor/editor.html").use { it.readBytes() }
        }.getOrElse { return respondText("editor assets missing", status = HttpStatusCode.NotFound) }
        response.headers.append(HttpHeaders.CacheControl, "no-cache")
        respondBytes(bytes, ContentType.Text.Html)
    }

    private val MIME = mapOf(
        "html" to ContentType.Text.Html,
        "js" to ContentType.parse("text/javascript"),
        "mjs" to ContentType.parse("text/javascript"),
        "css" to ContentType.Text.CSS,
        "json" to ContentType.Application.Json,
        "map" to ContentType.Application.Json,
        "wasm" to ContentType.parse("application/wasm"),
        "svg" to ContentType.parse("image/svg+xml"),
        "png" to ContentType.parse("image/png"),
        "ttf" to ContentType.parse("font/ttf"),
        "woff" to ContentType.parse("font/woff"),
        "woff2" to ContentType.parse("font/woff2"),
    )

    private suspend fun ApplicationCall.serveAsset(rel: String) {
        if (rel.contains("..") || rel.startsWith("/"))
            return respondText("bad path", status = HttpStatusCode.BadRequest)
        val ctx = appContext()
            ?: return respondText("editor needs the app process",
                status = HttpStatusCode.ServiceUnavailable)
        val bytes = runCatching {
            ctx.assets.open("editor/" + rel).use { it.readBytes() }
        }.getOrElse { return respondText("not found: $rel", status = HttpStatusCode.NotFound) }
        response.headers.append(HttpHeaders.CacheControl, "max-age=86400")
        respondBytes(bytes, MIME[rel.substringAfterLast('.', "").lowercase()]
            ?: ContentType.Application.OctetStream)
    }

}
