package party.qwer.irisgui.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.runBlocking
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.backend.IrisServer

/**
 * WatchdogReceiver — 부팅/하드비트/업데이트 직후 백엔드 생존을 확인하고 복구한다.
 *
 * ISSUE-08: 기존 구현은 IrisService 서비스 프로세스 생존 여부만 확인했다. ROOT_ADB 모드에서
 * 서비스(앱 uid)와 데몬(root app_process)은 별개 프로세스이므로, 데몬만 죽었을 때
 * "서비스는 살아있다"는 이유로 아무 조치 없이 영구히 백엔드가 죽은 채로 남는다.
 * 여기서부터는 모드에 맞는 백엔드 probe 를 우선 판정근거로 쓴다:
 *   - ROOT_ADB : /process-status 조회(실패 시 1회 재시도 후 포트 오픈 확인)
 *   - NON_ROOT : IrisServer.isStarted
 * 또한 건강한 주기에서도 heartbeat alarm 을 재장전하여 체인이 조용히 끊기는 것을 막는다.
 */
class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "party.qwer.irisgui.HEARTBEAT" || action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (!AppConfig.isServiceEnabled) return
            // 백엔드 probe 는 네트워크/디스크 IO 이므로 broadcast 스레드를 막지 않는다.
            val pending = goAsync()
            Thread {
                try {
                    watchOnce(context.applicationContext)
                } catch (e: Exception) {
                    android.util.Log.w(TAG_NAME, "watchdog failed: ${e.javaClass.simpleName}: ${e.message}")
                } finally {
                    pending.finish()
                }
            }.apply { isDaemon = true }.start()
        }
    }

    private fun watchOnce(context: Context) {
        AppModeManager.detectMode()
        val mode = AppModeManager.currentMode
        val serviceRunning = isServiceRunning(context)
        var backendAlive = backendAlive(mode)
        if (!backendAlive) {
            // 오탐 방지 — 잠시 후 한 번 더 확인하고 나서야 "죽음"으로 판정한다.
            runCatching { Thread.sleep(1_500) }
            backendAlive = backendAlive(mode)
        }

        when {
            !backendAlive -> {
                // 백엔드 사망 — 서비스 살아있음 여부와 무관하게 startLogic(멱등)로 복구한다.
                start(context)
            }
            !serviceRunning -> {
                // 서비스 probe(getRunningServices) 는 최신 OS 에서 필터링되어 false 를
                // 믿기 어렵지만 — 백엔드가 살아있으면 서비스도 사실상 동작 중, 재시작 churn 을 막는다.
                rescheduleHeartbeat(context)
            }
            else -> {
                //건강 — heartbeat alarm 체인을 재장전 (startLogic 의 early-return 으로
                // re-arm 이 건너뛰어지는 구간의 공백을 watchdog 사이클이 메운다).
                rescheduleHeartbeat(context)
            }
        }
    }

    /** 모드별 실제 백엔드 probe. ROOT_ADB 는 데몬 HTTP, NON_ROOT 는 인프로세스 서버. */
    private fun backendAlive(mode: AppMode): Boolean = runBlocking {
        when (mode) {
            AppMode.ROOT_ADB ->
                AdbProcessClient.queryStatus() != null || AdbProcessClient.isHttpPortOpen()
            AppMode.NON_ROOT -> IrisServer.isStarted
        }
    }

    private fun start(context: Context) {
        runCatching {
            context.startForegroundService(Intent(context, IrisService::class.java))
        }
    }

    /** IrisService.scheduleHeartbeat 과 동일한 되살림성 재장전 (15분 주기 반복). */
    private fun rescheduleHeartbeat(context: Context) {
        runCatching {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, WatchdogReceiver::class.java).apply {
                action = "party.qwer.irisgui.HEARTBEAT"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val interval = 15 * 60 * 1000L
            alarmManager.setRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + interval,
                interval,
                pendingIntent
            )
        }
    }

    /**
     * P20: 서비스가 이미 실행 중인지 확인.
     * 참고: Android 8+ 에서는 getRunningServices 가 필터링되어 자기 앱 항목까지
     * 비어 보이는 OS/롬이 있다. 이 함수의 false 는 신뢰도가 낮으므로 백엔드 probe 가
     * 판정을 뒤집는다.
     */
    private fun isServiceRunning(context: Context): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        for (service in activityManager.getRunningServices(Int.MAX_VALUE)) {
            if (IrisService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }

    companion object {
        private const val TAG_NAME = "WatchdogReceiver"
    }
}
