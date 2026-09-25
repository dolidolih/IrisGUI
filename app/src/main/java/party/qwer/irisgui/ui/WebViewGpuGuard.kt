package party.qwer.irisgui.ui

import android.os.Build
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import party.qwer.irisgui.RuntimeLog

/**
 * in-app 에디션(Monaco/xterm) 을 WebView 로 띄울 때의 ANGLE 렌더링 크래시(SIGSEGV in
 * libGLESv2_angle.so sh::TranslatorSPIRV) 회피.
 *
 * root 없는 기기: 문서-시작 JS 로 canvas 의 WebGL 컨텍스트 생성을 무력화하면 WebView 가
 * ANGLE(GLSL→SPIRV) 컴파일러 경로를 탈 수 없어서 크래시가 유발되지 않는다. Monaco/xterm.js
 * 는 컨텍스트 null 을 정상 폴백(2D 도메인 렌더러)으로 처리하므로 편집 기능은 그대로 동작한다.
 *
 * root 가능한 기기: /data/local/tmp/webview-command-line 에 --disable-3d-apis 등 원본
 * 플래그를 기록해 CSS-3D 경로까지 원천 차단한다. (flag 는 WebView 최초 생성 시 1회 읽혀
 * 다음 프로세스부터 반영되므로 JS 폴리필과 병행한다.)
 */
internal object WebViewGpuGuard {

    /** canvas 의 WebGL 컨텍스트 생성만 골라 무력화하는 스니펫. */
    private const val JS =
        "try{var _gc=HTMLCanvasElement.prototype.getContext;" +
        "HTMLCanvasElement.prototype.getContext=function(t){" +
        "if(typeof t==='string'&&/^webgl2?(experimental)?$/i.test(t))return null;" +
        "return _gc.apply(this,arguments);};}catch(e){}"

    /** WebView 생성 직후 호출. document-start JS 로 WebGL 을 차단한다. */
    fun harden(webView: WebView) {
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                WebViewCompat.addDocumentStartJavaScript(webView, JS, setOf("*"))
            }
        }.onFailure { RuntimeLog.warn("UI", "webview harden: $it") }
    }

    private const val FLAGS = "--disable-3d-apis --disable-webgl --disable-webgl2"
    private const val PATH = "/data/local/tmp/webview-command-line"

    /** root 가능 시에만 커맨드라인 파일 기록. 멱등·best-effort. */
    fun writeCommandLineFileIfNeeded() {
        runCatching {
            val f = java.io.File(PATH)
            if (f.isFile && f.readText().contains("--disable-3d-apis")) return
            // su 방언 probe — DaemonLauncher 와 같은 우선순위(redroid `su 0`, Magisk `su -c`).
            // Magisk 은 -c 가 *다음 argv 하나*만 커맨드로 소비하므로 probe/wrap 모두
            // 커맨드를 argv 하나로 붙여야 한다 (`su -c id -u`가 아니라 `su -c "id -u"`).
            val dialect = listOf("0", "-c").firstOrNull { v ->
                val probe = if (v == "-c") listOf("su", "-c", "id -u") else listOf("su", v, "id", "-u")
                runCatching {
                    val p = ProcessBuilder(probe).start()
                    p.waitFor() == 0 && p.inputStream.bufferedReader().readText().trim() == "0"
                }.getOrDefault(false)
            } ?: return
            val shell = "printf '%s\\n' '" + FLAGS + "' > " + PATH + " && chmod 666 " + PATH
            val wrap = if (dialect == "-c") listOf("su", "-c", "sh -c \"$shell\"")
            else listOf("su", dialect, "sh", "-c", shell)
            if (ProcessBuilder(wrap).start().waitFor() == 0) {
                RuntimeLog.info("UI", "webview-command-line 설치(root)")
            }
        }
    }
}
