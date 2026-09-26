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

    /** filesDir/linux. rootfs+proot+projects 의 공통 루트 (proot 플로우 전용, 불변). */
    internal fun prootRoot(context: Context): File = File(context.filesDir, DIR_NAME)

    /** 현재 동작 백엔드. 설치된 것이 있으면 그쪽 우선, 없으면 신규 설치 대상 판정. */
    enum class Backend { PROOT, BIONIC }

    fun backend(context: Context): Backend = when {
        File(prootRoot(context), ".ready_python").exists() &&
            prootPath(context).exists() -> Backend.PROOT
        BionicRuntime.installed(context) -> Backend.BIONIC
        else -> installTarget(context)
    }

    /** 신규 설치 대상 — 기기 상태(SELinux enforcing 여부)만의 함수.
     * 앱 모드(ROOT_ADB/NON_ROOT)는 판정에서 완전히 제외한다: 모드는 이벤트 소스
     * (DB 폴링/NLS)를 고르는 선택지일 뿐이고, userland 를 교체하면 그 자체가
     * "모드가 환경을 망치는" 설계 결함이기 때문 (2026-09 사용자 설계 지적).
     * enforcing(S26U 실측: proot exec 거부) → bionic 고정,
     * permissive/disabled(redroid 등) → proot — 모드와 무관하게 기기마다 일관. */
    internal fun installTarget(context: Context): Backend {
        val enforcing = runCatching {
            File("/sys/fs/selinux/enforce").readText().trim() == "1"
        }.getOrDefault(true)
        return if (enforcing) Backend.BIONIC else Backend.PROOT
    }

    /** UI 가 보는 공용 root — 백엔드별 루트 디렉터리. */
    fun rootDir(context: Context): File =
        if (backend(context) == Backend.BIONIC) BionicRuntime.prefix(context)
        else prootRoot(context)

    internal fun prootPath(context: Context): File = File(prootRoot(context), ".proot")

    /** host 의 프로젝트 부모. guest 에는 GUEST_PROJECTS 로 바인드된다.
     * bionic 백엔드는 chroot 가 없어 host 가 곧 guest 경로다. */
    fun projectsHostDir(context: Context): File =
        if (backend(context) == Backend.BIONIC) BionicRuntime.projectsHostDir(context)
        else File(prootRoot(context), "home/projects").apply { mkdirs() }

    /** guest 안 프로젝트 부모 경로 (proot 기준). */
    const val GUEST_PROJECTS = "/home/projects"

    /** host 프로젝트 디렉터리 → 실행 환경 내 경로. */
    fun guestProject(context: Context, name: String): String =
        if (backend(context) == Backend.BIONIC)
            File(BionicRuntime.projectsHostDir(context), name).absolutePath
        else "$GUEST_PROJECTS/$name"

    /** proot 로 guest 명령을 실행할 수 있는지. python 유무는 보지 않는다. */
    fun prootReady(context: Context): Boolean =
        abiTag() != null && prootPath(context).exists() && baseOk(prootRoot(context))

    /** 전체 준비(프로젝트 실행/편집) 가능 여부. 백엔드별 판정. */
    fun ready(context: Context): Boolean =
        if (backend(context) == Backend.BIONIC) BionicRuntime.ready(context)
        else prootReady(context) && ready2(context, "python")

    /** cheap 준비 상태 — UI 가 polling. 설치 진행/미설치를 구분. */
    fun status(context: Context): Status {
        if (backend(context) == Backend.BIONIC) {
            val s = BionicRuntime.status(context)
            return Status(s.state, s.ready, false, 0, s.message)
        }
        val dir = prootRoot(context)
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
        File(prootRoot(context), ".ready_$name")
    private fun ready2(context: Context, name: String) = marker(context, name).exists()
    /** ISSUE-23: 마커 쓰기 실패를 삼키지 않고 호출자에게 알린다 (실패 = 미준비). */
    private fun markReady(context: Context, name: String): Boolean {
        val m = marker(context, name)
        m.parentFile?.mkdirs()
        return runCatching {
            m.writeText("ok")
            m.length() > 0
        }.getOrDefault(false)
    }

    /** host 에서 python 프로세스의 cwd 스캔용. project 디렉터리 경로 집합. */
    internal val projectsHostBase = GUEST_PROJECTS

    // ── 프로비저닝 ────────────────────────────────────────────────────────

    /** rootfs+proot+DNS. python 등 추가 패키지는 설치하지 않는다. */
    fun ensureBase(context: Context): Result {
        val abi = abiTag() ?: return Result(false, "미지원 ABI (proot/rootfs 없음)")
        val dir = prootRoot(context)
        dir.mkdirs()

        if (!baseOk(dir)) {
            val arch = rootfsAbiTag() ?: return Result(false, "미지원 ABI (rootfs 없음)")
            RuntimeLog.info(TAG, "ubuntu rootfs 준비 시작 ($arch)")
            val tar = File(dir, "rootfs.tar.gz")
            // 예전 distro 트리가 남아있으면 통째로 비우고 다시 받는다.
            // ISSUE-23: wipe 시 .ready_* 마커도 함께 삭제 — 루트 없는 python 마커는 거짓.
            if (dir.listFiles()?.isNotEmpty() == true) {
                RuntimeLog.info(TAG, "기존 rootfs 트리 초기화")
                wipeRootfs(dir)
            }
            // ISSUE-23: 다운로드 무결성 검증. 부분 다운로드(8MB 룰만으론 통과)는
            // 언팩 실패 후 아카이브까지 남겨 항시 브로킹 상태에 빠지던 것을,
            // gzip 스트림 전량 판독 + 크기 검사로 재시도만이면 수렴하게 바꾼다.
            var attempt = 0
            while (tar.length() < 8L * 1024 * 1024 || !gzipIntact(tar)) {
                if (attempt >= 2) {
                    runCatching { tar.delete() }
                    return Result(false, "rootfs 아카이브 손상 — 재시도 후 중단")
                }
                if (attempt > 0 || tar.length() > 0) runCatching { tar.delete() }
                if (!download(ROOTFS_URL.replace("%ARCH%", arch), tar, 600_000)) {
                    runCatching { tar.delete() }
                    return Result(false, "rootfs 다운로드 실패 (네트워크?)")
                }
                if (tar.length() < 8L * 1024 * 1024) {
                    RuntimeLog.warn(TAG, "rootfs 다운로드가 너무 작음 (${tar.length()} bytes)")
                }
                attempt++
            }
            // 언팩은 staging 후 커밋 — 어느 시점에 크래시돼도 기존 트리는 온전하다.
            if (!unpackStaged(tar, dir)) {
                // 손상/잘린 아카이브를 남기지 않는다 — 다음 시도가 새 다운로드에서 시작.
                runCatching { tar.delete() }
                return Result(false, "rootfs 언팩 실패 — 다음 시도에서 재다운로드")
            }
            writeResolvConf(dir)
            if (!markReady(context, "rootfs"))
                return Result(false, "rootfs 마커 기록 실패 (용량 부족?)")
            runCatching { tar.delete() }
        }

        val proot = prootPath(context)
        if (!proot.exists()) {
            if (!download(PROOT_URL.replace("%ABI%", abi), proot, 120_000))
                return Result(false, "proot 바이너리 다운로드 실패")
            if (!proot.setExecutable(true)) return Result(false, "proot 실행권한 설정 실패")
            // 부분 다운로드(절단 ELF) 방지 — 최소 크기 게이트.
            if (proot.length() < 1_000_000) {
                runCatching { proot.delete() }
                return Result(false, "proot 바이너리 손상 — 삭제 후 재시도")
            }
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
            else {
                // 마커가 거짓말 — 마커를 내리고 아래 실제 준비 경로로 재진입.
                // (한 단계 재귀; base 가 망가졌으면 ensureBase 가 구조를 복구한다.)
                RuntimeLog.warn(TAG, "python 마커 불일치 — 재설치 진행")
                runCatching { marker(context, "python").delete() }
                ensurePython(context)
            }
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
        if (!markReady(context, "python"))
            return Result(false, "python 마커 기록 실패 (용량 부족?)")
        RuntimeLog.info(TAG, "userland python 준비 완료")
        return Result(true, "python 준비됨")
    }

    /**
     * ubuntu base 이미지에는 CA 번들이 없어 pip 의 TLS 가 죽는다. 파일 존재는 exec
     * 하나, 없으면 apt 로 설치. 멱등.
     */
    fun ensureTrustStore(context: Context): Result {
        if (backend(context) == Backend.BIONIC) {
            val f = File(BionicRuntime.prefix(context), "usr/tls/certs/ca-certificates.crt")
            if (!f.isFile) BionicRuntime.ensureTrustStore(context)
            return if (f.isFile) Result(true, "CA 준비됨")
            else Result(false, "CA 번들 없음 — 시스템 인증서 생성 실패")
        }
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

    /** 전체 준비: rootfs + python + ca. 멱등. blocking. 백엔드 자동 분기. */
    fun provision(context: Context): Result {
        if (backend(context) == Backend.BIONIC) return BionicRuntime.provision(context)
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
        val dir = prootRoot(context)
        // setsid 필수: proot 를 앱과 같은 세션/그룹에 두면 proot 의 SIGTERM cleanup 이
        // ptrace group-stop 을 세션 전체(앱 전 스레드 포함 = T stop, 입력 ANR)로 전파한다.
        // 자식에게 새 세션을 주면 stop 은 자식 세션에만 국한된다.
        val pb = ProcessBuilder(
            "/system/bin/setsid",
            prootPath(context).absolutePath,
            "-0",
            "-r", dir.absolutePath,
            "-b", "/proc", "-b", "/dev", "-b", "/sys",
            "-b", projectsHostDir(context).absolutePath + ":" + GUEST_PROJECTS,
            "-w", (cwdGuest ?: "/root"),
            "/bin/sh", "-c",
            "export PATH=$ENV_PATH\n" +
                "export HOME=/root\nexport TMPDIR=/tmp\n" +
                "export TERM=xterm-256color\n" +
                "export IRISGUI_API_URL=" + q("http://127.0.0.1:" + AppConfig.serverPort) + "\n" +
                // bash.bashrc 의 sudo 힌트가 $(groups) 를 호출한다. proot 는 host 의
                // android 보조그룹(3003/9997/20090/...) 을 guest /etc/group 에서 못 찾아
                // 매 셸마다 "cannot find name for group ID" spam 을 만든다. 조건을 끈다.
                ": > /root/.sudo_as_admin_successful 2>/dev/null\n" +
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
        if (backend(context) == Backend.BIONIC)
            return BionicRuntime.exec(context, inner, timeoutMs, cwdGuest?.let { File(it) })
        if (!prootPath(context).exists()) return Exec(-1, "userland 미준비 — 먼저 준비 필요")
        return try {
            val p = prootBuilder(context, inner, cwdGuest).start()
            // ISSUE-10: 기다리는 동안 stdout/stderr 를 별도 스레드로 소비한다.
            // 자식이 pipe 버퍼(~64KB)를 넘기면 write 에서 블로킹되어 waitFor 가
            // 타임아웃되는 고질 버그(apt/pip 설치, python traceback)를 없앤다.
            // 타임아웃 시에도 수집된 출력을 함께 돌려 원인을 남는다.
            val (finished, out) = collectOutput(p, timeoutMs)
            if (!finished) {
                // SIGTERM 먼저 — proot 의 ptrace 정리 로직을 동작시키기 위해.
                p.destroy()
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
                Exec(-1, "timed out\n$out".trim())
            } else Exec(p.exitValue(), out)
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "exec 실패: " + e.message)
            Exec(-1, e.message ?: "exec error")
        }
    }

    /** exec 가 수집하는 출력 상한 (tail 윈도우). apt/python 출력으로 충분한 양. */
    private const val OUT_CAP = 512 * 1024

    /**
     * 프로세스 stdout(redirectErrorStream 로 stderr 포함) 을 wait 중에도 읽어들인다.
     * @return (정규 종료 여부, 수집 출력 tail)
     */
    private fun collectOutput(p: Process, timeoutMs: Long): Pair<Boolean, String> {
        val sink = TailSink(OUT_CAP)
        val reader = Thread {
            runCatching {
                p.inputStream.use { ins ->
                    val b = ByteArray(16384)
                    while (true) {
                        val n = try { ins.read(b) } catch (io: java.io.IOException) { break }
                        if (n < 0) break
                        sink.write(b, 0, n)
                    }
                }
            }
        }
        reader.isDaemon = true
        reader.start()
        val finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        if (!finished) p.destroyForcibly()
        reader.join(if (finished) 3_000 else 500)
        if (reader.isAlive) runCatching { reader.interrupt() }
        return finished to sink.snapshot()
    }

    /** 순환 버퍼 — 마지막 `cap` bytes 만 남긴다. 잘린 분량은 머리 표식으로 표시. */
    private class TailSink(private val cap: Int) {
        private val buf = ByteArray(cap)
        private var total = 0L

        @Synchronized fun write(b: ByteArray, off: Int, len: Int) {
            var o = off
            var rem = len
            while (rem > 0) {
                val pos = (total % cap).toInt()
                val n = minOf(rem, cap - pos)
                b.copyInto(buf, pos, o, o + n)
                total += n; o += n; rem -= n
            }
        }

        @Synchronized fun snapshot(): String {
            val t = total
            if (t == 0L) return ""
            if (t <= cap) return String(buf, 0, t.toInt(), Charsets.UTF_8)
            val pos = (t % cap).toInt()
            val merged = ByteArray(cap)
            // wrap 지점 기준 tail: [pos..cap) + [0..pos)
            System.arraycopy(buf, pos, merged, 0, buf.size - pos)
            System.arraycopy(buf, 0, merged, buf.size - pos, pos)
            return "[...${t - cap} bytes truncated...]\n" + String(merged, Charsets.UTF_8)
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
        if (backend(context) == Backend.BIONIC) BionicRuntime.spawnBackground(
            context, inner, cwdGuest.let { if (it == "/root") null else File(it) }, logFile)
        else {
            val pb = prootBuilder(context, inner, cwdGuest)
            if (logFile != null) pb.redirectOutput(logFile)
            pb.start()
        }
    } catch (e: Exception) {
        RuntimeLog.error(TAG, "spawn 실패: ${e.message}")
        null
    }

    /** 앱 uid 로 tar.gz 를 rootDir 에 언팩. toybox tar 의 settime 경고는 무시.
     * ISSUE-10: 출력/경고를 wait 중 concurrently 소비 — pipe 만발 데드록 제거. */
    internal fun unpack(tar: File, dir: File): Boolean = try {
        val p = ProcessBuilder("/system/bin/tar", "xzf", tar.absolutePath, "-C", dir.absolutePath)
            .redirectErrorStream(true).start()
        val (finished, out) = collectOutput(p, 180_000)
        if (!finished) RuntimeLog.warn(TAG, "tar 언팩 시간초과: " + out.take(200))
        else if (out.isNotBlank()) RuntimeLog.info(TAG, "tar 언팩 출력: " + out.take(300))
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

    /** distro 교체/깨짐으로 rootfs 트리만 비운다. proot/마커/아카이브/프로젝트는 남긴다.
     * ISSUE-23: .ready_* 마커는 루트와 운명을 함께하므로 여기서 삭제한다
     * (루트 없는 python/ca 마커가 status() 를 속이는 것을 방지). */
    private fun wipeRootfs(dir: File) {
        val keep = setOf(".proot", "rootfs.tar.gz", "home", ".staging")
        dir.listFiles()?.forEach { f ->
            if (keep.any { f.name.startsWith(it) }) return@forEach
            if (f.name.startsWith(".ready")) { runCatching { f.delete() }; return@forEach }
            runCatching { f.deleteRecursively() }
        }
    }

    /** ISSUE-23: gzip 스트림 전량 판독으로 아카이브 무결성 검사. 압축 해제 후
     * 최소 8MB 이어야 ubuntu base 로서 정당하다. 손상/절단은 false. */
    private fun gzipIntact(f: File): Boolean = runCatching {
        if (!f.isFile || f.length() < 1) return false
        java.util.zip.GZIPInputStream(f.inputStream(), 1 shl 16).use { g ->
            val b = ByteArray(65536)
            var total = 0L
            while (true) {
                val n = try { g.read(b) } catch (io: java.io.IOException) { return false }
                if (n < 0) break
                total += n
            }
            total >= 8L * 1024 * 1024
        }
    }.getOrDefault(false)

    /** ISSUE-23: staging 언팩 → top-level entry 별 move 커밋. 크래시 포인트 어디에서
     * 죽어도 (1) 기존 rootfs 트리는 오염되지 않고, (2) .staging 는 다음 시도에 재언팩
     * 된다. 커밋 중 part 도 baseOk(dir) false 로 이어져 같은 경로가 반복된다. */
    private fun unpackStaged(tar: File, dir: File): Boolean {
        val staging = File(dir, ".staging")
        runCatching { staging.deleteRecursively() }
        if (!staging.mkdirs()) return false
        if (!unpack(tar, staging)) {
            runCatching { staging.deleteRecursively() }
            return false
        }
        var ok = true
        staging.listFiles()?.forEach { e ->
            if (!ok) return@forEach
            if (!moveInto(e, File(dir, e.name))) ok = false
        }
        if (ok) runCatching { staging.delete() }
        return ok
    }

    /** 같은 file-system 내 rename 우선, 실패 시 copy+delete 폴백. */
    private fun moveInto(src: File, dst: File): Boolean {
        if (src.renameTo(dst)) return true
        val ok = runCatching {
            if (dst.exists()) runCatching { dst.deleteRecursively() }
            if (src.isDirectory) src.copyRecursively(dst, overwrite = true)
            else { src.copyTo(dst, overwrite = true); true }
        }.getOrDefault(false)
        if (ok) runCatching { src.deleteRecursively() }
        return ok
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
        val call = client.newCall(Request.Builder().url(url).build())
        // ISSUE-23: 호출 단위 timeout — 고정 readTimeout 만 믿고 파라미터를
        // 무시하던 것을 실제로 지킨다 (정체된 다운로드를 통째로 끊는다).
        call.timeout().timeout(timeoutMs, TimeUnit.MILLISECONDS)
        call.execute().use { resp ->
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

    /** BionicRuntime 공용으로 노출하는 출력 수집기. */
    internal fun collectOutputPublic(p: Process, timeoutMs: Long): Pair<Boolean, String> =
        collectOutput(p, timeoutMs)
}
