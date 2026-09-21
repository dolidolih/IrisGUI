package party.qwer.irisgui.scripting

import android.content.Context
import party.qwer.irisgui.RuntimeLog
import java.io.File

/**
 * CodeServer — userland(proot) 안 code-server(=VS Code 서버) 관리.
 *
 * 워크스페이스 루트는 /home/projects (host: filesDir/linux/home/projects). 스크립트
 * 프로젝트와 같은 트리이므로 코드서에서 보이는 폴더가 곧 스크립트 목록이다.
 *
 * 수명주기:
 *   install()     = 리눅스 환경(rootfs/python/code-server) 설치 + code-server 실행.
 *   start()/stop()= guest 루프백(0.0.0.0:DEFAULT_PORT) 바인드. proot 는 host netns 를
 *                   공유하므로 host 루프백에서 도달 가능. proot parent 는 참조로 붙잡는다.
 *   openProject() = code-server 를 특정 프로젝트 main.py 에 포커스해서 (재)기동.
 *
 * 설치는 UserlandRuntime.ensureCodeServer (standalone tar 언팩) 가 담당.
 */
object CodeServer {
    private const val TAG = "CodeServer"

    const val DEFAULT_PORT = UserlandRuntime.DEFAULT_PORT

    data class Result(val ok: Boolean, val message: String)

    enum class State { DISABLED, NOT_INSTALLED, INSTALLING, READY, RUNNING, ERROR }

    data class Status(
        val state: State,
        val installed: Boolean,
        val running: Boolean,
        val port: Int,
        val message: String = ""
    )

    /** host / 외부 접근용 URL 표시. */
    fun lanIp(): String = runCatching {
        java.net.NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<java.net.Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull() ?: "127.0.0.1"

    fun installed(context: Context): Boolean = UserlandRuntime.codeServerInstalled(context)

    /** 코드서버 설치/실행 상태. userland 상태(DISABLED/NOT_INSTALLED/INSTALLING) 를 따라간다. */
    fun status(context: Context, port: Int = DEFAULT_PORT): Status {
        val running = UserlandRuntime.isCodeServerRunning(context)
        val base = UserlandRuntime.status(context)
        return when {
            base.state == UserlandRuntime.State.DISABLED ->
                Status(State.DISABLED, false, false, port, base.message)
            running -> Status(State.RUNNING, true, true, port, "실행 중 :$port")
            installed(context) -> Status(State.READY, true, false, port, "대기 (실행 필요)")
            else -> Status(State.NOT_INSTALLED, false, false, port, base.message)
        }
    }

    // ── 수명주기 ──────────────────────────────────────────────────────────

    private var process: Process? = null
    private var startedPort: Int = 0
    private var startedFocus: String? = null

    /** code-server 기동 인자 (focus 없으면 /home/projects 루트). */
    private fun guestTarget(focusPath: String?): String =
        if (focusPath.isNullOrBlank()) UserlandRuntime.GUEST_PROJECTS else focusPath

    private fun inner(port: Int, focusPath: String?): String =
        "export PATH=" + UserlandRuntime.q("/usr/local/sbin:/usr/local/bin:/usr/sbin:" +
            "/usr/bin:/sbin:/bin") + "\nexport HOME=/root\n" +
            "exec /codeserver/current/bin/code-server --auth none --bind-addr 0.0.0.0:" +
            port + " " + UserlandRuntime.q(guestTarget(focusPath)) + " 2>&1"

    private fun command(context: Context, port: Int, focusPath: String?): Array<String> =
        arrayOf(
            UserlandRuntime.prootPath(context).absolutePath,
            "-0", "-r", UserlandRuntime.rootDir(context).absolutePath,
            "-b", "/proc", "-b", "/dev", "-b", "/sys",
            "-b", UserlandRuntime.projectsHostDir(context).absolutePath +
                ":" + UserlandRuntime.GUEST_PROJECTS,
            "-w", UserlandRuntime.GUEST_PROJECTS,
            "/bin/sh", "-c", inner(port, focusPath)
        )

    /**
     * 리눅스 환경 설치 + code-server 실행. 멱등. UI 의 설치 버튼. blocking.
     * 이미 준비/실행 상태면 provision+start.
     */
    fun install(context: Context): Result {
        val r = UserlandRuntime.provision(context)
        if (!r.ok) return Result(false, r.message)
        return start(context, DEFAULT_PORT, null)
    }

    /** code-server 실행. 같은 포트 + 같은 focus 면 멱등. focusPath 는 프로젝트 main.py. */
    fun start(context: Context, port: Int, focusPath: String? = null): Result {
        if (!UserlandRuntime.prootReady(context)) return Result(false, "리눅스 환경 먼저 설치")
        if (!installed(context)) return Result(false, "code-server 미설치")
        if (process?.isAlive == true && startedPort == port && startedFocus == focusPath &&
            UserlandRuntime.isPortOpen(port)
        ) return Result(true, "이미 실행 중 :$port")
        stop()
        return try {
            val pb = ProcessBuilder(command(context, port, focusPath).toList())
            pb.redirectErrorStream(true)
            pb.redirectOutput(File(UserlandRuntime.rootDir(context), "code-server.log"))
            pb.environment().let { it["PROOT_NO_SECCOMP"] = "1" }
            val p = pb.start()
            process = p; startedPort = port; startedFocus = focusPath
            if (waitForUp(port, 60_000)) {
                RuntimeLog.info(TAG, "code-server 실행 :$port ${focusPath ?: "(root)"}")
                Result(true, "code-server 실행됨 http://127.0.0.1:$port")
            } else Result(false, "기동 타임아웃 — userland/code-server.log 확인")
        } catch (e: Exception) {
            Result(false, "start 실패: " + e.message)
        }
    }

    fun stop() {
        runCatching { process?.destroy() }
        runCatching { process?.waitFor(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        process = null
        startedFocus = null
    }

    /** 프로젝트 편집: code-server 를该项目 폴더 워크스페이스로 연다 (explorer + terminal). */
    fun openProject(context: Context, name: String, port: Int = DEFAULT_PORT): Result {
        if (!File(LinuxScripts.projectsDir(context), name).isDirectory)
            return Result(false, "$name 없음")
        return start(context, port, UserlandRuntime.guestProject(context, name))
    }

    private fun waitForUp(port: Int, timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (UserlandRuntime.isPortOpen(port)) return true
            Thread.sleep(500)
        }
        return UserlandRuntime.isPortOpen(port)
    }
}
