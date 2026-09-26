# IrisGUI — 카카오톡 봇 프레임워크 (Root & Non-Root 통합)

IrisGUI는 카카오톡 안드로이드 앱과 연동하여 HTTP/WebSocket 기반 채팅 봇을 작성할 수 있는 환경을 제공하는 **설치형 Android 앱**입니다.

루팅 환경에서 동작하던 [Iris](https://github.com/dolidolih/Iris)와 논루팅 환경에서 동작하던 IrisLite 두 프로젝트를 하나의 APK로 통합했으며, 기기 환경에 따라 두 가지 실행 모드 중 하나가 자동 감지됩니다.

**프로젝트 상태:** 베타

## 필요 조건

### 카카오톡

* **KakaoTalk 26.7.2 이상** — 최신 버전 설치가 필요합니다. 26.7.2 미만은 지원하지 않습니다.

### 안드로이드 버전 (모드별)

| 모드 | 안드로이드 | 추가 요구 사항 |
|---|---|---|
| **NON_ROOT** (논루팅·알림) | Android 14 (API 34) 이상 | 루트 불필요 |
| **ROOT_ADB** (루팅) | Android 11 (API 30) 이상 | 루트 권한 필요 (Magisk, redroid 등 root adb 환경) |

### 공통

* **카카오톡 설치** — 위 버전의 카카오톡이 로그인된 Android 기기
* **HTTP 서버 또는 WebSocket 클라이언트** — IrisGUI와 상호작용하여 메시지를 처리할 별도의 서버/클라이언트

## 설치

> [!NOTE]
> 기존 Iris와는 다르게 APK를 안드로이드에 직접 설치하는 형태로 제공됩니다.

1. **최신 IrisGUI APK를 [Releases](./releases)에서 다운로드하세요.**

2. **설치:** 기기에 APK를 복사해 실행하거나, adb로 설치하세요.

   ```shell
   adb install IrisGUI-vX.Y.Z.apk
   ```

3. **첫 실행 및 모드 선택:** 카카오톡이 로그인된 기기에서 앱을 실행하면 환경을 자동 감지하지만, 설정에서 모드를 직접 지정할 수 있습니다.

   * **NON_ROOT** — 상태 탭의 서비스 스위치를 ON. 알림 읽기 접근 권한 허용을 요청합니다.
   * **ROOT_ADB** — 상태 탭의 **[app_process 시작]** 버튼이 루트 확보 및 데몬 기동을 시도합니다. PC에서 제어하려면 아래 수동 기동 방법을 대신 사용하세요.

     ```sh
     adb shell
     su
     CLASSPATH=$(pm path party.qwer.irisgui | cut -d: -f2) app_process / party.qwer.irisgui.Main 3000 &
     ```

4. **설정:** 앱 UI에서 서버 포트, 메시지 전달 엔드포인트, 폴링/전송 속도 등을 수정합니다. 기본 HTTP 포트는 **3000** 입니다.

## 사용법

IrisGUI는 HTTP와 WebSocket으로 정보를 주고 받습니다. 모든 요청은 별도 명시 없으면 `Content-Type: application/json`인 `POST`입니다.

### HTTP API 엔드포인트

엔드포인트 지원 여부는 모드에 따라 다릅니다(✅ = 지원).

| 엔드포인트 | ROOT_ADB | NON_ROOT | 설명 |
|---|---|---|---|
| `/reply` | ✅ | ✅ | 채팅방에 메시지/사진/영상/오디오/파일 발송 |
| `/ws` | ✅ | ✅ | 이벤트 스트림 (WebSocket) |
| `/config`, `/config/{name}` | ✅ | ❌ | 설정 조회/변경 (endpoint, botname, dbrate, sendrate, botport) |
| `/query` | ✅ | ❌ | 카카오톡 DB SQL 쿼리 |
| `/decrypt` | ✅ | ❌ | 메시지 복호화 |
| `/aot` (GET) | ✅ | ❌ | AOT 토큰 조회 |
| `/rooms` (GET) | ✅ | ❌ | 최근 채팅방 목록 (`?limit=`) |
| `/user/*`, `/room/*`, `/reactions/*`, `/chat/*`, `/search` | ✅ | ❌ | 조회/검색 엔드포인트 |
| `/process-status`, `/process-command`, `/swagger`, `/openapi.json` | ✅ | ❌ | 데몬 관리/문서 |

전체 스펙은 `docs/api.md` 및 `GET /openapi.json` 참고.

#### `/reply`

```json
{
  "type": "text",            // 또는 "image", "image_multiple", "media", "video", "audio", "file"
  "room": "[CHAT_ROOM_ID]",  // 채팅방 ID (문자열)
  "data": "[MESSAGE_TEXT]"   // text: 메시지 | image_multiple: b64 문자열 배열(或 {name,b64}) |
                             // media/video/audio/file: object 하나 {name, b64[, mime]}
}
```

파일 첨부(`video`/`audio`/`file`)와 이름 지정 이미지는 `data`의 `name`을 그대로 카톡
첨부화면에 노출한다. 복붙용 규칙: **여러 장 전송은 이미지만 허용**, `media`/`video`/
`audio`/`file` 은 항상 단일 항목. 기존 이미지 요청(`b64` 문자열만 보낸 `image_multiple`)
은 이전과 동일하게 동작한다.

```shell
# 텍스트 답장
curl -X POST -H "Content-Type: application/json" \
  -d '{"type": "text", "room": "1234567890", "data": "hello from IrisGUI"}' \
  http://[DEVICE_IP]:3000/reply

# 이름 지정 파일 첨부
curl -X POST -H "Content-Type: application/json" \
  -d "{\"type\": \"file\", \"room\": \"1234567890\", \"data\": {\"name\": \"명세.pdf\", \"b64\": \"$(base64 -w0 명세.pdf)\"}}" \
  http://[DEVICE_IP]:3000/reply
```

#### `/query`

카카오톡 DB에 SQL 쿼리를 실행하며, 암호화된 필드는 자동으로 복호화됩니다. (ROOT_ADB 모드 전용)

```shell
curl -X POST -H "Content-Type: application/json" \
  -d '{"query": "SELECT _id, chat_id, user_id, message FROM chat_logs ORDER BY _id DESC LIMIT 5", "bind": []}' \
  http://[DEVICE_IP]:3000/query
```

### WebSocket `/ws`

연결 시 새 채팅 이벤트가 실시간으로 전달됩니다.

**ROOT_ADB 모드** (DB 폴링 기반, Iris 규격):

```json
{
  "msg": "[복호화된 메시지]",
  "room": "[채팅방 이름]",
  "sender": "[발신자 이름]",
  "json": { "chat_logs 행 전체 — message/attachment 복호화 포함" }
}
```

**NON_ROOT 모드** (알림 기반, IrisLite 규격):

```json
{
  "msg": "[알림 텍스트]",
  "room": "[채팅방 이름]",
  "sender": "[발신자 이름]",
  "is_lite": false,
  "is_group_chat": false,
  "profile_image": "[JPEG base64 또는 null]",
  "json": { "_id": null, "id": "[chatLogId]", "chat_id": "[방 ID]", "user_id": "[발신자 ID]", "message": "[텍스트]", ... }
}
```

설정된 `webEndpoint`가 있으면 동일 payload가 HTTP `POST`로도 전달됩니다.

### 내장 스크립팅

앱 내장(proot Ubuntu userland)에 프로젝트별 Python(venv) 실행 환경을 제공합니다. 스크립트 탭에서 프로젝트를 만들고 실행/편집/로그 확인이 가능하며, 스크립트는 모두 앱의 `/ws`에 접속하므로 같은 이벤트 스트림을 동시에 수신합니다.

## Credits

* **SendMsg & Initial Concept:** Based on the work of [`ye-seola/go-kdb`](https://github.com/ye-seola/go-kdb).
* **KakaoTalk Decryption Logic:** Decryption methods from [`jiru/kakaodecrypt`](https://github.com/jiru/kakaodecrypt).

## Disclaimer

This project is provided for educational and research purposes only. The developers are not responsible for any misuse or damage caused by this software. Use it at your own risk and ensure you comply with all applicable laws and terms of service.

## License

This project is licensed under the **MIT License**. See [LICENSE.md](LICENSE.md) for details.
