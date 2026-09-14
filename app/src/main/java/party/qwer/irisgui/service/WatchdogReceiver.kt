package party.qwer.irisgui.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import party.qwer.irisgui.AppConfig

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "party.qwer.irisgui.HEARTBEAT" || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (AppConfig.isServiceEnabled) {
                // P20: 서비스 중복 시작 방지 — 이미 실행 중이면 건너뜀
                if (!isServiceRunning(context)) {
                    val serviceIntent = Intent(context, IrisService::class.java)
                    context.startForegroundService(serviceIntent)
                }
            }
        }
    }

    /** P20: 서비스가 이미 실행 중인지 확인 */
    private fun isServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (IrisService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
}
