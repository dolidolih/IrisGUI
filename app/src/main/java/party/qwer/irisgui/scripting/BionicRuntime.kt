package party.qwer.irisgui.scripting

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.RuntimeLog
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import org.tukaani.xz.LZMA2InputStream
import org.tukaani.xz.XZInputStream

/**
 * BionicRuntime — non-root 스크립트 런타임 (Termux bionic userland + linker64 구동로).
 *
 * W^X 판정에 근거한 Option A 구현. Android 10+(targetSdk 29+)는 앱 data 디렉터리의
 * 파일을 커널 execve 로 실행할 수 없지만(execve_no_trans 거부), /system/bin/linker64 로
 * ET_DYN(PIE) ELF 를 run-program 으로 띄우는 것은 AOSP 가 스스로 허용한다(Crashpad 규칙).
 * bionic 으로 빌드된 Termux 바이너리(전부 PIE)는 이 경로로 전 구간 실행된다:
 *
 *   exec:  setsid linker64 <prefix>/usr/bin/bash -c <inner>     (LD_PRELOAD=libwxshim)
 *   shim 이 bash/python 내부의 후속 execve까지 same-rule 로 재시도하므로 venv/bin/python,
 *   pip shebang, subprocess 가 data 에 있어도 동작한다. mmap(PROT_EXEC) 은 W^X 예외라
 *   디스크의 라이브러리/extension/스크립트는 위치와 무관하다.
 *
 * 프로비저닝: Termux apt stable 리포에서 python3(+pip) 의존성 closure deb 세트를
 * 내려해 ar(data.tar.xz) → prefix 로 언팩. 멱등; 전체 마커로 재실행 회피.
 *
 * 프로젝트: filesDir/bionic/home/projects — guest 개념 없이 host 경로를 그대로 쓴다.
 */
object BionicRuntime {
    private const val TAG = "Bionic"
    private const val REPO = "https://packages.termux.dev/apt/termux-main"

    /** 실행 클로저: python + pip + 셸/도구 + CA. 의존 closure 는 Packages 로부터 계산.
     * python-ensurepip-wheels 는 Recommends 라 자동 포함되지 않는다 — venv의 ensurepip가
     * 이 휠에 얹혀 있으므로 반드시 명시한다. */
    private val ROOT_PKGS = listOf(
        "python", "python-pip", "python-ensurepip-wheels", "bash", "coreutils",
        "ca-certificates", "python-pillow")

    /** sdist 컴파일 왕복(순수 파이썬/TUR 휠 아닌 C 확장)을 위한 최소 세트.
     * 표준은 온-기기 컴파일: TUR android-wheel 인덱스가覆盖面 못하는 층을
     * 터미눅 방식(clang+library deb)으로 연다. Shim 덕분에 cc1/lld 도
     * enforcing W^X 를 linker64 왕복으로 넘긴다. */
    internal val BUILD_TOOLS = listOf("clang", "lld", "binutils", "make", "pkg-config")

    /** 서버발(pip/iris-pk) 설치와 provision 의 직렬화 — 같은 prefix 를 쓴다. */
    private val installLock = java.util.concurrent.Semaphore(1)

    /** android abi → termux 패키지 arch. */
    private val ARCHES = mapOf(
        "arm64-v8a" to "aarch64", "x86_64" to "x86_64", "arm64-v8a!" to "aarch64",
        "armeabi-v7a" to "arm", "armeabi" to "arm", "x86" to "i686",
    )

    private const val MARKER = ".ready_runtime3"  // v2: shebang 경로 재배선 언팩 포함

    fun prefix(context: Context): File = File(context.filesDir, "bionic")

    /** Context 없는 데몬(AdbServer 루트)용 prefix. PACKAGE_NAME 과 같은 절대치라
     * 안전 — 실제 기기에서 uid/패키지는 바꿀 수 없다. */
    internal fun prefixNoContext(): File =
        File("/data/user/0/party.qwer.irisgui/files/bionic")

    fun projectsHostDir(context: Context): File =
        File(prefix(context), "home/projects").apply { mkdirs() }

    fun installed(context: Context): Boolean =
        File(prefix(context), MARKER).exists() && File(prefix(context), "usr/bin/bash").isFile

    /** python3 실행 가능 상태. 설치 확인 + cheap probe 는 status() 에서. */
    fun ready(context: Context): Boolean = installed(context)

    class Status(val ready: Boolean, val state: UserlandRuntime.State, val message: String)

