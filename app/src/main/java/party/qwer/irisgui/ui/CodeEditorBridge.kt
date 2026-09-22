package party.qwer.irisgui.ui

import android.content.Context
import android.webkit.JavascriptInterface
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.RuntimeLog
import party.qwer.irisgui.scripting.LinuxScripts
import party.qwer.irisgui.scripting.UserlandRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * WebView(Monaco/터미널) ↔ Kotlin 브리지. 편집 화면은 호스트 파일시스템을 직접 보고,
 * 터미널은 proot guest 셸의 stdio 를 JS 로 흘려준다.
 *
 * 경로는 전부 프로젝트 루트 상대이며 ".." 로 escapes 하면 접근 거부한다. 게스트 시선은
 * /home/projects/<name> 에 같은 트리가 바인드되어 있다.
 */
internal class CodeEditorBridge(
    private val context: Context,
    private val project: String,
    private val emit: (String) -> Unit,
    private val requestClose: () -> Unit = {}
) {

    /** terminalId → 백그라운드 proot 셸. */
    private class Term(
        val process: Process,
        val input: java.io.BufferedOutputStream,
        val id: Int
    )

    private val terms = ConcurrentHashMap<Int, Term>()
    private var termSeq = 0
    @Volatile private var closed = false

    fun close() {
        closed = true
        terms.values.forEach { runCatching { it.process.destroyForcibly() } }
        terms.clear()
    }

    // ── 경로 ───────────────────────────────────────────────────────────────

    private fun root(): File = File(UserlandRuntime.projectsHostDir(context), project)

    /** 상대(또는 절대 guest) 경로를 host File 로. 트리를 벗어나면 null. */
    private fun resolve(rel: String?): File? {
        val r = root().canonicalFile
        val target = when {
            rel == null || rel.isBlank() -> r
            rel.startsWith("/home/projects/") ->
                File(rel.removePrefix("/home/projects/").substringAfter('/'))
                    .let { File(r.parentFile, project + "/" + it) }
            rel.startsWith("/") -> File(r, rel.trimStart('/'))
            else -> File(r, rel)
        }
        return runCatching {
            val c = target.canonicalFile
            if (c == r || c.path.startsWith(r.path + File.separator)) c else null
        }.getOrNull()
    }

    /** host File 을 프로젝트 상대 표시 경로로. */
    private fun relOf(f: File): String =
        runCatching { f.canonicalFile.path.removePrefix(root().canonicalFile.path).trim('/') }
            .getOrDefault(f.name)

    // ── JS 로 공개되는 API ───────────────────────────────────────────────────

    @JavascriptInterface
    fun projectInfo(): String = JSONObject().apply {
        put("name", project)
        put("guest", UserlandRuntime.guestProject(context, project))
        put("api", "http://127.0.0.1:" + AppConfig.serverPort)
        put("venvPython", guestVenvPython())
    }.toString()

    private fun guestVenvPython(): String =
        UserlandRuntime.guestProject(context, project) + "/.venv/bin/python"

    /** 프로젝트 트리. venv/pycache/node_modules 등 노이즈는 정리해서 보인다. */
    @JavascriptInterface
    fun listDir(): String {
        val arr = JSONArray()
        walk(root(), arr, depth = 0)
        return arr.toString()
    }

    private fun walk(dir: File, out: JSONArray, depth: Int) {
        val kids = (dir.listFiles()?.filter { keep(it) } ?: return)
            .sortedWith(compareByDescending<File> { it.isDirectory }
                .thenBy { it.name.lowercase() })
        for (f in kids) {
            val o = JSONObject()
            o.put("name", f.name)
            o.put("path", relOf(f))
            o.put("dir", f.isDirectory)
            if (f.isFile) {
                o.put("size", f.length())
                o.put("mtime", f.lastModified())
            } else if (depth < 8) {
                val c = JSONArray()
                walk(f, c, depth + 1)
                if (c.length() > 0) o.put("children", c)
            }
            out.put(o)
        }
    }

    /** .venv/.git/__pycache__ 등은 접지만. 사이트패키지 트리를 트리에서 보여주는 건 무의미. */
    private fun keep(f: File): Boolean = when {
        f.name == ".git" || f.name == "__pycache__" || f.name == ".idea" ||
            f.name == "node_modules" || f.name == ".mypy_cache" || f.name == ".pytest_cache" -> false
        f.name == ".venv" -> true
        f.isDirectory && f.name.startsWith(".") -> false
        f.length() > 8L * 1024 * 1024 && f.isFile -> false
        else -> true
    }

    @JavascriptInterface
    fun read(rel: String): String? = runCatching {
        val f = resolve(rel) ?: return null
        if (!f.isFile || f.length() > 4_000_000) null else f.readText(Charsets.UTF_8)
    }.getOrNull()

    @JavascriptInterface
    fun write(rel: String, text: String): String = try {
        val f = resolve(rel) ?: return err("경로 밖 접근")
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
        ok("saved")
    } catch (e: Exception) {
        err(e.message ?: "write failed")
    }

    @JavascriptInterface
    fun create(rel: String, isDir: Boolean): String {
        val f = resolve(rel) ?: return err("경로 밖 접근")
        if (f.exists()) return err("이미 존재")
        return runCatching {
            if (isDir) f.mkdirs()
            else {
                f.parentFile?.mkdirs()
                if (!f.createNewFile()) return err("생성 실패")
                if (rel.endsWith(".py")) f.writeText(SAMPLE_FILE_TEMPLATE, Charsets.UTF_8)
            }
            ok("created")
        }.getOrElse { err(it.message ?: "create failed") }
    }

    @JavascriptInterface
    fun remove(rel: String): String {
        val f = resolve(rel) ?: return err("경로 밖 접근")
        if (f == root().canonicalFile) return err("프로젝트 자체는 삭제 불가")
        if (f.name == "main.py") return err("main.py 는 삭제 불가")
        return if (runCatching { f.deleteRecursively() }.getOrDefault(false) && !f.exists())
            ok("deleted") else err("삭제 실패")
    }

    @JavascriptInterface
    fun rename(from: String, to: String): String {
        val s = resolve(from) ?: return err("경로 밖 접근")
        val d = resolve(to) ?: return err("경로 밖 접근")
        if (!s.exists() || d.exists()) return err("rename 불가")
        return runCatching {
            d.parentFile?.mkdirs()
            if (s.renameTo(d)) ok("renamed") else err("rename 실패")
        }.getOrElse { err(it.message ?: "rename failed") }
    }

    /** 이 프로젝트 venv 에 설치된 패키지 + import 가능한 최상위 모듈 이름. */
    @JavascriptInterface
    fun pipPackages(): String {
        val venvRoot = File(root(), ".venv/lib")
        val sp = File(venvRoot, "python3.12/site-packages").takeIf { it.isDirectory }
            ?: venvRoot.walkTopDown().firstOrNull {
                it.isDirectory && it.name == "site-packages"
            }
        val modules = sortedSetOf<String>()
        val pkgs = JSONArray()
        if (sp != null && sp.isDirectory) {
            sp.listFiles()?.forEach { f ->
                when {
                    f.name.endsWith(".dist-info") -> {
                        val (n, v) = distInfo(f)
                        pkgs.put(JSONObject().put("name", n).put("version", v))
                    }
                    f.isDirectory && File(f, "__init__.py").isFile -> modules += f.name
                    f.isFile && f.name.endsWith(".py") -> modules += f.name.removeSuffix(".py")
                }
            }
            // dist-info 를 못 만들어 남는 namespace 패키지 등도 이름만은 잡힌다.
            sp.listFiles()?.forEach { f ->
                if (f.isDirectory && !f.name.endsWith(".dist-info") && !f.name.endsWith(".egg-info") &&
                    !modules.contains(f.name) && !f.name.startsWith(".")
                ) modules += f.name
            }
        }
        val o = JSONObject()
        val mods = JSONArray()
        modules.forEach { mods.put(it) }
        o.put("modules", mods)
        o.put("packages", pkgs)
        o.put("site", sp?.let { relOf(it) } ?: "")
        return o.toString()
    }

    private fun distInfo(f: File): Pair<String, String> {
        var name = f.name.substringBefore('-')
        val version = f.name.substringAfter('-', "").substringBefore('.').take(12)
        runCatching {
            f.resolve("METADATA").bufferedReader().useLines { seq ->
                for (l in seq) {
                    if (l.startsWith("Name:")) { name = l.substringAfter(':').trim(); break }
                }
            }
        }
        return name to version
    }

    @JavascriptInterface
    fun runScript(): String = runCatching {
        val r = LinuxScripts.start(context, project)
        if (r.ok) ok("running") else err(r.message)
    }.getOrElse { err(it.message ?: "run failed") }

    @JavascriptInterface
    fun stopScript(): String = runCatching {
        val r = LinuxScripts.stop(context, project)
        if (r.ok) ok("stopped") else err(r.message)
    }.getOrElse { err(it.message ?: "stop failed") }

    @JavascriptInterface
    fun scriptRunning(): String =
        LinuxScripts.statusAll(context).firstOrNull { it.name == project }?.running
            ?.let { JSONObject().put("running", it).toString() } ?: "{\"running\":false}"

    // ── 터미널 (proot guest 셸, cwd=프로젝트) ─────────────────────────────────

    @JavascriptInterface
    fun termStart(cols: Int, rows: Int): String = try {
        val guest = UserlandRuntime.guestProject(context, project)
        // script(script util) 가 PTY 를 잡아 대화가 유지된다. script 를 못 쓰면
        // bash -i(pipe 모드라 echo만 되는 반쪽)라도 살려둔다. exec 금지: 마지막
        // exec 가 죽으면 폴백 없이 세션이 종결된다.
        val inner =
            "cd " + UserlandRuntime.q(guest) + " || cd ~\n" +
                "{ [ -f .venv/bin/activate ] && . .venv/bin/activate; }\n" +
                "export PS1=" + UserlandRuntime.q("$ ") + "\n" +
                "stty rows 24 cols 120 2>/dev/null\n" +
                "script -qc bash /dev/null 2>&1\n" +
                "exec bash -i 2>&1"
        val p = UserlandRuntime.spawnBackground(context, inner, guest, null)
            ?: return err("터미널 시작 실패")
        val id = ++termSeq
        val term = Term(p, java.io.BufferedOutputStream(p.outputStream), id)
        terms[id] = term
        Thread {
            val b = ByteArray(8192)
            runCatching {
                p.inputStream.buffered().use { ins ->
                    while (!closed) {
                        val n = ins.read(b)
                        if (n < 0) break
                        emit("window.Term.onData(" + id + ",'" +
                            Base64.getEncoder().encodeToString(b.copyOfRange(0, n)) + "')")
                    }
                }
            }.onFailure {
                if (!closed) emit("window.Term.onExit($id, 'io')")
            }
            val code = runCatching { p.waitFor() }.getOrNull()
            RuntimeLog.info("Editor", "$project: terminal $id exited code=$code")
            runCatching { emit("window.Term.onExit($id)") }
            terms.remove(id)
        }.apply { isDaemon = true }.start()
        JSONObject().put("id", id).toString()
    } catch (e: Exception) {
        err(e.message ?: "term start failed")
    }

    @JavascriptInterface
    fun termInput(id: Int, b64: String) {
        val t = terms[id] ?: return
        runCatching {
            t.input.write(Base64.getDecoder().decode(b64))
            t.input.flush()
        }
    }

    /** script/bash 는 SIGWINCH 를 안 쓰므로 초기 size 로 고정. no-op 로 두면 창 크기 변화에 무관. */
    @JavascriptInterface
    fun termResize(id: Int, cols: Int, rows: Int) = Unit

    @JavascriptInterface
    fun termStop(id: Int) {
        terms.remove(id)?.let { runCatching { it.process.destroyForcibly() } }
    }

    @JavascriptInterface
    fun closeEditor() { requestClose() }

    @JavascriptInterface
    fun log(msg: String) {
        RuntimeLog.info("Editor", "$project: ${msg.take(300)}")
    }

    private fun ok(m: String) = JSONObject().put("ok", true).put("message", m).toString()
    private fun err(m: String) = JSONObject().put("ok", false).put("message", m).toString()

    companion object {
        private const val SAMPLE_FILE_TEMPLATE =
            "# -*- coding: utf-8 -*-\n# 새 모듈. main.py 에서 import 해서 사용하세요.\n"
    }
}
