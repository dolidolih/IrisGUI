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
 * su 방언은 경로별로 다르다.
 *   PATH1(앱 프로세스 exec) — Magisk 는 `su -c "<cmd>"` 만 허용하므로 `su -c` 만 쓴다.
 *   PATH2(로컬 adb 셸) — redroid 는 셸 자체가 uid=0 인 경우가 많고, debug su 는
 *   `-c` 를 "invalid uid/gid '-c'" 로 거부하고 uid 인자형만 허용한다. 따라서 PATH2
 *   는 셸 root 확인 후 `su <variant> id` 를 probe하며, 우선순위는 검증된 `su 0`.
 */
object DaemonLauncher {

    enum class FailureReason { NO_ROOT, NO_ADBD, AUTH_FAILED, EXEC_FAILED, START_TIMEOUT }

    sealed class StartResult {
        object AlreadyRunning : StartResult()
        object Started : StartResult()
        data class Failed(val reason: FailureReason, val detail: String) : StartResult()
    }

    private const val MAIN_CLASS = "party.qwer.irisgui.Main"

    /** 앱(uid) 프로세스에서 실제로 exec 가능한 su 후보.
     *  /system/xbin/su 는 PATH 에 잡히지만 앱 네임스페이스에서 inaccessible,
     *  /sbin/su 만 exec 가능. */
    private val SU_PATHS = listOf("su", "/sbin/su")

    /** PATH2(로컬 adb 셸) 탐색 순서. redroid AOSP su는 `-c`를
     *  "invalid uid/gid '-c'"로 거부하며 uid 인자형만 허용한다. 검증된 `su 0`부터.
     */
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

            // 이전 세션의 잔여 데몬(HTTP만 끊고 poller는 남은 채 포트를 점유)을 정리한다.
            // 건너뛰면 새 기동이 BindException(Address already in use)으로 HTTP만 띄우지 못한 채 poller만 도는 귀먹은 상태가 된다.
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
            return@withContext awaitDaemonUp(logPath = logPath)
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
     * root 를 얻으면 결과를 그대로 반환(PATH2 불필요). 어떤 후보도 root를 못 얻으면 null(PATH2 진행).
     */
    private suspend fun tryStartViaInProcessSu(apkPath: String, logPath: String): StartResult? {
        val inner = daemonStartCommand(apkPath, logPath)
        for (suPath in SU_PATHS) {
            val probe = runSu(suCommand(suPath, "id"), SU_PROBE_TIMEOUT_MS)
            if (probe == null || !probe.second.contains("uid=0")) {
                println("DaemonLauncher: in-proc '$suPath' no root (${(probe?.second ?: "").trim().take(80)})")
                continue
            }
            println("DaemonLauncher: in-proc '$suPath -c' has root; launching daemon")
            // 이전 세션 로그가 남아 있으면 진단이 남의 오류를 집는다. 먼저 비운다.
            runSu(suCommand(suPath, "rm -f $logPath"))
            runSu(suCommand(suPath, inner))
            val r = awaitDaemonUp(maxWaitMs = 4_000, logPath = logPath)
            if (r is StartResult.Started) {
                println("DaemonLauncher: daemon started via in-proc su (no adb)")
            } else {
                println("DaemonLauncher: in-proc su HTTP not up")
            }
            // PATH1 에서 이미 루트를 확보해 기동까지 마쳤다. PATH2 도 같은 `su -c` 를
            // 쓰므로 더 나은 결과가 나오지 않는다. HTTP 미상승은 daemon.log 로 판단한다.
            return r
        }
        return null
    }

    /** `su -c "<한 단계 감싼 inner>"` 커맨드 — inner 에 double quote 는 없다. */
    private fun suCommand(suPath: String, inner: String): String =
        suPath + " -c " + '"' + inner + '"'


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
    private suspend fun awaitDaemonUp(maxWaitMs: Long = 10_000, logPath: String? = null): StartResult {
        val steps = (maxWaitMs / 500).toInt().coerceAtLeast(1)
        repeat(steps) {
            delay(500)
            if (AdbProcessClient.queryStatus() != null) return StartResult.Started
            val died = diagnoseDaemonLog(logPath)
            if (died != null) {
                println("DaemonLauncher: $died")
                return StartResult.Failed(FailureReason.START_TIMEOUT, died)
            }
        }
        val tail = readDaemonLogTail(logPath)
        if (tail.isNotBlank()) println("DaemonLauncher: \n$tail")
        val detail = if (tail.isBlank()) {
            "데몬은 기동했으나 /process-status 가 ${maxWaitMs / 1000}s 내 응답하지 않았습니다."
        } else {
            "데몬은 기동했으나 /process-status 가 응답하지 않습니다.\n— daemon.log —\n" + tail
        }
        return StartResult.Failed(FailureReason.START_TIMEOUT, detail)
    }

    /** daemon.log 에 명확한 종료 원인이 있으면 한 줄 요약, 없으면 null. */
    private fun diagnoseDaemonLog(logPath: String?): String? {
        val text = readDaemonLog(logPath) ?: return null
        return when {
            text.contains("KakaoTalk app path not found") ->
                "KakaoTalk 이 설치되어 있지 않아 데몬이 즉시 종료되었습니다. (KakaoTalk app path not found)"
            text.contains("permission to access KakaoTalk Database") ->
                "KakaoTalk DB 접근 권한이 없어 데몬이 종료되었습니다. (SELinux/uid 권한 문제)"
            text.contains("SQLiteException") ->
                "KakaoTalk DB 여는 중 SQLite 오류로 데몬이 종료되었습니다. (DB 손상/권한 가능성)"
            text.contains("Address already in use") ->
                "포트 ${AppConfig.serverPort} 가 이미 사용 중입니다. 이전 데몬이 살아 있습니다."
            text.contains("BindException") ->
                "HTTP 서버 바인딩 실패로 데몬이 종료되었습니다. 포트/재시작 문제 확인."
            else -> null
        }
    }

    private fun readDaemonLog(logPath: String?): String? {
        if (logPath == null) return null
        return try {
            val f = File(logPath)
            if (!f.exists()) null else f.readText()
        } catch (e: Exception) {
            null
        }
    }

    /** daemon.log 마지막 12줄. */
    private fun readDaemonLogTail(logPath: String?): String {
        val text = readDaemonLog(logPath) ?: return ""
        val lines = text.trimEnd().lines().filter { it.isNotBlank() }
        val from = if (lines.size > 12) lines.size - 12 else 0
        return lines.subList(from, lines.size).joinToString("\n")
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

    /** 데emon 정지 — HTTP /process-command 우선, 없으면 su pkill(PATH1 → PATH2). */
    suspend fun stopDaemon(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (AdbProcessClient.stopProcess()) {
            println("DaemonLauncher: stopped via /process-command")
            return@withContext true
        }
        println("DaemonLauncher: /process-command unavailable, trying su pkill")

        // PATH 1: 인-프로세스 su pkill (Magisk `su -c "<cmd>"`)
        for (suPath in SU_PATHS) {
            val out = runSu(suCommand(suPath, "pkill -f $MAIN_CLASS")) ?: continue
            if (!looksDenied(out.second)) {
                delay(1000)
                if (AdbProcessClient.queryStatus() == null) {
                    println("DaemonLauncher: stopped via in-proc su '$suPath -c'")
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
            if (detail.isNotBlank()) "기동되지 않았습니다. $detail"
            else "데몬이 기동되지 않았습니다. daemon.log(/data/local/tmp 또는 앱 filesDir)를 확인하세요."
    }
}
