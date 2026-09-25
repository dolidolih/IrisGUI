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
    val port: Int? = null,
    val types: List<String>? = null,
    val enable: Boolean? = null
)

@Serializable
data class ConfigResponse(
    // ISSUE-13: 데몬 JSON 을 받아 쓰는 모델 — 모든 필드에 기본값을 둬서
    // 필드 누락/이름 변경/중간-시동 부분 응답에서도 디코딩이 실패하지 않게 한다.
    // (null 입력은 클라이언트 Json.coerceInputValues 로 기본값 치환된다)
    val bot_name: String = "",
    val bot_http_port: Int = 0,
    val web_server_endpoint: String = "",
    val db_polling_rate: Long = 0,
    val message_send_rate: Long = 0,
    val bot_id: Long = 0,
    val broadcast_types: List<String>? = null
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

// ── path 기반 read-only 조회 API (info provision) ────────
// payload 필드는 실제 JSON 객체(JsonObject)를 그대로 싣기 위해 JsonElement를 쓴다.
// (Map<String, Any?>는 kotlinx.serialization 불가)
@Serializable
data class JsonPayloadResponse(
    val payload: JsonElement,
    val error: String? = null
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
data class AotResponse(
    val success: Boolean,
    val aot: JsonElement
)

// ── P21: ADB 프로세스 상태/제어 API ──────────────────────────

@Serializable
data class AdbProcessStatusResponse(
    // ISSUE-13: 데몬 JSON 디코딩은 "살아있음"과 "정지"를 가르는 1심 판정근거다.
    // 필수 필드(기본값 없는 non-null)가 하나라도 없으면 전체 디코딩 실패 → null("Down")
    // 이 되어 한가하지만 살아있는 데몬을 두 번째 시동/.pk로 모는 오탐을 낳는다.
    // 따라서 상태 필드는 전부 nullable+기본값, 로그성 필드는 빈 리스트 기본으로 한다.
    val server_running: Boolean? = null,
    val port: Int? = null,
    val db_observing: Boolean? = null,
    val bot_id: Long? = null,
    val bot_name: String? = null,
    val bot_http_port: Int? = null,
    val web_server_endpoint: String? = null,
    val db_polling_rate: Long? = null,
    val message_send_rate: Long? = null,
    /** 데몬 stdout/stderr 로그(최신 선행) — 로그 탭의 "실행 로그"에 병합 표시. */
    val logs: List<RuntimeLog.Entry> = emptyList(),
    /**
     * 최근 DB 채팅 로그(raw 행, 최신 선행) — 로그 탭의 수신 메시지 목록.
     * 데몬(app_process) 프로세스의 상태이므로 UI는 반드시 HTTP 로 가져야 한다.
     */
    val last_logs: List<Map<String, String?>> = emptyList()
)

@Serializable
data class AdbProcessCommandRequest(
    val command: String  // "stop", "restart"
)

// ── crypto_database 全文 검색 API ────────────────────────

@Serializable
data class SearchRequest(
    val query: String,
    val limit: Int = 50,
    // 선택 필터 — 미지정 시 동작/응답은 `{"query","limit"}` 만 보낸 현행과 동일.
    val room: Long? = null,
    val user_id: Long? = null,
    val types: List<String>? = null,
    val from_created_at: Long? = null,
    val to_created_at: Long? = null
)

@Serializable
data class SearchHit(
    val id: Long,
    val preview: String,
    // 필터 적용 시에만 채워지는 enrichment 필드 (미지정 경로는 null 유지 → 기존 클라이언트 무영향).
    val chat_id: String? = null,
    val user_id: String? = null,
    val created_at: String? = null,
    val type_name: String? = null,
    val room_name: String? = null,
    val sender_name: String? = null
)

@Serializable
data class SearchResponse(
    val hits: List<SearchHit>,
    val error: String? = null
)
