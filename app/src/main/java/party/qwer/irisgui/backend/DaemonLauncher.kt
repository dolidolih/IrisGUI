package party.qwer.irisgui.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import party.qwer.irisgui.AppConfig

/**
 * DaemonLauncher — app_process 데몬의 기기 내 자율 기동/정지 (host PC 불필요)
 *
 * 루트 확보 우선순위 (외부 PC 없이 기기 안에서만 루트 획득):
 *
 *   PATH 1 — Magisk/앱 shell 허용 su (인-프로세스 exec):
 *     매직스크(Magisk)가 루팅된 기기, 또는 앱 shell을 허용하도록 설정된 기기라면
 *     Runtime.exec("su ...")로 앱 프로세스에서 바로 루트 셸을 얻을 수 있다.
 *     adbd·TCP 연결을 전혀 쓰지 않는 가장 매끄러운 경로.
 *
 *   PATH 2 — 기기 내 로컬 ADB:
 *     앱이 adbd(TCP 127.0.0.1:5555)에 ADB 클라이언트로 직접 연결해 셸 세션을 연다.
 *     셸은 adbd 자식 도메인이므로 거기서 su를 수행하면 host PC의 `adb shell su ...`와 동일.
 *     redroid(userdebug/debuggable)에서는 셸 자체가 uid=0인 경우가 많아 su 불필요.
 *
 * su 방언 주의: AOSP debug su(redroid)는 `su -c <cmd>`를 "invalid uid/gid '-c'"로 거부하고
 * `su 0 <cmd>`만 허용한다. 매직스크 su는 `su -c`를 허용한다. 따라서 방언은 런타임에
 * `su <variant> id`를 probe해 uid=0을 얻는 형식을 택한다. 우선순위는 검증된 `su 0`.
 */
object DaemonLauncher {

    enum class FailureReason { NO_ROOT, NO_ADBD, AUTH_FAILED, EXEC_FAILED, START_TIMEOUT }

    sealed class StartResult {
        object AlreadyRunning : StartResult()
        object Started : StartResult()
        data class Failed(val reason: FailureReason, val detail: String) : StartResult()
    }

    private const val MAIN_CLASS = "party.qwer.irisgui.Main"

    /** uid=0을 만드는 형식을 런타임에 선택하기 위한 `su <variant>` 후보. */
    private val SU_VARIANTS = listOf("0", "-c", "root")

    /** PATH1(인-프로세스) 방언 probe 타임아웃 — su 미설치/redroid에서는 즉시 실패해야 PATH2로 넘어간다. */
    private const val SU_PROBE_TIMEOUT_MS = 2_000L

    /** 데몬 시작 커맨드 — 로그는 [logPath]로 리다이렉트(진단용). */
    /**
     * 루트 셸이 즉시 종료되어도 살아남도록 이중 fork(set sid + 백그라운드)로 탈출시킨다.
     * LocalAdb 셸은 pty가 없어 세션 종료 시 자식이 정리되므로 `setsid prog &` 만으로는 부족하다.
     */
    private fun daemonStartCommand(apkPath: String, logPath: String): String =
        "setsid sh -c 'CLASSPATH=$apkPath app_process / $MAIN_CLASS ${AppConfig.serverPort} " +
            ">$logPath 2>&1 </dev/null &'"

