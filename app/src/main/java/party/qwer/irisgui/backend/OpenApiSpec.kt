package party.qwer.irisgui.backend

/**
 * HTTP API 의 OpenAPI 3.1 명세를 생성한다.
 *
 * 데몬(app_process)은 Android Context/리소스 로더 없이 동작하므로, 명세는 이 코드에서
 * 직접 만들어 `/openapi.json` 으로 제공한다 (폐지된 `/dashboard` 가 `PageRenderer` 로 HTML 을
 * 코드에서 생성해 respondText 했던 것과 동일한 패턴).
 *
 * `GET /swagger` 는 Swagger UI 셸을 제공한다. 셸만 HTML 로 반환하고 Swagger UI 번들은
 * 브라우저가 CDN 에서 읽으므로 APK 에 정적 에셋을 넣지 않는다 (에셋 로딩 불필요).
 */
object OpenApiSpec {

    fun render(port: Int, host: String? = null): String {
        val base = baseUrls(port, host).first().removeSuffix("/")
        return JSON
            .replace("__SERVER_URL__", base)
            .replace("@@REF@@", "\$ref")
    }

    /**
     * 서버 주소는 요청 Host(Host 헤더)에서 만든다. 명세에 127.0.0.1 를 박으면 Swagger UI 의
     * "Try it out" 이 반드시 접속자 본인만 바라보게 되어 기기 IP 로 접속한 테스트가 전부 실패한다.
     *
     * 포트는 Host 가 지정한 값만 쓴다 — 붙여넣거나 만들지 않는다. Host 에 포트가 없는 것은
     * 클라이언트가 기본 포트(80/443, 프록시 경유 등)로 도달했다는 뜻이므로, 여기에 바인드
     * 포트를 덧붙이면 사용자가 쓰지 않는 포트를 명세가 bogus 하게 된다. `port` 는 Host 를
     * 전혀 받지 못한 경우(예: Host 없이 접근)의 폴백에만 쓰인다.
     * Host 는 JSON 에 그대로 들어가는 값이므로 허용 문자만 남긴다.
     */
    fun baseUrls(port: Int, host: String?): List<String> {
        val authority = host?.replace(Regex("[^A-Za-z0-9.:\\-\\[\\]]"), "")
        if (authority.isNullOrEmpty()) return listOf("http://127.0.0.1:$port")
        return listOf("http://$authority")
    }

    /** Swagger UI 셸. CDN 로드 실패 시에도 `/openapi.json` 링크는 보이도록 폴백 포함. */
    fun html(): String = HTML
}

private const val HTML = """
<!doctype html>
<html lang="ko">
<head>
  <meta charset="utf-8">
  <title>IrisGUI API</title>
  <link rel="stylesheet" href="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui.css">
  <style>body{margin:0}.offline{font:14px system-ui;padding:16px}a{color:#06c}</style>
</head>
<body>
<div id="swagger-ui"></div>
<script src="https://cdn.jsdelivr.net/npm/swagger-ui-dist@5/swagger-ui-bundle.js"></script>
<script>
window.addEventListener('DOMContentLoaded', function () {
  if (typeof SwaggerUIBundle === 'undefined') {
    document.getElementById('swagger-ui').innerHTML =
      '<div class="offline">Swagger UI 번들을 불러오지 못했습니다 (오프라인 환경). ' +
      '<a href="openapi.json">openapi.json</a> 을 직접 조회하거나, 아래 명세를 사용하세요.</div>';
    return;
  }
  SwaggerUIBundle({ url: 'openapi.json', dom_id: '#swagger-ui', deepLinking: true });
});
</script>
</body>
</html>
"""

