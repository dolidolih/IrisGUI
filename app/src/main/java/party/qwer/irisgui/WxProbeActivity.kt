package party.qwer.irisgui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import java.io.File

/**
 * WxProbe — 일회성 실측 하네스 (W^X 연구 판정용). 추후 제거 예정.
 *
 * 목적: targetSdk 35 앱 도메인(untrusted_app)에서
 *   (A) execve(filesDir/<binary>)        → W^X 로 EACCES 예상
 *   (B) execve("/system/bin/linker64", filesDir/<binary>) → AOSP Crashpad 규칙으로 허용 예상
 * 가 삼성 OneUI(Android 16)에서 실제로 성립하는지 확인한다. 둘 다 동일 프로세스에서
 * 실행하므로(=동일 SELinux 도메인) run-as 셸처럼 run_as_app 도메인의 예외에 오염되지 않는다.
 *
 * 실행: adb shell am start -n party.qwer.irisgui/.WxProbeActivity
 * 결과: adb logcat -d -s WxProbe:V
 */
class WxProbeActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            val r = runCatching { probe() }.getOrElse { "probe threw: $it" }
            Log.i("WxProbe", "==== summary: $r")
            runCatching { finish() }
        }.start()
    }

    private fun probe(): String {
        val log = StringBuilder()
        fun out(s: String) {
            Log.i("WxProbe", s)
            log.append(s).append('\n')
        }

        out("selinux=${readOr("/proc/self/attr/current")}")

        // bionic-호환 PIE 바이너리 토이를 filesDir 에 복사 (시스템 읽기는 허용됨).
        val src = File("/system/bin/toybox")
        val dst = File(filesDir, "wxbox")
        if (!src.isFile) return "FATAL: no /system/bin/toybox"
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        val execOk = dst.setExecutable(true)
        out("copied=${dst.length()} bytes executable=$execOk")

        run("A-plain-execve", listOf(dst.absolutePath, "id"), ::out)
        run("B-linker64", listOf("/system/bin/linker64", dst.absolutePath, "id"), ::out)

        // 참고: linker 가 실제 execve 된 것이 아니므로 comm 은 linker64 로 찍힌다.
        runCatching {
            val p = ProcessBuilder("/system/bin/sh", "-c", "cat /proc/self/comm")
                .redirectErrorStream(true).start()
            out("self-comm=${p.inputStream.bufferedReader().readText().trim()}")
        }
        return "done"
    }

    private fun run(tag: String, cmd: List<String>, out: (String) -> Unit) {
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val o = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            out("$tag OK exit=$code out='${o.trim()}'")
        } catch (e: Exception) {
            // execve EACCES 는 IOException(error=13 Permission denied) 로 온다.
            out("$tag FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readOr(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("?")
}
