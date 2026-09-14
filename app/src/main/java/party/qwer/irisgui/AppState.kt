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
}
