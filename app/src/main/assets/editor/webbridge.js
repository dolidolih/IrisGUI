/* EditorWeb 원격 브리지 — 다른 기기 브라우저에서 네이티브 AndroidBridge 를 대체.
 * WebView 창(native addJavascriptInterface)에서는 창이 이미 차 있으므로 이 파일
 * 은 가드에서 바로 빠지고 WS 조차 만들지 않는다.
 *
 * 평면:
 *  · RPC — B.<method>(...) → 동기 XHR(/editor/api/<method>). 편집 화면 코드가
 *    동기 반환값에 의존하므로 async 전면개편 대신 같은 시맨틱(같은-원 동기 XHR 은
 *    deprecated 표시에도 아직 동작).
 *  · WS — /editor/ws. 서버 emit(js 스니펫) 은 new Function 으로 실행(webview 의
 *    evaluateJavascript 와 동격), 터미널 제어(input/resize/stop) 는 ws 로 흘려
 *    키마다 main thread 를 막지 않는다.
 *
 * 서버측 대응: backend/EditorWeb.kt */
if (!window.AndroidBridge) {
  window.AndroidBridge = (function () {
    'use strict';

    var project = (new URLSearchParams(location.search).get('project')) || 'main';
    var ws = null, backoff = 500, queue = [];

    function note(m) {
      var t = document.getElementById('toast');
      if (t) { t.textContent = m; t.style.display = 'block'; }
    }

    function rpc(method, qs, body) {
      var u = '/editor/api/' + method + '?project=' + encodeURIComponent(project);
      if (qs) u += '&' + qs;
      try {
        var x = new XMLHttpRequest();
        x.open(body != null ? 'POST' : 'GET', u, false);
        if (body != null) x.send(body); else x.send();
        if (x.status === 204) return null;
        if (x.status !== 200) throw new Error('HTTP ' + x.status);
        return x.responseText;
      } catch (e) { note('에디터 서버 오류: ' + e); throw e; }
    }

    /* WS: 수신은 emit(webview evaluateJavascript 와 같은 신뢰수준), 발신은 제어. */
    function wsSend(s) {
      if (ws && ws.readyState === 1) ws.send(s);
      else queue.push(s);   //再接속까지 폐기하지 않는다 — 터미널 입력 유실이 더 나쁘다.
    }
    function wsConnect() {
      var p = location.protocol === 'https:' ? 'wss://' : 'ws://';
      var s;
      try { s = new WebSocket(p + location.host + '/editor/ws?project=' + project); }
      catch (e) { setTimeout(wsConnect, Math.min(30000, backoff *= 2)); return; }
      s.onopen = function () {
        backoff = 500;
        var q = queue.splice(0);
        for (var i = 0; i < q.length; i++) try { s.send(q[i]); } catch (e) { }
      };
      s.onmessage = function (e) {
        var d = String(e.data || '');
        if (d.charCodeAt(0) === 123) {         // {"err":...} 오류 페이로드
          try { note(JSON.parse(d).err || '서버 오류'); } catch (p) { }
          return;
        }
        try { (new Function(d))(); } catch (err) { }   // emit: window.Term.onData(...)
      };
      s.onclose = function () {
        ws = null;
        setTimeout(wsConnect, Math.min(30000, backoff *= 2));
      };
      ws = s;
    }
    wsConnect();

    function ctrl(obj) { wsSend(JSON.stringify(obj)); return 'ok'; }

    return {
      projectInfo: function () { return rpc('projectInfo'); },
      listDir: function () { return rpc('listDir'); },
      read: function (rel) { return rpc('read', 'rel=' + encodeURIComponent(rel)); },
      write: function (rel, text) { return rpc('write', 'rel=' + encodeURIComponent(rel), text); },
      create: function (rel, isDir) {
        return rpc('create', 'rel=' + encodeURIComponent(rel) + '&dir=' + (isDir ? 1 : 0));
      },
      remove: function (rel) { return rpc('remove', 'rel=' + encodeURIComponent(rel)); },
      rename: function (from, to) {
        return rpc('rename', 'from=' + encodeURIComponent(from) + '&to=' + encodeURIComponent(to));
      },
      pipPackages: function () { return rpc('pipPackages'); },
      members: function (m) { return rpc('members', 'module=' + encodeURIComponent(m)); },
      runScript: function () { return rpc('runScript'); },
      runScriptAsync: function () { return rpc('runScriptAsync'); },
      stopScript: function () { return rpc('stopScript'); },
      scriptRunning: function () { return rpc('scriptRunning'); },
      termStart: function (cols, rows) { return rpc('termStart', 'cols=' + cols + '&rows=' + rows); },
      // 터미널 제어는 ws 로( 회신 불필요 ), 시작만 rpc.
      termInput: function (id, b64) { return ctrl({ op: 'input', id: id, b64: b64 }); },
      termResize: function (id, cols, rows) { return ctrl({ op: 'resize', id: id, cols: cols, rows: rows }); },
      termStop: function (id) { return ctrl({ op: 'stop', id: id }); },
      clipboardGet: function () { return rpc('clipboardGet'); },
      clipboardSet: function (t) { return rpc('clipboardSet', null, t); },
      log: function (m) { rpc('log', null, String(m)); },
      // 웹에는 화면/플러싱 개념이 없다 — 시점만 맞춰 호출하고 실제로는 건드리지 않는다.
      closeEditor: function () { },
      editorFlushed: function () { },
      beginFlushWait: function () { },
      awaitFlushAck: function () { return true; },
    };
  })();
}
