package party.qwer.irisgui.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.models.ReplyAction
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/**
 * ReplyManager — 논루팅(알림) 모드 답장 큐.
 *
 * ISSUE-21: 재시작/모드전환 churn 중에 답장이 조용히 사라지던 자리를 고쳤다.
 *  - 채널과 consumer 는 한 start 사이클에서 **함께 만들어지고 두 번 재할당되지 않는다**
 *    (예전엔 `messageChannel` var 를 stopQueue 가 갈아끼워, 오래된 consumer 가 닫힌 채널에
 *     묶이거나 startQueue 이 새 채널의 두 번째 consumer 를 띄울 수 있었다).
 *  - enqueue 는 사이클 generation 에 묶여, stale 큐에는 절대 send 하지 않는다.
 *  - startQueue 는 이전 consumer 가 완전히(join) 사라진 뒤에만 새 consumer 를 만든다.
 *  - 큐가 서 not running 상태에서의 enqueue 은 실패를 lastError 에 남긴다 (호출자는 여전히
 *    "Enqueued" 라고 응답하므로 UI 는 그대로지만, 최소한 원인이 사라지지 않는다).
 */
object ReplyManager {
    val replyActions = ConcurrentHashMap<String, ReplyAction>()

    /** start 사이클 하나의 (channel, scope, consumer job) 묶음 — 재할당 없음. */
    private class Cycle(
        val channel: kotlinx.coroutines.channels.Channel<suspend () -> Unit>,
        val scope: CoroutineScope,
        val job: Job
    )

    private val lifecycleLock = ReentrantLock()

    @Volatile
    private var cycle: Cycle? = null

    @Volatile
    private var generation: Long = 0

    /**
     * 큐 정지/재시작/폐기로 인해 lost 된 항목 수와 마지막 오류 — churn 중 답장이
     * 빠졌는지 로그에서 구분하게 하기 위한 노출 (상태화면 연결은 UI 배선 필요, note 참조).
     */
    private val dropped = AtomicLong()

    @Volatile
    var lastError: String? = null
        private set

    private val submitted = AtomicLong()
    private val consumed = AtomicLong()

    /** 아직 consumer 를 통과하지 않은 답장 수 (submitted - consumed, 단조 증가 카운터 차액). */
    val pendingCount: Long get() = (submitted.get() - consumed.get()).coerceAtLeast(0)

    val isRunning: Boolean get() = cycle?.job?.isActive == true

    /** dropped counter reset (테스트/상태 화면 초기화용). */
    fun resetDroppedCount() {
        dropped.set(0)
    }

    val droppedCount: Long get() = dropped.get()

    /**
     * 답장 큐 시작 — 이미 살아있으면 아무것도 하지 않고, 이전 사이클이 남아 있으면
     * consumer 가 join 된 뒤에 새 consumer 를 만든다 (동일 채널에 두 consumer 금지).
     */
    fun startQueue() {
        lifecycleLock.lock()
        try {
            val current = cycle
            if (current != null && current.job.isActive) return
            if (current != null) quiesce(current)
            val newCycle = newCycle()
            cycle = newCycle
            generation++
        } finally {
            lifecycleLock.unlock()
        }
    }

    private fun newCycle(): Cycle {
        val channel = kotlinx.coroutines.channels.Channel<suspend () -> Unit>(
            kotlinx.coroutines.channels.Channel.UNLIMITED
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val job = scope.launch {
            for (action in channel) {
                consumed.incrementAndGet()
                //레이트 제한은 mutex 로 유지 — 다른 send 와 직렬화된 뒤 delay 한다.
                mutex.withLock {
                    try {
                        action.invoke()
                    } catch (e: Exception) {
                        lastError = "reply send failed: ${e.javaClass.simpleName}: ${e.message}"
                        e.printStackTrace()
                    }
                    delay(AppConfig.sendRate)
                }
            }
        }
        return Cycle(channel, scope, job)
    }

    private val mutex = Mutex()

    /** cancel + bounded join — consumer 가 완전히 내려간 뒤 호출측이 다음 사이클을 만든다. */
    private fun quiesce(target: Cycle) {
        target.channel.close()
        runBlocking {
            val joined = withTimeoutOrNull(JOIN_BUDGET_MS) {
                target.job.cancelAndJoin()
                true
            }
            if (joined != true) {
                lastError = "reply consumer did not join within ${JOIN_BUDGET_MS}ms"
                System.err.println("ReplyManager: $lastError")
            }
        }
        target.scope.cancel()
    }

    /**
     * 동기 enqueue — 호출자는 코루틴이 아니어도 된다. 사이클이 없으면 (정지된 서비스)
     * 항목을 버리고 lastError 에 남긴다: 이 경우 "Enqueued" 응답은 UI 를 속이지만, 원인을
     * 추적할 수 있게 하려고 dropped 도 올린다.
     */
    fun enqueue(action: suspend () -> Unit) {
        val target = cycle
        val gen = generation
        if (target == null || !target.job.isActive) {
            dropped.incrementAndGet()
            lastError = "reply queue is not running — item dropped"
            System.err.println("ReplyManager: $lastError")
            return
        }
        target.scope.launch {
            // start/stop 과 경주한 경우에만 stale 사이클이 된다 — 그 경우 send 하지 않고
            // 재시작된 큐에 다시 넣는다 (한 번만).
            if (generation == gen && cycle === target) {
                submitted.incrementAndGet()
                runCatching { target.channel.send(action) }
                    .onFailure {
                        dropped.incrementAndGet()
                        lastError = "enqueue into closed queue: ${it.message}"
                        System.err.println("ReplyManager: $lastError")
                    }
            } else {
                //큐가 갈아끼워졌다 — 새 큐에 재투입 (in-flight 회신 유실 방지).
                val latest = cycle
                if (latest != null && latest.job.isActive) {
                    submitted.incrementAndGet()
                    runCatching { latest.channel.send(action) }
                        .onFailure {
                            dropped.incrementAndGet()
                            lastError = "re-enqueue after churn failed: ${it.message}"
                        }
                } else {
                    dropped.incrementAndGet()
                    lastError = "no live queue after restart — item dropped"
                }
            }
        }
    }

    /** ReplyManager 정지 — 코루틴 및 채널 정리 (재시작 시 새 사이클이 만들어진다). */
    fun stopQueue() {
        lifecycleLock.lock()
        try {
            val current = cycle ?: return
            cycle = null
            generation++
            quiesce(current)
        } finally {
            lifecycleLock.unlock()
        }
    }

    private const val JOIN_BUDGET_MS = 1_500L
}
