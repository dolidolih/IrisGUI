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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.AppState
import party.qwer.irisgui.RuntimeLog
import party.qwer.irisgui.R
import party.qwer.irisgui.backend.AdbProcessClient
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

    /** start/stop 직렬화 — 모드 전환처럼 연속 전송되는 명령의 경쟁 방지 */
    private val logicMutex = kotlinx.coroutines.sync.Mutex()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Toast 등 UI API 는 main looper 에서 호출해야 한다. */
    private val mainLooperHandler = android.os.Handler(android.os.Looper.getMainLooper())

    companion object {
        const val ACTION_START_SERVICE = "party.qwer.irisgui.START"
        const val ACTION_STOP_SERVICE = "party.qwer.irisgui.STOP"
        const val ACTION_EXIT_SERVICE = "party.qwer.irisgui.EXIT"
        const val ACTION_RESTART_SERVICE = "party.qwer.irisgui.RESTART"
        const val NOTIFICATION_ID = 1001

        /** RuntimeLog source tag */
        private const val TAG = "IrisService"
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
                serviceScope.launch { stopLogic(reportUser = false) }
                cleanup()
                stopForeground(Service.STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            // 모드 전환/재시작: 이전 모드 백엔드가 완전히 내려간 뒤 새 모드를 기동한다.
            // 정지를 기다리지 않으면 이전 모드가 쓰던 포트가 남아 BindException(포트 사용 중)이 발생한다.
            ACTION_RESTART_SERVICE -> serviceScope.launch {
                stopLogic(reportUser = false)
                awaitBackendDown()
                startLogic(reportUser = true)
            }
            ACTION_START_SERVICE -> serviceScope.launch { startLogic(reportUser = true) }
            ACTION_STOP_SERVICE -> serviceScope.launch { stopLogic(reportUser = true) }
            else -> serviceScope.launch {
                if (AppConfig.isServiceEnabled) startLogic(reportUser = false) else stopLogic(reportUser = false)
            }
        }

        // Foreground 서비스는 startForegroundService 직후 (대략 5s 내) main thread 에서
        // synchronous 로 startForeground 를 호출해야 한다. 여기서 기동용 notification 을
        // 올리고, startLogic/stopLogic 의 실제 동작 결과가 확정되면 updateForegroundNotification()
        // 으로 문구만 갱신한다.
        val notification = buildServiceNotification(AppConfig.isServiceEnabled)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        return START_STICKY
    }

    /**
     * 서비스 기동 — 백엔드 상태를 확인하고 띄운 뒤 결과를 토스트로 알려준다.
     * 이미 실행 중이면 아무것도 하지 않는다(워치독/재전송 대비 멱등).
     */
    @SuppressLint("WakelockTimeout")
    private suspend fun startLogic(reportUser: Boolean): Boolean =
        logicMutex.withLock { startLogicLocked(reportUser) }

    private suspend fun startLogicLocked(reportUser: Boolean): Boolean {
        // 백엔드 생존 여부를 직접 확인한다 — AppConfig.isServiceEnabled만 믿으면
        // 데몬이 죽은 뒤에도 "실행 중"으로 보여 기동이 스킵된다.
        if (backendRunning()) {
            AppState.running = true
            AppConfig.isServiceEnabled = true
            if (reportUser) toast("서비스는 이미 실행 중입니다")
            RuntimeLog.info(TAG, "start skipped — backend already running")
            return true
        }

        wakeLock?.let { if (!it.isHeld) it.acquire() }
        AppConfig.isServiceEnabled = true

        // UI 경유 없이 서비스가 기동되면(adb am, 워치독, 부팅 후 재시작 등)
        // currentMode가 default(NON_ROOT)에 머물러 있어 영속화된 모드를 먼저 반영한다.
        AppModeManager.detectMode()
        val mode = AppModeManager.currentMode
        RuntimeLog.info(TAG, "start requested, mode=$mode")

        val port = AppConfig.serverPort
        var started = false
        var failure: String? = null

        // 모드 전환 직전 모드에서 사용하던 백엔드가 살아 있으면 같은 포트를 노리는
        // 새 백엔드가 BindException(포트 사용 중)으로 실패한다. 먼저 반납시킨다.
        runCatching {
            withContext(Dispatchers.IO) {
                if (mode == AppMode.NON_ROOT && AdbProcessClient.queryStatus() != null) {
                    DaemonLauncher.stopDaemon(applicationContext)
                    DaemonLauncher.waitForDaemonDown()
                }
            }
        }
        if (mode == AppMode.ROOT_ADB && IrisServer.isStarted) IrisServer.stop()

        when (mode) {
            AppMode.ROOT_ADB -> {
                // 데emon(app_process)을 기기 내 자체 ADB 연결로 자율 기동 (host PC 불필요).
                // 반드시 await — 기다리지 않으면 UI가 즉시Polling해서 "OFF"로 표기된다.
                val result = withContext(Dispatchers.IO) {
                    DaemonLauncher.startDaemon(applicationContext)
                }
                when (result) {
                    is DaemonLauncher.StartResult.AlreadyRunning -> {
                        started = true
                        RuntimeLog.info(TAG, "daemon already running")
                    }
                    is DaemonLauncher.StartResult.Started -> {
                        started = true
                        RuntimeLog.info(TAG, "daemon started")
                    }
                    is DaemonLauncher.StartResult.Failed -> {
                        failure = DaemonLauncher.failureMessage(result.reason, result.detail)
                        RuntimeLog.error(TAG, "daemon start failed (${result.reason}): ${result.detail}")
                    }
                }
            }
            AppMode.NON_ROOT -> {
                val ok = IrisServer.start(applicationContext)
                if (ok) {
                    startNlsService()
                    started = true
                    RuntimeLog.info(TAG, "NLS mode started on port $port")
                } else {
                    failure = if (IrisServer.isPortInUseError) {
                        "포트 $port 이 이미 사용 중입니다. 다른 포트(예: ${port + 1})로 바꿔주세요"
                    } else {
                        "서버 시작 실패: ${IrisServer.lastError ?: "알 수 없는 오류"}"
                    }
                    RuntimeLog.error(TAG, failure!!)
                }
            }
        }

        if (started) {
            AppState.running = true
            scheduleHeartbeat()
            if (reportUser) toast("서비스를 시작했습니다 (포트 $port)")
        } else {
            // 실패 상태에서 enabled=true 를 남기면 워치독이 무한 재시작하므로 되돌린다.
            AppConfig.isServiceEnabled = false
            AppState.running = false
            cancelHeartbeat()
            val message = failure ?: "서비스 시작에 실패했습니다"
            if (reportUser) toast(message, long = failure != null)
        }
        updateForegroundNotification()
        return started
    }

    /** 백엔드(데몬 / 인프로세스 서버)의 실제 생존 여부. */
    private suspend fun backendRunning(): Boolean = when (AppModeManager.currentMode) {
        AppMode.ROOT_ADB -> AdbProcessClient.queryStatus()?.server_running == true
        AppMode.NON_ROOT -> IrisServer.isStarted
    }

    /**
     * 모드 전환 직전 백엔드가 포트를 반납할 때까지 대기.
     * stopLogic 은 명령 전송까지만 하고 끝나므로, 직후 새 백엔드가 같은 포트를
     * 바인드하면 BindException(포트 사용 중)이 된다. 데몬은 HTTP 응답 폐기로,
     * 인프로세스 서버는 isStarted=false 로 반납이 확인된다.
     */
    private suspend fun awaitBackendDown(timeoutMs: Long = 6_000): Boolean =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val daemonGone = AdbProcessClient.queryStatus() == null
                val serverGone = !IrisServer.isStarted
                if (daemonGone && serverGone) return@withContext true
                kotlinx.coroutines.delay(200)
            }
            AdbProcessClient.queryStatus() == null && !IrisServer.isStarted
        }

    /**
     * 서비스 정지 — 모드와 무관하게 NLS/서버/데몬을 모두 내린다.
     * (모드 전환 시 정지는 '새 모드' 기준으로 호출되므로 모드별 분기가 안전하지 않다.)
     * @return true: 백엔드가 내려간 상태.
     */
    private suspend fun stopLogic(reportUser: Boolean): Boolean =
        logicMutex.withLock { stopLogicLocked(reportUser) }

    private suspend fun stopLogicLocked(reportUser: Boolean): Boolean {
        cleanup()
        AppConfig.isServiceEnabled = false
        AppState.running = false

        var stopped = true
        try {
            stopService(Intent(this, IrisNotificationService::class.java))
            RuntimeLog.info(TAG, "NLS service stopped")
        } catch (e: Exception) {
            RuntimeLog.warn(TAG, "NLS 정지 실패: ${e.message}")
        }

        // 데몬은 서비스 바깥의 별개 프로세스이므로 모드를 바꿔도 항상 정리한다.
        // (정지를 await 하지 않으면 이전 모드 포트가 점유된 채로 새 모드가.BindException 된다.)
        val daemonDown = withContext(Dispatchers.IO) {
            if (AdbProcessClient.queryStatus() != null) {
                val commanded = DaemonLauncher.stopDaemon(applicationContext)
                if (commanded) RuntimeLog.info(TAG, "daemon stop 명령 전송")
                else RuntimeLog.error(TAG, "daemon stop failed — 포트 점유 중일 수 있음")
                // 정지 응답은 프로세스 종료 전에 오므로 포트 반납까지 확인해야
                // 직후 같은 포트로 기동해도 BindException 되지 않는다.
                val down = DaemonLauncher.waitForDaemonDown()
                if (down) RuntimeLog.info(TAG, "daemon down (포트 반납 확인)")
                else RuntimeLog.error(TAG, "daemon 종료 확인 실패")
                down
            } else true
        }
        if (!daemonDown) stopped = false
        if (IrisServer.isStarted) stopped = false

        if (reportUser) {
            if (stopped) toast("서비스를 정지했습니다")
            else toast("정지 명령을 보냈으나 백엔드 일부가 남아 있습니다")
        }
        updateForegroundNotification()
        return stopped
    }

    /**
     * 시작/정지 결과 표시 — Toast 는 쓰지 않는다. Android 13 은 앱 UID 가 background
     * 로 밀린 상태에서 띄운 Toast 를 "by user request" 로 조용히 폐기하므로(기기 logcat
     * 확인됨) 토글 결과로 화면과 백그라운드에서 모두 확실히 보이려면 스낵바가 유일한
     * 수단이다. 또한 스낵바로 통일하면 Foreground 알림의 상태 라벨과 이중으로 뜨지 않는다.
     * 결과는 AppState.lastFeedback 를 갱신해 UI(MainScreen) 스낵바로 확정 표시되고,
     * 백그라운드 확인이 필요하면 RuntimeLog 에 남는다.
     */
    private fun toast(message: String, long: Boolean = false) {
        RuntimeLog.info(TAG, message)
        AppState.postFeedback(message, isError = long)
    }

    /**
     * 상주(sticky) 알림 갱신 — startLogic/stopLogic 의 결과가 확정된 뒤 호출된다.
     * Foreground 서비스는 main thread 에서 갱신해야 하므로 mainLooper 로 넘긴다.
     * 기동 성공/정지 모두에서 호출하며, 서비스 라이프타임 내내 ongoing 알림을 유지한다.
     */
    private fun updateForegroundNotification() {
        val active = serviceScope.coroutineContext[kotlinx.coroutines.Job]?.isActive
        if (active != true) return
        mainLooperHandler.post {
            runCatching {
                val running = AppState.running
                val n = buildServiceNotification(running)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(
                        NOTIFICATION_ID,
                        n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                    )
                } else {
                    startForeground(NOTIFICATION_ID, n)
                }
            }
        }
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
        // proot 로 도는 python 스크립트와 code-server 는 서비스 종료와 별개로 살아
        // 남을 수 있으므로 IO 에서 먼저 전부 정지한다.
        serviceScope.launch(Dispatchers.IO) {
            runCatching {
                party.qwer.irisgui.scripting.LinuxScripts.stopAll(applicationContext)
            }
            runCatching { party.qwer.irisgui.scripting.CodeServer.stop() }
        }
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
