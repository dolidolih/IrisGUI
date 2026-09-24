package party.qwer.irisgui.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    DisposableEffect(bridge) { onDispose { bridge.close() } }

    // IME inset 을 JS 에게만 알려 화면이 키보드 높이만큼 줄어들게 한다 (redroid 는 창이
    // 실제 리사이즈되지 않는 경우가 있어 네이티브 inset 이 확정 경로). requestLayout 나
    // focus/blurring 같은 네이티브 개입은 렌더러를 불안정하게 해 웹뷰가 죽으므로 금지.
    val wvRef = webViewRef
    val decorView = LocalView.current.rootView
    DisposableEffect(Unit) {
        ViewCompat.setOnApplyWindowInsetsListener(decorView) { _, ins ->
            val px = ins.getInsets(WindowInsetsCompat.Type.ime()).bottom
            wvRef.value?.evaluateJavascript(
                "window.IrisEditor&&IrisEditor.setImeBottom($px)", null
            )
            ins
        }
        ViewCompat.requestApplyInsets(decorView)
        onDispose { ViewCompat.setOnApplyWindowInsetsListener(decorView, null) }
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF1B1D27))) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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
