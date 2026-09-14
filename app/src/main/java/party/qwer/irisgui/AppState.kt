package party.qwer.irisgui

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import party.qwer.irisgui.models.NotificationEvent
import party.qwer.irisgui.models.StoredRoom

/**
 * AppState — 앱 전역 상태 관리
 *
 * IrisLite의 AppState에 Iris의 ObserverHelper.lastChatLogs, isObserving 등을 통합.
 */
object AppState {
    // 알림 히스토리 (논루팅 모드 NLS에서 감지한 알림)
    val notificationHistory = mutableStateListOf<NotificationEvent>()

    // 답장 가능한 방 목록
    val storedRooms = mutableStateListOf<StoredRoom>()

    // DB 관찰 상태 (루팅 모드)
    var isObserving = false

    /**
     * 서비스 동작 여부 — 백엔드(데몬 / 인프로세스 서버)의 실제 상태를 UI가 폴링해 반영한다.
     * 모드(ROOT_ADB/NON_ROOT)와 무관한 단일 표현. StatusScreen이 갱신한다.
     */
    var running: Boolean by mutableStateOf(false)

    /**
     * 직전 서비스 시작/정지 결과 메시지. 백그라운드에서 띄우는 Toast 는 Android 13
     * 이후 조용히 폐기된다(UID 가 background 로 밀리기 때문)므로, 화면에 확실히
     * 보이도록 스낵바로 함께 노출한다. id 로 신규 여부만 판별한다.
     */
    data class Feedback(val id: Long, val message: String, val isError: Boolean)
    var lastFeedback: Feedback? by mutableStateOf(null)
        private set

    private val feedbackCounter = java.util.concurrent.atomic.AtomicLong(0)

    /** 서비스 시작/정지 결과를 UI(스낵바)에 1회 노출되도록 남긴다. */
    fun postFeedback(message: String, isError: Boolean) {
        lastFeedback = Feedback(feedbackCounter.incrementAndGet(), message, isError)
    }

    // 마지막 DB 로그 (루팅 모드 DBObserver에서 업데이트)
    val lastChatLogs = mutableStateListOf<Map<String, String?>>()

    /**
     * 수신 메시지 단일 표현 — 모드(ROOT_ADB / NON_ROOT)와 무관한 공통 DTO.
     * 로그 탭 화면은 모드별로 서로 다른 소스(DB 로그 / 알림)를 이 형태로 정규화해 사용한다.
     */
    data class AppMessage(
        val id: String,
        val roomName: String,
        val senderName: String,
        val text: String,
        val timeMs: Long,
        val isGroup: Boolean
    )
}