private const val JSON = """
{
  "openapi": "3.1.0",
  "info": {
    "title": "IrisGUI HTTP API",
    "version": "1.0.0",
    "summary": "ROOT_ADB 데몬(Ktor/Netty) API",
    "description": "KakaoTalk bot 데몬(HTTP) + 이벤트 스트리밍(`/ws`) 명세. 데몬(`app_process`)은 루팅 모드에서만 구동하므로 표시 없는 항목은 논루팅(NON_ROOT, 앱 내장) 모드에서 제공되지 않습니다. irispy-client 호환 계약은 `json` 키 필드명/경로 변경 금지가 최상위 규격입니다.\n\n`/ws` 는 OpenAPI 로 설명 불가하므로 `x-websocket` 으로 나타냅니다.",
    "contact": { "name": "IrisGUI" }
  },
  "servers": [ { "url": "__SERVER_URL__", "description": "요청 Host 기준 (기기 IP · forward 포트 포함)" } ],
  "tags": [
    { "name": "events", "description": "이벤트 스트림 · 답장 발송" },
    { "name": "config", "description": "설정 조회/변경" },
    { "name": "lookup", "description": "사용자/방/리액션 path 조회 (read-only)" },
    { "name": "chat", "description": "메시지 로그 조회 · 삭제 회수 · 오프셋 · 정리" },
    { "name": "data", "description": "SQL 쿼리 · 검색 · 복호화" },
    { "name": "runtime", "description": "토큰 · 데몬 상태/제어 · API 명세" }
  ],
  "paths": {
    "/reply": {
      "post": {
        "tags": ["events"], "summary": "답장 발송 (텍스트/이미지)",
        "description": "성공 시 메시지는 즉시 큐잉되고 `Enqueued` 를 반환합니다.",
        "operationId": "postReply",
        "requestBody": { "required": true,
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ReplyRequest" },
            "examples": {
              "text": { "value": { "room": "123", "type": "text", "data": "안녕" } },
              "image": { "value": { "room": "123", "type": "image", "data": "iVBOR…" } },
              "images": { "value": { "room": "123", "type": "image_multiple", "data": ["a.jpg", "b.jpg"] } }
            } } } },
        "responses": {
          "200": { "description": "처리", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ApiResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" }
        }
      }
    },
    "/config": {
      "get": {
        "tags": ["config"], "summary": "설정 조회", "operationId": "getConfig",
        "responses": { "200": { "description": "설정",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ConfigResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/config/{name}": {
      "post": {
        "tags": ["config"], "summary": "설정 항목 변경", "operationId": "postConfig",
        "description": "각 항목은 해당 `name` 에서만 인식하는 키를 받습니다. 잘못된 값은 500 으로 거부됩니다.",
        "parameters": [ { "name": "name", "in": "path", "required": true,
          "schema": { "type": "string", "enum": ["endpoint", "botname", "dbrate", "sendrate", "botport", "types", "extension"] } } ],
        "requestBody": { "required": true,
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ConfigRequest" } } } },
        "responses": { "200": { "description": "저장됨",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ApiResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/rooms": {
      "get": {
        "tags": ["lookup"], "summary": "최근 채팅방 목록 (최신 30건)", "operationId": "getRooms",
        "responses": { "200": { "description": "방 목록. DB 준비 실패 시 빈 배열.",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/RoomListResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/user/{id}": {
      "get": {
        "tags": ["lookup"], "summary": "사용자 프로필", "operationId": "getUser",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "프로필",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
            "example": { "payload": { "id": "3", "name": "홍길동", "profile_image_url": "https://…" } } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/user/name/{id}": {
      "get": {
        "tags": ["lookup"], "summary": "사용자 이름", "operationId": "getUserName",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "이름 (crypto_user_database 우선, open_chat_member 복호화 폴백)",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
            "example": { "payload": { "name": "홍길동" } } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/room/{id}": {
      "get": {
        "tags": ["lookup"], "summary": "방 메타", "operationId": "getRoom",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "방 메타",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
            "example": { "payload": { "id": "7", "name": "팀 대화", "active_member_ids": "[1,2,3]", "link_id": null, "type": 2, "last_updated_at": "2026-09-19 01:02:03" } } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/room/name/{id}": {
      "get": {
        "tags": ["lookup"], "summary": "방 이름", "operationId": "getRoomName",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "이름", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" }, "example": { "payload": { "name": "팀 대화" } } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/room/{id}/members": {
      "get": {
        "tags": ["lookup"], "summary": "방 멤버", "operationId": "getRoomMembers",
        "description": "OpenChat 은 `link_id` → `db2.open_chat_member`, otherwise `chat_rooms.active_member_ids` JSON 해석.",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "멤버", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
          "example": { "payload": { "members": [ { "user_id": "3", "nickname": "홍길동", "profile_image_url": "https://…" } ] } } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/room/{id}/links": {
      "get": {
        "tags": ["lookup"], "summary": "방 오픈링크 (`db2.open_link`)", "operationId": "getRoomLinks",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "link_id 가 없으면 payload 는 null",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/reactions/{id}": {
      "get": {
        "tags": ["lookup"], "summary": "로그의 리액션", "operationId": "getReactions",
        "description": "`db2.chat_log_meta.type=2` → `content.rx`. 조인 키는 `chat_log_meta.log_id == chat_logs.id`.",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "리액션", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
          "example": { "payload": { "reactions": [ { "id": 3, "emotion_id": "1200509_021", "count": 4, "label": "👍" } ] } } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/chat/{id}": {
      "get": {
        "tags": ["chat"], "summary": "로그 한 건 (`/ws` 프레임과 동일 구조)", "operationId": "getChat",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": { "200": { "description": "프레임", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
          "example": { "payload": { "msg": "text", "room": "방이름", "sender": "보낸이", "json": { "id": "1", "type": "1" }, "extension": { "type_code": 1, "type_base": 1, "is_openchat": false, "type_name": "text", "log_id": "1" } } } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/chat/{id}/deleted": {
      "get": {
        "tags": ["chat"], "summary": "삭제된 메시지 회수", "operationId": "getDeletedChat",
        "description": "`id` 에 삭제 마크 id(→원본) 또는 이미 삭제된 원본 id(→마크) 어느 쪽을 넣어도 동일 결과를 냅니다. 삭제 마크는 `deleted_at>0` & `origin` ∈ {SYNCDLMSG=작성자, SYNCMODMSG=방장} 인 별도 행이며, 원본 행은 삭제 후에도 DB 에 잔존합니다(OpenChat blind cover `SYNCREWR` 는 삭제가 아니라 `system` 으로 분류).",
        "parameters": [ { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } } ],
        "responses": {
          "200": { "description": "삭제 사실 + 원본 프레임(`original` 은 아직 DB 에 있을 때만)",
            "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
              "example": { "payload": { "deleted": true, "who": "writer", "log_id": "3932471491267289089", "marker_id": "3932471560916510721", "original_exists": true, "original": { "msg": "원본 평문", "room": "방이름", "sender": "보낸이", "json": {} } } } } } },
          "400": { "description": "log id 형식 오류 / DB 미준비 / 삭제 메시지가 아님",
            "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ApiResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" }
        }
      }
    },
    "/chat/{id}/{direction}/{n}": {
      "get": {
        "tags": ["chat"], "summary": "같은 방의 n번째 이전/다음 메시지", "operationId": "getChatRelative",
        "description": "`direction` 은 `prev`/`before`(생성순 앞), `next`/`after`(뒤). `before`/`after` 는 `prev`/`next` 의 시맨틱 별칭.",
        "parameters": [
          { "name": "id", "in": "path", "required": true, "schema": { "@@REF@@": "#/components/schemas/BigInt" } },
          { "name": "direction", "in": "path", "required": true, "schema": { "type": "string", "enum": ["prev", "before", "next", "after"] } },
          { "name": "n", "in": "path", "required": true, "schema": { "type": "integer", "minimum": 1, "default": 1 } }
        ],
        "responses": {
          "200": { "description": "프레임 배열", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
            "example": { "payload": [ { "msg": "…", "room": "…", "sender": "…", "json": {} } ] } } } },
          "400": { "description": "id/n 형식 오류", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ApiResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" }
        }
      }
    },
    "/chat/logs": {
      "delete": {
        "tags": ["chat"], "summary": "days일 이전 chat_logs 영구 삭제", "operationId": "deleteChatLogs",
        "description": "production 데이터를 삭제합니다. `created_at = 0` (미기록) 행은 보존되며, 경계(`== cutoff`)는 보존됩니다 (`<` 연산).",
        "parameters": [ { "name": "days", "in": "query", "required": true, "description": "0.5 == 12시간. 0 이하 불가.", "schema": { "type": "number", "exclusiveMinimum": 0 } } ],
        "responses": {
          "200": { "description": "삭제 결과", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/Payload" },
            "example": { "payload": { "deleted": 128, "cutoff_days": 30 } } } } },
          "400": { "description": "`days` 누락/음수", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ApiResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" }
        }
      }
    },
    "/query": {
      "post": {
        "tags": ["data"], "summary": "KakaoTalk DB SQL 조회", "operationId": "postQuery",
        "description": "ATTACH 토폴로지: `main`(:memory:) + db1(KakaoTalk.db, owns `chat_logs`/`chat_rooms`/`chat_threads`) + db2(KakaoTalk2.db) + db3(multi_profile_database.db). 조인 키 표기는 `db1.chat_logs` 처럼 스키마 접두를 권장.",
        "requestBody": { "required": true, "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/QueryRequest" } } } },
        "responses": { "200": { "description": "행 배열(복호화 적용). 실패 시 `error` 에 원인.",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/QueryResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/search": {
      "post": {
        "tags": ["data"], "summary": "암호 DB 전문(full-text) 검색", "operationId": "postSearch",
        "description": "`crypto_database.chat_log_search.searchable_text` 검색.",
        "requestBody": { "required": true, "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/SearchRequest" } } } },
        "responses": { "200": { "description": "검색 결과", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/SearchResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/decrypt": {
      "post": {
        "tags": ["data"], "summary": "메시지 페로드 복호화", "operationId": "postDecrypt",
        "requestBody": { "required": true, "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/DecryptRequest" } } } },
        "responses": { "200": { "description": "평문(오류 시 `Error: …` 문자열). HTTP 200 유지.",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/DecryptResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/aot": {
      "get": {
        "tags": ["runtime"], "summary": "AOT 토큰 조회", "operationId": "getAot",
        "responses": { "200": { "description": "토큰(획득 실패 시 success=false, aot=null)",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/AotResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/process-status": {
      "get": {
        "tags": ["runtime"], "summary": "데몬/관찰 상태", "operationId": "getProcessStatus",
        "responses": { "200": { "description": "전체 상태 + 최근 로그",
          "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ProcessStatus" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/process-command": {
      "post": {
        "tags": ["runtime"], "summary": "데몬 제어 (`stop` / `restart`)", "operationId": "postProcessCommand",
        "description": "루팅 모드 서비스 시작/정지 제어. `stop` 은 서버 엔진까지 종료하므로 재가동에는 별도 기동이 필요합니다.",
        "requestBody": { "required": true, "content": { "application/json": { "schema": { "type": "object", "required": ["command"],
          "properties": { "command": { "type": "string", "enum": ["stop", "restart"] } } } } } },
        "responses": { "200": { "description": "처리", "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ApiResponse" } } } },
          "default": { "@@REF@@": "#/components/responses/ApiError" } }
      }
    },
    "/openapi.json": {
      "get": {
        "tags": ["runtime"], "summary": "이 문서 자체", "operationId": "getOpenApiJson",
        "responses": { "200": { "description": "OpenAPI 3.1 문서", "content": { "application/json": { "schema": { "type": "object" } } } } }
      }
    },
    "/swagger": {
      "get": {
        "tags": ["runtime"], "summary": "Swagger UI", "operationId": "getSwagger",
        "description": "브라우저가 Swagger UI 번들을 CDN 에서 로드합니다. 오프라인이면 `openapi.json` 링크로 대체됩니다.",
        "responses": { "200": { "description": "HTML", "content": { "text/html": { "schema": { "type": "string" } } } } }
      }
    }
  },
  "components": {
    "responses": {
      "ApiError": {
        "description": "실패. StatusPages 는 예외를 HTTP 500 + ApiResponse 로 매핑하고, 일부 경로는 검증 실패를 HTTP 200 + success=false 로 반환합니다. 클라이언트는 `success`/`payload`/`error` 를 함께 판단해야 합니다.",
        "content": { "application/json": { "schema": { "@@REF@@": "#/components/schemas/ApiResponse" } } }
      }
    },
    "schemas": {
      "BigInt": {
        "type": "string", "pattern": "^[0-9]+$",
        "description": "64bit long. Kakao snowflake(`chat_logs.id`) 는 JS 정밀도를 넘으므로 문자열 표기를 권장합니다. path 에는 숫자 리터럴을 보내도 동작(`toLongOrNull` 방어).",
        "examples": ["3932471491267289089"]
      },
      "ApiResponse": {
        "type": "object", "required": ["success", "message"],
        "properties": { "success": { "type": "boolean" }, "message": { "type": ["string", "null"] } }
      },
      "Payload": {
        "type": "object",
        "description": "`JsonPayloadResponse{payload, error}` 래퍼. `error` 은 payload 가 null/실패일 때만.",
        "properties": {
          "payload": { "description": "엔드포인트별 결과. 실패 시 null." },
          "error": { "type": ["string", "null"] }
        }
      },
      "ChatLogRow": {
        "type": "object",
        "description": "`chat_logs` 행 필드 (일부만). `message`, `attachment`, `supplement` 는 쿼리/조회 시 복호화되어 평문으로 대체됩니다. 미지 필드 허용(`ignoreUnknownKeys`).",
        "properties": {
          "_id": { "type": "string" }, "id": { "@@REF@@": "#/components/schemas/BigInt" }, "type": { "type": "string" },
          "chat_id": { "@@REF@@": "#/components/schemas/BigInt" }, "user_id": { "@@REF@@": "#/components/schemas/BigInt" },
          "message": { "type": "string" }, "attachment": { "type": ["string", "null"] },
          "created_at": { "type": "string" }, "deleted_at": { "type": "string" },
          "client_message_id": { "type": ["string", "null"] }, "prev_id": { "type": ["string", "null"] },
          "referer": { "type": ["string", "null"] }, "supplement": { "type": ["string", "null"] },
          "v": { "type": ["string", "null"], "description": "JSON. `origin`(MSG/WRITE/SYNCMSG/MCHATLOGS/SYNCDLMSG/SYNCMODMSG/SYNCREWR/NEWMEM/DELMEM/FEED/…) + `enc` 암호화 플래그 + OpenChat 비트." }
        },
        "additionalProperties": true
      },
      "Extension": {
        "type": "object",
        "description": "`POST /config/extension {enable:true}` 일 때만 `msg/room/sender/json` 에 top-level 로 **추가**. 기본 off 라 프레이임 바이트는 현행과 동일. irispy-client 는 미지 키를 무시하므로 무중단.",
        "properties": {
          "type_code": { "type": "integer", "description": "base + OpenChat 비트(16384)." },
          "type_base": { "type": "integer", "description": "type_code & ~16384." },
          "is_openchat": { "type": "boolean" },
          "type_name": { "type": "string",
            "enum": ["text", "photo", "video", "audio", "file", "contact", "photo_animation", "gif", "emoticon", "list", "app", "app_feed", "feed", "feed_share", "talk_memo", "long_app", "current_user", "mchatlog", "syncmsg", "deleted", "system", "unknown"] },
          "log_id": { "@@REF@@": "#/components/schemas/BigInt" },
          "is_mine": { "type": "boolean", "description": "보낸이가 botId 일 때만 존재." },
          "reactions": { "type": "array", "items": {
            "type": "object", "properties": {
              "id": { "type": "integer" }, "emotion_id": { "type": "string" },
              "count": { "type": "integer" }, "label": { "type": "string" } } } },
          "is_deleted": { "type": "boolean", "description": "행이 삭제 마크일 때만 존재." },
          "deleted_by": { "type": "string", "enum": ["writer", "admin"], "description": "writer=SYNCDLMSG(feedType14), admin=SYNCMODMSG(feedType25)." }
        }
      },
      "ChatEvent": {
        "type": "object",
        "required": ["msg", "room", "sender", "json"],
        "properties": {
          "msg": { "type": "string" }, "room": { "type": "string", "description": "방 이름(1:1 은 상대방 이름)." },
          "sender": { "type": "string" }, "json": { "@@REF@@": "#/components/schemas/ChatLogRow" },
          "extension": { "@@REF@@": "#/components/schemas/Extension" }
        },
        "additionalProperties": true
      },
      "ReplyRequest": {
        "type": "object", "required": ["room", "type", "data"],
        "properties": {
          "room": { "type": "string", "description": "chat_id" },
          "type": { "type": "string", "enum": ["text", "image", "image_multiple"] },
          "data": { "description": "type=text: 문자열. image: b64/URL 문자열. image_multiple: 배열." },
          "threadId": { "type": ["string", "null"], "description": "스레드(원래 스노우플레이크) id" }
        }
      },
      "ConfigRequest": {
        "type": "object",
        "description": "항목별 키만 유효. 유휴 키는 무시.",
        "properties": {
          "endpoint": { "type": ["string", "null"], "description": "name=endpoint. 웹 서버 endpoint." },
          "botname": { "type": ["string", "null"], "description": "name=botname. 공백 불가." },
          "rate": { "type": ["integer", "null"], "description": "name=dbrate|sendrate. ms." },
          "port": { "type": ["integer", "null"], "description": "name=botport. 1–65535.", "minimum": 1, "maximum": 65535 },
          "types": { "type": ["array", "null"], "items": { "type": "string", "enum": ["text", "photo", "video", "audio", "file", "contact", "photo_animation", "gif", "emoticon", "list", "app", "app_feed", "feed", "feed_share", "talk_memo", "long_app", "current_user", "mchatlog", "syncmsg", "system", "unknown", "deleted"] }, "description": "name=types. 빈 배열 = 필터 해제(null, 현행 동작)." },
          "enable": { "type": ["boolean", "null"], "description": "name=extension. WS 에 extension 키 포함 여부." }
        }
      },
      "ConfigResponse": {
        "type": "object",
        "properties": {
          "bot_name": { "type": "string" }, "bot_http_port": { "type": "integer" },
          "web_server_endpoint": { "type": "string" }, "db_polling_rate": { "type": "integer", "format": "int64" },
          "message_send_rate": { "type": "integer", "format": "int64" }, "bot_id": { "type": "integer", "format": "int64", "description": "bot 사용자 id (JSON 숫자). Long 직렬화라 snowflake 도 숫자로 전송됩니다 — precision 은 클라이언트측 유의." },
          "broadcast_types": { "type": ["array", "null"], "items": { "type": "string" },
            "description": "필터 설정 시에만 키 존재. 미설정 시 키 생략." }
        }
      },
      "RoomListResponse": {
        "type": "object",
        "properties": {
          "rooms": {
            "type": "array",
            "items": {
              "type": "object",
              "properties": {
                "id": { "type": "string" },
                "name": { "type": ["string", "null"] },
                "updated_at": { "type": ["string", "null"] }
              }
            }
          }
        }
      },
      "QueryRequest": {
        "type": "object", "properties": {
          "query": { "type": ["string", "null"] },
          "bind": { "type": ["array", "null"], "description": "JsonPrimitive 배열. 따옴표 없는 숫자 허용(List<String> 아님)",
            "items": { "type": ["string", "number"] } },
          "queries": { "type": ["array", "null"], "items": { "@@REF@@": "#/components/schemas/QueryRequest" } }
        }
      },
      "QueryResponse": {
        "type": "object", "required": ["data"],
        "properties": {
          "data": { "type": "array", "items": { "type": "object", "additionalProperties": { "type": ["string", "null"] } },
            "description": "복호화 적용된 키-값 행." },
          "error": { "type": ["string", "null"] }
        }
      },
      "SearchRequest": { "type": "object", "required": ["query"], "properties": {
        "query": { "type": "string" }, "limit": { "type": "integer", "default": 50 } } },
      "SearchResponse": {
        "type": "object", "required": ["hits"],
        "properties": {
          "hits": { "type": "array", "items": { "type": "object", "required": ["id", "preview"],
            "properties": { "id": { "type": "integer", "format": "int64" }, "preview": { "type": "string" } } } },
          "error": { "type": ["string", "null"] } }
      },
      "DecryptRequest": {
        "type": "object", "required": ["enc", "b64_ciphertext"],
        "properties": {
          "enc": { "type": "integer", "description": "암호화 알고리즘 플래그 (0=평문, 31=암호)." },
          "b64_ciphertext": { "type": "string" },
          "user_id": { "type": ["integer", "null"], "format": "int64", "description": "생략 시 botId." } }
      },
      "DecryptResponse": { "type": "object", "required": ["plain_text"], "properties": {
        "plain_text": { "type": "string", "description": "오류 시 `Error: <msg>`." } } },
      "AotResponse": { "type": "object", "required": ["success"], "properties": {
        "success": { "type": "boolean" }, "aot": { "description": "성공 시 토큰 JSON, 실패 시 null." } } },
      "ProcessStatus": {
        "type": "object",
        "properties": {
          "server_running": { "type": "boolean" }, "db_observing": { "type": "boolean" },
          "port": { "type": "integer" }, "bot_id": { "type": "integer", "format": "int64" },
          "bot_name": { "type": "string" }, "bot_http_port": { "type": "integer" },
          "web_server_endpoint": { "type": "string" },
          "db_polling_rate": { "type": "integer", "format": "int64" },
          "message_send_rate": { "type": "integer", "format": "int64" },
          "logs": { "type": "array", "description": "데몬 로그(최신 선행)", "items": {
            "type": "object", "properties": {
              "timeMs": { "type": "integer", "format": "int64", "description": "Unix millis." },
              "level": { "type": "string", "example": "INFO" },
              "source": { "type": "string", "example": "데몬" },
              "message": { "type": "string" } } } },
          "last_logs": { "type": "array", "description": "최근 DB 채팅 로그(raw 행, 최신 선행) — 로그 탭의 수신 목록",
            "items": { "type": "object", "additionalProperties": { "type": ["string", "null"] } } }
        }
      }
    }
  },
  "x-websocket": {
    "/ws": {
      "summary": "채팅 이벤트 스트림 (단방향 broadcast)",
      "description": "서버→클라이언트 JSON 문자열. 전송 트리거는 DB 폴링이며 동일 log_id 는 중복 전송되지 않습니다. `origin == SYNCMSG` 는 항상 제외, `MCHATLOGS` 는 필터 미설정 시 제외.",
      "message": {
        "content": { "application/json": { "schema": { "oneOf": [
          { "@@REF@@": "#/components/schemas/ChatEvent" },
          { "@@REF@@": "#/components/schemas/ApiResponse" }
        ] } } }
      }
    }
  }
}
"""
