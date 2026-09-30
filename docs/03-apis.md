# API 동작 상세

엔드포인트의 스펙 자체보다 "각 API는 IrisGUI 안에서 어떤 방식으로 동작하는가"를 다룹니다.
개별 요청·응답 형식은 [API 레퍼런스](06-api-reference.md)에 정리했습니다.

## API와 대화하는 두 가지 축

IrisGUI와 당신의 봇 사이의 대화는 항상 두 개의 축으로 나뉩니다.

| 방향 | 어떤 채널 | 설명 |
|---|---|---|
| 기기 → 봇 (이벤트 수신) | WebSocket `/ws` 또는 HTTP POST(webEndpoint) | 새 메시지가 있을 때 IrisGUI가 능동적으로 알림 |
| 봇 → 기기 (메시지 발송) | HTTP `POST /reply` | 봇이 판단을 마쳤으면 답장을 요청 |

이 분리 때문에 "메시지를 받는 코드"와 "보내는 코드"가 같은 언어·같은 컴퓨터일 필요가
전혀 없습니다. 받는 쪽은 IrisGUI와 다른 컴퓨터에서 접속할 수 있고, 보내는 쪽은
HTTP 요청 하나만 성립하면 되기 때문입니다.

## 이벤트 수신: WebSocket `/ws`

가장 기본적인 통로입니다. `ws://<기기>:<포트>/ws`에 접속하면 이후 모든 채팅 이벤트가
JSON 한 건으로 즉시 도착합니다.

- **동시 연결 가능** — 여러 클라이언트가 동시에 붙어도 동일 스트림을 모두 받습니다.
- **연결은 유지** — 이벤트가 없는 시간에는 아무 메시지도 오지 않으며, 클라이언트가
  끊기 전까지 계속 연결된 채로 유지됩니다.
- **모드별 형식 차이** — ROOT_ADB·HAYUL은 DB에서 온 원본 필드까지 담은 `json`,
  NON_ROOT은 알림에서 볼 수 있는 정보만 담고 `is_lite`, `is_group_chat`, `profile_image`
  같은 알림 전용 필드가 포함됩니다. 형식 전체는 레퍼런스 참고.

원본 Iris/IrisLite 호환성이 계약으로 붙어 있기 때문에 최상위 `msg`, `room`, `sender`, `json`
네 키는 모드에 관계없이 항상 유지됩니다. 확장 정보(타입 코드, 리액션, 삭제 여부)는
`POST /config/extension {enable:true}` 설정을 한 뒤에만 `extension` 으로 추가되며,
기본 설정은 항상 형식 그대로입니다.

## 이벤트 수신: HTTP 푸시(webEndpoint)

WebSocket을 여는 것 자체가 부담스러운 경우(서버리스 함수, 짧은 생명 주기 워커),
대신 **IrisGUI가 보내주는** 방식을 쓸 수 있습니다. `webEndpoint`(URL) 설정하면 새 이벤트마다
JSON을 `POST` 요청으로 던집니다.

- 서버는 200만 반환하면 되고, 이벤트 내용 검증은 하지 않습니다.
- 보내는 쪽은 응답을 기다리지 않고 다음 이벤트로 진행하므로, endpoint 응답이 느려도
  이벤트가 밀릴 뿐 봇 프로세스는 막히지 않습니다.
- webEndpoint와 `/ws`는 동시에 사용할 수 있습니다. 실시간 처리 워커와 배치 저장 서버를
  따로 두는 구성이 가능합니다.

## 메시지 발송: `POST /reply`

IrisGUI 봇이 실제로 발신하는 유일한 방법입니다. `room`, `type`, `data` 세 항목을 담습니다.

- `type: "text"` — data에 텍스트 문자열.
- `type: "image"`, `image_multiple` — base64 첨부. 여러 장은 `image_multiple` 형식만 허용하며,
  항목마다 `name`을 넣으면 그대로 카톡 첨부 화면에 표시됩니다.
- `type: "media" | "video" | "audio" | "file"` — 첨부 하나, `name`과 `b64`를 포함한
  object 하나.
- `threadId` — 스레드 답장을 하고 싶을 때 원본 로그 id를 지정.

