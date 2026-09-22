package party.qwer.irisgui.scripting

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.RuntimeLog
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * UserlandRuntime — proot(Ubuntu userland) 기반 리눅스 환경 하나.
 *
 * Ubuntu rootfs+python+pip+venv + 스크립트 프로젝트. 편집/터미널 UI 는 앱 내장 화면이
 * exec/spawn API 위에서만 동작하고, userland 에 별도 서버(code-server)를 두지 않는다.
 * root 불필요: proot 는 ptrace 로 경로를 붙이므로 일반 앱 uid 로 동작. proot 는 host net
 * ns 를 그대로 공유하므로 guest 의 루프백 리스너는 host 루프백에서 그대로 도달 가능.
 *
 * 프로비저닝(멱등, 단계별 .ready_* 마커로 건너뜀):
 *   base    = ubuntu rootfs 언팩 + proot 바이너리 + DNS
 *   python  = apt python3/pip/venv (+ ca-certificates)
 *
 * 프로젝트는 userland/home/projects. host 의 filesDir/linux/home/projects 를 guest
 * /home/projects 로 바인드하므로 host 에서 읽고 쓰면서 guest 에서 실행/편집 가능.
 */
object UserlandRuntime {
    private const val TAG = "Userland"

    /** userland root: filesDir/linux. */
    private const val DIR_NAME = "linux"

    /**
     * ubuntu base. 26.04 은 coreutils 가 Rust uutils 멀티콜이라 proot(ptrace) 에서
     * argv[0] 재작성 때문에 cat/tail/date/apt 가 전부 "unknown program" 으로 깨진다
     * (termux/proot-distro#662 과 같은 버그). 24.04 는 GNU coreutils 를 갖춘 마지막 LTS.
     */
    private const val VERSION = "24.04.5"
    private const val ROOTFS_URL =
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$VERSION/release/ubuntu-base-$VERSION-base-%ARCH%.tar.gz"
    private const val PROOT_URL =
        "https://github.com/proot-me/proot/releases/download/v5.3.0/proot-v5.3.0-%ABI%-static"

    /** proot 바이너리용 abi 명. */
    private val ABIS = mapOf(
        "x86_64" to "x86_64",
        "arm64-v8a" to "aarch64",
        "armeabi-v7a" to "armv7",
        "armeabi" to "armv7",
    )

    /** rootfs 아카이브 abi 명 (x86 i386 이미지는 없음). */
    private val ROOTFS_ABIS = mapOf(
        "x86_64" to "amd64",
        "arm64-v8a" to "arm64",
        "armeabi-v7a" to "armhf",
        "armeabi" to "armhf",
    )

    /** apt 공통 옵션. proot 안에서 _apt 비권한 드랍/서비스 시동을 막기 위한 우회. */
    const val APT =
        "apt-get -o APT::Sandbox::User=root -o Dpkg::Options::=--force-confdef " +
            "-o Dpkg::Options::=--force-confnew "

    /** policy-rc.d: proot 안 init 시스템이 없어서 invoke-rc.d 를 통과시킨다.
     * /data/local/tmp: base 이미지에 없어 ca-certificates postinst 의 mktemp 가 실패한다. */
    const val APT_PREP =
        "printf '#!/bin/sh\\nexit 101\\n' > /usr/sbin/policy-rc.d; " +
            "chmod +x /usr/sbin/policy-rc.d 2>/dev/null; " +
            "mkdir -p /data/local/tmp 2>/dev/null; chmod 777 /data/local/tmp 2>/dev/null; " +
            "export DEBIAN_FRONTEND=noninteractive; "

    private const val ENV_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"

    enum class State { DISABLED, NOT_INSTALLED, INSTALLING, READY, RUNNING, ERROR }

    data class Status(
        val state: State,
        val installed: Boolean,
        val running: Boolean,
        val port: Int,
        val message: String = ""
    )

    data class Result(val ok: Boolean, val message: String)
    data class Exec(val exitCode: Int, val output: String)

    /** filesDir/linux. rootfs+proot+projects 의 공통 루트. */
    fun rootDir(context: Context): File = File(context.filesDir, DIR_NAME)

    internal fun prootPath(context: Context): File = File(rootDir(context), ".proot")

    /** host 의 프로젝트 부모. guest 에는 GUEST_PROJECTS 로 바인드된다. */
    fun projectsHostDir(context: Context): File =
        File(rootDir(context), "home/projects").apply { mkdirs() }

