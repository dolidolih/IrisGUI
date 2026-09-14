#!/bin/bash
# ============================================================================
# IrisGUI control script (Linux / macOS) — 앱에서 내보냄
# 설치형 앱(party.qwer.irisgui)을 ADB 루팅 환경에서 app_process로 실행/정지한다.
# APK 경로는 pm path 로 자동 해석. 포트 기본값 __PORT__ (인자로 변경 가능).
# 사용법: ./iris_control {status | start [-v] [PORT] | stop | restart [PORT]}
# ============================================================================
PACKAGE="party.qwer.irisgui"
MAIN_CLASS="party.qwer.irisgui.Main"
DEFAULT_PORT=__PORT__
ADB_CMD=""

select_adb_device() {
  [ -n "$ADB_CMD" ] && return 0
  mapfile -t devices < <(adb devices | grep -w "device" | grep -v "List of devices" | awk '{print $1}')
  local n=${#devices[@]}
  if [ "$n" -eq 0 ]; then echo "ADB 기기 없음 (adb connect <ip>:<port>)"; return 1
  elif [ "$n" -eq 1 ]; then ADB_CMD="adb -s ${devices[0]}"; echo "기기: ${devices[0]}"; return 0
  else
    local i=1; for d in "${devices[@]}"; do echo "  $i) $d"; i=$((i+1)); done
    read -p "번호 선택 (1-$n): " c; ADB_CMD="adb -s ${devices[$((c-1))]}"; return 0
  fi
}

resolve_apk_path() { $ADB_CMD shell "su root sh -c 'pm path $PACKAGE'" | tr -d '\r' | sed -n 's/^package://p' | head -n1; }
get_iris_pid() { $ADB_CMD shell "su root sh -c 'ps -ef'" | tr -d '\r' | grep "$MAIN_CLASS" | grep -v 'sh -c' | grep -v grep | awk '{print $2}' | head -n1; }

iris_status() { local p; p=$(get_iris_pid); [ -n "$p" ] && echo "실행 중. PID: $p" || echo "실행 중이 아님"; }
iris_start() {
  local port="${1:-$DEFAULT_PORT}"; local p; p=$(get_iris_pid)
  [ -n "$p" ] && { echo "이미 실행 중 (PID: $p)"; return 0; }
  local apk; apk=$(resolve_apk_path); [ -z "$apk" ] && { echo "APK 경로를 찾을 수 없음 (앱 설치 확인)"; return 1; }
  echo "실행 (포트 $port, APK: $apk)..."
  # setsid 로 분리해야 adb 연결 종료 후에도 살아남음
  $ADB_CMD shell "su root sh -c \"CLASSPATH=$apk setsid app_process / $MAIN_CLASS $port > /dev/null 2>&1 < /dev/null &\""
  sleep 2; local np; np=$(get_iris_pid); [ -n "$np" ] && echo "시작됨. PID: $np (http://<기기IP>:$port/dashboard)" || echo "시작 실패"
}
iris_start_fg() {
  local port="${1:-$DEFAULT_PORT}"; local apk; apk=$(resolve_apk_path)
  [ -z "$apk" ] && { echo "APK 경로를 찾을 수 없음"; return 1; }
  $ADB_CMD shell "su root sh -c \"CLASSPATH=$apk app_process / $MAIN_CLASS $port\""
}
iris_stop() {
  local p; p=$(get_iris_pid); [ -z "$p" ] && { echo "실행 중이 아님"; return 0; }
  $ADB_CMD shell "su root sh -c 'pkill -f $MAIN_CLASS'"; sleep 1
  [ -z "$(get_iris_pid)" ] && echo "정지됨" || echo "정지 실패"
}

select_adb_device || exit 1
case "$1" in
  status) iris_status ;;
  start) if [ "$2" = "-v" ]; then iris_start_fg "$3"; else iris_start "$2"; fi ;;
  stop) iris_stop ;;
  restart) iris_stop; sleep 1; iris_start "$2" ;;
  *) echo "Usage: $0 {status | start [-v] [PORT] | stop | restart [PORT]}"; exit 1 ;;
esac
exit 0