동작상 알아 둘 점:

1. **응답은 즉시** — `{"success":true,"message":"Enqueued"}`는 큐에 들어갔다는 뜻이지,
   카톡 전송 확인이 아닙니다. 전송 자체는 비동기로 진행되며, 전송 오류는 앱 로그로 확인합니다.
2. **속도는 제어된 상태로** — 요청은 `message_send_rate`(기본 50ms)마다 한 건씩
   처리되는 큐에 쌓이므로, 연속 요청이 카카오톡을 흔들지 않습니다.
3. **room의 해석** — 방 ID를 넣으면 정확히 지정되지만, 방 이름을 넣으면 이름으로 해석됩니다.
   자동화에는 ID 지정이 안전합니다.

## 조회·검색 API (ROOT_ADB·HAYUL)

이벤트로 실시간 스트리밍되지 않는 정보는 조회 API로 꺼냅니다.

| 그룹 | 엔드포인트 | 언제 필요한가 |
|---|---|---|
| 방/사용자 | `/rooms`, `/user/{id}`, `/room/{id}` 및 그 하위 경로 | 방 목록이 필요하거나 id→이름 역참조가 필요할 때 |
| 메시지 | `/chat/{id}`, `/chat/{room}/messages`, `/chat/{id}/prev/{n}`, `/chat/{id}/next/{n}` | 특정 방의 과거 대화가 필요할 때. `id` 커서 기반이라 실시간 유입 중에도 페이지 경계가 흔들리지 않음 |
| 삭제 회수 | `/chat/{id}/deleted` | 상대가 지운 메시지가 왜 사라졌는지 확인이 필요할 때 |
| 리액션 | `/reactions/{id}` | 메시지별 리액션 현황 |
| 전문 검색 | `POST /search` | 내용만 남아 있어서 위치를 못 잡을 때. 방/보낸이/시간/형식 필터 가능 |
| 직접 질의 | `POST /query`, `POST /decrypt` | 카톡 DB 구조에 직접 질의해야 하는 고급 용도 |

`/chat/{room}/messages`의 커서는 `id`가 기준이며, `before`(그 이전), `after`(그 이후),
`around`(기준 포함) 중 하나만 쓸 수 있습니다.

## 설정 API

`GET /config`로 현재 상태를, `POST /config/{이름}`으로 개별 항목을 바꿉니다.

| 항목 | 변경 내용 | 적용 시점 |
|---|---|---|
| `botname` | 봇 이름 | 즉시 |
| `dbrate` | DB 폴링 주기(ms) | 즉시 |
| `sendrate` | 답장 간격(ms) | 즉시 |
| `botport` | 서버 포트 | 서버 재시작 후 |
| `types` | `/ws`에 내보낼 메시지 형식 필터 | 즉시 |
| `extension` | 이벤트의 `extension` 필드 포함 여부 | 즉시 |
| `endpoint` | webEndpoint URL | 즉시 |

포트는 즉시 반영되는 다른 항목들과 달리 반드시 서버를 다시 띄워야 적용됩니다.

## 모드별 제공 범위

| 기능 | ROOT_ADB / HAYUL | NON_ROOT |
|---|---|---|
| `/ws` 이벤트 | DB 기반 원본 형식 | 알림 기반 IrisLite 형식 |
| `/reply` 텍스트 | 내부 서비스 경유 | PendingIntent 경유 |
| `/reply` 첨부 | 지원 | 지원 |
| `/query`, `/decrypt`, `/aot` | 제공 | 미제공 |
| 조회·검색·설정 | 제공 | 미제공 |
| `/process-status`, `/process-command` | 제공(서버 상태 제어) | 미제공 |

## 상태 모니터링

ROOT_ADB는 `/process-status`가 서버의 동작 여부와 최근 로그(실행 로그, 최근 DB 이벤트)를
함께 알려줍니다. 앱의 상태 탭도 이 엔드포인트를 3초마다 조회하고 있어, 문제 진단
시에는 웹 브라우저에서 `http://<기기>:<포트>/swagger`로 직접 같은 응답을 검사할 수
있습니다. Swagger UI의 "Try it out" 은 실제 요청과 동일하게 동작합니다.
