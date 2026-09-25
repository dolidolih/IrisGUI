package party.qwer.irisgui.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * StartedPollLoop — ISSUE-28: 모든 탭 폴링의 공용 뼈대.
 *
 *   · STARTED 이상일 때만 돈다 (split-screen/정지 화면에서 계속 돌지 않음).
 *   · tick 이 false(조회 실패)를 내리면 기하급수 백오프: base → 2base → … maxMs.
 *     성공하면 즉시 base 로 복귀 — 백엔드가 죽은 채로 몇 분씩 5초 socket churn 을
 *     내는 배터리 낭비를 막는다.
 *   · isBusy(시작/정지 대기 등) 이 true 면 실패 카운트와 무관하게 base cadence —
 *     전이 상태 감지 지연은 올리지 않는다.
 *
 * 화면에서 공통적으로 반복 구현되던 것이고, 화면 쪽 상태 기계(starting/pending
 * 대기)와 분리해 재사용하기 위해 여기 묶는다.
 */
@Composable
internal fun StartedPollLoop(
    vararg keys: Any?,
    baseMs: Long,
    maxMs: Long = 30_000L,
    isBusy: () -> Boolean = { false },
    tick: suspend () -> Boolean,
) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val curTick = rememberUpdatedState(tick)
    val curBusy = rememberUpdatedState(isBusy)
    androidx.compose.runtime.LaunchedEffect(keys) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var fails = 0
            while (currentCoroutineContext().isActive) {
                val ok = runCatching { curTick.value() }.getOrElse { false }
                fails = if (ok) 0 else fails + 1
                val d =
                    if (ok || curBusy.value()) baseMs else pollBackoffDelay(fails, baseMs, maxMs)
                delay(d)
            }
        }
    }
}

/** 연속 실패 횟수 기반 백오프 — 실패 0 은 base. */
internal fun pollBackoffDelay(fails: Int, baseMs: Long, maxMs: Long): Long {
    if (fails <= 1) return baseMs
    var d = baseMs
    var i = 1
    while (i < fails && d < maxMs) {
        d = d * 2
        i++
    }
    return minOf(d, maxMs)
}