    /** guest 안 프로젝트 부모 경로. */
    const val GUEST_PROJECTS = "/home/projects"

    /** host 프로젝트 디렉터리 → guest 경로. */
    fun guestProject(context: Context, name: String): String = "$GUEST_PROJECTS/$name"

    /** proot 로 guest 명령을 실행할 수 있는지. python 유무는 보지 않는다. */
    fun prootReady(context: Context): Boolean =
        abiTag() != null && prootPath(context).exists() && baseOk(rootDir(context))

    /** 전체 준비(프로젝트 실행/편집) 가능 여부: proot+python. */
    fun ready(context: Context): Boolean =
        prootReady(context) && ready2(context, "python")

    /** cheap 준비 상태 — UI 가 polling. 설치 진행/미설치를 구분. */
    fun status(context: Context): Status {
        val dir = rootDir(context)
        return when {
            abiTag() == null -> Status(State.DISABLED, false, false, 0, "미지원 ABI")
            !baseOk(dir) ->
                if (File(dir, "rootfs.tar.gz").length() > 0)
                    Status(State.NOT_INSTALLED, false, false, 0, "리눅스 이미지 다운로드 중")
                else Status(State.NOT_INSTALLED, false, false, 0, "미설치")
            !ready2(context, "python") ->
                Status(State.NOT_INSTALLED, false, false, 0, "구성요소 설치 중")
            else -> Status(State.READY, true, false, 0, "준비됨")
        }
    }

    private fun abiTag(): String? =
        android.os.Build.SUPPORTED_ABIS.firstOrNull { ABIS.containsKey(it) }?.let { ABIS[it] }

    private fun rootfsAbiTag(): String? =
        android.os.Build.SUPPORTED_ABIS.firstOrNull { ROOTFS_ABIS.containsKey(it) }
            ?.let { ROOTFS_ABIS[it] }

    internal fun archTag(): String =
        if (rootfsAbiTag() == "arm64") "arm64" else "amd64"

    private fun marker(context: Context, name: String): File =
        File(rootDir(context), ".ready_$name")
    private fun ready2(context: Context, name: String) = marker(context, name).exists()
    private fun markReady(context: Context, name: String) {
        val m = marker(context, name); m.parentFile?.mkdirs(); runCatching { m.writeText("ok") }
    }

    /** host 에서 python 프로세스의 cwd 스캔용. project 디렉터리 경로 집합. */
    internal val projectsHostBase = GUEST_PROJECTS

    // ── 프로비저닝 ────────────────────────────────────────────────────────

    /** rootfs+proot+DNS. python 등 추가 패키지는 설치하지 않는다. */
    fun ensureBase(context: Context): Result {
        val abi = abiTag() ?: return Result(false, "미지원 ABI (proot/rootfs 없음)")
        val dir = rootDir(context)
        dir.mkdirs()

        if (!baseOk(dir)) {
            val arch = rootfsAbiTag() ?: return Result(false, "미지원 ABI (rootfs 없음)")
            RuntimeLog.info(TAG, "ubuntu rootfs 준비 시작 ($arch)")
            val tar = File(dir, "rootfs.tar.gz")
            // 예전 distro 트리가 남아있으면 통째로 비우고 다시 받는다.
            if (dir.listFiles()?.isNotEmpty() == true) {
                RuntimeLog.info(TAG, "기존 rootfs 트리 초기화")
                wipeRootfs(dir)
            }
            if (tar.length() < 8 * 1024 * 1024) {
                if (!download(ROOTFS_URL.replace("%ARCH%", arch), tar, 600_000))
                    return Result(false, "rootfs 다운로드 실패 (네트워크?)")
            }
            if (!unpack(tar, dir)) return Result(false, "rootfs 언팩 실패")
            writeResolvConf(dir)
            markReady(context, "rootfs")
            runCatching { tar.delete() }
        }

        val proot = prootPath(context)
        if (!proot.exists()) {
            if (!download(PROOT_URL.replace("%ABI%", abi), proot, 120_000))
                return Result(false, "proot 바이너리 다운로드 실패")
            if (!proot.setExecutable(true)) return Result(false, "proot 실행권한 설정 실패")
        }

        if (!baseOk(dir)) return Result(false, "rootfs 검증 실패")
        if (!proot.exists()) return Result(false, "proot 없음")
        return Result(true, "rootfs 준비됨")
    }