    fun status(context: Context): Status {
        if (termuxArch() == null) return Status(false, UserlandRuntime.State.DISABLED, "미지원 ABI")
        if (!installed(context)) return Status(false, UserlandRuntime.State.NOT_INSTALLED, "미설치")
        // 오래된 설치에도 iris-pk 만은 언제든 보충 (파일 1회 쓰기라 status 에서 봐도 안전).
        ensurePkCommand(context, prefix(context))
        return Status(true, UserlandRuntime.State.READY, "준비됨")
    }

    private fun termuxArch(): String? =
        android.os.Build.SUPPORTED_ABIS.firstOrNull { it.replace("!", "") in ARCHES }
            ?.let { ARCHES[it.replace("!", "")] }

    // ── 커맨드 조립 ───────────────────────────────────────────────────────

    val linker: String
        get() = if (android.os.Build.SUPPORTED_ABIS.any { it.contains("64") })
            "/system/bin/linker64" else "/system/bin/linker"

    /** 프로세스 환경. preload shim 은 jniLib(nativeLibraryDir) 에 있다. */
    private fun envOf(context: Context): Map<String, String> {
        val p = prefix(context)
        return mapOf(
            "LD_LIBRARY_PATH" to File(p, "usr/lib").absolutePath,
            "LD_PRELOAD" to (context.applicationInfo.nativeLibraryDir + "/libwxshim.so"),
            // redroid(x86_64 컨테이너) 실측: 프로세스가 ~110 초 넘게 블로킹했다
            // 깨어나 첫 심벌(poll/PyEval_RestoreThread)을 lazy bind하면 점프 대상이
            // 로더 오프셋(0x5c56 등)으로 엉뚱하다 → SEGV. startup 시 전부 미리
            // bind(=BIND_NOW) 시켜 이 경로를 원천봉쇄한다.
            "LD_BIND_NOW" to "1",
            "PATH" to File(p, "usr/bin").absolutePath + ":/system/bin:/system/xbin",
            "HOME" to p.absolutePath,
            "TMPDIR" to File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath,
            "PREFIX" to p.absolutePath,
            "SHELL" to File(p, "usr/bin/bash").absolutePath,
            "TERM" to "xterm-256color",
            "SSL_CERT_FILE" to File(p, "usr/tls/certs/ca-certificates.crt").absolutePath,
            // TUR 의 android 전용 wheel 인덱스 — pandas/numpy/scipy 같은 C 확장도
            // 온-디바이스 컴파일 없이 android_<api>_<abi> 태그 왕복으로 설치된다.
            "PIP_EXTRA_INDEX_URL" to "https://termux-user-repository.github.io/pypi/",
            // 실기기 판별결과: 두 인덱스 max-version 이 sdist-전용(신버전)이면 왕을
            // 버리고 소스컴파일로 빠져 실패한다(pandas 함정). 기본은 "설치 가능만"
            // 으로 하고, 컴파일 의사는 pip 플래그(--no-binary)로 명시 opt-in 한다.
            "PIP_ONLY_BINARY" to ":all:",
            // 온-기기 C 컴파일(pip sdist) 왕복: clang 기본 prefix 가 com.termux 를
            // 박아놓고 태어남 → 우리 트리는 CPATH/LDFLAGS 로 붙여 쓴다.
            "CPATH" to File(p, "usr/include").absolutePath,
            // python이 sysconfig 에 박아둔 CC(x86_64-linux-android-clang) 는 남의
            // 빌드네임 — env CC/CXX 가 우선이고, 안 쓰는 세대를 위해 alias 도 깐다.
            "CC" to File(p, "usr/bin/clang").absolutePath,
            "CXX" to File(p, "usr/bin/clang++").absolutePath,
            "PKG_CONFIG_PATH" to File(p, "usr/lib/pkgconfig").absolutePath,
            "LDFLAGS" to "-L" + File(p, "usr/lib").absolutePath,
            "IRISGUI_API_URL" to "http://127.0.0.1:" + AppConfig.serverPort,
        )
    }

    /** bash 로 inner 문자열을 실행하는 ProcessBuilder. cwdHost: host cwd. */
    internal fun builder(
        context: Context, inner: String, cwdHost: File?, logFile: File?
    ): ProcessBuilder {
        val bash = File(prefix(context), "usr/bin/bash").absolutePath
        val pb = ProcessBuilder("/system/bin/setsid", linker, bash, "-c", inner)
        pb.environment().clear()
        pb.environment().putAll(envOf(context))
        pb.redirectErrorStream(true)
        if (cwdHost != null) pb.directory(cwdHost)
        if (logFile != null) pb.redirectOutput(logFile)
        return pb
    }

