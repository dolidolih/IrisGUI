# 외부 클라이언트 개발

다른 컴퓨터(또는 서버)에서 IrisGUI의 API를 직접 사용하는 봇을 만드는 방법입니다.
공식 Python 클라이언트 **irispy-client**를 기준으로 설명하고, 마지막에 다른 언어용
최소 프로토콜을 덧붙입니다.

## irispy-client란?

Iris/IrisLite 규격의 API를 Python에서 다룰 수 있게 해 주는 라이브러리입니다.
`pip install irispy-client` 로 설치하며, 봇 코드는 다음 세 가지만 합니다.

1. `Bot(URL)` — 기기 IP/포트로 접속.
2. `@bot.on_event("message")` — 이벤트 도착마다 호출되는 콜백 등록.
3. `bot.run()` — 연결을 유지하며 이벤트를 소비.

## 첫 봇

```python
from iris.bot import Bot

bot = Bot("http://192.168.0.10:3000")   # 기기 IP와 서버 포트

@bot.on_event("message")
def on_message(ctx):
    if ctx.message.command == "!ping":
        ctx.reply("pong")

if __name__ == "__main__":
    bot.run()
```

- `ctx.message` — 본문 텍스트. `command` 속성은 `!`로 시작하는 명령어를 다루기
  위해 텍스트에서 따 온 값입니다.
- `ctx.reply(text)` — `POST /reply` 요청과 같은 효과를 냅니다.
- `ctx.room`, `ctx.sender` — 이벤트의 방 이름/보낸이 이름.

이벤트 수는 타입 필터로 줄일 수 있습니다(`POST /config/types`). 그러나 특정
명령에만 답하는 봇이 가장 안전하므로, `command` 검사처럼 조건부터 거는 방식을
권장합니다. 모든 메시지에 답장하지 않는 것이 기본 원칙입니다.

## 이벤트 안에서 쓸 수 있는 정보

이벤트는 모드에 따라 다른 필드를 담고 있지만, irispy-client는 공통 필드를
일관되게 노출합니다. ROOT_ADB 모드에서는 확장 필드를 켜면 더 많은 정보에 접근할 수
있습니다. 확장 필드는 `POST /config/extension {"enable": true}` 로 켤 수 있습니다.

```python
@bot.on_event("message")
def on_message(ctx):
    # 일반 텍스트 / 사진 / 영상 / 기타 등을 type_name으로 구분
    if ctx.raw.get("type_name") == "photo":
        ...
    # 리액션·삭제 정보도 확장 필드에 들어 있음
```

`ctx.raw`에는 `/ws` 이벤트의 `json`(원본 로그 행)이 들어 있습니다.

## 다른 네트워크에서 접속하기

- 클라이언트는 봇 서버와 기기IP/포트를 입력받습니다. 기기에서 봇을 직접 실행하기
  위해 사용하는 `127.0.0.1`은 다른 기기에서 기기를 보는 IP로 대체합니다.
- 기기IP 확인: 기기 설정 또는 PC에서 `adb shell ip addr`.
- 방화벽 등으로 접속 refused가 나면 기기 자체 방화벽/네트워크 세그먼트/포트 주소를
  확인합니다. 포트포워딩을 사용하는 환경에서는 `servers.url`의 주소와 실제 클라이언트
  주소가 일치해야 합니다.
- HAYUL, NON_ROOT 모드에서도 클라이언트 코드에는 변화가 없습니다. 이벤트의 json 필드
  형태만 바뀝니다.

## 직접 HTTP/WebSocket 구현하기 (공식 클라이언트 외의 언어)

`irispy-client`는 Python 전용입니다. 다른 언어에서는 아래 최소 규격만 지키면 됩니다.

**이벤트 수신** — `ws://<기기>:<포트>/ws` 접속 후 수신 JSON을 처리합니다. 각 프레임은
`{"msg": "...", "room": "...", "sender": "...", "json": {...}}` 한 건입니다.

**답장 발송** — `POST /reply` 에 `Content-Type: application/json`,

```json
{ "room": "<방ID 또는 방 이름>", "type": "text", "data": "<텍스트>" }
```

**채팅방 목록** — `GET /rooms` → `{"rooms": [{"id","name","updated_at"}]}` (ROOT_ADB·HAYUL).

**그 외** — 요청마다 응답을 JSON으로 받습니다. `{"success": false, "message": "..."}`가
오면 메시지 내용을 확인합니다. 500이 아니면 성공입니다.

## webEndpoint로 서버리스 구현하기

WebSocket 연결을 유지하기 어려운 환경에서는, IrisGUI에 webEndpoint URL(예: Cloudflare
worker, flask 서버)을 설정하고 이벤트마다 POST로 받는 방법도 쓸 수 있습니다.
POST 본문은 `/ws` 이벤트와 동일한 JSON이며, 봇은 응답으로 `{"success": true}`만 반환하면
됩니다. 이 방식은 연결을 유지하지 않아도 되는 대신, 서버가 잠시 죽어 있는 동안의
이벤트는 재시도 없이 사라지므로 중요한 봇에는 `/ws` 방식이 더 맞습니다.

## 실전 팁

- **bot id** — `GET /config`의 `bot_id`가 내 계정 id입니다. "내가 친 메시지"는
  이벤트의 `user_id`와 비교해 판단하세요. 이벤트에서 `origin` 필드가 `SYNCMSG` 계열인
  것은 이미 처리된 메시지일 가능성이 높습니다.
- **동일 방 복수 발화** — `message_send_rate` 조절로 카톡을 안전하게. 자동화 답변은
  같은 방에 2초 간격 이내로 연달아 보내지 않도록 코드로 막는 것을 권장합니다.
- **속도 제한의 기준점** — `reply()`는 응답 즉시 반환합니다. 발송 성공/실패는 로그로
  나며 `success: true`만으로는 카톡 전송 완료 상태가 아닙니다.
- **재접속** — 기기가 서버를 재시작하면 `/ws` 연결이 끊깁니다. irispy-client는 연결
  유지를 위해 자동으로 재접속합니다. 직접 구현한다면 연결이 끊겼을 때 스스로
  재접속 처리를 해야 합니다.
