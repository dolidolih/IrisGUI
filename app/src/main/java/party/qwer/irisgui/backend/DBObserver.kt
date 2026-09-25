package party.qwer.irisgui.backend

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppState

/**
 * DBObserver — 카톡 DB 폴링 스케줄러.
 *
 * ISSUE-20: 폴링 태스크 안의 Throwable/Error 하나가 scheduleWithFixedDelay 의
 * future 를 조용히 취소해, UI/상태는 "관찰 중"인데 이벤트는 영원히 안 나오는
 * 정지-while-주행 상태가 만든다. 여기서부터는
 *   - 태스크 본문을 Throwable 까지 catch — 스케줄 자체가 사망하지 않고 다음 틱에 재시도,
 *   - 실패 시 isObserving/AppState.isObserving=false 로 정직하게 표시, 성공 틱에서 복구,
 *   - start/stop 은 synchronized — check-then-act 경쟁 제거,
 *   - stopPolling 은 어떤 상태 분기에서도 반드시 플래그를 정리.
 */
class DBObserver(private val kakaoDB: KakaoDB, private val observerHelper: ObserverHelper) {
    private var scheduler: ScheduledExecutorService? = null
    private var scheduledFuture: ScheduledFuture<*>? = null

    @Volatile
    private var isObserving: Boolean = false

    fun startPolling() = synchronized(this) {
        if (scheduler == null || scheduler!!.isShutdown) {
            scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "DB-Polling-Thread")
            }
        }

        if (scheduledFuture == null || scheduledFuture!!.isCancelled || scheduledFuture!!.isDone) {
            scheduledFuture = scheduler?.scheduleWithFixedDelay({
                try {
                    observerHelper.checkChange(kakaoDB)
                    // 성공 틱 — 에러로 떨어뜨린 관찰 상태를 되올린다.
                    if (!isObserving || !AppState.isObserving) {
                        isObserving = true
                        AppState.isObserving = true
                        println("DB Polling recovered — observing resumed.")
                    }
                } catch (t: Throwable) {
                    // ISSUE-20: unbound rethrow 는 future 을 취소시켜 폴링이 조용히 죽는다.
                    // catch 후 반환하면 스케줄은 유지되고, 상태만 오류로 표시한다.
                    isObserving = false
                    AppState.isObserving = false
                    System.err.println("DB polling task failed (schedule kept, observing=false): $t")
                    t.printStackTrace()
                }
            }, 0, AppConfig.dbPollingRate.takeIf { it > 0 } ?: 1000L, TimeUnit.MILLISECONDS)
            isObserving = true
            AppState.isObserving = true
            println("DB Polling thread started.")
        } else {
            println("DB Polling thread is already running.")
        }
    }

    fun stopPolling() = synchronized(this) {
        // ISSUE-20: 이전 구현은 future 이 살아있는 한 분기에서만 플래그를 정리했다 —
        // 그 외 경로(예: future 가 이미 취소된 뒤)에서는 AppState 가 계속 "observing" 으로
        // 남는다. 상태 정리와 무관하게 여기서는 무조건 내린다.
        val future = scheduledFuture
        if (future != null) {
            runCatching { future.cancel(true) }
            scheduledFuture = null
        }
        val sch = scheduler
        if (sch != null && !sch.isShutdown) {
            runCatching { sch.shutdown() }
            scheduler = null
        }
        isObserving = false
        AppState.isObserving = false
        println("DB Polling thread stopped.")
    }

    val isPollingThreadAlive: Boolean
        get() = scheduledFuture?.let { !it.isCancelled && !it.isDone } == true
}
