package party.qwer.irisgui.scripting

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import party.qwer.irisgui.RuntimeLog

/**
 * 사용자 환경(userland) 설치의 전역 진행 상태.
 *
 * 화면과 무관한 프로세스 수명 코루틴에서 돌린다 — ScriptScreen 의 remember/
 * scope 는 탭 이동 시 소멸하므로, 그곳에서 provision 을 걸면 화면을 빠져나간
 * 순간 설치 coroutine 이 함께 취소돼 "tab 을 건너가면 설치가 처음부터" 한다.
 * 진행도는 @Composable mutableStateOf 로 노출해 탭 어디에서 복귀해도 그대로
 * 보이고, 로그(`설치:` 프리픽스)는 로그 탭에서 추적 가능하다.
 */
object UserlandInstall {
    val running = mutableStateOf(false)
    val stage = mutableStateOf("")
    val current = mutableStateOf("")
    val done = mutableStateOf(0)
    val total = mutableStateOf(0)
    val result = mutableStateOf<String?>(null)

    /** 백엔드(a11y 로그 라벨용): stage 와 병기된다. */
    @Volatile var backendLabel: String = ""

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun begin(stageText: String) {
        stage.value = stageText
        RuntimeLog.info("UserlandInstall", "설치 단계 — $stageText")
    }

    /** installSet 루프 같은 곳에서 호출 — done/total 은 전체 설치 프로그레션. */
    fun step(currentName: String, doneNow: Int, totalNow: Int) {
        current.value = currentName
        done.value = doneNow
        total.value = totalNow
    }

    /** 멱등 provision + 기본 프로젝트 생성. 이미 실행 중이면 그냥 통과. */
    fun start(context: Context) {
        if (running.value) return
        running.value = true
        result.value = null
        done.value = 0
        total.value = 0
        current.value = ""
        scope.launch {
            val started = System.currentTimeMillis()
            val target = runCatching { UserlandRuntime.backend(context).name }
                .getOrDefault("?")
            backendLabel = target
            RuntimeLog.info("UserlandInstall", "설치 시작 — 백엔드 $target (탭 이동 무시, 계속 실행)")
            val r = try {
                UserlandRuntime.provision(context)
            } catch (t: Throwable) {
                UserlandRuntime.Result(false, t.message ?: "예외")
            }
            if (r.ok) runCatching { LinuxScripts.bootstrapDefault(context) }
            val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
            result.value = if (r.ok) "설치 완료 (${secs}s) — ${r.message}"
            else "설치 실패 (${secs}s) — ${r.message}"
            RuntimeLog.info("UserlandInstall", result.value!!)
            stage.value = ""
            current.value = ""
            running.value = false
        }
    }

    /** 프로그레션 비율 null(불가정) 이 아닌 값. provision 단계를 stage 로 노출. */
    fun progress(): Float? = when {
        total.value > 0 -> done.value.toFloat() / total.value
        running.value -> null
        else -> null
    }
}
