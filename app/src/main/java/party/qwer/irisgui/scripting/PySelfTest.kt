package party.qwer.irisgui.scripting

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File

/**
 * PySelfTest — 임베디드 CPython(Chaquopy) 기동·import selftest.
 *
 * 주의: Python.start() 는 Context 가 있는 앱 프로세스에서만 성공한다.
 * app_process 데몬은 Context 가 없으므로 데몬 안에서 기동하지 않고,
 * 데몬이 띄운 HTTP 서버(127.0.0.1:port) 를 파이썬측 클라이언트로 호출하는 구조를 쓴다.
 *
 * adb 로 직접 트리거하는 스파이크용 프로브 (UI 화면 없음):
 *   adb shell am broadcast -n party.qwer.irisgui/.scripting.PySelfTestReceiver
 *   adb shell cat /data/user/0/party.qwer/irisgui/files/pyselftest.log
 */
object PySelfTest {

    /** 임베디드 CPython 멱등 기동 + selftest 결과를 lines 로 반환. */
    fun run(context: Context): List<String> {
        val out = ArrayList<String>()
        fun line(s: String) {
            out += s
            println("PySelfTest: $s")
        }

        line("=== PySelfTest start ===")
        try {
            if (!Python.isStarted()) {
                Python.start(AndroidPlatform(context))
            }
            line("Python.isStarted=${Python.isStarted()}")
        } catch (t: Throwable) {
            line("Python.start FAILED: $t")
            return out
        }

        val py = Python.getInstance()


        // platform.python_version() 로 간단히.
        try {
            val ver = py.getModule("platform").callAttr("python_version").toString()
            line("version=$ver (executable=${py.getModule("sys").get("executable")})")
        } catch (t: Throwable) {
            line("sys probe FAILED: $t")
        }

        for (m in listOf("requests", "websockets", "httpx", "PIL", "iris", "iris.bot")) {
            val r = try {
                py.getModule("builtins").callAttr("__import__", m)
                "ok"
            } catch (t: Throwable) {
                "FAIL: $t"
            }
            line("import $m -> $r")
        }

        val hs = try {
            py.getModule("irisgui._probe").callAttr("describe").toString()
        } catch (t: Throwable) {
            "probe module FAILED: $t"
        }
        line("probe: $hs")

        line("=== PySelfTest end ===")
        val path = File(context.filesDir, "pyselftest.log").apply {
            writeText(out.joinToString("\n"))
        }
        line("written ${path.absolutePath}")
        return out
    }
}
