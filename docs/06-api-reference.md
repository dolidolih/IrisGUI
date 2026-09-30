# API 레퍼런스

엔드포인트 전체 목록과 요청·응답 형식입니다. 기기에서 확인은 `http://<기기>:<포트>/swagger`
(Swagger UI) 또는 `GET /openapi.json`으로도 가능합니다.

- 기본 서버: `http://<기기 IP>:<서버 포트>/` (포트 기본값 3000, 상태 탭에서 확인)
- 응답 규격: 성공·실패 ACK는 `{"success": true|false, "message": "..."}`
- 에러: HTTP 500 + `{"success": false, "message": "<원인>"}`
- 요청 JSON의 모르는 필드는 무시됩니다.
- 표의 **전체 모드** = ROOT_ADB, HAYUL. NON_ROOT는 `/reply`, `/ws`만 제공됩니다.

## 엔드포인트 요약

| 엔드포인트 | 메서드 | 전체 모드 | NON_ROOT | 설명 |
|---|---|:-:|:-:|---|
| `/ws` | WS | ✅ | ✅ | 채팅 이벤트 실시간 스트림 |
| `/reply` | POST | ✅ | ✅ | 메시지(텍스트·첨부) 발송 요청 |
| `/config` | GET | ✅ | ❌ | 봇 설정 조회 |
| `/config/{name}` | POST | ✅ | ❌ | 봇 설정 변경 |
| `/query` | POST | ✅ | ❌ | 카카오톡 DB SQL 질의 |
| `/decrypt` | POST | ✅ | ❌ | 암호화된 메시지 복호화 |
| `/aot` | GET | ✅ | ❌ | 인증 토큰(AOT) 조회 |
| `/rooms` | GET | ✅ | ❌ | 최근 대화방 목록 |
| `/user/{id}`, `/user/name/{id}` | GET | ✅ | ❌ | 사용자 프로필·이름 |
| `/room/{id}` 계통 | GET | ✅ | ❌ | 방 메타·이름·멤버·오픈링크 |
| `/reactions/{id}` | GET | ✅ | ❌ | 특정 로그의 리액션 |
| `/chat/{id}` 계통 | GET | ✅ | ❌ | 로그 1건·인접 메시지·삭제 회수 |
| `/chat/{room}/messages` | GET | ✅ | ❌ | 방 기준 메시지 커서 페이지네이션 |
| `/chat/logs` | DELETE | ✅ | ❌ | 오래된 로그 삭제 |
| `/search` | POST | ✅ | ❌ | 메시지 전문 검색 |
| `/process-status` | GET | ✅ | ❌ | 서버 상태·최근 로그 |
| `/process-command` | POST | ✅ | ❌ | 서버 중지/재시작 |
| `/openapi.json`, `/swagger` | GET | ✅ | ❌ | API 명세·탐색 UI |

## WebSocket `/ws`

`ws://<기기>:<포트>/ws` 접속 후 별도 준비 없이 이벤트 수신 시작. 이벤트마다 JSON
한 건이 도착합니다.

### ROOT_ADB · HAYUL 형식 (DB 기반)

```json
{
  "msg": "메시지 본문(복호화됨)",
  "room": "방 이름 (1:1은 상대 이름)",
  "sender": "보낸이 이름",
  "json": { "<chat_logs 행 전체 필드>" }
}
```

- `json`에는 `chat_logs`의 모든 컬럼이 담깁니다. `message`, `attachment`, `supplement`는
  복호화된 평문으로 대체됩니다.
- `json.attachment`에 `src_logId`, `src_isThread`가 추가되어 스레드 메시지를 추적할 수
  있습니다.
- 과거 역주행에 해당하는 `origin`(SYNCMSG, MCHATLOGS) 로그는 브로드캐스트되지 않습니다.

`extension` 설정(`POST /config/extension {"enable": true}`)이 켜져 있으면 같은
프레임에 top-level로 다음이 더해집니다.

