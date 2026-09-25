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

    /** android abi → termux 패키지 arch. */
    private val ARCHES = mapOf(
        "arm64-v8a" to "aarch64", "x86_64" to "x86_64", "arm64-v8a!" to "aarch64",
        "armeabi-v7a" to "arm", "armeabi" to "arm", "x86" to "i686",
    )

    private const val MARKER = ".ready_runtime2"  // v2: shebang 경로 재배선 언팩 포함

    fun prefix(context: Context): File = File(context.filesDir, "bionic")

    fun projectsHostDir(context: Context): File =
        File(prefix(context), "home/projects").apply { mkdirs() }

    fun installed(context: Context): Boolean =
        File(prefix(context), MARKER).exists() && File(prefix(context), "usr/bin/bash").isFile

    /** python3 실행 가능 상태. 설치 확인 + cheap probe 는 status() 에서. */
    fun ready(context: Context): Boolean = installed(context)

    class Status(val ready: Boolean, val state: UserlandRuntime.State, val message: String)

    fun status(context: Context): Status {
        if (termuxArch() == null) return Status(false, UserlandRuntime.State.DISABLED, "미지원 ABI")
        return if (installed(context)) Status(true, UserlandRuntime.State.READY, "준비됨")
        else Status(false, UserlandRuntime.State.NOT_INSTALLED, "미설치")
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
            "PATH" to File(p, "usr/bin").absolutePath + ":/system/bin:/system/xbin",
            "HOME" to p.absolutePath,
            "TMPDIR" to File(context.cacheDir, "tmp").apply { mkdirs() }.absolutePath,
            "PREFIX" to p.absolutePath,
            "SHELL" to File(p, "usr/bin/bash").absolutePath,
            "TERM" to "xterm-256color",
            "SSL_CERT_FILE" to File(p, "usr/tls/certs/ca-certificates.crt").absolutePath,
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
        if (installed(context)) return UserlandRuntime.Result(true, "준비됨")

        val pkgs = try {
            resolveClosure(arch)
        } catch (e: Exception) {
            return UserlandRuntime.Result(false, "패키지 목록 읽기 실패: ${e.message}")
        }
        RuntimeLog.info(TAG, "bionic userland 준비 시작 (arch=$arch, ${pkgs.size} packages)")
        p.mkdirs()

        val tmp = File(context.cacheDir, "debtmp").apply { deleteRecursively(); mkdirs() }
        try {
            for ((i, pair) in pkgs.withIndex()) {
                val (name, filename) = pair
                val deb = File(tmp, "$name.deb")
                if (!download("$REPO/$filename", deb, 300_000))
                    return UserlandRuntime.Result(false, "$name 다운로드 실패")
                if (!extractDeb(deb, p)) {
                    runCatching { deb.delete() }
                    return UserlandRuntime.Result(false, "$name 언팩 실패")
                }
                runCatching { deb.delete() }
                if (i % 4 == 3) RuntimeLog.info(TAG, "bionic: ${i + 1}/${pkgs.size} 패키지 설치")
            }
        } finally {
            runCatching { tmp.deleteRecursively() }
        }

        if (!File(p, "usr/bin/python3").exists()) return UserlandRuntime.Result(false, "python 없음 — 설치 검증 실패")
        ensureTrustStore(context)
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
    private fun resolveClosure(arch: String): List<Pair<String, String>> {
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
        val queue = ArrayDeque(ROOT_PKGS)
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
                        val body = ByteArray(size.toInt())
                        raf.seek(bodyOff)
                        raf.readFully(body)
                        val ok = extractDataTar(body, name, dest)
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

    private fun extractDataTar(body: ByteArray, name: String, dest: File): Boolean {
        val data = try {
            when {
                name.endsWith(".xz") ->
                    XZInputStream(java.io.ByteArrayInputStream(body)).readBytes()
                name.endsWith(".gz") ->
                    GZIPInputStream(java.io.ByteArrayInputStream(body)).readBytes()
                name.endsWith(".bz2") -> return false // 미지원 — Termux 는 xz 고정
                else -> body
            }
        } catch (e: Exception) {
            RuntimeLog.error(TAG, "xz/gz 디코딩 실패 ($name): $e")
            return false
        }
        return untar(data, dest)
    }

    /** ustar/GNU tar 리더. symlink/hardlink/dir/file. prefix는 data/data/com.termux/files 제거. */
    private fun untar(bytes: ByteArray, dest: File): Boolean {
        var pos = 0
        var nfile = 0
        var nlink = 0
        var ndir = 0
        var longName: String? = null
        while (pos + 512 <= bytes.size) {
            if (bytes[pos] == 0.toByte()) { pos += 512; continue }
            val header = bytes.copyOfRange(pos, pos + 512)
            val oct = fun(s: Int, e: Int): String =
                String(header, s, e - s, Charsets.ISO_8859_1).trim('\u0000', ' ')
            val size = runCatching {
                if (header[312].toInt() and 0xff == 0x80) oct(312, 324).toLong(8)
                else oct(124, 136).trim().let { if (it.isEmpty()) 0 else it.toLong(8) }
            }.getOrDefault(-1L)
            if (size < 0) return false
            var name = oct(0, 100)
            val prefix = oct(345, 500)
            if (prefix.isNotEmpty()) name = "$prefix/$name"
            if (longName != null) { name = longName; longName = null }
            val type = (header[156].toInt() and 0xff).toChar()
            val mode = oct(100, 108).trim().toLongOrNull(8) ?: 0b110100100
            val link = oct(157, 157 + 100)
            val dataStart = pos + 512
            val body = bytes.copyOfRange(dataStart, dataStart + size.toInt())
            pos = dataStart + ((size + 511) / 512 * 512).toInt()

            // termux 는 data/data/com.termux/files/usr/... 구조로 담는다.
            var rel = name.removePrefix("./")
            val strip = "data/data/com.termux/files/"
            rel = if (rel.startsWith(strip)) rel.substring(strip.length)
            else if (rel.startsWith("./$strip")) rel.substring(strip.length + 2) else rel
            if (rel.isEmpty()) continue

            if (type == 'L') { longName = String(body, Charsets.UTF_8).trim('\u0000'); continue }
            if (type == 'x' || type == 'g') { // pax header → 경로만 추출
                val m = Regex("(?:^|\\n)\\d+ path=([^\\n]+)").find(String(body))
                if (m != null) longName = m.groupValues[1]
                continue
            }

            val target = File(dest, rel)
            target.parentFile?.mkdirs()
            try {
                when (type) {
                    '/' -> { target.mkdirs(); ndir++ }
                    '0', '\u0000' -> {
                        target.outputStream().use { it.write(rebindTermuxPaths(body, dest)) }
                        android.system.Os.chmod(target.absolutePath, mode.toInt() and 0xfff)
                        nfile++
                    }
                    '2' -> {
                        runCatching { target.delete() }
                        android.system.Os.symlink(link, target.absolutePath)
                        nlink++
                    }
                    '1' -> {
                        // hardlink: 같은 트리 내 상대 링크로 재구성. 없으면 symlink 폴백.
                        val src = File(dest, link.removePrefix("./"))
                        runCatching { target.delete() }
                        if (src.exists()) runCatching { android.system.Os.link(src.absolutePath, target.absolutePath) }
                            .onFailure { runCatching { target.delete(); android.system.Os.symlink(link, target.absolutePath) } }
                        else runCatching { target.delete(); android.system.Os.symlink(link, target.absolutePath) }
                    }
                    else -> { /* chars/devices/fifo — userland 에 불필요, skip */ }
                }
            } catch (e: Exception) {
                // symlink 이 이미 있을 수 있다 등 — tree 계층은 계속 진행.
                if (!(e is java.io.IOException && e.message?.contains("File exists") == true))
                    RuntimeLog.warn(TAG, "tar 멤버 실패 $rel: ${e.message}")
            }
        }
        RuntimeLog.info(TAG, "untar 완료: files=$nfile links=$nlink dirs=$ndir")
        return true
    }

    /** Termux deb 의 내용물은 전부 /data/data/com.termux/files/ 하드코딩으로
     * 빌드된다. 남의 host 에는 그 경로가 없으므로 스크립트(#! 헤더)만
     * 바이트 안전한 문자열 치환으로 우리 userland 루트를 가리키게 다시 배선한다.
     * ELF 바이너리는 손대지 못하지만 python/venv 런타임은 자기 경유로
     * 잡으므로(binfmt shebang 은 interpreter 경로 문제 → shim/linker 왕복) 실해지 적다. */
    private fun rebindTermuxPaths(body: ByteArray, dest: File): ByteArray {
        if (body.size < 32 || body.size > 8_000_000) return body
        if (!(body[0] == '#'.code.toByte() && body[1] == '!'.code.toByte())) return body
        val s = String(body, Charsets.ISO_8859_1)
        val from = "/data/data/com.termux/files/"
        if (!s.contains(from)) return body
        return s.replace(from, dest.absolutePath + "/").toByteArray(Charsets.ISO_8859_1)
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(300, TimeUnit.SECONDS)
        .followRedirects(true).build()

    private fun download(url: String, target: File, timeoutMs: Long): Boolean = runCatching {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return false
            resp.body?.byteStream()?.use { ins ->
                target.outputStream().use { out -> ins.copyTo(out) }
            } ?: return false
            target.length() >= 8
        }
    }.getOrDefault(false)
}
