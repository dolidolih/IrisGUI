@file:Suppress("PropertyName")

package party.qwer.irisgui.models

import android.app.PendingIntent
import android.app.RemoteInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.SerialName
import party.qwer.irisgui.RuntimeLog

// ── 공통 모델 ──────────────────────────────────────────

@Serializable
data class ApiResponse(
    val success: Boolean,
    val message: String
)

@Serializable
data class CommonErrorResponse(
    val message: String
)

// ── 답장 요청/응답 ─────────────────────────────────────

@Serializable
data class ReplyRequest(
    val room: String,
    val type: String,
    val data: JsonElement,
    val threadId: String? = null
)

enum class ReplyType { TEXT, IMAGE, IMAGE_MULTIPLE }

// ── 답장 액션 (NLS에서 추출) ──────────────────────────

data class ReplyAction(
    val pendingIntent: PendingIntent,
    val remoteInput: RemoteInput
)

// ── 알림 관련 ──────────────────────────────────────────

@Serializable
data class NotificationPayload(
    val msg: String,
    val room: String,
    val sender: String,
    @SerialName("is_lite")
    val isLite: Boolean = false,
    val is_group_chat: Boolean,
    val profile_image: String?,
    val json: IrisJsonData
)

@Serializable
data class IrisJsonData(
    val _id: String? = null,
    val id: String?,
    val type: String? = null,
    val chat_id: String?,
    val scope: String? = null,
    val user_id: String?,
    val message: String,
    val attachment: String? = null,
    val created_at: String? = null,
    val deleted_at: String? = null,
    val client_message_id: String? = null,
    val prev_id: String? = null,
    val referer: String? = null,
    val supplement: String? = null,
    val v: String? = null
)

data class NotificationEvent(
    val timestamp: Long,
    val room: String,
    val roomId: String,
    val senderName: String,
    val senderId: String,
    val isGroupChat: Boolean,
    val text: String,
    val rawDump: String
)

data class StoredRoom(val name: String, val id: String)

// ── 설정 API ───────────────────────────────────────────

@Serializable
data class ConfigRequest(
    val endpoint: String? = null,
    val botname: String? = null,
    val rate: Long? = null,
    val port: Int? = null
)

@Serializable
data class ConfigResponse(
    val bot_name: String,
    val bot_http_port: Int,
    val web_server_endpoint: String,
    val db_polling_rate: Long,
    val message_send_rate: Long,
    val bot_id: Long
)

@Serializable
data class ConfigValues(
    var botName: String = "Iris",
    var botHttpPort: Int = 3000,
    var webServerEndpoint: String = "",
    var dbPollingRate: Long = 100,
    var messageSendRate: Long = 50,
    var botId: Long = 0L
)

// ── 쿼리/복호화 API ───────────────────────────────────

@Serializable
data class QueryRequest(
    val query: String? = null,
    // 원본 Iris 규격과 동일하게 JsonPrimitive 리스트로 받는다.
    // irispy-client는 bind에 따옴표 없는 숫자(방 id, 사용자 id 등)를 보내므로
    // List<String>으로 받으면 역직렬화에 실패해 쿼리가 빈 결과를 반환한다.
    val bind: List<JsonPrimitive>? = null,
    val queries: List<QueryRequest>? = null  // bulk query
)

@Serializable
data class QueryResponse(
    val data: List<Map<String, String?>>,
    val error: String? = null
)

@Serializable
data class DecryptRequest(
    val enc: Int,
    val b64_ciphertext: String,
    val user_id: Long? = null
)

@Serializable
data class DecryptResponse(
    val plain_text: String
)

// ── 대시보드/상태 API ─────────────────────────────────

// ── 방 목록 (ADB 모드 전용) ────────────────────────────

@Serializable
data class RoomInfo(
    val id: String,
    val name: String? = null,
    val updated_at: String? = null
)

@Serializable
data class RoomListResponse(
    val rooms: List<RoomInfo> = emptyList()
)

// 최신 카톡의 방 이름은 알림 extras에 없고 대화별 동적 쇼트컷 레이블에 있다.
// conversationId(=sbn.tag) → 방 이름 매핑 (데몬 루트가 시스템 쇼트컷 스토어에서 제공).
@Serializable
data class ShortcutRoomResponse(
    val success: Boolean = true,
    val rooms: Map<String, String> = emptyMap()
)

@Serializable
data class DashboardStatusResponse(
    // 주의: 필드명은 원본 Iris의 API 규격(camelCase)과 반드시 일치해야 한다.
    // irispy-client 및 번들 dashboard.html이 이 키 이름으로 역직렬화한다.
    val isObserving: Boolean,
    val statusMessage: String,
    val lastLogs: List<Map<String, String?>> = emptyList()
)

@Serializable
data class AotResponse(
    val success: Boolean,
    val aot: JsonElement
)

// ── P21: ADB 프로세스 상태/제어 API ──────────────────────────

@Serializable
data class AdbProcessStatusResponse(
    val server_running: Boolean,
    val port: Int,
    val db_observing: Boolean,
    val bot_id: Long,
    val bot_name: String,
    val bot_http_port: Int,
    val web_server_endpoint: String,
    val db_polling_rate: Long,
    val message_send_rate: Long,
    /** 데몬 stdout/stderr 로그(최신 선행) — 로그 탭의 "실행 로그"에 병합 표시. */
    val logs: List<RuntimeLog.Entry> = emptyList()
)

@Serializable
data class AdbProcessCommandRequest(
    val command: String  // "stop", "restart"
)

// ── crypto_database 全文 검색 API ────────────────────────

@Serializable
data class SearchRequest(
    val query: String,
    val limit: Int = 50
)

@Serializable
data class SearchHit(
    val id: Long,
    val preview: String
)

@Serializable
data class SearchResponse(
    val hits: List<SearchHit>,
    val error: String? = null
)
