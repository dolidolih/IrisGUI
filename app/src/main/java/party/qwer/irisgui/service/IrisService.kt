package party.qwer.irisgui.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.R
import party.qwer.irisgui.backend.DaemonLauncher
import party.qwer.irisgui.backend.IrisServer

/**
 * IrisService — ForegroundService
 *
 * 모드별 분기:
 * - 논루팅(알림): NLS 서비스 시작 + 인프로세스 IrisServer(/reply, /ws)
 * - 루팅 ADB: 앱 프로세스 내 서버 없음. 데몬(app_process)을 기기 내 자체 ADB 연결로
 *   자율 기동 (DaemonLauncher) — host PC의 adb 불필요
 */
class IrisService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "iris_service_channel"

    companion object {
        const val ACTION_START_SERVICE = "party.qwer.irisgui.START"
        const val ACTION_STOP_SERVICE = "party.qwer.irisgui.STOP"
        const val ACTION_EXIT_SERVICE = "party.qwer.irisgui.EXIT"
        const val ACTION_RESTART_SERVICE = "party.qwer.irisgui.RESTART"
        const val NOTIFICATION_ID = 1001
    }

    override fun onCreate() {
        super.onCreate()
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "IrisGUI::ServerWakeLock")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()

        when (intent?.action) {
            ACTION_EXIT_SERVICE -> {
                cleanup()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RESTART_SERVICE -> {
                // P17: 모드 변경 시 자동 재시작
                stopIrisLogic()
                Thread.sleep(500) // 리소스 정리 대기
                startIrisLogic()
            }
            ACTION_START_SERVICE -> startIrisLogic()
            ACTION_STOP_SERVICE -> stopIrisLogic()
            else -> {
                if (AppConfig.isServiceEnabled) {
                    startIrisLogic()
                } else {
                    stopIrisLogic()
                }
            }
        }

        val notification = buildServiceNotification(AppConfig.isServiceEnabled)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    @SuppressLint("WakelockTimeout")
    private fun startIrisLogic() {
        if (AppConfig.isServiceEnabled && wakeLock?.isHeld == true) return

        wakeLock?.let {
            if (!it.isHeld) it.acquire()
        }

        AppConfig.isServiceEnabled = true

        // UI 경유 없이 서비스가 기동되면(adb am, 워치독, 부팅 후 재시작 등)
        // currentMode가 default(NON_ROOT)에 머물러 있어 영속화된 모드를 먼저 반영한다.
        // detectMode()는 AppConfig.appMode(SharedPreferences)를 우선 사용한다.
        AppModeManager.detectMode()
        println("IrisService: mode resolved = ${AppModeManager.currentMode}")

        // A1: 모드별 리소스 관리 — 각 서버가 자신의 isStarted 상태 관리
        when (AppModeManager.currentMode) {
            AppMode.ROOT_ADB -> {
                // ADB 루팅 모드: 앱 프로세스 내 서버 없음.
                // 데몬(app_process)을 기기 내 자체 ADB 연결로 자율 기동 시도 (host PC 불필요).
                println("IrisService: ADB mode — attempting self-start of app_process via local ADB")
                CoroutineScope(Dispatchers.IO).launch {
                    val result = DaemonLauncher.startDaemon(applicationContext)
                    when (result) {
                        is DaemonLauncher.StartResult.AlreadyRunning ->
                            println("IrisService: daemon already running")
                        is DaemonLauncher.StartResult.Started ->
                            println("IrisService: daemon self-started")
                        is DaemonLauncher.StartResult.Failed ->
                            println("IrisService: daemon self-start failed (${result.reason}): ${result.detail}")
                    }
                }
            }
            AppMode.NON_ROOT -> {
                IrisServer.start(applicationContext)
                startNlsService()
            }
        }

        // 하트비트 스케줄링
        scheduleHeartbeat()
    }

    /**
     * 논루팅 모드에서 NLS 서비스 시작
     */
    private fun startNlsService() {
        try {
            val nlsIntent = Intent(this, IrisNotificationService::class.java)
            startService(nlsIntent)
            println("IrisService: NLS service started")
        } catch (e: Exception) {
            System.err.println("IrisService: Failed to start NLS: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun stopIrisLogic() {
        cleanup()  // IrisServer.stop() 포함
        AppConfig.isServiceEnabled = false

        // 모드와 무관하게 NLS를 정지한다.
        // 모드 전환 시 stopIrisLogic은 '이전 모드'가 아니라 '현재(새) 모드' 기준으로
        // 호출되므로, 모드별 분기로 정지하면 직전 모드에서 켜진 NLS가 남는 문제가 있었다.
        // 따라서 항상 NLS와 서버를 모두 정지한다.
        try {
            stopService(Intent(this, IrisNotificationService::class.java))
            println("IrisService: NLS service stopped")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ADB 모드: 데몬도 함께 정지 (서비스 ON 시 자율 기동한 대칭 동작)
        if (AppModeManager.currentMode == AppMode.ROOT_ADB) {
            CoroutineScope(Dispatchers.IO).launch {
                DaemonLauncher.stopDaemon(applicationContext)
            }
        }
    }

    private fun buildServiceNotification(isRunning: Boolean): Notification {
        val title = if (isRunning) "IrisGUI가 작동 중입니다." else "IrisGUI가 작동 중이지 않습니다."
        val buttonText = if (isRunning) "중지" else "시작"
        val actionType = if (isRunning) ACTION_STOP_SERVICE else ACTION_START_SERVICE

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val actionIcon = Icon.createWithResource(this, R.mipmap.irislogo)

        val actionIntent = Intent(this, IrisService::class.java).apply {
            action = actionType
        }
        val pendingIntent = PendingIntent.getService(
            this,
            if (isRunning) 1 else 2,
            actionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val actionBuilder = Notification.Action.Builder(
            actionIcon,
            buttonText,
            pendingIntent
        ).build()

        val exitIntent = Intent(this, IrisService::class.java).apply {
            action = ACTION_EXIT_SERVICE
        }
        val exitPendingIntent = PendingIntent.getService(
            this,
            3,
            exitIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val exitAction = Notification.Action.Builder(
            actionIcon,
            "종료",
            exitPendingIntent
        ).build()

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("IrisGUI Background Service")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.btn_star_big_off)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(actionBuilder)
            .addAction(exitAction)

        contentPendingIntent?.let {
            builder.setContentIntent(it)
        }

        return builder.build()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        super.onTimeout(startId, fgsType)
        cleanup()
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        IrisServer.stop()
        AppConfig.isServiceEnabled = false
        cancelHeartbeat()
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun scheduleHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WatchdogReceiver::class.java).apply {
            action = "party.qwer.irisgui.HEARTBEAT"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val interval = 15 * 60 * 1000L
        alarmManager.setRepeating(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + interval,
            interval,
            pendingIntent
        )
    }

    private fun cancelHeartbeat() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, WatchdogReceiver::class.java).apply {
            action = "party.qwer.irisgui.HEARTBEAT"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "IrisGUI Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(channel)
    }
}
