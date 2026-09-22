package party.qwer.irisgui

import android.app.Application

class IrisGUIApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // println/System.err 로그를 로그 탭에 노출하기 위해 stdout/stderr를 버퍼에 중첩한다.
        RuntimeLog.captureStdout("UI")
        // WebView(Monaco 편집화면) 의 ANGLE 렌더 크래시를 root 없는 기기에서도 막는다.
        // root 기기면 커맨드라인 파일도 남긴다. 상세 구현: ui/WebViewGpuGuard.kt
        party.qwer.irisgui.ui.WebViewGpuGuard.writeCommandLineFileIfNeeded()
        AppConfig.init(this)
    }
}
