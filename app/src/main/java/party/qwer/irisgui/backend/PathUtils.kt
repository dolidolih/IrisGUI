package party.qwer.irisgui.backend

import java.io.File

object PathUtils {
    /** KakaoTalk 설치 유무를 판단. 없으면 null 환인. */
    fun getAppPathOrNull(): String? {
        val androidUid = System.getenv("KAKAOTALK_APP_UID") ?: "0"
        val mirrorPath = "/data_mirror/data_ce/null/$androidUid/com.kakao.talk/"
        val defaultPath = "/data/data/com.kakao.talk/"

        return when {
            File(mirrorPath).exists() -> mirrorPath.also { println("Returning mirrorPath: $it") }
            File(defaultPath).exists() -> defaultPath.also { println("Returning defaultPath: $it") }
            else -> null
        }
    }

    fun getAppPath(): String =
        getAppPathOrNull() ?: throw RuntimeException("KakaoTalk app path not found")
}