    /** 데몬 기동. 이미 기동 중이면 [StartResult.AlreadyRunning]. */
    suspend fun startDaemon(context: Context): StartResult = withContext(Dispatchers.IO) {
        if (AdbProcessClient.queryStatus() != null) {
            println("DaemonLauncher: daemon already running")
            return@withContext StartResult.AlreadyRunning
        }

        val apkPath = context.applicationInfo.sourceDir
        val logPath = File(context.filesDir, "daemon.log").absolutePath

        // ── PATH 1: 인-프로세스 su (Magisk / 앱 shell 허용) ────────────────
        val inProc = runCatching { tryStartViaInProcessSu(apkPath, logPath) }.getOrNull()
        if (inProc != null) return@withContext inProc

        // ── PATH 2: LocalAdb 셸 세션 ──────────────────────────────────────
        if (AdbProcessClient.queryStatus() != null) {
            println("DaemonLauncher: daemon came up late (PATH1), returning Started")
            return@withContext StartResult.Started
        }
        val adb = LocalAdb(context)
        val connFailure = adb.connect()
        when (connFailure) {
            is LocalAdb.Failure.NoAdbd ->
                return@withContext StartResult.Failed(FailureReason.NO_ADBD, connFailure.detail)
            is LocalAdb.Failure.AuthFailed ->
                return@withContext StartResult.Failed(FailureReason.AUTH_FAILED, connFailure.detail)
            is LocalAdb.Failure.ExecFailed ->
                return@withContext StartResult.Failed(FailureReason.EXEC_FAILED, connFailure.detail)
            else -> Unit
        }

        try {
            val root = probeRootInShell(adb)
                ?: return@withContext StartResult.Failed(
                    FailureReason.NO_ROOT,
                    "local shell not root and no su variant yielded uid=0: " +
                        adb.lastOutput.trim().take(200)
                )
            println("DaemonLauncher: local ADB root via '${root.display()}'")

            val prefix = if (root.variant.isEmpty()) "" else "su ${root.variant} "

            // 이전 세션의 잔여 데몬(HTTP만 죽고 poller는 남은 채 포트를 점유)을 정리한다.
            // 건너뛰면 새 기동이 BindException(Address already in use)으로 HTTP만 못 띄우고
            // poller만 도는 귀먹은 상태가 된다. (stop과 동일 커맨드로 포트부터 비운다.)
            adb.execService("shell:${prefix}pkill -9 -f $MAIN_CLASS")
            delay(1_500)

            val execFailure = adb.execService("shell:${prefix}sh -c \"${daemonStartCommand(apkPath, logPath)}\"")
            if (execFailure != null) {
                return@withContext StartResult.Failed(
                    FailureReason.EXEC_FAILED,
                    execFailure.toString() + " out=" + adb.lastOutput.trim().take(200)
                )
            }
            println("DaemonLauncher: start command sent via local ADB (apk=$apkPath)")
            return@withContext awaitDaemonUp()
        } finally {
            adb.close()
        }
    }

    /** 셸 세션의 root 확보 결과 — variant("")은 셸 자체가 root임을 의미. */
    private data class ShellRoot(val variant: String) {
        fun display(): String = if (variant.isEmpty()) "shell(uid=0)" else "su $variant"
    }

    /**
     * PATH 1 helper — 앱 프로세스에서 su로 데몬을 띄운다.
     * 성공 시 Started/AlreadyRunning, 어떤 su 방언도 root를 못 얻으면 null(PATH2 진행).
     */
    private suspend fun tryStartViaInProcessSu(apkPath: String, logPath: String): StartResult? {
        val inner = daemonStartCommand(apkPath, logPath)
        for (variant in SU_VARIANTS) {
            val probe = runSu("su $variant id", SU_PROBE_TIMEOUT_MS)
            when {
                probe == null -> {
                    println("DaemonLauncher: in-proc su '$variant' unavailable")
                    continue
                }
                probe.second.contains("uid=0") -> Unit
                isSuUnavailable(probe.second) -> {
                    // su 바이너리 부재/SELinux 거부 — 앱 shell 루트는 구조적으로 불가. PATH2로 직행.
                    println("DaemonLauncher: in-proc su '$variant' unavailable cause: ${probe.second.trim().take(80)}")
                    return null
                }
                else -> {
                    println("DaemonLauncher: in-proc su '$variant' no root (${probe.second.trim().take(80)})")
                    continue
                }
            }
            println("DaemonLauncher: in-proc su '$variant' has root; launching daemon")
            runSu("su $variant sh -c \"($inner)\"")
            val r = awaitDaemonUp(maxWaitMs = 4_000)
            if (r is StartResult.Started) {
                println("DaemonLauncher: daemon started via in-proc su (no adb)")
                return r
            }
            println("DaemonLauncher: in-proc su launched command but daemon not up")
        }
        return null
    }

    /** 셸 세션에서 root 확보: 셸 자체 root or su 방언. 없으면 null. */
    private fun probeRootInShell(adb: LocalAdb): ShellRoot? {
        adb.execService("shell:id")
        if (adb.lastOutput.contains("uid=0")) {
            println("DaemonLauncher: local shell already root (uid=0)")
            return ShellRoot("")
        }
        for (variant in SU_VARIANTS) {
            adb.execService("shell:su $variant id")
            if (adb.lastOutput.contains("uid=0")) {
                println("DaemonLauncher: local adb root via su $variant")
                return ShellRoot(variant)
            }
        }
        return null
    }

    /** 데몬 HTTP가 올라올 때까지 최대 maxWaitMs 대기. */
    private suspend fun awaitDaemonUp(maxWaitMs: Long = 10_000): StartResult {
        val steps = (maxWaitMs / 500).toInt().coerceAtLeast(1)
        repeat(steps) {
            delay(500)
            if (AdbProcessClient.queryStatus() != null) return StartResult.Started
        }
        return StartResult.Failed(
            FailureReason.START_TIMEOUT,
            "daemon started but /process-status did not come up in ${maxWaitMs / 1000}s"
        )
    }

