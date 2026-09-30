# 스크립트 · 리눅스 환경

IrisGUI는 기기 안에 완전한 Linux userland(우분투 기반)를 갖고, Python 봇 코드를
기기 안에서 직접 실행할 수 있습니다. 외부 PC 없이 기기 하나로 봇을 완결하거나, 여러
봇을 동시에 돌리는 데 씁니다.

## 스크립트 탭 — 프로젝트 모델

"스크립트" 탭이 스크립트 환경의 진입점입니다.

- **첫 설치** — 최초 1회 userland가 설치됩니다. 우분투 rootfs + Python + git/gh까지
  자동으로 준비됩니다. 카드이 "설치" 안내를 보여주면 탭을 열기만 하면 진행됩니다.
- **프로젝트** — 프로젝트를 만들면 `linux/home/projects/<name>/` 에 `main.py`가 생기고,
  가상환경(venv)이 자동으로 따라 만들어집니다. 카드는 프로젝트마다
  [실행]·[중지]·[편집]·[로그]·[삭제]을 제공합니다.
- **기본 `main`** — 첫 실행부터 `!hhhi` 명령에 `hey from irisgui!` 로만 응답하는
  샘플 프로젝트가 준비되어 있어, 별도 코드 없이 왕복 테스트가 가능합니다.

실행 여부는 실제 프로세스를 검사해 판단하므로, 편집기 터미널이 열려 있다고 "실행 중"으로
보이지 않습니다. 삭제는 항상 먼저 정상 중지가 확인되어야 진행됩니다.

## 편집기 화면

프로젝트의 [편집]을 누르면 Monaco 편집기와 xterm 터미널 화면이 열립니다.

- 파일 저장 · 자동완성(venv에 설치된 패키지 기준) · 실행·중지가 카드에서 누르는 것과
  동일하게 동작합니다.
- 터미널은 실제 proot shell 이므로, `pip install <패키지>`로 필요한 패키지를 가상환경에
  직접 넣을 수 있습니다. C 확장(numpy, pandas, scipy)도 wheel 인덱스와 연동되어
  컴파일 없이 설치됩니다.
- **pip 설치는 편집기 터미널(가상환경)에서만** 수행됩니다. 시스템 Python에 넣으면
  프로젝트 코드에서 사용할 수 없습니다.
- 같은 네트워크의 다른 PC 브라우저에서 `http://<기기 IP>:<포트>/editor/?project=main`
  으로 열면 앱 편집기와 같은 화면(파일·실행·터미널)을 원격으로 쓸 수 있습니다.

## 봇 코드에서 IrisGUI API 쓰기

프로젝트가 시작되면 IrisGUI의 API 주소가 `IRISGUI_API_URL` 환경변수로 주어집니다.
`irispy-client`(가상환경에 기본 설치)와 조합하는 표준 형태는 아래와 같습니다.

```python
import os
from iris.bot import Bot

bot = Bot(os.environ.get("IRISGUI_API_URL", "http://127.0.0.1:3000"))

@bot.on_event("message")
def on_message(ctx):
    if ctx.message.command == "!time":
        from datetime import datetime
        ctx.reply(datetime.now().strftime("%H:%M"))

if __name__ == "__main__":
    bot.run()
```

핵심은 **여러 프로젝트가 같은 이벤트 스트림을 동시에 구독한다**는 점입니다.
모든 `main.py`가 기기 loopback의 `/ws`에 접속하므로, 스크립트 카드에 프로젝트를
몇 개를 실행시키든 각자 다른 논리로 같은 메시지를 함께 받습니다. 이벤트 처리를
"받기·걸러내기·보내기" 단계로 나눈 뒤 프로젝트로 분리하는 방식이 관리에 편합니다.

## 인터넷 환경 만들기

userland가 완전한 Linux인 만큼, 스크립트는 기기 안에서 인터넷에 직접 접속할 수
있습니다. 이 특성으로 "기기 + 인터넷" 경계의 봇 환경을 만들 수 있습니다.

**1. 외부 API 호출** — 답장 판단에 인터넷 데이터가 필요할 때 그냥 requests를 씁니다.

```python
import requests

@bot.on_event("message")
def on_message(ctx):
    if ctx.message.command == "!weather":
        r = requests.get("https://wttr.in/Seoul?format=3", timeout=5)
        ctx.reply(r.text)
```

**2. 자체 HTTP 서버 열기** — 기기에서 프로세스로 서버를 띄워 다른 기기·클라우드에서
부르도록 할 수 있습니다. 예로 Flask로 헬스체크 엔드포인트를 만들고, 받은 메시지를
다른 서비스에 넘기는 webhook 도우미로 쓸 수 있습니다.

**3. 외부 서비스로 이벤트 내보내기** — IrisGUI 자체 기능인 webEndpoint에, 기기 안에서
돌리는 userland 서버 URL(예: `http://127.0.0.1:8080/ingest`)을 설정하면 같은 기기의
스크립트가 다른 인터넷 봇과 이벤트를 브로커링하는 구조가 됩니다. 루팅 모드든
일반 모드든 설정 화면의 webEndpoint는 동일하게 동작합니다.

**4. git으로 코드 관리** — userland에 git/gh가 설치되어 있어 GitHub에서 clone/pull을
그대로 사용할 수 있습니다. 편집기 터미널에서 `git clone` 후 폴더만 프로젝트 경로로 옮겨
실행하면 됩니다. CI처럼 pull만으로 코드를 갱신하는 운용이 가능합니다.

**5. apt 패키지** — `apt install`로 curl, jq, tmux 같은 도구를 넣을 수 있습니다.
시스템 도구와 프로젝트 코드를 분리하는 습관이 나중에 디버깅을 편하게 합니다.

## 다른 기기·PC와 조합하기

| 구성 | 통로 | 설명 |
|---|---|---|
| 기기 스크립트 ↔ 기기 IrisGUI | loopback `/ws` | 기본 구성. `IRISGUI_API_URL` 로 자동 연결 |
| PC 봇 ↔ 기기 IrisGUI | LAN `/ws` | irispy-client 참고. 같은 네트워크 필수 |
| 기기 스크립트 ↔ 클라우드 서버 | 인터넷 HTTP/WS | userland에서 직접 발신. NAT 밖으로는 tunnel/ssh 역방향 사용 |
| 클라우드 ↔ 기기 스크립트 | 인터넷 inbound | 포트 포워딩/미러링 설정 후 userland 서버 대기 |

**NAT 문제**: 홈 네트워크에서 기기에 inbound를 열려면 공유기 포트 포워딩이 필요하므로,
역방향 SSH tunnel이나 cloudflare tunnel처럼 outbound만으로 터널링하는 편이 실전에 강합니다.
userland가 SSH를 갖고 있으므로 `ssh -R` 방식이 가장 간단합니다.
