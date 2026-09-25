package party.qwer.irisgui.scripting

import android.content.Context
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.RuntimeLog
import java.io.File

/**
 * LinuxScripts — proot userland 의 home/projects 아래 프로젝트 단위 스크립트 관리.
 *
 * 저장 구조: filesDir/linux/home/projects/<name>/main.py + .venv + .log
 *   (guest 에는 /home/projects/<name>/ 로 바인드. 편집 워크스페이스와 동일.)
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
    /** ISSUE-11: start 가 기록하는 python 시작 pid 파일 (cwd=project 에 상대). */
    internal const val RUN_PID = ".run.pid"
    /** ISSUE-36: 로그 로테이션 — 개시당 ~2MB × (1 + LOG_KEEP) 개까지. */
    private const val LOG_MAX = 2 * 1024 * 1024
    private const val LOG_KEEP = 2
    /** venv 생성 진행 중 로그에 남는 자리표시자. */
    private const val VENV_PROGRESS = "venv preparing"
    /** 프로세스 재시작 등으로 venv 생성이 끊긴 경우 placeholder 를 stale 처리. */
    private const val STALE_AFTER_MS = 30 * 60 * 1000L

    /**
     * ISSUE-22: per-project mutex — start/stop/delete/ensureVenv(및 create 의 venv
     * 스레드)를 직렬화한다. 카드 버튼 + 에디터 bridge 의 동시 실행, venv 생성 경합,
     * 실행 중 삭제를 한꺼번에 막는다. Reentrant 라 start()→ensureVenv() 중첩 호출도
     * 데드록 없다. 락 하나가 venv 설치(수 분)를 직렬화할 수 있으나 UI 는 이미
     * "준비 중" pending 상태를 보여주므로 세매 변경 없음.
     */
    private val projectLocks = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock>()

    private fun <T> withProjectLock(name: String, block: () -> T): T {
        val l = projectLocks.getOrPut(name) { java.util.concurrent.locks.ReentrantLock() }
        l.lock()
        try {
            return block()
        } finally {
            l.unlock()
        }
    }

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
            // venv 생성 스레드의 python/pip 도 cwd 가 프로젝트라 cwd 스캔이면 "실행
            // 중"으로 읽힌다. done 마커가 없으면 무조건 준비 중 — 이 순서가 깨지면
            // recém 생성 카드가 바로 running 배지를 단다.
            // ISSUE-36: 정지된 프로젝트의 채워진 로그는 표시 대상도 아니고 자라도
            // 아무 의미가 없다 — 잠들 때 회전. 실행 중 리네임은 writer fd 를 .log.1 에
            // 붙여버리므로 running 에게는 절대로 하지 않는다.
            if (!running.containsKey(name) && File(dir, LOG).length() >= LOG_MAX)
                withProjectLock(name) {
                    if (!runningPidsByProject(context).containsKey(name)) rotateLogs(dir)
                }
            Script(
                name = name,
                running = running.containsKey(name) && !pending,
                pid = running[name]?.firstOrNull(),
                hasVenv = venv,
                venvPending = pending,
                error = if (!done && !pending) "가상환경 준비 실패 — 편집기 터미널에서 직접 설치하세요"
                else null
            )
        }
    }

    /**
     * 프로젝트 신규 생성. main.py(샘플) 작성 + venv 백그라운드 생성.
     * 이미 있으면 false. blocking — mkdir + 파일 쓰기만 하고 venv 는 별도 스레드.
     */
    fun create(context: Context, name: String): UserlandRuntime.Result =
        withProjectLock(name) { createImpl(context, name) }

    private fun createImpl(context: Context, name: String): UserlandRuntime.Result {
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
    fun delete(context: Context, name: String): UserlandRuntime.Result =
        withProjectLock(name) { deleteImpl(context, name) }

    private fun deleteImpl(context: Context, name: String): UserlandRuntime.Result {
        // ISSUE-22: 살아있는 프로세스 밑의 파일/venv/log 삭제를 막는다 — 정지가
        // 확인되지 않으면 삭제 자체를中止 (유령 respawn + detached .log 방지).
        val s = stopImpl(context, name)
        if (!s.ok)
            return UserlandRuntime.Result(false, "정지 확인 실패로 삭제하지 않음 — 정지 후 재시작 (${s.message})")
        val dir = projectDir(context, name)
        if (!dir.isDirectory) return UserlandRuntime.Result(false, "$name 없음")
        return if (runCatching { dir.deleteRecursively() }.getOrDefault(false)) {
            RuntimeLog.info(TAG, "프로젝트 삭제: $name")
            UserlandRuntime.Result(true, "$name 삭제됨")
        } else UserlandRuntime.Result(false, "삭제 실패")
    }

    /** venv 생성. 멱등: 이미 .venv/가 있고 done 마커 있으면 skip. blocking. */
    fun ensureVenv(context: Context, name: String): UserlandRuntime.Result =
        withProjectLock(name) { ensureVenvImpl(context, name) }

    private fun ensureVenvImpl(context: Context, name: String): UserlandRuntime.Result {
        val dir = projectDir(context, name)
        if (!dir.isDirectory) return UserlandRuntime.Result(false, "$name 없음")
        val venv = File(dir, ".venv")
        if (venv.isDirectory && File(dir, VENV_DONE).exists() &&
            File(venv, "bin/python").isFile
        ) return UserlandRuntime.Result(true, "venv 이미 있음")
        // pip 의 TLS 는 CA 번들이 있어야 동작한다. (base 이미지엔 없음)
        val trust = UserlandRuntime.ensureTrustStore(context)
        if (!trust.ok) return trust
        File(dir, VENV_DONE).delete()
        // 생성 진행 표시 — 로그는 완료 시 덮어쓰므로 진행 중 표식으로 미리 touch.
        File(dir, VENV_LOG).writeText(VENV_PROGRESS + "\n")
        // 파이프 사용 금지: tail 의 exit code 가 pip 를 덮어써서 실패해도
        // .venv.done 이 만들어진다. -q 로 출력만 줄이고 exit code 는 그대로 탄다.
        // --system-site-packages: bionic 레이어는 Termux 자체 빌드 바이너리
        // (python-pillow 등)을 이미 갖고 있으므로 venv가 이를 재사용한다 (소스 컴파일 금지).
        val cmd = "python3 -m venv --system-site-packages .venv 2>&1 && .venv/bin/pip install -q --only-binary :all: irispy-client " +
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
            .onSuccess { if (!it.ok) RuntimeLog.error(TAG, "venv 생성 실패 $name: ${it.message}") }
    }

    /** main.py 실행. 이미 실행 중이면 멱등. blocking. */
    fun start(context: Context, name: String): UserlandRuntime.Result =
        withProjectLock(name) { startImpl(context, name) }

    private fun startImpl(context: Context, name: String): UserlandRuntime.Result {
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
        // ISSUE-36: 수-일 장기 실행이 .log 를 파티션 가득 채우지 않게 — 시작 전에
        // 이미 상한이면 로그를 회전(.log.1/.log.2 로 보존, 전체 다운로드 가능 유지).
        if (File(dir, LOG).length() >= LOG_MAX) rotateLogs(dir)
        // ISSUE-11: .run.pid 에 시작 pid 를 남긴다 (sh exec 는 pid 유지 → python
        // 시작 pid 가 그대로 기록된다). stop/탐지가 정확한 대상으로 참조한다.
        runCatching { File(dir, RUN_PID).delete() }
        val inner = "export IRISGUI_SCRIPT=" + q(name) +
            "\nexport IRISGUI_API_URL=" + q("http://127.0.0.1:" + AppConfig.serverPort) +
            "\necho $$ > " + q(RUN_PID) +
            "\nexec $py ${q(MAIN)} 2>&1 # irisproj=" + name
        val logFile = File(dir, LOG)
        val p = UserlandRuntime.spawnBackground(context, inner,
            UserlandRuntime.guestProject(context, name), logFile)
            ?: return UserlandRuntime.Result(false, "실행 실패 (proot 시작 불가)")
        synchronized(this) { processes[name] = p }
        RuntimeLog.info(TAG, "스크립트 시작: $name")
        return UserlandRuntime.Result(true, "$name 실행됨")
    }

    /**
     * 정지. 대상은 그 프로젝트 dir 를 기준으로 도는 모든 프로세스 — proot wrapper,
     * python, 그리고 wrapper 가 죽고 init 에 떠려 살아남는 orphan python 전부.
     * host kill 은 same uid 라 non-root(NLS) 에서도 별돈 privilege 없는 동작.
     */
    fun stop(context: Context, name: String): UserlandRuntime.Result =
        withProjectLock(name) { stopImpl(context, name) }

    private fun stopImpl(context: Context, name: String): UserlandRuntime.Result {
        synchronized(this) {
            val p = processes.remove(name)
            if (p?.isAlive == true) runCatching { p.destroy() }
        }
        // ISSUE-11: 대상은 정확한 시작 트리 — .run.pid 에 기록된 pid + 정밀 스캔
        // (venv python+main.py, IRISGUI_SCRIPT wrapper) 만. 터미널의 pip/python 은
        // 절대 타깃에 들어가지 않는다. kill → 확인 4 라운드.
        var remaining = pidsOf(context, name)
        var rounds = 0
        while (rounds < 4 && remaining.isNotEmpty()) {
            remaining.forEach { runCatching { killPid(it) } }
            Thread.sleep(200)
            remaining = pidsOf(context, name)
            rounds++
        }
        // 보험: guest pkill sweep — 패턴은 python cmdline 의 main.py 실행만 잡도록
        // 좁혔다. `python -m pip ...`/interactive shell 은 매치되지 않는다.
        if (remaining.isNotEmpty()) {
            val py = UserlandRuntime.guestProject(context, name) + "/\\.venv/bin/python"
            val pat = py + " [^ ]*main\\.py"
            runCatching {
                UserlandRuntime.exec(context, "pkill -9 -f " + q(pat) + " 2>/dev/null; true", 15_000)
            }
            remaining = pidsOf(context, name)
        }
        val ok = remaining.isEmpty()
        if (ok) runCatching { File(projectDir(context, name), RUN_PID).delete() }
        RuntimeLog.info(TAG, if (ok) "스크립트 정지: $name"
            else "스크립트 정지 실패: $name (pids=$remaining)")
        return UserlandRuntime.Result(ok, if (ok) "$name 정지됨"
            else "$name 정지 실패 — 편집기 터미널에서 kill 로 정리 ($remaining)")
    }

    /** 서비스 종료 시 전체 정지. */
    fun stopAll(context: Context) {
        // processes 맵은 앱 재탄생 후 비워진다 — /proc 정밀 스캔의 프로젝트도 함께
        // 정지해야 고독 python 이 reply 파이프라인으로 살아남지 않는다 (ISSUE-22).
        val names = (synchronized(this) { processes.keys.toList() } +
            runningPidsByProject(context).keys).toSet()
        names.forEach { runCatching { stop(context, it) } }
    }

    /**
     * 프로젝트 로그 tail (host 파일). ISSUE-36: 전체 readLines() 재분석(1.5s 폴링의
     * CPU/지연 원인)을 버리고 마지막 ~256KB 만 seek — 로그가 수 MB로 커져도 비용은
     * 표시 창 고정. 통째로 다 읽히는 일은 폴링에서 두 번 다시 없다.
     */
    fun logsFor(context: Context, name: String, limit: Int = 400): List<String> {
        val f = File(projectDir(context, name), LOG)
        if (!f.isFile) return emptyList()
        return runCatching {
            java.io.RandomAccessFile(f, "r").use { raf ->
                val len = raf.length()
                if (len == 0L) return emptyList()
                val take = minOf(len, 256L * 1024L)
                raf.seek(len - take)
                val buf = ByteArray(take.toInt())
                raf.readFully(buf)
                val raw = String(buf, Charsets.UTF_8)
                val lines = if (take < len) raw.lineSequence().drop(1).toList() else raw.lines()
                val body = lines.filter { it.isNotBlank() }
                if (body.size <= limit) body else body.takeLast(limit)
            }
        }.getOrDefault(emptyList())
    }

    /** ISSUE-36: logrotate 식 회전 — .log → .log.1 → .log.2 (KEEP 유지). */
    private fun rotateLogs(dir: File) {
        runCatching {
            for (i in LOG_KEEP downTo 1) {
                val src = if (i == 1) File(dir, LOG) else File(dir, "$LOG.${i - 1}")
                val dst = File(dir, "$LOG.$i")
                if (i == LOG_KEEP && dst.exists()) dst.delete()
                if (src.exists()) src.renameTo(dst)
            }
        }
    }

    /** main.py 원문 (status 표시 정도; 편집은 편집기 화면). */
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
     * 프로젝트별 실행 python pid 집합. ISSUE-11: 패턴/cwd 매칭을 버리고 정확히 —
     *   1) .run.pid 에 기록된 시작 pid (wrapper 가 죽어도 살아남는 orphan python;
     *      /proc cmdline 에 여전히 python 이라 읽혀야만 인정 — pid 재사용 안전장치)
     *   2) /proc 정밀 주사: argv[0] 가 프로젝트 venv python 이고 argv 에 main.py
     *      실행 인자가 있는 프로세스만. (터미널의 `python -m pip install`,
     *      interactive bash, `python -m venv` 생성기 are 전부 제외)
     *   3) IRISGUI_SCRIPT 마커가 있는 시작 proot wrapper cmdline 의 -w 마커.
     * host /proc cwd 심링크 매칭은 interactive shell 도 걸려서 폐기 — cwd 가 아니라
     * cmdline 는 proot 가 host 에 남기므로 orphan 추적에도 충분하다.
     */
    private fun runningPidsByProject(context: Context): Map<String, MutableSet<Int>> {
        val out = HashMap<String, MutableSet<Int>>()
        fun add(name: String, pid: Int) {
            if (name.isNotBlank()) out.getOrPut(name) { mutableSetOf() }.add(pid)
        }

        // 1) 기록된 시작 pid (pidfile 재사용 방지: /proc活着 + cmdline python 확인)
        projectsDir(context).listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.forEach { d ->
                recordedPids(File(d, RUN_PID)).forEach { pid ->
                    if (cmdlineOf(pid).contains("python")) add(d.name, pid)
                }
            }

        val guestBase = if (UserlandRuntime.backend(context) ==
            UserlandRuntime.Backend.BIONIC
        ) UserlandRuntime.projectsHostDir(context).absolutePath + "/"
        else UserlandRuntime.GUEST_PROJECTS + "/"
        val venvRe = Regex("home/projects/([^ /]+)/\\.venv/bin/python")
        val dirs = runCatching {
            File("/proc").listFiles { f -> f.isDirectory && f.name.all { c -> c.isDigit() } }
        }.getOrNull() ?: return out
        for (d in dirs) {
            val pid = d.name.toIntOrNull() ?: continue
            val argv = argvOf(pid)
            if (argv.isEmpty()) continue
            val cmd = argv.joinToString(" ")

            // 3) 시작 wrapper 표식 — 우리 cmd 만 IRISGUI_SCRIPT 를 싣는다.
            if (cmd.contains("IRISGUI_SCRIPT")) {
                val fromW = Regex("-w " + Regex.escape(guestBase) + "([^ /]+)")
                    .find(cmd)?.groupValues?.getOrNull(1)
                    ?: Regex("irisproj=([^\\s']+)").find(cmd)?.groupValues?.getOrNull(1)
                if (fromW != null) add(fromW.trim('/').substringBefore('/'), pid)
                continue
            }

            // 2) venv python + main.py 실행 인자 정확 일치.
            val exe = argv.first()
            if (!exe.contains("python")) continue
            val m = venvRe.find(exe) ?: continue
            if (argv.none { it.trim('\'') == MAIN }) continue
            add(m.groupValues[1], pid)
        }
        return out
    }

    /** .run.pid 파일의 살아있는 pid 목록. */
    private fun recordedPids(pidFile: File): Set<Int> {
        val pids = runCatching {
            pidFile.readLines().mapNotNull { it.trim().toIntOrNull() }
        }.getOrDefault(emptyList())
        return pids.filter { it > 0 && File("/proc/$it").isDirectory }.toSet()
    }

    private fun cmdlineOf(pid: Int): String = runCatching {
        String(File("/proc/$pid/cmdline").readBytes(), Charsets.ISO_8859_1)
            .replace('\u0000', ' ').trim()
    }.getOrDefault("")

    private fun argvOf(pid: Int): List<String> = runCatching {
        String(File("/proc/$pid/cmdline").readBytes(), Charsets.ISO_8859_1)
            .split('\u0000').filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    /** 프로젝트에 딸린 실행 pid 전부 — proot wrapper + python + 고독(orphan) 포함. */
    fun pidsOf(context: Context, name: String): Set<Int> =
        runningPidsByProject(context)[name] ?: emptySet()

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
        # irispy-client 를 사용합니다. 필요하면 편집기 터미널에서
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