    /** 인-프로세스 exec. @return (exitCode, stdout+stderr), exec 불가 시 null. */
    private fun runSu(cmd: String, timeoutMs: Long = 8_000): Pair<Int, String>? {
        return try {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
            val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                p.destroyForcibly()
                return -1 to "timed out"
            }
            val out = p.inputStream.bufferedReader().use { it.readText() }
            p.exitValue() to out
        } catch (e: Exception) {
            println("DaemonLauncher: runSu failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    /** su 바이너리/정책 미사용 여부 판단 — true면 앱 shell 루트가 구조적으로 불가(PATH2 직행). */
    private fun isSuUnavailable(out: String): Boolean {
        val l = out.lowercase()
        return l.contains("not found") || l.contains("no such file") ||
            l.contains("permission denied") || l.contains("avc") ||
            l.contains("denied") || l.contains("inaccessible")
    }

    /** 데emon 정지 — HTTP /process-command 우선, 없으면 su pkill(PATH1 → PATH2). */
    suspend fun stopDaemon(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (AdbProcessClient.stopProcess()) {
            println("DaemonLauncher: stopped via /process-command")
            return@withContext true
        }
        println("DaemonLauncher: /process-command unavailable, trying su pkill")

        // PATH 1: 인-프로세스 su pkill
        for (variant in SU_VARIANTS) {
            val out = runSu("su $variant pkill -f $MAIN_CLASS") ?: continue
            if (!looksDenied(out.second)) {
                delay(1000)
                if (AdbProcessClient.queryStatus() == null) {
                    println("DaemonLauncher: stopped via in-proc su '$variant'")
                    return@withContext true
                }
            }
        }

        // PATH 2: local adb
        val adb = LocalAdb(context)
        if (adb.connect() != null) {
            println("DaemonLauncher: pkill fallback skipped (no adbd)")
            return@withContext false
        }
        try {
            val root = probeRootInShell(adb)
                ?: return@withContext false
            val prefix = if (root.variant.isEmpty()) "" else "su ${root.variant} "
            adb.execService("shell:${prefix}pkill -f $MAIN_CLASS")
            delay(1000)
            val stopped = AdbProcessClient.queryStatus() == null
            println("DaemonLauncher: pkill via local ADB, stopped=$stopped")
            stopped
        } finally {
            adb.close()
        }
    }

    /**
     * 데몬이 실제로 내려갈 때까지 대기한다.
     * 정지 응답은 프로세스가 종료되기 전에 반환되므로, 포트까지 반납되었는지
     * 확인하지 않으면 직후 같은 포트로 기동해 BindException(포트 사용 중)이 발생한다.
     */
    suspend fun waitForDaemonDown(timeoutMs: Long = 4_000): Boolean = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (AdbProcessClient.queryStatus() == null) return@withContext true
            delay(200)
        }
        AdbProcessClient.queryStatus() == null
    }

    private fun looksDenied(out: String): Boolean {
        val l = out.lowercase()
        return l.contains("invalid") || l.contains("denied") ||
            l.contains("not found") || l.contains("permission") || l.contains("no such")
    }

    /** UI용 한국어 실패 메시지 */
    fun failureMessage(reason: FailureReason, detail: String): String = when (reason) {
        FailureReason.NO_ROOT ->
            "기기 자체적으로 루트를 얻지 못했습니다. Magisk 설치 여부와 adb 셸에서 `su` 동작(adb shell su)을 " +
                "확인하세요. redroid는 adb 셸이 이미 root인 경우가 많습니다. ($detail)"
        FailureReason.NO_ADBD ->
            "기기 내 adbd(127.0.0.1:5555)에 연결할 수 없습니다. Magisk가 없는 redroid/실물 기기는 한 번만 외부 adb로 " +
                "`adb shell setprop persist.adb.tcp.port 5555` 후 재시도하세요. (redroid는 보통 불필요)"
        FailureReason.AUTH_FAILED ->
            "기기 화면의 'USB 디버깅 승인' 다이얼로그를 허용해 주세요 (최초 1회, 이후 자동)."
        FailureReason.EXEC_FAILED ->
            "데몬 시작 명령 실행 실패: $detail"
        FailureReason.START_TIMEOUT ->
            "데몬이 기동되지 않았습니다. daemon.log(/data/local/tmp 또는 앱 filesDir)를 확인하세요."
    }
}
