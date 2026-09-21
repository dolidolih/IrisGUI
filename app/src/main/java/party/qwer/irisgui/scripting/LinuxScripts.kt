package party.qwer.irisgui.scripting

import android.content.Context
import party.qwer.irisgui.RuntimeLog
import java.io.File

/**
 * LinuxScripts — proot userland 의 home/projects 아래 프로젝트 단위 스크립트 관리.
 *
 * 저장 구조: filesDir/linux/home/projects/<name>/main.py + .venv + .log
 *   (guest 에는 /home/projects/<name>/ 로 바인드. code-server 워크스페이스와 동일.)
 *
 * 수명주기:
 *   create(name) = 프로젝트 디렉터리 + main.py(샘플) + 백그라운드 venv 생성.
 *   start/stop   = proot 안에서 `.venv/bin/python main.py` 를 cwd=프로젝트로 백그라운드.
 *                  proot 는 parent 종료 시 ptrace 중인 자식을 죽이므로 이 object 가
 *                  proot Process 를 참조로 붙잡는다.
 *   statusAll    = /proc/<pid>/cwd 가 projects/<name> 인 python 프로세스 스캔.
 *                  실행 추적을 host 프로세스 목록 기준(cwd)으로 한다.
 *
 * 모든 프로젝트의 main.py 는 IRISGUI_API_URL(이 앱의 http/ws 포트) 로 iris 서버에
 * 접속하므로 같은 이벤트를 같은 시기에 수신한다.
 */
object LinuxScripts {
    private const val TAG = "LinuxScripts"
    const val MAIN = "main.py"
    private const val LOG = ".log"
    private const val VENV_LOG = ".venv.log"
    private const val VENV_DONE = ".venv.done"
    /** venv 생성 진행 중 로그에 남는 자리표시자. */
    private const val VENV_PROGRESS = "venv preparing"
    /** 프로세스 재시작 등으로 venv 생성이 끊긴 경우 placeholder 를 stale 처리. */
    private const val STALE_AFTER_MS = 30 * 60 * 1000L

    /** 프로젝트 + 런타임 상태 스냅샷. */
    data class Script(
        val name: String,
        val running: Boolean,
        val pid: Int?,
        val hasVenv: Boolean,
        val venvPending: Boolean,
        val error: String?
    )

    /** host 의 프로젝트 부모 디렉터리. */
    fun projectsDir(context: Context): File = UserlandRuntime.projectsHostDir(context)

    private fun projectDir(context: Context, name: String): File =
        File(projectsDir(context), name)

    /**
     * 환경 설치 직후 기본 프리로드 프로젝 만든다. 없을 때만 (멱등).
     * 이름은 항상 main, main.py 는 irispy-client 샘플(!hhhi → hey from irisgui!).
     */
    fun bootstrapDefault(context: Context) {
        if (list(context).isEmpty() && UserlandRuntime.ready(context)) {
            runCatching { create(context, "main") }
        }
    }

    /** 프로젝트 이름 검증. guest 셸에 들어가지 않게 safe set 만 허용. */
    fun isValidName(name: String): Boolean =
        name.length in 1..64 && name.all { it.isLetterOrDigit() || it == '_' || it == '-' } &&
            !name.startsWith(".")

    /** 저장된 프로젝트 이름 목록. */
    fun list(context: Context): List<String> =
        projectsDir(context).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.map { it.name }?.sorted() ?: emptyList()

    /** list + /proc cwd 스캔을 합친 스냅샷. */
    fun statusAll(context: Context): List<Script> {
        val running = runningPidsByProject(context)
        return list(context).map { name ->
            val dir = projectDir(context, name)
            val venv = File(dir, ".venv").isDirectory
            val done = File(dir, VENV_DONE).exists()
            val logTxt = runCatching { File(dir, VENV_LOG).readText() }.getOrDefault("")
            // 자리표시자만 담긴 로그 + done 없음 = 생성 진행 중. 실제 출력이 남았는데
            // done 이 없으면 생성 실패 -> error 로 띄워 재시작을 안내한다.
            val stale = File(dir, VENV_LOG).lastModified() <
                System.currentTimeMillis() - STALE_AFTER_MS
            val pending = !done && (logTxt.isBlank() || logTxt.contains(VENV_PROGRESS)) && !stale
            Script(
                name = name,
                running = running.containsKey(name),
                pid = running[name],
                hasVenv = venv,
                venvPending = pending,
                error = if (!done && !pending) "가상환경 준비 실패 — code-server 터미널에서 직접 설치하세요"
                else null
            )
        }
    }

