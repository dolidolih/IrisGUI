package party.qwer.irisgui.scripting

import android.content.Context
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.RuntimeLog
import org.json.JSONArray
import org.json.JSONObject

/**
 * ScriptManager — 스크립트 생명주기 오케스트레이션.
 *
 * UI 는 이 object 만 사용한다:
 *   list / save / delete / start / stop / statusAll / logsFor
 *
 * Python 사이드는 irisgui.manager 가, 런타임 기동은 PythonRuntime 이 담당한다.
 */
object ScriptManager {
    data class Script(
        val id: String,
        val name: String,
        val source: String,
        val requires: List<String>,
        val grants: List<String>,
        val updatedAt: Long,
        val state: String,
        val error: String?,
        val logs: List<LogLine>
    )

    data class LogLine(val timeMs: Long, val level: String, val text: String)

    private const val TAG = "ScriptMgr"

    /** 저장 목록 + 런타임 상태를 병합한 스냅샷 (최근 변경 순). */
    fun list(context: Context): List<Script> {
        val statuses = statusMap(context, force = false)
        return ScriptStore.all(context).map { m ->
            val st = statuses[m.id]
            Script(
                id = m.id,
                name = m.name,
                source = m.source,
                requires = m.requires,
                grants = m.grants,
                updatedAt = m.updatedAt,
                state = st?.optString("state") ?: "stopped",
                error = st?.let { if (it.isNull("error")) null else it.optString("error") },
                logs = emptyList()
            )
        }
    }

    /** status_all 조회. force=true 일 때만 런타임을 (재)기동한다.
     *  cleanup 같이 서비스 main thread 에서 호출되는 context 는 force=false —
     *  파이썬이 이미 떠있지 않으면 빈 맵으로 no-op. */
    private fun statusMap(context: Context, force: Boolean = true): Map<String, JSONObject> {
        val out = LinkedHashMap<String, JSONObject>()
        if (force) {
            if (!PythonRuntime.ensureStarted(context)) return out
        } else if (!PythonRuntime.isReady()) {
            return out
        }
        val raw = PythonRuntime.call("status_all")
        val arr = try {
            JSONArray(raw)
        } catch (e: Throwable) {
            return out
        }
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id")
            if (id.isNotEmpty()) out[id] = o
        }
        return out
    }

    fun logsFor(context: Context, id: String, limit: Int = 200): List<LogLine> {
        val raw = PythonRuntime.call("logs", id, limit)
        val arr = try {
            JSONArray(raw)
        } catch (e: Throwable) {
            return emptyList()
        }
        val out = ArrayList<LogLine>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            out += LogLine(
                o.optLong("timeMs"),
                o.optString("level"),
                o.optString("text")
            )
        }
        return out
    }

    /** 저장 + 필요 시 런타임 시작(이미 실행 중이면 restart 아님; 먼저 stop). */
    fun save(context: Context, id: String, source: String) {
        val before = PythonRuntime.call("status", id)
        ScriptStore.write(context, id, source)
        try {
            val st = JSONObject(before).optString("state")
            if (st == "running" || st == "starting") {
                PythonRuntime.call("stop", id, 8.0)
            }
        } catch (_: Throwable) {
        }
        RuntimeLog.info(TAG, "스크립트 저장: $id")
    }

    fun start(context: Context, id: String): JSONObject? {
        if (!PythonRuntime.ensureStarted(context)) return null
        val source = ScriptStore.read(context, id) ?: return null
        val url = "http://127.0.0.1:${AppConfig.serverPort}"
        val raw = PythonRuntime.call("start", id, id, source, url)
        RuntimeLog.info(TAG, "스크립트 시작 요청: $id")
        return try {
            JSONObject(raw)
        } catch (e: Throwable) {
            JSONObject().put("state", "error").put("error", raw)
        }
    }

    fun stop(context: Context, id: String): String? {
        val raw = PythonRuntime.call("stop", id, 8.0)
        RuntimeLog.info(TAG, "스크립트 정지 요청: $id")
        return try {
            JSONObject(raw).optString("state", "unknown")
        } catch (e: Throwable) {
            raw
        }
    }

    /** 실행 목록 전체 정지 (포렌지 서비스 종료 시 호출). */
    fun stopAll(context: Context) {
        PythonRuntime.call("stop_all", 5.0)
    }

    /** 실행 중 스크립트 수. force=false — 파이썬 미기동 시 0. */
    fun runningCount(context: Context): Int =
        statusMap(context, force = false).values.count {
            val s = it.optString("state")
            s == "running" || s == "starting"
        }

    /** 신규 생성용 템플릿 소스 (manifest 헤더 포함). */
    fun newTemplate(id: String): String =
        """
        # irisgui: name: $id
        # irisgui: requires: requests
        # irisgui: grant: send
        #
        # 이벤트 핸들러: @event("<이름>")
        #   chat | message | new_member | del_member | error
        # 컨텍스트 필드: ctx.room / ctx.sender / ctx.message / ctx.reply(text)
        from irisgui import event


        @event("message")
        def on_message(ctx):
            log(f"{ctx.room.name} / {ctx.sender.name}: {ctx.message.msg}")
            ctx.reply("반가워요")
        """.trimIndent() + "\n"
}
