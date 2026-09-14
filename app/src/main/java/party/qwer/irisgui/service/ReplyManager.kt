package party.qwer.irisgui.service

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.models.ReplyAction
import java.util.concurrent.ConcurrentHashMap

object ReplyManager {
    val replyActions = ConcurrentHashMap<String, ReplyAction>()

    private var messageChannel = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val mutex = Mutex()
    private var messageSenderJob: Job? = null

    fun startQueue() {
        if (messageSenderJob?.isActive == true) return
        messageSenderJob = CoroutineScope(Dispatchers.IO).launch {
            for (action in messageChannel) {
                mutex.withLock {
                    try {
                        action.invoke()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    delay(AppConfig.sendRate)
                }
            }
        }
    }

    fun enqueue(action: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { messageChannel.send(action) }
    }

    /** ReplyManager 정지 — 코루틴 및 채널 정리 */
    fun stopQueue() {
        messageSenderJob?.cancel()
        messageSenderJob = null
        messageChannel.close()
        // 다음 startQueue()에서 재사용할 수 있도록 새 채널을 준비한다.
        // (닫힌 채널을 그대로 두면 재시작 시 큐가 즉시 종료되고 enqueue()가
        //  ClosedSendChannelException을 던진다.)
        messageChannel = Channel(Channel.UNLIMITED)
    }
}
