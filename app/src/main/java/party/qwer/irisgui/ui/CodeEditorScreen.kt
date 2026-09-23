package party.qwer.irisgui.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.text.InputType
import android.view.inputmethod.InputConnection
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader

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
    DisposableEffect(bridge) { onDispose { bridge.gateListener = null; bridge.close() } }

    Box(Modifier.fillMaxSize().background(Color(0xFF1B1D27))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                object : WebView(c) {
                    // 키보드 방지: 게이트 OFF 면 IM 연결에 TYPE_NULL 을 박아 IME 가
                    // 키보드를 올릴 input type 을 아예 못 받게 한다.
                    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
                        // super 는 렌더 초기화 이전엔 null 을 반환할 수 있다 → nullable 로 선언.
                        if (bridge.imeActive) return super.onCreateInputConnection(outAttrs)
                        outAttrs.inputType = InputType.TYPE_NULL
                        return BaseInputConnection(this, false)
                    }

                    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
                        super.onWindowFocusChanged(hasWindowFocus)
                        if (!bridge.imeActive) hideSoftInput()
                    }

                    /** 올라와 있던 키보드를 즉시 내린다. */
                    fun hideSoftInput() = post {
                        runCatching {
                            (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                                as? InputMethodManager)?.hideSoftInputFromWindow(
                                windowToken, InputMethodManager.HIDE_NOT_ALWAYS
                            )
                        }
                    }
                }.apply {
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
                            view: WebView,
                            request: android.webkit.WebResourceRequest
                        ): android.webkit.WebResourceResponse? =
                            loader.shouldInterceptRequest(request.url)

                        override fun onReceivedError(
                            view: WebView,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            // 워커/폰트 실패 정도는 치명적이지 않다 — 로그만.
                            if (request?.isForMainFrame == true) {
                                party.qwer.irisgui.RuntimeLog.error(
                                    "Editor", "page load: ${error?.description}"
                                )
                            }
                        }
                    }
                    webChromeClient = android.webkit.WebChromeClient()
                    addJavascriptInterface(bridge, "AndroidBridge")
                    ViewCompat.setOnApplyWindowInsetsListener(this) { v, ins ->
                        val bars = ins.getInsets(
                            WindowInsetsCompat.Type.systemBars(),
                        )
                        v.setPadding(bars.left, 0, bars.right, bars.bottom)
                        ins
                    }
                    loadUrl(URL_EDITOR)
                    webViewRef.value = this
                }
            }
        )
    }

    // 키보드 게이트: 게이트 토글/다이얼로그 예외 때마다 실제 IM 제어를 재 동기화한다.
    LaunchedEffect(bridge) {
        bridge.gateListener = gate@{
            val w = webViewRef.value ?: return@gate
            // 입력 커넥션은 포커스 유지 중엔 재생성되지 않는다. 게이트가 바뀌었으면
            // IME 가 onCreateInputConnection 을 다시 읽도록 restartInput 한다.
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE)
                as? InputMethodManager
            imm?.restartInput(w)
            if (!bridge.imeActive) {
                w.post {
                    runCatching {
                        (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                            as? InputMethodManager)?.hideSoftInputFromWindow(
                            w.windowToken, InputMethodManager.HIDE_NOT_ALWAYS
                        )
                    }
                }
            } else {
                w.requestFocus()
            }
        }
    }

    // 닫기 = flush(autosave 반영) 후 화면 반환. flush 는 JS autosave(1.5s) 로 이미 저장된 게 대부분.
    BackHandler {
        webViewRef.value?.evaluateJavascript(
            "window.IrisEditor && window.IrisEditor.flush()", null
        )
        currentBack.value()
    }
}
private const val URL_EDITOR =
    "https://appassets.androidplatform.net/assets/editor/editor.html"