    internal fun exec(context: Context, inner: String, timeoutMs: Long, cwdHost: File? = null):
        UserlandRuntime.Exec {
        return try {
            val p = builder(context, inner, cwdHost, null).start()
            val (finished, out) = UserlandRuntime.collectOutputPublic(p, timeoutMs)
            if (!finished) {
                p.destroy()
                if (!p.waitFor(1, TimeUnit.SECONDS)) p.destroyForcibly()
                UserlandRuntime.Exec(-1, "timed out\n$out".trim())
            } else UserlandRuntime.Exec(p.exitValue(), out)
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "bionic exec 실패: ${e.message}")
            UserlandRuntime.Exec(-1, e.message ?: "exec error")
        }
    }

    internal fun spawnBackground(
        context: Context, inner: String, cwdHost: File?, logFile: File?
    ): Process? = try {
        builder(context, inner, cwdHost, logFile).start()
    } catch (e: Exception) {
        RuntimeLog.error(TAG, "bionic spawn 실패: ${e.message}")
        null
    }

    // ── 프로비저닝 ────────────────────────────────────────────────────────

    /** 멱등 설치. blocking. phase 텍스트로 진행률을 드러낸다(UI 는 status message). */
    fun provision(context: Context): UserlandRuntime.Result {
        val arch = termuxArch() ?: return UserlandRuntime.Result(false, "미지원 ABI")
        val p = prefix(context)
        if (installed(context)) {
            // 설치된 지 오래된 환경에는 iris-pk 가 아직 없다 — 커맨드만 멥등 보충.
            ensurePkCommand(context, p)
            return UserlandRuntime.Result(true, "준비됨")
        }

        val pkgs = try {
            resolveClosure(arch, ROOT_PKGS)
        } catch (e: Exception) {
            return UserlandRuntime.Result(false, "패키지 목록 읽기 실패: ${e.message}")
        }
        RuntimeLog.info(TAG, "bionic userland 준비 시작 (arch=$arch, ${pkgs.size} packages)")
        UserlandInstall.begin("termux bionic 패키지 ${pkgs.size}개 다운로드/언팩")
        UserlandInstall.total.value = pkgs.size
        p.mkdirs()

        val ok = installLock.tryAcquire(60, java.util.concurrent.TimeUnit.SECONDS)
        if (!ok) return UserlandRuntime.Result(false, "다른 설치 중 — 잠시 후 재시도")
        try {
            val r = installSet(context, pkgs)
            if (!r.ok) return r
        } finally {
            installLock.release()
        }
        writePkCommand(context)
        writeCompilerAliases(context)

        if (!File(p, "usr/bin/python3").exists()) return UserlandRuntime.Result(false, "python 없음 — 설치 검증 실패")
        ensureTrustStore(context)
        ensureAptSources(p)
        // 마커 쓰기 전에 venv/pip 왕복으로 실사용 확인. 실패하면 마커 없이 실패 반환.
        val probe = exec(context, "python3 -m venv --help >/dev/null 2>&1; " +
            "python3 -c 'import ssl,sqlite3,venv,ensurepip,pip,PIL' && echo BIONIC_OK", 120_000)
        if (!probe.output.contains("BIONIC_OK"))
            return UserlandRuntime.Result(false, "bionic python 검증 실패: ${probe.output.take(200)}")
        File(p, "home/projects").mkdirs()
        File(p, MARKER).writeText("ok")
        RuntimeLog.info(TAG, "bionic userland 준비 완료")
        return UserlandRuntime.Result(true, "파이썬 환경 준비됨")
    }

    /** Termux 는 ca-certificates 를 postinst 으로 생성하지만 우리에겐 postinst 가 없다.
     *  Android 시스템 스토어(/system/etc/security/cacerts, 파일마다 PEM)를 통째로
     *  이어붙여 python/pip 가 쓰는 SSL_CERT 파일을 만든다. */
    internal fun ensureTrustStore(context: Context) {
        val dest = File(prefix(context), "usr/tls/certs/ca-certificates.crt")
        if (dest.isFile && dest.length() > 1024) return
        val dir = File("/system/etc/security/cacerts")
        val certs = dir.listFiles()?.filter { it.length() > 512 }?.sorted() ?: return
        dest.parentFile?.mkdirs()
        dest.outputStream().bufferedWriter().use { w ->
            for (c in certs) runCatching { c.readText().let { if (it.contains("BEGIN CERTIFICATE")) w.write(it) } }
        }
    }

    /** Packages.gz 로부터 ROOT_PKGS closure 계산. Termux 는 binary-all 인덱스가 없고
     * Architecture: all 패키지까지 binary-<arch>/에 색인하므로 arch 하나만 읽는다. */
    /** pkg(터미눅 셸)의 업뎃 거울검사 는 apt sources.list 원본을 본다. dpkg/apt
     * 바이너리 자체는 우리 런타임에 없으므로 `pkg install` 은 불가하고 python
     * 확장 은 pip(TUR android wheel 인덱스 연동)를 쓴다. 파일만 만들어
     * 'No such file' 노이즈 를 없앤다. */
    private fun ensureAptSources(p: File) {
        val f = File(p, "usr/etc/apt/sources.list")
        if (f.isFile) return
        runCatching {
            f.parentFile?.mkdirs()
            f.writeText("deb $REPO stable main\n")
        }
    }

    // ── 비동기 패키지 작업 ────────────────────────────────────────────────
    // POST /pkg-install 은 즉시 접수만 받고 백그라운드로 처리한다. 왕복마다
    // 클라이언트(iris-pk)가 GET /pkg-install-status 로 넘겨받기: 클라이언트가
    // 단 하나 recv 에 ~110 초 이상 블로킹하면 redroid-x86_64 + termux python3.14
    // 의 PL lazy-bind 가 엉뚱한 오프셋으로 점프한다(2026-09 실측). 요청을 짧게
    // 유지하는 것이 요구사항이다 — 형이상식이 아니다.
    object PkgJob {
        @Volatile var running = false
        @Volatile var current = ""
        @Volatile var done = 0
        @Volatile var total = 0
        @Volatile var message = ""
        @Volatile var error = ""
    }

    fun statusJson(): Map<String, Any> = synchronized(PkgJob) {
        mapOf(
            "running" to PkgJob.running, "current" to PkgJob.current,
            "done" to PkgJob.done, "total" to PkgJob.total,
            "message" to PkgJob.message, "error" to PkgJob.error,
        )
    }


    /** sysconfig 가 부르는 clang 별명(abi-linux-android-clang 등)을 우리 clang 으로 튼다. */
    private fun writeCompilerAliases(context: Context) {
        val bin = File(prefix(context), "usr/bin")
        val tag = when (termuxArch()) {
            "aarch64" -> "aarch64-linux-android"
            "x86_64" -> "x86_64-linux-android"
            "arm" -> "arm-linux-androideabi"
            else -> null
        } ?: return
        for (name in listOf("${tag}-clang", "${tag}-clang++", "${tag}-gcc", "${tag}-cc", "gcc", "c++")) {
            val f = File(bin, name)
            if (f.isFile && f.length() > 0) continue
            f.writeText("#!/system/bin/sh\nexec \"${'$'}(dirname \"${'$'}0\")/clang\" \"${'$'}@\"\n")
            runCatching { android.system.Os.chmod(f.absolutePath, 0b111111101) }
        }
    }

    /** 백그라운드로 설치를 시작한다. (accepted, message). 이미 실행 중이면
     * 접수만 true 로 알려주고 상태 폴링에 맡긴다. */
    fun startAsync(context: Context?, names: List<String>): Pair<Boolean, String> {
        if (names.isEmpty()) return false to "패키지 이름 없음"
        synchronized(PkgJob) {
            if (PkgJob.running) return true to "이미 설치 진행 중"
            PkgJob.running = true
            PkgJob.current = "의존성 해석"
            PkgJob.done = 0
            PkgJob.total = 0
            PkgJob.message = ""
            PkgJob.error = ""
        }
        Thread {
            val r = runCatching { installNamed(context, names) }
                .getOrElse { UserlandRuntime.Result(false, "설치 예외: ${it.message}") }
            synchronized(PkgJob) {
                PkgJob.message = r.message
                if (!r.ok) PkgJob.error = r.message
                PkgJob.running = false
                PkgJob.current = ""
            }
        }.apply { isDaemon = true; name = "iris-pk-job"; start() }
        return true to "설치 시작"
    }

    /** 임의 Termux deb 설치 — iris-pk/pip 전역에서 오는 의존성 왕복. 루트 이름
     * 리스트의 의존 closure까지 받아 같은 트리에 언팩한다. `--build-tools` 는
     * clang 세트 전개. userland 가 아직 없으면 provision 을 먼저 요구한다. */
    internal fun installNamed(context: Context?, names: List<String>): UserlandRuntime.Result {
        if (names.isEmpty()) return UserlandRuntime.Result(false, "패키지 이름 없음")
        val p = if (context != null) prefix(context) else prefixNoContext()
        if (!File(p, "usr/bin/bash").isFile)
            return UserlandRuntime.Result(false, "userland 없음 — 리눅스 환경 설치 먼저")
        val arch = termuxArch() ?: return UserlandRuntime.Result(false, "미지원 ABI")
        val roots = names.flatMap { n ->
            when {
                n == "--build-tools" -> BUILD_TOOLS
                n == "--help" || n == "-h" -> return UserlandRuntime.Result(true,
                    "iris-pk install <deb...> [--build-tools] — 터미눅 main 저장소 클로저. C 확장 컴파일 도구는 --build-tools, 라이브러리/툴은 이름 직접(libjpeg-turbo, openssl…)")
                else -> listOf(n)
            }
        }
        if (roots.isEmpty()) return UserlandRuntime.Result(true, "요청 없음")
        val pkgs = try {
            resolveClosure(arch, roots)
        } catch (e: Exception) {
            return UserlandRuntime.Result(false, "패키지 목록 읽기 실패: ${e.message}")
        }
        PkgJob.total = pkgs.size
        val waited = installLock.tryAcquire(600, java.util.concurrent.TimeUnit.SECONDS)
        if (!waited) return UserlandRuntime.Result(false, "다른 설치 중 — 잠시 후 재시도")
        val r = try {
            installSet(context, pkgs)
        } finally {
            installLock.release()
        }
        if (r.ok) RuntimeLog.info(TAG, "iris-pk 설치 완료: $names")
        return r
    }

    /** download+언팩 루프의 단 하나 구현. pkgs: name→pool path.
     * context가 없는 데몬(app_process, 루트)도 같은 prefix 에 쓰는 경로를 탄다. */
    private fun installSet(context: Context?, pkgs: List<Pair<String, String>>): UserlandRuntime.Result {
        val p = if (context != null) prefix(context) else prefixNoContext()
        val tmp = if (context != null) File(context.cacheDir, "debtmp")
            else File("/data/local/tmp/irisgui-debtmp")
        tmp.apply { deleteRecursively(); mkdirs() }
        try {
            for ((i, pair) in pkgs.withIndex()) {
                val (name, filename) = pair
                PkgJob.current = name; PkgJob.done = i; PkgJob.total = pkgs.size
                if (UserlandInstall.running.value) UserlandInstall.step(name, i, pkgs.size)
                val deb = File(tmp, "$name.deb")
                if (!download("$REPO/$filename", deb, 300_000))
                    return UserlandRuntime.Result(false, "$name 다운로드 실패")
                if (!extractDeb(deb, p))
                    return UserlandRuntime.Result(false, "$name 언팩 실패")
                runCatching { deb.delete() }
                if (i % 4 == 3) RuntimeLog.info(TAG, "bionic: ${i + 1}/${pkgs.size} 패키지 설치")
            }
        } finally {
            runCatching { tmp.deleteRecursively() }
        }
        return UserlandRuntime.Result(true, "설치 완료")
    }

    /** 게스트에 iris-pk 커맨드 생성(멱등) — 앱의 /pkg-install REST 를 두드리는
     * stdlib 만 쓰는 최소 python 클라이언트. */
    /** 구/신 프로토콜 구별: 상태 폴링 엔드포인트 문구가 있으면 신식. */
    private fun ensurePkCommand(context: Context, p: File) {
        val f = File(p, "usr/bin/iris-pk")
        val fresh = f.isFile && runCatching { f.readText().contains("/pkg-install-status") }.getOrDefault(false)
        if (!fresh) runCatching { writePkCommand(context) }
        runCatching { writeCompilerAliases(context) }
    }

    private fun writePkCommand(context: Context) {
        val bin = File(prefix(context), "usr/bin")
        bin.mkdirs()
        val f = File(bin, "iris-pk")
        val python = File(prefix(context), "usr/bin/python3").absolutePath
        // 접수 즉시반답 + 짧은 폴링 — 클라이언트 단일 블로킹 금지 (PkgJob 주석 참조).
        val body = buildString {
            append("#!").append(python).append("\n")
            append("# iris-pk v2 — IrisGUI bionic userland 패키지 설치 클라이언트\n")
            append("#   iris-pk install <deb...> [--build-tools]\n")
            append("import json, os, sys, time, urllib.request\n\n")
            append("def api(path, payload=None):\n")
            append("    base = os.environ.get(\"IRISGUI_API_URL\", \"http://127.0.0.1:3000\").rstrip(\"/\")\n")
            append("    req = urllib.request.Request(base + path,\n")
            append("        data=json.dumps(payload).encode() if payload is not None else None,\n")
            append("        headers={\"Content-Type\": \"application/json\"})\n")
            append("    with urllib.request.urlopen(req, timeout=30) as r:\n")
            append("        return json.loads(r.read().decode())\n\n")
            append("def main(argv):\n")
            append("    if len(argv) < 2 or argv[0] != \"install\":\n")
            append("        print(\"usage: iris-pk install <deb...> [--build-tools]\")\n")
            append("        return 1\n")
            append("    try:\n")
            append("        api(\"/pkg-install\", {\"packages\": argv[1:]})\n")
            append("    except Exception as e:\n")
            append("        print(\"iris-pk: 서버 응답 없음 (앱 실행 중이어야 함):\", e)\n")
            append("        return 1\n")
            append("    seen = \"\"\n")
            append("    deadline = time.time() + 3600\n")
            append("    while True:\n")
            append("        time.sleep(2)\n")
            append("        try:\n")
            append("            st = api(\"/pkg-install-status\")\n")
            append("        except Exception as e:\n")
            append("            print(\"iris-pk: 상태 확인 지연:\", e)\n")
            append("            if time.time() > deadline: return 1\n")
            append("            continue\n")
            append("        if not st.get(\"running\"):\n")
            append("            print(st.get(\"message\") or \"설치 완료\")\n")
            append("            return 1 if st.get(\"error\") else 0\n")
            append("        cur = \"%s (%d/%d)\" % (st.get(\"current\", \"\"), st.get(\"done\", 0), st.get(\"total\", 0))\n")
            append("        if cur != seen:\n")
            append("            seen = cur\n")
            append("            print(\"...\", cur, flush=True)\n")
            append("        if time.time() > deadline:\n")
            append("            print(\"iris-pk: 설치 대기 시간 초과\")\n")
            append("            return 1\n\n")
            append("if __name__ == \"__main__\":\n")
            append("    sys.exit(main(sys.argv[1:]))\n")
        }
        f.writeText(body)
        runCatching { android.system.Os.chmod(f.absolutePath, 0b111101101) }
    }

    private fun resolveClosure(
        arch: String, roots: List<String> = ROOT_PKGS
    ): List<Pair<String, String>> {
        val index = HashMap<String, Pair<String, String>>() // name → (depends, filename)
        val txt = fetchPackagesGz("$REPO/dists/stable/main/binary-$arch/Packages.gz")
        var name = ""
        var depends = ""
        var filename = ""
        fun flush() {
            if (name.isNotEmpty() && filename.isNotEmpty()) index[name] = depends to filename
            name = ""; depends = ""; filename = ""
        }
        txt.lineSequence().forEach { line ->
            when {
                line.isEmpty() -> flush()
                line.startsWith("Package: ") -> name = line.substring(9).trim()
                line.startsWith("Depends: ") -> depends = line.substring(9)
                line.startsWith("Filename: ") -> filename = line.substring(10).trim()
            }
        }
        flush()
        val out = LinkedHashMap<String, String>()
        val queue = ArrayDeque(roots)
        val seen = HashSet<String>()
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            if (n in seen) continue
            seen += n
            val entry = index[n] ?: continue
            val (dep, filename) = entry
            out[n] = filename
            parseDepends(dep).forEach { queue.addLast(it) }
        }
        return out.map { it.key to it.value }
    }

    /** "a (>= 1), b | c" → [a, b, c] (첫 후보 우선은 큐에 그대로 넣은 뒤 index 조회). */
    private fun parseDepends(dep: String): List<String> =
        dep.split(',').mapNotNull { part ->
            part.split('|').firstOrNull { it.trim().isNotEmpty() }
                ?.trim()?.substringBefore('(')?.substringBefore('[')?.trim()
        }

    private fun fetchPackagesGz(url: String): String = URL(url).openStream().use { raw ->
        GZIPInputStream(raw, 1 shl 16).reader().readText()
    }

    // ── deb / tar 언팩 ───────────────────────────────────────────────────

    /** debian ar(.deb)의 data.tar.{xz,gz} 회원을 dest 트리에 언팩. */
    internal fun extractDeb(deb: File, dest: File): Boolean {
        try {
            RandomAccessFile(deb, "r").use { raf ->
                if (raf.length() < 120) {
                    RuntimeLog.warn(TAG, "deb 너무 작음: ${deb.name} ${deb.length()}")
                    return false
                }
                val head = ByteArray(8)
                raf.readFully(head)
                if (String(head) != "!<arch>\n") {
                    RuntimeLog.warn(TAG, "deb magic 아님: ${deb.name} ${deb.length()} " +
                        head.joinToString(" ") { "%02x".format(it) })
                    return false
                }
                var off = 8L
                while (off + 60 <= raf.length()) {
                    raf.seek(off)
                    val hdr = ByteArray(60)
                    raf.readFully(hdr)
                    val h = String(hdr, Charsets.ISO_8859_1)
                    // deb 의 ar 멤버명은 트레일링 슬래시를 동반한다 ("data.tar.xz/").
                    val name = h.substring(0, 16).trim().removeSuffix("/")
                    val size = runCatching { h.substring(48, 58).trim().toLong() }.getOrNull()
                        ?: return false
                    val bodyOff = off + 60
                    if (name != "`" && name.startsWith("data.tar")) {
                        // 멤버는 스트림만 넘긴다 — clang/libllvm 급 deb은 언팩 산출물이
                        // GB급이라 바이트배열 사수하면 OOM(데몬 500의 실체).
                        raf.seek(bodyOff)
                        val ok = extractDataTar(raf, name, dest)
                        if (!ok) RuntimeLog.warn(TAG, "언팩 실패: ${deb.name} $name/$size")
                        return ok
                    }
                    off = bodyOff + size + (size % 2)
                }
                RuntimeLog.warn(TAG, "deb에 data.tar 없음: ${deb.name}")
            }
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "extractDeb ${deb.name}: $e")
        }
        return false
    }


    private fun name16(h: ByteArray): String =
        String(h, 0, 16, Charsets.ISO_8859_1).replace('\u0000', '?')

    /** deb의 data.tar.{xz,gz} 회원을 RandomAccessFile position에서 스트리밍 디코딩. */
    private fun extractDataTar(raf: java.io.RandomAccessFile, name: String, dest: File): Boolean {
        val inp = try {
            when {
                name.endsWith(".xz") -> org.tukaani.xz.XZInputStream(java.io.BufferedInputStream(RafInputStream(raf), 1 shl 16))
                name.endsWith(".gz") -> java.util.zip.GZIPInputStream(java.io.BufferedInputStream(RafInputStream(raf), 1 shl 16))
                name.endsWith(".bz2") -> return false // 미지원 — Termux 는 xz 고정
                else -> RafInputStream(raf)
            }
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "xz/gz 디코딩 실패 ($name): $e")
            return false
        }
        return try {
            inp.use { untarStream(it, dest) }
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "untar 실패: $e")
            false
        }
    }

    /** RandomAccessFile current position부터 readable stream. deb ar 멤버를
     * 통째로 읽지 않고 디코딩만 탈 있게 하는 어댑터. */
    private class RafInputStream(private val raf: java.io.RandomAccessFile) : java.io.InputStream() {
        override fun read(): Int = runCatching { raf.read() }.getOrDefault(-1)
        override fun read(b: ByteArray, off: Int, len: Int): Int = runCatching {
            raf.read(b, off, len)
        }.getOrDefault(-1)
        override fun available(): Int = 0
        override fun close() {}
    }

    /** Streaming tar reader._termux debs(libllvm 등)은 언팩이 GB급 — 내용물은
     * 복사본을 만들지 않고 바로 파일로 쓴다. ustar/GNU/pax 모두 대응. */
    private fun untarStream(inp: java.io.InputStream, dest: File): Boolean {
        val header = ByteArray(512)
        var longName: String? = null
        var longLink: String? = null
        var nfile = 0
        var nlink = 0
        var ndir = 0

        fun readBlock(): Boolean {
            var off = 0
            while (off < 512) {
                val n = inp.read(header, off, 512 - off)
                if (n < 0) {
                    // eof 감지: 블록 경계(off==0)에서만 정상 종료. 중간 읽힘이면 잘림.}
                    if (off != 0) throw java.io.IOException("tar 잘림")
                    return false
                }
                if (n == 0) continue      // never spin
                off += n
            }
            return true
        }
        fun oct(h: ByteArray, s: Int, e: Int): String =
            String(h, s, e - s, Charsets.ISO_8859_1).trim('\u0000', ' ')

        while (readBlock()) {
            if (header.all { it == 0.toByte() }) continue // end-of-archive zero blocks
            val size = runCatching {
                if (header[312].toInt() and 0xff == 0x80) {
                    var v = 0L
                    for (i in 313 until 324) v = (v shl 8) or (header[i].toLong() and 0xff)
                    v
                } else oct(header, 124, 136).trim().let { if (it.isEmpty()) 0 else it.toLong(8) }
            }.getOrDefault(-1L)
            if (size < 0) { RuntimeLog.warn(TAG, "untar: size 파싱 불가 pos? 헤더=" + name16(header)); return false }
            var name = oct(header, 0, 100)
            val prefix = oct(header, 345, 500)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            if (longName != null) { name = longName; longName = null }
            val type = (header[156].toInt() and 0xff).toChar()
            val mode = oct(header, 100, 108).trim().toLongOrNull(8) ?: 0b110100100
            val link = oct(header, 157, 157 + 100)
            val padded = ((size + 511) / 512) * 512

            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                // special 헤더: L=긴 이름, K=긴 링크 경로(GNU), x/g=pax. 전부 read-all.
                val b = ByteArray(size.toInt())
                var got = 0
                while (got < b.size) {
                    val n = inp.read(b, got, b.size - got)
                    if (n < 0) { RuntimeLog.warn(TAG, "untar: special 멤버 truncated"); return false }
                    got += n
                }
                when (type) {
                    'L' -> longName = String(b, Charsets.UTF_8).trim('\u0000')
                    'K' -> longLink = String(b, Charsets.UTF_8).trim('\u0000')
                    else -> {
                        val m = Regex("(?:^|\\n)\\d+ path=([^\\n]+)").find(String(b, Charsets.UTF_8))
                        if (m != null) longName = m.groupValues[1]
                    }
                }
                skip(inp, padded - size)
                continue
            }

            var rel = name.removePrefix("./")
            val strip = "data/data/com.termux/files/"
            rel = if (rel.startsWith(strip)) rel.substring(strip.length) else rel

            if (rel.isNotEmpty()) {
                val target = File(dest, rel)
                target.parentFile?.mkdirs()
                try {
                    when (type) {
                        '/' -> { target.mkdirs(); ndir++; skip(inp, padded) }
                        '0', '\u0000' -> {
                            // 실행 중인 바이너리/공유라이브러리를 open+truncate 로
                            // 덮어쓰면 mapped text 페이지가 사라져 프로세스가 SEGV 한다
                            // (python deb 언팩 실측). 신버전만 새파일에 쓰고 rename —
                            // 살아있는 구동자는 옛 inode 를 계속 쓴다.
                            val fresh = if (target.isFile) File(target.parentFile, target.name + ".irisnew") else null
                            if (fresh != null) {
                                fresh.outputStream().use { copyN(inp, it, size) }
                                skip(inp, padded - size)
                                // deb 의 owner-mode 를 믿지 말 것: root-unpack 에서 other 읽기/실행
                                // 이 없으면 앱 uid 는 언팩된 바이너리조차 못 쓴다 — 0755 를下限으로 탄다.
                                android.system.Os.chmod(fresh.absolutePath, (mode.toInt() and 0xfff) or 0x1ff)
                                runCatching { android.system.Os.rename(fresh.absolutePath, target.absolutePath) }
                                    .onFailure { runCatching { fresh.delete() } }
                            } else {
                                target.outputStream().use { copyN(inp, it, size) }
                                skip(inp, padded - size)
                                android.system.Os.chmod(target.absolutePath, (mode.toInt() and 0xfff) or 0x1ff)
                            }
                            nfile++
                        }
                        '2' -> {
                            runCatching { target.delete() }
                            android.system.Os.symlink(link, target.absolutePath)
                            nlink++; skip(inp, padded)
                        }
                        '1' -> {
                            val target0 = (longLink ?: link)
                            longLink = null
                            val src = File(dest, target0.removePrefix("./"))
                            runCatching { target.delete() }
                            if (src.exists()) runCatching { android.system.Os.link(src.absolutePath, target.absolutePath) }
                                .onFailure { runCatching { target.delete(); android.system.Os.symlink(link, target.absolutePath) } }
                            else runCatching { target.delete(); android.system.Os.symlink(link, target.absolutePath) }
                            skip(inp, padded)
                        }
                        else -> skip(inp, padded) // devices/fifo — userland 불필요
                    }
                } catch (e: Exception) {
                    if (!(e is java.io.IOException && e.message?.contains("File exists") == true))
                        RuntimeLog.warn(TAG, "tar 멤버 실패 $rel: ${e.message}")
                    skip(inp, padded)
                }
            } else skip(inp, padded)
        }
        RuntimeLog.info(TAG, "untar 완료: files=$nfile links=$nlink dirs=$ndir")
        return true
    }

    /** n 바이트만 body 스트림으로 복사. */
    private fun copyN(inp: java.io.InputStream, out: java.io.OutputStream, n: Long) {
        val buf = ByteArray(1 shl 16)
        var left = n
        while (left > 0) {
            val got = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (got < 0) throw java.io.IOException("tar truncated")
            out.write(buf, 0, got)
            left -= got
        }
    }

    /** n 바이트 패딩 폐기. */
    private fun skip(inp: java.io.InputStream, n: Long) {
        val buf = ByteArray(4096)
        var left = n
        while (left > 0) {
            val got = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (got < 0) return
            left -= got
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true).build()

    private fun download(url: String, target: File, timeoutMs: Long): Boolean = runCatching {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            resp.body?.byteStream()?.use { ins ->
                target.outputStream().use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                    out.flush()
                }
            } ?: return false
            target.length() >= 8
        }
    }.getOrDefault(false)
}