    /** userland python3/pip/venv 준비. 멱등. blocking. */
    fun ensurePython(context: Context): Result {
        if (ready2(context, "python")) {
            val probe = exec(context, "python3 -V 2>&1", 20_000)
            return if (probe.output.contains("Python 3")) Result(true, "python 준비됨")
            else { runCatching { marker(context, "python").delete() }; ensurePython(context) }
        }
        val base = ensureBase(context)
        if (!base.ok) return base
        // 동봉 ubuntu-keyring(2023) 가 archive 의 새 열쇠(871920D1991BC93C) 보다 먼저라
        // apt update 서명 검증이 실패한다. 오프라인 rootfs 에서는 keyring 갱신이 안 되므로
        // unauthenticated 허용. 채널이 이미 http/archive 라 MITM 방어요는 없다.
        val insecure = "-o Acquire::AllowInsecureRepositories=true "
        val (_, out) = exec(context,
            APT_PREP + APT + insecure + "update -y -q >/dev/null 2>&1; " + APT +
                "--allow-unauthenticated install -y -q --no-install-recommends " +
                "python3 python3-pip python3-venv python3-wheel 2>&1 | tail -3; " +
                "python3 -V 2>&1", 1_200_000)
        if (!out.contains("Python 3")) {
            return Result(false, "python 준비 실패: " + out.take(300))
        }
        markReady(context, "python")
        RuntimeLog.info(TAG, "userland python 준비 완료")
        return Result(true, "python 준비됨")
    }

    /**
     * ubuntu base 이미지에는 CA 번들이 없어 pip 의 TLS 가 죽는다. 파일 존재는 exec
     * 하나, 없으면 apt 로 설치. 멱등.
     */
    fun ensureTrustStore(context: Context): Result {
        val probe = exec(context, "test -f /etc/ssl/certs/ca-certificates.crt && echo CA_OK",
            20_000)
        if (probe.output.contains("CA_OK")) return Result(true, "CA 준비됨")
        // base 이미지엔 apt lists 가 비어있다 -> update 먼저. keyring 실패(NO_PUBKEY)로
        // update 가 warn 을 낸해도 다른 컴포넌트는 남아 install 은 동작한다.
        val (_, out) = exec(context, APT_PREP + APT +
            "-o Acquire::AllowInsecureRepositories=true --allow-unauthenticated " +
            "update -q 2>&1 | tail -3; " + APT +
            "-o Acquire::AllowInsecureRepositories=true --allow-unauthenticated " +
            "install -y -q --no-install-recommends ca-certificates 2>&1 | tail -5", 600_000)
        val after = exec(context,
            "test -f /etc/ssl/certs/ca-certificates.crt && echo CA_OK", 20_000)
        return if (after.output.contains("CA_OK")) Result(true, "CA 인증서 설치됨")
        else Result(false, "CA 인증서 설치 실패: " + out.take(200))
    }

    /** 전체 준비: rootfs + python + ca. 멱등. blocking. */
    fun provision(context: Context): Result {
        val base = ensureBase(context)
        if (!base.ok) return base
        val py = ensurePython(context)
        if (!py.ok) return py
        return ensureTrustStore(context)
    }

