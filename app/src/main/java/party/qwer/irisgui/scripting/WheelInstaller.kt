package party.qwer.irisgui.scripting

import android.content.Context
import party.qwer.irisgui.RuntimeLog
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * WheelInstaller — 앱 안에서 순수 파이썬 wheel 을 받아 scripts/pylib 에 푼다.
 *
 *Cha quopy 의 런타임 pip install API 는 없다. 순수 파이썬 wheel
(*-py3-none-any.whl) 만 지원한다. C 확장 wheel 은 ABI 태그 (cp312, linux_x86_64 …)
가 붙으므로 풀어도 import 되지 않는다. 명확히 거부하고 Chaquopy pip {} 구성으로
안내한다. unpack 디렉터리는 PythonRuntime.configure() 가 sys.path 에 추가한다.
* deps 자동추적은 하지 않는다. wheel 은 보통 의존성 덩어리를 데려오지만 user 가
* 직접 요구패키지로 추가해야 한다.
 */
object WheelInstaller {
    private const val TAG = "WheelInstaller"
    private const val PYPI_JSON = "https://pypi.org/pypi"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Result(val ok: Boolean, val message: String)

    /** user wheel unpack 디렉터리. */
    fun libDir(context: Context): File =
        File(context.filesDir, "scripts/pylib")

    /** nameSpec: PyPI 프로젝트명 (버전 표기 포함 가능 "rich==13.7.1"). */
    fun install(context: Context, nameSpec: String): Result {
        val (name, version) = splitSpec(nameSpec)
        if (name.isBlank()) return Result(false, "패키지명 필요")
        val wheel = resolveWheel(name, version)
            ?: return Result(false, "$name — 순수 파이썬 wheel 없음")
        return try {
            unzip(wheel.bytes, wheel.filename, libDir(context))
            Result(true, "${wheel.name} ${wheel.version} 설치됨")
        } catch (t: Throwable) {
            Result(false, t.message ?: "설치 실패")
        }
    }

    /** unpack 되어있는 dist-info 기준 설치 목록. */
    fun installed(context: Context): List<String> {
        val d = libDir(context).listFiles()
            ?.filter { it.isDirectory && it.name.endsWith(".dist-info") }
            ?.map { it.name.removeSuffix(".dist-info") }
            ?: emptyList()
        return d.sorted()
    }

    /** RECORD 에 명시된 경로를 지워 wheel 을 제거. */
    fun uninstall(context: Context, name: String): Result {
        val root = libDir(context)
        val dist = root.listFiles { f ->
            f.isDirectory && f.name.endsWith(".dist-info") &&
                f.name.removeSuffix(".dist-info").substringBefore('-') == name
        }?.firstOrNull() ?: return Result(false, "$name 미설치")
        val record = File(dist, "RECORD")
        if (record.exists()) {
            for (line in record.readLines()) {
                val rel = line.substringBefore(',').trim()
                if (rel.isEmpty()) continue
                File(root, rel).let { f -> if (f.isFile) f.delete() }
            }
        }
        dist.deleteRecursively()
        val top = File(dist, "top_level.txt")
        if (top.exists()) {
            for (line in top.readLines()) {
                val pkg = line.trim()
                if (pkg.isNotEmpty()) File(root, pkg).deleteRecursively()
            }
        }
        return Result(true, "$name 제거됨")
    }

    // ── internals ──────────────────────────────────────────────────

    private data class Wheel(val name: String, val version: String, val filename: String, val bytes: ByteArray)

    private fun splitSpec(spec: String): Pair<String, String> {
        for (sep in arrayOf("==", ">=", "<=", "~=")) {
            val i = spec.indexOf(sep)
            if (i >= 0) return spec.substring(0, i).trim() to spec.substring(i + sep.length).trim()
        }
        return spec.trim() to ""
    }

    private fun resolveWheel(name: String, version: String): Wheel? {
        val url = if (version.isEmpty()) "$PYPI_JSON/$name/json" else "$PYPI_JSON/$name/$version/json"
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val json = JSONObject(body)
            val spec = selectPureWheel(json) ?: return null
            return downloadWheel(name, spec.first, spec.second, spec.third)
        }
    }

    /** PyPI JSON 에서 순수 파이썬 wheel (filename, url, sha256) 하나. */
    private fun selectPureWheel(json: JSONObject): Triple<String, String, String>? {
        if (json.has("urls")) return pickFromUrls(json.getJSONArray("urls"))
        if (json.has("releases")) {
            val releases = json.getJSONObject("releases")
            val keys = releases.keys().asSequence().toList().sorted()
            for (k in keys) {
                val arr = releases.getJSONArray(k)
                if (arr.length() == 0) continue
                return pickFromUrls(arr)
            }
            return null
        }
        return null
    }

    private fun pickFromUrls(urls: JSONArray): Triple<String, String, String>? {
        for (i in 0 until urls.length()) {
            val u = urls.getJSONObject(i)
            val fname = u.optString("filename")
            if (!fname.endsWith(".whl")) continue
            if (!isPurePythonWheel(fname)) continue
            val url = u.optString("url")
            val sha = u.optJSONObject("digests")?.optString("sha256").orEmpty()
            return Triple(fname, url, sha)
        }
        return null
    }

    /** filename: {name}-{ver}(-{build})?-{py}-{abi}-{platform}.whl */
    private fun isPurePythonWheel(fname: String): Boolean {
        val parts = fname.removeSuffix(".whl").split("-")
        if (parts.size < 5) return false
        val abi = parts[parts.size - 2]
        val platform = parts[parts.size - 1]
        return abi == "none" && platform.endsWith("any")
    }

    private fun downloadWheel(name: String, filename: String, url: String, sha256: String): Wheel? {
        val req = Request.Builder().url(url).get().build()
        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val data = res.body?.bytes() ?: return null
            if (sha256.isNotEmpty() && sha256Hex(data) != sha256.lowercase()) {
                RuntimeLog.warn(TAG, "$name sha256 mismatch")
                return null
            }
            val version = filename.substringAfter('-').takeWhile { it != '-' }
            return Wheel(name, version, filename, data)
        }
    }

    private fun unzip(data: ByteArray, filename: String, target: File) {
        if (!target.exists()) target.mkdirs()
        val tmp = File.createTempFile(filename, ".whl")
        try {
            tmp.writeBytes(data)
            ZipFile(tmp).use { zip ->
                val base = target.canonicalFile
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.isDirectory) continue
                    val dest = File(target, entry.name)
                    if (!dest.normalize().startsWith(base)) continue // Zip Slip 방어
                    dest.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { ins ->
                        dest.outputStream().use { out -> ins.copyTo(out) }
                    }
                }
            }
        } finally {
            tmp.delete()
        }
    }

    private fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it) }
}