```json
{
  "extension": {
    "type_code": 16385,
    "type_base": 1,
    "is_openchat": true,
    "type_name": "text",
    "log_id": "3913804818896836608",
    "is_mine": false,
    "reactions": [ { "id": 3, "emotion_id": "1200509_021", "count": 4, "label": "👍" } ],
    "is_deleted": true,
    "deleted_by": "writer"
  }
}
```

| 필드 | 설명 |
|---|---|
| `type_code` | 원본 타입 플래그 값. openchat 비트 포함 |
| `type_base` | `type_code`에서 openchat 비트를 제외한 값 |
| `is_openchat` | 오픈채팅 이벤트 여부 |
| `type_name` | `text`, `photo`, `video`, `audio`, `file`, `contact`, `photo_animation`, `gif`, `emoticon`, `list`, `app`, `app_feed`, `feed`, `feed_share`, `talk_memo`, `long_app`, `current_user`, `mchatlog`, `syncmsg`, `deleted`, `system`, `unknown` 중 하나 |
| `log_id` | 카카오 로그 id (스노우플레이크). 조회 API의 `{id}`에 사용 |
| `is_mine` | 내 발신 메시지 여부 |
| `reactions` | 실제 리액션이 존재하는 경우에만 포함 |
| `is_deleted` / `deleted_by` | 삭제 마크일 때만 포함. `deleted_by`는 `writer`(작성자 삭제) 또는 `admin`(방장 삭제) |

### NON_ROOT 형식 (알림 기반)

```json
{
  "msg": "알림 텍스트",
  "room": "방 이름",
  "sender": "보낸이 이름",
  "is_lite": false,
  "is_group_chat": false,
  "profile_image": "JPEG base64 또는 null",
  "json": {
    "_id": null, "id": "chatLogId", "type": null, "chat_id": "방ID",
    "user_id": "보낸이ID", "message": "본문",
    "attachment": null, "created_at": null, "deleted_at": null
  }
}
```

DB 접근이 불가하므로 `json`의 빈 필드는 채워지지 않습니다. 방 ID는 `json.chat_id`,
로그 ID는 `json.id`에 들어 있습니다.

## POST /reply

텍스트·첨부를 카카오톡으로 발송합니다. 응답은 발송 완료 전 큐 등록 시점에 반환됩니다.

**요청**

```json
{
  "room": "방ID 또는 방 이름",
  "type": "text | image | image_multiple | media | video | audio | file",
  "data": "<형식별 페이로드>",
  "threadId": "<스레드 답장인 경우 원본 로그 id>"
}
```

**형식별 `data`**

| type | data | 비고 |
|---|---|---|
| `text` | 문자열 | |
| `image` | base64 문자열 또는 `{"name": "...", "b64": "..."}` | |
| `image_multiple` | base64 문자열 배열, 또는 object 배열 `[{name, b64, mime?, kind?}]` | 복수 첨부가 허용되는 유일한 형식 |
| `media`/`video`/`audio`/`file` | object **1개** `{"name", "b64", "mime?"}` | 항목이 여러 개면 이미지만 가능 |

- `name`은 그대로 파일명으로 쓰입니다 — 첨부 카드에 그 이름이 그대로 나갑니다.
- `mime`은 생략 시 확장자로 추정합니다.
- **응답**: `{"success": true, "message": "Enqueued"}` / 형식 오류:
  `{"success": false, "message": "Unknown reply type: ..."}`

## GET /config

```json
{
  "bot_name": "Iris",
  "bot_http_port": 3000,
  "web_server_endpoint": "https://…/ingest",
  "db_polling_rate": 100,
  "message_send_rate": 50,
  "bot_id": 321468085
}
```

- `bot_id`가 0이면 DB 폴링이 움직이지 않습니다. 감지되지 않은 상태입니다.
- 타입 필터를 설정한 경우에만 `"broadcast_types": ["text", "photo"]` 키가 추가됩니다.

## POST /config/{name}

변경할 이름 하나와 대응하는 필드만 요청합니다.

