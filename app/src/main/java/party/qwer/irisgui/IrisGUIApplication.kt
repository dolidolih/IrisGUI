package party.qwer.irisgui

import android.app.Application

class IrisGUIApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // println/System.err 로그를 로그 탭에 노출하기 위해 stdout/stderr를 버퍼에 중첩한다.
        RuntimeLog.captureStdout("UI")
        AppConfig.init(this)
    }
}
