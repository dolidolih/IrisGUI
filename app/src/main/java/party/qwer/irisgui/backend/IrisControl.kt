package party.qwer.irisgui.backend

import android.content.Context

/**
 * IrisControl — 앱에서 내보낼 iris_control 스크립트 텍스트 제공.
 *
 * 스크립트 본문은 assets/iris_control.sh, assets/iris_control.ps1 에 plain text로 보관하며
 * (저장소 루트의 동명 스크립트와 동일 동작), `__PORT__` 자리표시자를 사용자가 [설정]에서
 * 정한 포트로 치환해 반환한다. 포트는 스크립트 인자로도 바꿀 수 있다.
 */
object IrisControl {
    fun bash(context: Context, port: Int): String = read(context, "iris_control.sh", port)
    fun powershell(context: Context, port: Int): String = read(context, "iris_control.ps1", port)

    private fun read(context: Context, name: String, port: Int): String =
        context.assets.open(name).bufferedReader().use { it.readText() }
            .replace("__PORT__", port.toString())
}