| `{name}` | 요청 필드 | 검증 |
|---|---|---|
| `endpoint` | `{"endpoint": "URL 또는 null"}` | null→빈 문자열(푸시 해제) |
| `botname` | `{"botname": "이름"}` | 비어 있으면 오류 |
| `dbrate` | `{"rate": 200}` | ms. null 불가 |
| `sendrate` | `{"rate": 80}` | ms. null 불가 |
| `botport` | `{"port": 3100}` | 1–65535. **재시작 후 반영** |
| `types` | `{"types": ["text", "photo"]}` | 빈 배열 `[]`은 필터 해제 |
| `extension` | `{"enable": true}` | 이벤트의 `extension` 필드 포함 여부 |

성공 시 `{"success": true, "message": "success"}`.

## GET /rooms

`last_log_id`가 존재하는 방을 최근 갱신 순으로 최대 30개 반환(`?limit=` 조정 가능).

```json
{ "rooms": [ { "id": "479588580459315", "name": "개발 모임", "updated_at": "1699…" } ] }
```

## 사용자 조회 (GET)

| 경로 | 응답(`payload`) |
|---|---|
| `/user/{id}` | `{"id": …, "name": "홍길동", "profile_image_url": "https://…"}` |
| `/user/name/{id}` | `{"name": "홍길동"}` |

탈락·차단 등으로 이름이 없는 사용자의 경우에도 `name: null`로 페이로드 자체는 반환됩니다.

## 방 조회 (GET)

| 경로 | 응답(`payload`) |
|---|---|
| `/room/{id}` | `{"id", "name", "active_member_ids", "link_id", "type", "last_updated_at"}` |
| `/room/name/{id}` | `{"name": "방 제목"}` (오픈채팅은 open_link의 이름) |
| `/room/{id}/members` | `{"members": [{"user_id", "nickname", "profile_image_url", "privilege?"}]}` |
| `/room/{id}/links` | 오픈링크 존재 시 `{"id", "name", "url", "image_url", "member_limit", "searchable", "description"}`, 없으면 `null` |

조회 응답은 전체가 `{"payload": …, "error": null}` 래퍼 안에 들어 있습니다.

## GET /reactions/{id}

```json
{ "payload": { "reactions": [ { "id": 3, "emotion_id": "1200509_021", "count": 4, "label": "👍" } ] } }
```

`{id}`에는 `/ws` 이벤트의 `json.id`(카카오 로그 id)를 넣습니다.

## 메시지 조회

### GET /chat/{id}

`{id}` 한 건을 `/ws`와 같은 `{msg, room, sender, json}` 프레임으로 반환합니다.
`{id}`에는 삭제 마크 로그 id와 원본 id 모두 사용 가능합니다.

### GET /chat/{id}/deleted

삭제된 메시지를 회수합니다. 삭제 마크 로그 id에도 원본 로그 id를 넣어도 결과가 같습니다.

### GET /chat/{id}/prev/{n} · /chat/{id}/next/{n}

같은 방의 n개 전/후 인접 메시지 (`before`/`after` 별칭 경로도 동일). n 최대값에 주의.

### GET /chat/{room}/messages

방 기준 `id` 커서 기반 페이지네이션. 각 항목은 `/ws` 채팅 이벤트와 같은 필드 구성을 갖습니다.

```
GET /chat/{id}/messages?limit=50
GET /chat/{id}/messages?limit=50&before=3933298010694445057
GET /chat/{id}/messages?limit=50&after=3933298010694445057
GET /chat/{id}/messages?around=3933298010694445057&limit=25
GET /chat/{id}/messages?limit=50&types=text,photo&user_id=4480249
```

| 파라미터 | 기본값 | 설명 |
|---|---|---|
| `limit` | 50 | `1..100` |
| `before` | — | 기준 id보다 이전만, 내림차순. 기준값은 제외 |
| `after` | — | 기준 id보다 이후만, 오름차순. 기준값은 제외 |
| `around` | — | 기준값 포함 내림차순 |
| `user_id` | — | 발신자 필터 |
| `types` | — | `,` 구분 `type_name` 필터 |
| `from_created_at` / `to_created_at` | — | epoch 초 기준 시각 필터 |

