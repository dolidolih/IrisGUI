package party.qwer.irisgui.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import party.qwer.irisgui.AppColors
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import party.qwer.irisgui.RuntimeLog

/**
 * Monaco 편집 화면 — code-server 없이 assets/editor/editor.html 을 직접 서빙.
 *
 * WebViewAssetLoader(https://appassets.androidplatform.net) 를 쓰는 이유: Monaco 의
 * 언어 워커 생성이 file:// 오리진에서는 SecurityError 로 죽는다. https 도메인은 페이지와
 * 동원이론이라 워커·esm import 가 그대로 동작한다. 파일 IO·터미널은 AndroidBridge.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MonacoEditorScreen(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val currentBack = rememberUpdatedState(onBack)
    val webViewRef = remember { mutableStateOf<WebView?>(null) }
    /** ISSUE-25: 렌더러 크래시 후 재로딩용 epoch/플래그. */
    val editorEpoch = remember { mutableIntStateOf(0) }
    var rendererGone by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val bridge = remember(name) {
        CodeEditorBridge(
            context = context,
            project = name,
            emit = { js ->
                webViewRef.value?.post {
                    runCatching { webViewRef.value?.evaluateJavascript(js, null) }
                }
            },
            requestClose = { currentBack.value() }
        )
    }
    DisposableEffect(bridge) { onDispose { bridge.close() } }

    // IME 기하: 이 redroid 는 앱 영역이 네비게이션 바로 잘려 있어(창 최하단이 아니라
    // 1184 에 걸림) IME inset(창 기준 622) 과 어떤 계산을 해도 96px(네프바) 엇갈림 —
    // 키보드 위/아래가 항상 96px씩 어긋나 검은 띠가 남는 원인이었다.
    // 편집 화면이 있는 동안만: edge-to-edge(decorFits=false, 창 0..1280 고정,
    // ADJUST_NOTHING) → inset 기준선이 화면 최하단이 되어 ime.bottom 이 곧 실제
    // 키보드 높이. Compose 쪽 bottom padding == ime inset 이면 뷰가 키보드 윗선에
    // 정확히 맞물리고, 키보드 닫힘이면 pad=0 으로 빈 띠 없이 쭉 뻗는다.
    val wvRef = webViewRef
    val decorView = LocalView.current.rootView
    var imeBottomPx by remember { mutableStateOf(0) }
    val density = LocalDensity.current
    DisposableEffect(Unit) {
        val window = (context as? android.app.Activity)?.window
        window?.also { WindowCompat.setDecorFitsSystemWindows(it, false) }
        val prevMode = window?.attributes?.softInputMode
        window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
        )
        ViewCompat.setOnApplyWindowInsetsListener(decorView) { _, ins ->
            val ime = ins.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val bars = ins.getInsets(WindowInsetsCompat.Type.systemBars())
            wvRef.value?.let { wv ->
                val loc = IntArray(2)
                wv.getLocationOnScreen(loc)
                android.util.Log.i(
                    "EditorIme", "ime=$ime nav=${bars.bottom} stat=${bars.top} " +
                        "view=${wv.width}x${wv.height} onscreen=${loc[0]},${loc[1]}"
                )
            }
            if (imeBottomPx != ime) imeBottomPx = ime
            ins
        }
        ViewCompat.requestApplyInsets(decorView)
        onDispose {
            ViewCompat.setOnApplyWindowInsetsListener(decorView, null)
            // 키보드가 열린 채로 편집 화면을 나가면(✕/back) WebView 가 죽으면서 IME 가
            // 조용히 내려가고 inset 재전송이 안 남아 — Scaffold 이 키보드 공간을
            // 계속 예약해 탭 바 아래로 빈 띠가 생긴다. 나가기 전에 IME 를 명시적으로
            // 내리고 inset 을 재계산시킨다.
            wvRef.value?.let { wv ->
                runCatching {
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                        as? android.view.inputmethod.InputMethodManager)
                        ?.hideSoftInputFromWindow(wv.windowToken, 0)
                }
                wv.clearFocus()
            }
            window?.setSoftInputMode(prevMode
                ?: android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED)
            // 창은 edge-to-edge 로 유지한다 — decorFit(true) 로 되돌리면 Android 15+
            // 에서 무시되어 실기기는 status bar 겹침이 남고, 오래된 OS 만 레이아웃이
            // 잘리는 비균일 상태가 된다. inset 는 Compose Scaffold 가 소비하므로
            // 레이아웃 잔존값 없이 재계산만 시킨다.
            ViewCompat.requestApplyInsets(decorView)
            // IME 페이더다운 애니메이션 이후에도 최종 inset 이 재확인되도록
            decorView.postDelayed({ ViewCompat.requestApplyInsets(decorView) }, 350)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .padding(bottom = with(density) { imeBottomPx.toDp() })
            .background(Color(0xFF1B1D27))
    ) {
        if (rendererGone) {
            // ISSUE-25: render process gone 에 true 만 반환하면 WebView 는 죽은 채
            // 화면만 얼음 — soft-lock 이다. 죽은 뷰를 정리하고 새로 띄울 수 있는
            // 재시작 오버레이를 보인다.
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "편집기를 다시 불러올 수 없습니다",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "WebView 렌더러가 종료됐습니다 — 다시 시도하거나 탭으로 나가세요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSub
                )
                Spacer(Modifier.height(14.dp))
                androidx.compose.material3.Button(onClick = {
                    runCatching { webViewRef.value?.destroy() }
                    webViewRef.value = null
                    editorEpoch.intValue++
                    rendererGone = false
                }) { Text("재시작") }
            }
        }
        if (!rendererGone) key(editorEpoch.intValue) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            onRelease = { wv ->
                // ISSUE-25: dispose 는 뷰 제거만 하고 네이티브 메모리는 GC 에게 맡긴다
                // — destroy 로 명시 정리 (bridge.close 는 terms 스레드만 단다).
                runCatching { wv.destroy() }
                if (webViewRef.value === wv) webViewRef.value = null
            },
            factory = { c ->
                WebView(c).apply {
                    if ((c.applicationInfo.flags and
                            android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                    ) WebView.setWebContentsDebuggingEnabled(true)
                    WebViewGpuGuard.harden(this)
                    setBackgroundColor(0xFF1B1D27.toInt())
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.textZoom = 100
                    // APK 가 교체됐어도 WebView 캐시 때문에 구버set 에디터가 남아버리지
                    // 않게 —— URL 에 rev 까지 걸어서 확실히 무력화.
                    settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                    settings.useWideViewPort = false
                    settings.loadWithOverviewMode = false
                    setOnTouchListener { v, _ ->
                        v.requestFocus(); false
                    }
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(c))
                        .build()
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView, request: android.webkit.WebResourceRequest
                        ): android.webkit.WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)

                        // 렌더러/GPU 크래시가 프로세스 전체를 죽이지 못하게. redroid 의 불안정한
                        // ANGLE/Vulkan 스택에서 WebView 렌더러가 죽으면 기본 동작은 앱 종료인데,
                        // true 를 반환하면 편집 화면만 죽고 앱은 산다.
                        // ISSUE-25: 죽은 뷰를 트리에서 떼어내고 재시작 오버레이를
                        // 띄운다 — 얼음 화면 방치 금지 (기존: true 만 반환하고 동결).
                        override fun onRenderProcessGone(
                            view: WebView, detail: android.webkit.RenderProcessGoneDetail
                        ): Boolean {
                            RuntimeLog.error(
                                "Editor", "renderer gone (didCrash=${detail.didCrash()})"
                            )
                            runCatching {
                                (view.parent as? android.view.ViewGroup)?.removeView(view)
                            }
                            android.os.Handler(android.os.Looper.getMainLooper())
                                .post { rendererGone = true }
                            return true
                        }

                        override fun onReceivedError(
                            view: WebView, request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // 워커/폰트 실패 정도는 치명적이지 않다 — 로그만.
                            if (request?.isForMainFrame == true) {
                                RuntimeLog.error("Editor", "page load: ${error?.description}")
                            }
                        }
                    }
                    webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                            android.util.Log.i("EditorJS", m.message() + " @" + m.lineNumber())
                            return true
                        }
                    }
                    addJavascriptInterface(bridge, "AndroidBridge")
                    loadUrl(URL_EDITOR)
                    webViewRef.value = this
                }
            }
        )
        }
    }

    // 닫기 = flush 후 화면 반환. ISSUE-25: flush 투입 후 ack 을 최대 300ms 대기 —
    // evaluateJavascript 직후 compose dispose/teardown 이 선행되면 마지막 <1.5s 편집이
    // 날아간다. bridge 가 JS 로부터 editorFlushed ack 을 받아 저장 확인 후 나간다.
    BackHandler {
        val wv = webViewRef.value
        if (wv == null || rendererGone) {
            currentBack.value()
        } else {
            bridge.beginFlushWait()
            scope.launch {
                wv.post {
                    runCatching {
                        wv.evaluateJavascript(
                            "window.IrisEditor && window.IrisEditor.flush()", null
                        )
                    }
                }
                val acked = withContext(Dispatchers.Default) { bridge.awaitFlushAck(300L) }
                if (!acked) RuntimeLog.warn("Editor", "flush ack timed out — autosave 에 의존")
                currentBack.value()
            }
        }
    }
}
private const val URL_EDITOR =
    "https://appassets.androidplatform.net/assets/editor/editor.html?rev=qa3-fix"