    /** host 루프백 포트가 열려있는지. */
    internal fun isPortOpen(port: Int): Boolean = runCatching {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress("127.0.0.1", port), 500)
        }
        true
    }.getOrDefault(false)

    // ── proot 실행 ────────────────────────────────────────────────────────

    /** proot 를 띄우는 공통 ProcessBuilder. cwd=guest dir, /home/projects 바인드. */
    private fun prootBuilder(context: Context, inner: String, cwdGuest: String?): ProcessBuilder {
        val dir = rootDir(context)
        val pb = ProcessBuilder(
            prootPath(context).absolutePath,
            "-0",
            "-r", dir.absolutePath,
            "-b", "/proc", "-b", "/dev", "-b", "/sys",
            "-b", projectsHostDir(context).absolutePath + ":" + GUEST_PROJECTS,
            "-w", (cwdGuest ?: "/root"),
            "/bin/sh", "-c",
            "export PATH=$ENV_PATH\n" +
                "export HOME=/root\nexport TMPDIR=/tmp\n" +
                "export IRISGUI_API_URL=" + q("http://127.0.0.1:" + AppConfig.serverPort) + "\n" +
                inner
        )
        pb.redirectErrorStream(true)
        pb.environment().let { it["PROOT_NO_SECCOMP"] = "1" }
        return pb
    }

    /**
     * 코드 문자열을 proot 로 실행. env 주입과 PATH 를 여기서 조립한다.
     * timeout 안에 끝나야 로그 텍스트를 돌려준다.
     */
    internal fun exec(context: Context, inner: String, timeoutMs: Long): Exec =
        exec(context, inner, timeoutMs, null)

    internal fun exec(
        context: Context, inner: String, timeoutMs: Long, cwdGuest: String?
    ): Exec {
        if (!prootPath(context).exists()) return Exec(-1, "userland 미준비 — 먼저 준비 필요")
        return try {
            val p = prootBuilder(context, inner, cwdGuest).start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly(); Exec(-1, "timed out")
            } else Exec(p.exitValue(), p.inputStream.bufferedReader().use { it.readText() })
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "exec 실패: " + e.message)
            Exec(-1, e.message ?: "exec error")
        }
    }

    /**
     * proot 로 guest 셸 명령을 백그라운드로 띄움 (python script 등).
     * proot 는 종료 시 ptrace 중인 자식을 강제종료하므로, 자식이 살아있게 하려면 이
     * proot 프로세스를 Kotlin 쪽에서 붙잡고 있어야 한다. stdout 은 host logFile 에
     * redirection (null 시 파이프).
     */
    internal fun spawnBackground(
        context: Context, inner: String, cwdGuest: String, logFile: File?
    ): Process? = try {
        val pb = prootBuilder(context, inner, cwdGuest)
        if (logFile != null) pb.redirectOutput(logFile)
        pb.start()
    } catch (e: Exception) {
        RuntimeLog.error(TAG, "spawn 실패: ${e.message}")
        null
    }

    /** 앱 uid 로 tar.gz 를 rootDir 에 언팩. toybox tar 의 settime 경고는 무시. */
    private fun unpack(tar: File, dir: File): Boolean = try {
        val p = ProcessBuilder("/system/bin/tar", "xzf", tar.absolutePath, "-C", dir.absolutePath)
            .redirectErrorStream(true).start()
        if (!p.waitFor(180_000, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            RuntimeLog.warn(TAG, "tar 언팩 시간초과")
        } else {
            p.inputStream.bufferedReader().use { it.readText() }
        }
        baseOk(dir)
    } catch (e: Exception) {
        RuntimeLog.error(TAG, "unpack 실패: " + e.message)
        false
    }

    /** 언팩된 rootfs 가 유효한 ubuntu 트리인지 (판올림되면 false 로 wipe). */
    private fun baseOk(dir: File): Boolean {
        val os = runCatching { File(dir, "etc/os-release").readText() }.getOrDefault("")
        if (!os.contains("ubuntu", true)) return false
        val major = VERSION.substringBeforeLast('.')
        if (!os.contains("VERSION_ID=\"" + major + "\"")) return false
        return File(dir, "usr/bin/bash").exists() || File(dir, "bin/bash").exists()
    }

    /** distro 교체/깨짐으로 rootfs 트리만 비운다. proot/마커/아카이브/프로젝트는 남긴다. */
    private fun wipeRootfs(dir: File) {
        val keep = setOf(".proot", ".ready", "rootfs.tar.gz", "home")
        dir.listFiles()?.forEach { f ->
            if (keep.any { f.name.startsWith(it) }) return@forEach
            runCatching { f.deleteRecursively() }
        }
    }

    /** ubuntu base 의 /etc/resolv.conf 는 broken 심볼릭이라 proot 안에서 DNS 가 죽는다. */
    private fun writeResolvConf(dir: File) {
        val f = File(dir, "etc/resolv.conf")
        runCatching {
            f.parentFile?.mkdirs(); runCatching { f.delete() }; f.writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    internal fun download(url: String, target: File, timeoutMs: Long): Boolean = try {
        target.parentFile?.mkdirs()
        client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful || resp.body == null) {
                RuntimeLog.error(TAG, "다운로드 실패 $url code=" + resp.code)
                return false
            }
            target.outputStream().use { resp.body!!.byteStream().copyTo(it) }
        }
        true
    } catch (e: Exception) {
        RuntimeLog.error(TAG, "다운로드 예외 $url: ${e.message}")
        false
    }

    internal fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