`before`/`after`/`around`은 동시에 사용할 수 없습니다. 커서 미지정 시 최신부터 내림차순.

```json
{ "payload": {
    "items": [ { "msg": "…", "room": "…", "sender": "…", "json": { … } } ],
    "next_cursor": "3933298010694445057", "has_more": true },
  "error": null }
```

`next_cursor`를 다음 요청의 `before`에 넣으면 그 다음 과거 페이지입니다.

### DELETE /chat/logs?days=

지정 일수보다 오래된 채팅 로그를 영구 삭제합니다. `(10.5)`처럼 실수도 허용합니다.
되돌릴 수 없으므로 테스트용 데이터에만 사용하세요.

## POST /search

`crypto_database` 전문(full-text) 검색. 최소 요청은 `{"query": "…", "limit": 25}`
이며, 선택 필터를 넣으면 결과(hit)마다 enrich 필드가 붙습니다.

```json
{ "query": "정산", "limit": 25,
  "room": 18333832973839609, "user_id": 8149793448660328833,
  "types": ["text", "file"], "from_created_at": 1789747200, "to_created_at": 1789833600 }
```

- 필터로만 쓰인 값이 null인 경우 enrich 필드는 채워지지 않습니다.
- `hits[]`의 `id` 값은 `chat_logs.id` — `json.id`가 들어 있으므로 `/chat/{id}`로 상세조회 가능.

## POST /query

DB SQL 질의. `bind`의 값은 type이 무엇이든 JSON primitive 그대로 넣으세요.

```json
{ "query": "SELECT * FROM chat_logs WHERE _id > ?", "bind": [123456] }
```

응답: `{"data": [ {열: 값, …} ], "error": null}`. rows message/attachment/etc 복호화 포함.

## POST /decrypt

```json
{ "enc": 0, "b64_ciphertext": "<base64>", "user_id": 123456 }
```

응답: `{"plain_text": "…"}` — 실패해도 HTTP 200에서 `plain_text`가 `"Error: …"`로 반환됩니다.

## GET /aot

인증 토큰(AOT) 조회. `{"success": true, "aot": { … , "d_id": … }}` 형태로 반환.
실패 시 `{"success": false, "aot": null}`.

## GET /process-status

```json
{
  "server_running": true, "port": 3000,
  "notification_polling": true, "db_observing": true,
  "bot_id": 0, "bot_name": "Iris", "bot_http_port": 3000,
  "web_server_endpoint": "", "db_polling_rate": 100, "message_send_rate": 50,
  "logs": [ "…서버 실행 로그(최근 60줄)…", "..." ],
  "last_logs": [ { "_id": "…", "chat_id": "…", "user_id": "…", "message": "…",
                    "created_at": "…", "room_name": "…", "user_name": "…" } ]
}
```

- `server_running`: 서버 응답 가능 여부. 상태 탭의 표시 값도 여기서 옵니다.
- `logs`: 서버 실행 stdout/stderr 최근 기록.
- `last_logs`: 최근 DB 이벤트(raw 로그 행, 최신이 앞에).

## POST /process-command

```json
{ "command": "stop" | "restart" }
```

서버 프로세스 중지/재시작. 루트 환경 없이도 API로 제어 가능합니다.

## 브라우저에서 명세 확인하기

| 경로 | 설명 |
|---|---|
| `GET /swagger` | Swagger UI. 요청 Host로 주소가 생성되므로 same-origin "실행해보기"가 가능 |
| `GET /openapi.json` | OpenAPI 3.1 명세 (JSON) |

WebSocket(`/ws`)은 OpenAPI로 설명할 수 없어 명세에는 `x-websocket` 확장으로만
표기되어 있습니다. 이벤트 형식은 이 문서의 `/ws` 절을 참고하세요.