    /**
     * 프로젝트 신규 생성. main.py(샘플) 작성 + venv 백그라운드 생성.
     * 이미 있으면 false. blocking — mkdir + 파일 쓰기만 하고 venv 는 별도 스레드.
     */
    fun create(context: Context, name: String): UserlandRuntime.Result {
        if (!isValidName(name)) return UserlandRuntime.Result(false, "사용할 수 없는 이름")
        val dir = projectDir(context, name)
        if (dir.exists()) return UserlandRuntime.Result(false, "$name 이미 존재")
        if (!UserlandRuntime.ready(context))
            return UserlandRuntime.Result(false, "리눅스 환경 먼저 설치")
        if (!dir.mkdirs()) return UserlandRuntime.Result(false, "프로젝트 생성 실패")
        File(dir, MAIN).writeText(SAMPLE_MAIN)
        Thread { createVenv(context, name) }.start()
        RuntimeLog.info(TAG, "프로젝트 생성: $name")
        return UserlandRuntime.Result(true, "$name 생성됨 (가상환경 생성 중)")
    }

    /** 프로젝트 삭제. 실행 중이면 먼저 정지. */
    fun delete(context: Context, name: String): UserlandRuntime.Result {
        stop(context, name)
        val dir = projectDir(context, name)
        if (!dir.isDirectory) return UserlandRuntime.Result(false, "$name 없음")
        return if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
            RuntimeLog.info(TAG, "프로젝트 삭제: $name")
            UserlandRuntime.Result(true, "$name 삭제됨")
        } else UserlandRuntime.Result(false, "삭제 실패")
    }

    /** venv 생성. 멱등: 이미 .venv/가 있고 done 마커 있으면 skip. blocking. */
    fun ensureVenv(context: Context, name: String): UserlandRuntime.Result {
        val dir = projectDir(context, name)
        if (!dir.isDirectory) return UserlandRuntime.Result(false, "$name 없음")
        val venv = File(dir, ".venv")
        if (venv.isDirectory && File(dir, VENV_DONE).exists() &&
            File(venv, "bin/python").isFile
        ) return UserlandRuntime.Result(true, "venv 이미 있음")
        File(dir, VENV_DONE).delete()
        // 생성 진행 표시 — 로그는 완료 시 덮어쓰므로 진행 중 표식으로 미리 touch.
        File(dir, VENV_LOG).writeText(VENV_PROGRESS + "\n")
        // 파이프 사용 금지: tail 의 exit code 가 pip 를 덮어써서 실패해도
        // .venv.done 이 만들어진다. -q 로 출력만 줄이고 exit code 는 그대로 탄다.
        val cmd = "python3 -m venv .venv 2>&1 && .venv/bin/pip install -q irispy-client " +
            "2>&1 && touch .venv.done"
        val log = File(dir, VENV_LOG)
        val r = UserlandRuntime.exec(context, cmd, 1_800_000,
            UserlandRuntime.guestProject(context, name))
        log.writeText(r.output)
        return if (r.exitCode == 0 && File(dir, VENV_DONE).exists()) {
            RuntimeLog.info(TAG, "venv 준비 완료: $name")
            UserlandRuntime.Result(true, "$name 가상환경 준비 완료")
        } else {
            RuntimeLog.error(TAG, "venv 생성 실패 $name: ${r.output.take(200)}")
            UserlandRuntime.Result(false, "venv 생성 실패: " + r.output.take(300))
        }
    }

    /** 백그라운드 venv 생성 스레드 (ensureVenv 는 blocking). */
    private fun createVenv(context: Context, name: String) {
        runCatching { ensureVenv(context, name) }
            .onFailure { RuntimeLog.error(TAG, "venv 스레드 실패 $name: ${it.message}") }
    }

    /** main.py 실행. 이미 실행 중이면 멱등. blocking. */
    fun start(context: Context, name: String): UserlandRuntime.Result {
        val dir = projectDir(context, name)
        if (!dir.isDirectory) return UserlandRuntime.Result(false, "$name 없음")
        if (!UserlandRuntime.ready(context))
            return UserlandRuntime.Result(false, "리눅스 환경 먼저 설치")

        val running = runningPidsByProject(context)
        if (running.containsKey(name)) return UserlandRuntime.Result(true, "$name 실행 중")

        // venv 생성이 백그라운드에서 진행 중일 수 있다. python 바이너리가 없으면 여기서 대기.
        val py = venvBin(context, name)
        if (!File(dir, ".venv/bin/python").isFile) {
            val r = ensureVenv(context, name)
            if (!r.ok) return r
        }
        val inner = "exec $py ${q(MAIN)} 2>&1"
        val logFile = File(dir, LOG)
        val p = UserlandRuntime.spawnBackground(context, inner,
            UserlandRuntime.guestProject(context, name), logFile)
            ?: return UserlandRuntime.Result(false, "실행 실패 (proot 시작 불가)")
        synchronized(this) { processes[name] = p }
        RuntimeLog.info(TAG, "스크립트 시작: $name")
        return UserlandRuntime.Result(true, "$name 실행됨")
    }

    /** 정지: host 프로세스 kill + proot 안 잔여 프로세스 pattern kill. */
    fun stop(context: Context, name: String): UserlandRuntime.Result {
        var stopped = false
        synchronized(this) {
            val p = processes.remove(name)
            if (p?.isAlive == true) { runCatching { p.destroy() }; stopped = true }
        }
        val pid = runningPidsByProject(context)[name]
        if (pid != null) {
            runCatching { killPid(pid) }
            stopped = true
        }
        // proot destroy 와 무관하게 살아남을 수 있는 잔여 python 정리. guest venv
        // python 경로의 첫 글자만 클래스 표기로 바꿔 pkill/sh 자신의 cmdline 은
        // 매칭되지 않게 한다.
        val guestPy = UserlandRuntime.guestProject(context, name) + "/.venv/bin/python"
        val pat = "[h]" + guestPy.substring(1)
        runCatching {
            UserlandRuntime.exec(context, "pkill -9 -f " + q(pat) + " 2>/dev/null; true", 15_000)
        }
        return if (stopped) {
            RuntimeLog.info(TAG, "스크립트 정지: $name")
            UserlandRuntime.Result(true, "$name 정지됨")
        } else UserlandRuntime.Result(true, "$name 정지 (이미 멈춤)")
    }

    /** 서비스 종료 시 전체 정지. */
    fun stopAll(context: Context) {
        synchronized(this) { processes.keys.toList() }.forEach {
            runCatching { stop(context, it) }
        }
    }

    /** 프로젝트 로그 tail (host 파일). */
    fun logsFor(context: Context, name: String, limit: Int = 400): List<String> {
        val f = File(projectDir(context, name), LOG)
        if (!f.isFile) return emptyList()
        val all = f.readLines().filter { it.isNotBlank() }
        return if (all.size <= limit) all else all.takeLast(limit)
    }

    /** main.py 원문 (UI 편집용 아니고 status 표시 정도). code-server 가 편집한다. */
    fun readMain(context: Context, name: String): String? =
        File(projectDir(context, name), MAIN).takeIf { it.isFile }?.readText()

    /** main.py 를 새로 쓸 필요 없이 존재 확인만. create 시 샘플을 미리 심는다. */
    fun writeMain(context: Context, name: String, source: String): UserlandRuntime.Result {
        val dir = projectDir(context, name)
        if (!dir.isDirectory) return UserlandRuntime.Result(false, "$name 없음")
        return try {
            File(dir, MAIN).writeText(source)
            UserlandRuntime.Result(true, "저장됨")
        } catch (e: Exception) {
            UserlandRuntime.Result(false, e.message ?: "저장 실패")
        }
    }

    // ── 내부 ──────────────────────────────────────────────────────────────

    private val processes = HashMap<String, Process>()

    private fun venvBin(context: Context, name: String): String =
        UserlandRuntime.guestProject(context, name) + "/.venv/bin/python"

    /**
     * /proc/<pid>/cwd 가 filesDir/linux/home/projects/<name> 아래인 python 프로세스
     * 스캔. proot 는 host pid ns 를 그대로 쓰기 때문에 host /proc 에서 바로 보인다.
     * cwd 는 바인드 경로(/home/projects/<name>) 도 host real path 도 아니다: proot 는
     * ptrace 로 chdir 시 guest 경로를 host real path 로 매핑해서 실행한다.
     */
    private fun runningPidsByProject(context: Context): Map<String, Int> {
        val base = UserlandRuntime.projectsHostDir(context).canonicalFile.absolutePath + "/"
        val out = HashMap<String, Int>()
        val dirs = runCatching {
            File("/proc").listFiles { f -> f.isDirectory && f.name.all { c -> c.isDigit() } }
        }.getOrNull() ?: return out
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val cmd = runCatching {
                String(File(d, "cmdline").readBytes(), Charsets.ISO_8859_1)
                    .replace('\u0000', ' ').trim()
            }.getOrDefault("")
            if (cmd.isBlank() || !cmd.contains("python")) continue
            val cwd = runCatching {
                java.nio.file.Files.readSymbolicLink(d.toPath().resolve("cwd")).toString()
            }.getOrNull()
            if (cwd.isNullOrEmpty()) continue
            // proot 의 chdir 경로변환에 따라 host real path 로 떨어지지만, guest
            // bind 경로 그대로 나타날 수도 있어 둘 다 허용.
            val real = runCatching { File(cwd).canonicalFile.absolutePath }.getOrDefault(cwd)
            val rest = when {
                real.startsWith(base) -> real.substringAfter(base)
                real.startsWith(UserlandRuntime.GUEST_PROJECTS + "/") ->
                    real.substringAfter(UserlandRuntime.GUEST_PROJECTS + "/")
                else -> continue
            }
            val name = rest.substringBefore('/')
            if (name.isNotBlank()) out.putIfAbsent(name, pid)
        }
        return out
    }

    private fun killPid(pid: Int) {
        // /proc/<pid>/task/*/kill 로 시그널을 보낼 권한은 same uid 라 존재.
        val cmd = listOf("/system/bin/kill", "-9", pid.toString())
        ProcessBuilder(cmd).start().waitFor(5_000, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * 프리로드 샘플. 모든 메시지에 반응하지 않는다 — "!hhhi" 명령만 잡아서
     * "hey from irisgui!" 라고 답한다.
     */
    private val SAMPLE_MAIN = """
        # main.py — IrisGUI 스크립트
        #
        # irispy-client 를 사용합니다. 필요하면 code-server 터미널에서
        # `pip install <패키지>` 로 추가로 설치하세요.
        #
        # 주의: reply() 는 실제 대화방에 메시지가 전송됩니다.
        #   필요할 때만 사용하세요.
        import os
        from iris.bot import Bot

        iris_url = os.environ.get("IRISGUI_API_URL", "127.0.0.1:3000")
        bot = Bot(iris_url)


        @bot.on_event("message")
        def on_message(ctx):
            # 모든 메시지에 응답하지 않습니다. "!hhhi" 일 때만 인사합니다.
            if ctx.message.command == "!hhhi":
                ctx.reply("hey from irisgui!")


        if __name__ == "__main__":
            bot.run()
    """.trimIndent() + "\n"

    /** main.py 가 없거나 비어있으면 샘플을 넣는다 (pre-load 보장). */
    fun ensureSampleMain(context: Context, name: String) {
        val f = File(projectDir(context, name), MAIN)
        if (!f.isFile || f.readText().isBlank()) {
            f.parentFile?.mkdirs()
            f.writeText(SAMPLE_MAIN)
        }
    }
}
