package party.qwer.irisgui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import party.qwer.irisgui.backend.*

class Main {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            try {
                // 데몬 stdout/stderr 전체를 로그 버퍼에 담아 UI 로그 탭에 노출한다.
                RuntimeLog.captureStdout("데몬")

                // ADB 모드: SharedPreferences 없이 AdbConfig(JSON 파일) 사용
                val wsEventFlow = MutableSharedFlow<String>(extraBufferCapacity = 64)

                // 0. AdbConfig 초기화 — JSON 파일에서 설정 로드
                AdbConfig.saveToPrefs()

                // 0.1. 실행 인자(args[0])로 서버 포트 지정.
                // Android 앱은 일반 uid라 app_process가 읽는 /data/local/tmp/IrisGUI.json에
                // 쓸 수 없다. 따라서 UI에서 설정한 포트는 가이드가 생성한 실행 명령의 인자로
                // 전달되며, 여기서 AdbConfig(루트 권한으로 JSON 저장)에 반영한다.
                args.firstOrNull()?.trim()?.toIntOrNull()?.let { p ->
                    if (p in 1..65535) {
                        AdbConfig.serverPort = p
                        println("IrisGUI: server port set from argument = $p")
                    } else {
                        System.err.println("IrisGUI: invalid port argument ignored: ${args.firstOrNull()}")
                    }
                }
                println("IrisGUI: AdbConfig loaded, mode=${AdbConfig.appMode}, port=${AdbConfig.serverPort}")

                // 0.5. botId 자동 감지 (DB에서 isMine:true 사용자 ID 추출)
                AdbConfig.detectBotId()

                // 1. Replier 초기화 (메시지 전송)
                Replier.startMessageSender()
                println("IrisGUI: Message sender thread started")

                // 2. KakaoDB 초기화 (SQLite ATTACH)
                val kakaoDb = KakaoDB()
                val observerHelper = ObserverHelper(kakaoDb, wsEventFlow)

                // 3. DBObserver 시작 (DB 폴링)
                val dbPollingRate = AdbConfig.dbPollingRate
                val dbObserver = DBObserver(kakaoDb, observerHelper)
                dbObserver.startPolling()
                println("IrisGUI: DBObserver started (polling interval: ${dbPollingRate}ms)")

                // 4. 이미지 정리 시작
                val imageDeleter = ImageDeleter(
                    IMAGE_DIR_PATH,
                    java.util.concurrent.TimeUnit.HOURS.toMillis(1)
                )
                imageDeleter.startDeletion()
                println("IrisGUI: ImageDeleter started (1h interval)")

                // 5. ADB 전용 HTTP 서버 시작
                // ISSUE-05: /process-command stop 은 데몬 워스(DB poller, 이미지 정리)까지
                // 내려야 truly stop — UI 가 "정지"인데 백그라운드에서 관찰/답장이 계속되는
                // 반-정지 상태를 막기 위해 stop 훅을 등록한다.
                val workerStop = Runnable {
                    runCatching { dbObserver.stopPolling() }
                    runCatching { imageDeleter.stopDeletion() }
                }
                AdbServer.workerStopHook = workerStop
                AdbServer.startServer()
                println("IrisGUI: AdbServer started on port ${AdbConfig.serverPort}")

                // 6. 상태 업데이트 (UI 브로드캐스트)
                AppState.isObserving = true

                // 8. WebSocket 브로드캐스트 — DBObserver에서 보낸 메시지를 클라이언트에 전달
                CoroutineScope(Dispatchers.IO).launch {
                    wsEventFlow.collect { msg ->
                        AdbServer.broadcastToClients(msg)
                    }
                }

                println("IrisGUI: All services started. Waiting for requests...")

                // 9. 정리 — 프로세스 종료 전 리소스 해제 (shutdown hook)
                val imageDeleterRef = imageDeleter
                val dbObserverRef = dbObserver
                Runtime.getRuntime().addShutdownHook(object : Thread("IrisGUI-Shutdown") {
                    override fun run() {
                        println("IrisGUI: Shutting down...")
                        imageDeleterRef.stopDeletion()
                        dbObserverRef.stopPolling()
                        AdbServer.stopServer()
                        println("IrisGUI: Shutdown complete.")
                    }
                })

                // 메인 스레드에서 블록 — app_process 프로세스가 종료되지 않도록
                Thread.sleep(java.lang.Long.MAX_VALUE)

            } catch (e: Exception) {
                System.err.println("IrisGUI Error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
