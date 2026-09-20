package party.qwer.irisgui.scripting

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import org.json.JSONObject

/**
 * PythonRuntime — Chaquopy 임베디드 CPython 기동 + irisgui.kotlin JSON API 브리지.
 *
 * Kotlin 은 `irisgui.kotlin` module 의 JSON-string 함수(start/stop/status_all/logs)만
 * 호출한다. Python 측 ScriptHost 는 manager.py 가 소유한다.
 *
 * 주의: Python.start() 는 Context 가 있는 앱 프로세스에서만 성공한다.
 */
object PythonRuntime {
    @Volatile
    private var ready = false
    private var initError: String? = null

    fun isReady(): Boolean = ready

    fun lastError(): String? = initError

    /** 멱등 초기화. UI 호출/백그라운드 호출 모두 안전. */
    @Synchronized
    fun ensureStarted(context: Context): Boolean {
        if (ready) return true
        return try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            val py = Python.getInstance()
            py.getModule("irisgui.kotlin").callAttr("configure", urlFor(context), 4)
            ready = true
            initError = null
            true
        } catch (t: Throwable) {
            initError = t.toString()
            false
        }
    }

    /** 이 앱의 로컬 IRIS 엔드포인트 — 데몬/UI 서브 모드의 HTTP 서버. */
    private fun urlFor(context: Context): String {
        val port = party.qwer.irisgui.AppConfig.serverPort
        return "http://127.0.0.1:$port"
    }

    fun call(fn: String, vararg args: Any?): String {
        if (!ready) return "{}"
        return try {
            val mod = Python.getInstance().getModule("irisgui.kotlin")
            val out = mod.callAttr(fn, *args)
            out?.toString() ?: "null"
        } catch (t: Throwable) {
            JSONObject().put("error", t.toString()).toString()
        }
    }
}
