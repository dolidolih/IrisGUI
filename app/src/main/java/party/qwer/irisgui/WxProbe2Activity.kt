package party.qwer.irisgui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * WxProbe2 — 판정 단계 2 (W^X 연구). redroid(x86_64) 1 차 판정에서 추가 사실이 드러났다:
 *   - bionic linker 의 run-program 경로는 ET_DYN(PIE) 만 허용. ET_EXEC(static non-PIE)
 *     proot/x86 python 은 "unexpected e_type: 2" 로 거부.
 *   - proot 는 execve 를 스스로/loader 로 우회하지 않는다 → proot 내 첫 guest exec 는
 *     host 경로 execve 그대로라 enforcing 에서 EACCES 예상.
 * arm64 실기기(S26U)에서는 rootfs 바이너리 대부분이 PIE 로 바뀌므로 결과가 달라진다.
 * 아래를 실기기에서 확인하는 것이 목적이다.
 *
 *   E1: ELF e_type 스냅샷 — proot / rootfs sh / python3 가 ET_DYN 인지
 *   P1: linker64 로 proot --help        → proot PIE 면 기동, ET_EXEC 이면 거부
 *   P2: linker64 로 glibc sh/python 구동 → bionic linker 가 glibc ELF 를 프로그램 구동
 *       ("main 심블" or inter-loader 방식) 가능한가. redroid 에서는 ET_EXEC 로 이미 거부됨.
 *   P3: proot → 첫 guest execve EACCES 재현 (enforcing 실측)
 *
 * 제거 예정 (리서치 전용).
 */
class WxProbe2Activity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            runCatching { probe() }
                .onFailure { Log.i("WxProbe2", "probe threw: $it") }
                .also { Log.i("WxProbe2", "==== summary done") }
            runCatching { finish() }
        }.start()
    }

    private fun rootDir(): File = File(filesDir, "linux")
    private val linker =
        if (File("/system/bin/linker64").isFile) "/system/bin/linker64" else "/system/bin/linker"

    /** ELF e_type: 2=ET_EXEC(non-PIE), 3=ET_DYN(PIE/DSO). */
    private fun etype(f: File): String = try {
        val b = ByteArray(20)
        f.inputStream().use { it.read(b) }
        val v = ByteBuffer.wrap(b, 16, 2).order(ByteOrder.LITTLE_ENDIAN).short
        when (v.toInt()) {
            2 -> "ET_EXEC(non-PIE)"
            3 -> "ET_DYN(PIE)"
            else -> "type=$v"
        }
    } catch (e: Exception) { "?" }

    private fun abiLibDir(): String = when (android.os.Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a" -> "aarch64-linux-gnu"
        "x86_64" -> "x86_64-linux-gnu"
        "armeabi-v7a" -> "arm-linux-gnueabihf"
        else -> "x86_64-linux-gnu"
    }

    private fun probe() {
        val proot = File(rootDir(), ".proot")
        val sh = listOf(File(rootDir(), "bin/sh"), File(rootDir(), "usr/bin/sh")).firstOrNull { it.isFile }
        val py = listOf(File(rootDir(), "usr/bin/python3"), File(rootDir(), "usr/bin/python3.12")).firstOrNull { it.isFile }
        if (proot.isFile) Log.i("WxProbe2", "E1 proot=${etype(proot)} sh=${sh?.let { etype(it) }} py=${py?.let { etype(it) }}")

        if (!proot.isFile) {
            Log.i("WxProbe2", "P0: proot not installed — provision 후 재시도")
            return
        }

        run("P1-proot-via-linker", listOf(linker, proot.absolutePath, "--help"), 2)

        if (sh != null) run("P2a-glibcSh-via-linker", listOf(linker, sh.absolutePath, "-c", "echo WX_OK2a"), 3)
        if (py != null) run("P2b-python-via-linker", listOf(linker, py.absolutePath, "-c", "print('WX_OK2b')"), 3)
        // bionic linker 는 ET_DYN 을 dlopen 경로로 취급해 의존성(.so) 을 자기 네임스페이스에서
        // 본다. -E LD_LIBRARY_PATH 로 guest lib 를 주입하면 glibc ELF 도 구동되는지 확인.
        // bionic linker 는 ET_DYN 을 main executable 로 적재하면서 의존성을
        // parse_LD_LIBRARY_PATH(자신에게 넘어온 env) 에서 해결한다 → env 에 guest lib 를
        // 심으면 glibc ELF 도 main 으로 구동되는가. (실패 시 undefined sym/segv 예상)
        if (sh != null) {
            val lp = "${rootDir().absolutePath}/lib:" +
                "${rootDir().absolutePath}/lib/${abiLibDir()}:" +
                "${rootDir().absolutePath}/usr/lib:${rootDir().absolutePath}/usr/lib/${abiLibDir()}"
            run("P2c-glibcSh-envpath", listOf(linker, sh.absolutePath, "-c", "echo WX_OK2c"), 4, mapOf("LD_LIBRARY_PATH" to lp))
        }
        if (py != null) {
            val lp = "${rootDir().absolutePath}/usr/lib:${rootDir().absolutePath}/lib:${rootDir().absolutePath}/usr/lib/${abiLibDir()}"
            run("P2d-python-envpath", listOf(linker, py.absolutePath, "-c", "print('WX_OK2d')"), 4, mapOf("LD_LIBRARY_PATH" to lp))
        }

        run("P3a-proot-exec-true",
            listOf(linker, proot.absolutePath, "-0", "-r", rootDir().absolutePath, "/bin/true"), 5)
        run("P3b-proot-exec-echo",
            listOf(linker, proot.absolutePath, "-0", "-r", rootDir().absolutePath, "/bin/sh", "-c", "echo WX_OK3"), 5)
    }

    private fun run(tag: String, cmd: List<String>, head: Int, env: Map<String, String> = emptyMap()) {
        val t0 = System.currentTimeMillis()
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).apply {
                env.forEach { (k, v) -> environment()[k] = v }
            }.start()
            val o = p.inputStream.bufferedReader().lineSequence().take(50).joinToString("\n")
            val code = p.waitFor()
            val lines = o.trim().lines().take(head).joinToString(" | ")
            Log.i("WxProbe2", "$tag ${if (code == 0) "OK" else "exit=$code"} ${System.currentTimeMillis() - t0}ms out='$lines'")
        } catch (e: Exception) {
            Log.i("WxProbe2", "$tag FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
