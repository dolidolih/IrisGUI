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

        out("selinux=${readOr("/sys/fs/selinux/enforce")} ctx=${readOr("/proc/self/attr/current")}")

        // bionic ELF 토이를 filesDir 에 복사 (시스템 읽기는 허용됨). sh(mksh) 는 멀티콜
        // 어절 해석 없이 argv 로 바로 실행되므로 harness 에 알맞다.
        val candidates = listOf("/system/bin/mksh", "/system/bin/sh", "/system/bin/toybox")
        val src = candidates.map(::File).firstOrNull { it.isFile }
            ?: return "FATAL: no mksh/sh/toybox"
        val dst = File(filesDir, "wxbin")
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
        val execOk = dst.setExecutable(true)
        out("src=$src copied=${dst.length()} executable=$execOk")

        // 동일 바이너리·동일 인자: 두 경로는 커널 exec 창만 다르다.
        val sh = if (src.name == "toybox") {
            listOf(dst.absolutePath, "id")
        } else {
            listOf(dst.absolutePath, "-c", "echo WX_OK uid=$(id -u)")
        }
        val linker = if (File("/system/bin/linker64").isFile) "/system/bin/linker64" else "/system/bin/linker"
        run("A-plain-execve", sh, ::out)
        run("B-linker64", listOf(linker, *sh.toTypedArray()), ::out)
        return "done"
    }

    private fun run(tag: String, cmd: List<String>, out: (String) -> Unit) {
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val o = p.inputStream.bufferedReader().readText()
            val code = p.waitFor()
            out("$tag ${if (code == 0) "OK" else "exit=$code"} out='${o.trim()}' cmd=${cmd.joinToString(" ")}")
        } catch (e: Exception) {
            // execve EACCES 는 IOException(error=13 Permission denied) 로 온다.
            out("$tag FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun readOr(path: String): String =
        runCatching { File(path).readText().trim() }.getOrDefault("?")
}
