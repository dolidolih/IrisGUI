package party.qwer.irisgui.backend

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppState

class DBObserver(private val kakaoDb: KakaoDB, private val observerHelper: ObserverHelper) {
    private var scheduler: ScheduledExecutorService? = null
    private var scheduledFuture: ScheduledFuture<*>? = null
    @Volatile
    private var isObserving: Boolean = false

    fun startPolling() {
        if (scheduler == null || scheduler!!.isShutdown) {
            scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "DB-Polling-Thread")
            }
        }

        if (scheduledFuture == null || scheduledFuture!!.isCancelled || scheduledFuture!!.isDone) {
            scheduledFuture = scheduler?.scheduleWithFixedDelay({
                try {
                    observerHelper.checkChange(kakaoDb)
                } catch (e: Exception) {
                    System.err.println("Error during DB polling: $e")
                }
            }, 0, AppConfig.dbPollingRate.takeIf { it > 0 } ?: 1000L, TimeUnit.MILLISECONDS)
            isObserving = true
            AppState.isObserving = true
            println("DB Polling thread started.")
        } else {
            println("DB Polling thread is already running.")
        }
    }

    fun stopPolling() {
        if (scheduledFuture != null && !scheduledFuture!!.isCancelled && !scheduledFuture!!.isDone) {
            scheduledFuture?.cancel(true)
            scheduledFuture = null
            isObserving = false
            AppState.isObserving = false
            println("DB Polling thread stopped.")
        }
        if (scheduler != null && !scheduler!!.isShutdown) {
            scheduler?.shutdown()
            scheduler = null
        }
    }

    val isPollingThreadAlive: Boolean
        get() = scheduledFuture != null && !scheduledFuture!!.isCancelled && !scheduledFuture!!.isDone
}