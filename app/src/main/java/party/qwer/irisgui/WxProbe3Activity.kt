package party.qwer.irisgui

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import java.io.File

/**
 * WxProbe3 — bionic runtime(Option A) 판정:
 *   A1: 앱 도메인에서 files/bionic python 직접 execve   → enforcing 에서 EACCES 예상
 *   A2: linker64 경유 python 실행                        → bionic PIE 이므로 성공 예상
 *   A3: linker64 경유 python 내부 exec (subprocess sh)   → 내부 exec 가 W^X 를 넘는가
 *   A4: python -m pip --version                          → ensurepip wheels 동작 확인
 *   A5: venv 생성 + venv/bin/python 실행(linker64)      → sys.executable/launcher 회피 확인
 *   A6: pip install pure wheel(six)                      → pip flow 실측
 */
class WxProbe3Activity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Thread {
            runCatching { probe() }
                .onFailure { Log.i("WxProbe3", "threw: $it") }
                .also { Log.i("WxProbe3", "==== done") }
            runCatching { finish() }
        }.start()
    }

    private val linker = if (File("/system/bin/linker64").isFile) "/system/bin/linker64" else "/system/bin/linker"

    private fun probe() {
        val prefix = File(filesDir, "bionic")
        val py = File(prefix, "usr/bin/python3.14")
        if (!py.isFile) { Log.i("WxProbe3", "no $py — push 먼저"); return }

        val envBase = mapOf(
            "LD_LIBRARY_PATH" to File(prefix, "usr/lib").absolutePath,
            "HOME" to prefix.absolutePath,
            "TMPDIR" to cacheDir.absolutePath,
            "PATH" to prefix.absolutePath + "/usr/bin:/system/bin",
            "PREFIX" to prefix.absolutePath
        )

        run("A1-direct-execve", listOf(py.absolutePath, "--version"), envBase, 2)
        run("A2-linker-python", listOf(linker, py.absolutePath, "-c",
            "import sys,ssl,sqlite3; print('OK', sys.version.split()[0])"), envBase, 3)
        run("A3-internal-exec", listOf(linker, py.absolutePath, "-c",
            "import subprocess,sys; r=subprocess.run([sys.executable,'-c','print(1+1)'],capture_output=True,text=True,env={'LD_LIBRARY_PATH':'" +
                File(prefix, "usr/lib").absolutePath + "'}); print('SUB', r.returncode, r.stdout.strip(), r.stderr.strip()[:80])"), envBase, 3)
        run("A4-pip-version", listOf(linker, py.absolutePath, "-m", "pip", "--version"), envBase, 2)
        run("A5-venv", listOf(linker, py.absolutePath, "-c",
            "import venv.run,os,sys; venv.run.main(['--without-pip','" + cacheDir.absolutePath + "/v1']); print('VENV_MADE')"
        ), envBase, 3)
        run("A5b-venv-exec", listOf(linker, cacheDir.absolutePath + "/v1/bin/python", "-c",
            "import sys; print('VENV_OK', sys.prefix, sys.executable)"), envBase, 2)
        run("A6-pip-install", listOf(linker, py.absolutePath, "-m", "pip", "install", "--quiet",
            "--no-warn-script-location", "six"), envBase, 4)
        run("A6b-import-six", listOf(linker, py.absolutePath, "-c", "import six; print('SIX', six.__version__)"), envBase, 2)
    }

    private fun run(tag: String, cmd: List<String>, env: Map<String, String>, head: Int) {
        val t0 = System.currentTimeMillis()
        try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).apply {
                env.forEach { (k, v) -> environment()[k] = v }
            }.start()
            val o = p.inputStream.bufferedReader().lineSequence().take(80).joinToString("\n")
            val code = p.waitFor()
            Log.i("WxProbe3", "$tag ${if (code == 0) "OK" else "exit=$code"} ${System.currentTimeMillis() - t0}ms :: " +
                o.trim().lines().take(head).joinToString(" | ").take(400))
        } catch (e: Exception) {
            Log.i("WxProbe3", "$tag FAIL ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
