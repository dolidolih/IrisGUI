package party.qwer.irisgui

import android.app.Application

class IrisGUIApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppConfig.init(this)
    }
}
