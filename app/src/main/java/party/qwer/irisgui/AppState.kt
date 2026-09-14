package party.qwer.irisgui

import androidx.compose.runtime.mutableStateListOf
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
