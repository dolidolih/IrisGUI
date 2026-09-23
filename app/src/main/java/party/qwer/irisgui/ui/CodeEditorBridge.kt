package party.qwer.irisgui.ui

import android.content.Context
import android.view.inputmethod.InputMethodManager
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
    ) {
        @Volatile var lastInput = 0L
        @Volatile var pending: Pair<Int, Int>? = null
    }

    // 셸은 바뀐 것도 아닌 stty 명령을 에코해서 화면을 어지럽힌다. 크기 파일(/home/projects/
    // .termsize 는 guest 와 host 가 같은 디렉터리라 바운드 필요 없음)에 쓰고 프롬프트마다
    // PROMPT_COMMAND 가 반영하게 한다. 입력 줄에 끼어드는 일도 없다.
    private val sizeFile = File(UserlandRuntime.projectsHostDir(context), ".termsize")

    private fun writeSizeFile(cols: Int, rows: Int) {
        runCatching { sizeFile.writeText("$cols $rows\n") }
    }

    private fun Term.applyResize(cols: Int, rows: Int) = Unit


    private val terms = ConcurrentHashMap<Int, Term>()
    private var termSeq = 0
    @Volatile private var closed = false

    /** 화면 키보드 게이트. false 이면 WebView 의 IM 연결/표시를 막는다. */
    @Volatile var keyboardAllowed = false
        private set
    @Volatile private var imeOverride = false
    /** 게이트 상태가 바뀌었을 때 화면(실제 IME 제어)에 통지한다. */
    var gateListener: (() -> Unit)? = null

    /** 입력 커넥션을 허락할지: 항상 허용(kb 토글 ON) 또는 임시 예외(다이얼로그). */
    val imeActive: Boolean get() = keyboardAllowed || imeOverride

    @JavascriptInterface
    fun setKeyboardAllowed(allowed: Boolean) {
        if (keyboardAllowed == allowed) return
        keyboardAllowed = allowed
        runCatching { gateListener?.invoke() }
    }

    /** 키보드 ON 전환 즉시 IM 을 올린다 (포커스는 이미 WebView 에 걸려 있다). */
    @JavascriptInterface
    fun showSoftKeyboard() {
        val activity = context as? android.app.Activity ?: return
        activity.window.decorView.post {
            runCatching {
                val view = activity.window.currentFocus ?: activity.window.decorView
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                imm.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    /** 올라와 있는 화면 키보드를 즉시 숨긴다 (포커스 요구와 방지 모드 사이 경쟁 방지). */
    @JavascriptInterface
    fun hideSoftKeyboard() {
        val activity = context as? android.app.Activity ?: return
        activity.window.decorView.post {
            runCatching {
                val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as InputMethodManager
                imm.hideSoftInputFromWindow(activity.window.currentFocus?.windowToken, 0)
            }
        }
    }

    /** 다이얼로그 입력 등 임시 허용. false 로 되돌리면 열려 있던 키보드를 숨긴다. */
    @JavascriptInterface
    fun setImeOverride(allowed: Boolean) {
        if (imeOverride == allowed) return
        imeOverride = allowed
        runCatching { gateListener?.invoke() }
    }

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

    /** venv python 으로 모듈을 import 해 dir() 공개 멤버를 나열한다. 실패는 조용히 빈 목록으로. */
    @JavascriptInterface
    fun members(module: String): String = runCatching {
        val mod = module.trim().take(64)
        if (!Regex("^[A-Za-z0-9_.]+$").matches(mod)) return err("bad module")
        val key = project + ":" + mod
        memberCache[key]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < 30000) return membersJson(list)
        }
        val guest = UserlandRuntime.guestProject(context, project)
        val py =
            "import sys,importlib\n" +
                "try:\n" +
                "    m = importlib.import_module(sys.argv[1])\n" +
                "    print(chr(10).join(x for x in dir(m) if not x.startswith('_'))[:4000])\n" +
                "except BaseException:\n" +
                "    pass\n"
        val exec = UserlandRuntime.exec(
            context,
            "printf '%s' " + UserlandRuntime.q(py) + " | " + guestVenvPython() +
                " -c " + UserlandRuntime.q("import sys;exec(sys.stdin.read())") + " " + mod,
            5000, guest
        )
        val list = if (exec.exitCode == 0) {
            exec.output.trim().split("\n")
                .filter { it.isNotBlank() && Regex("^[A-Za-z_]\\w*$").matches(it.trim()) }
                .map { it.trim() }
        } else emptyList()
        memberCache[key] = System.currentTimeMillis() to list
        membersJson(list)
    }.getOrElse { err(it.message ?: "members failed") }

    private val memberCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<String>>>()

    private fun membersJson(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return JSONObject().put("members", arr).toString()
    }

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
        val isRoot = depth == 0
        val kids = (dir.listFiles()?.filter { keep(it, isRoot) } ?: return)
            .sortedWith(compareBy<File> { it.name == ".venv" || it.name == "venv" }
                .thenByDescending<File> { it.isDirectory }
                .thenBy { it.name.lowercase() })
        for (f in kids) {
            val o = JSONObject()
            o.put("name", f.name)
            o.put("path", relOf(f))
            o.put("dir", f.isDirectory)
            val venv = f.name == ".venv" || f.name == "venv"
            if (f.isFile) {
                o.put("size", f.length())
                o.put("mtime", f.lastModified())
            } else if (!venv && depth < 8) {
                // venv 는 노드의 일부가 아니다. 자식을 내려주면 트리 들여쓰기가
                // "전부가 .venv 아래"처럼 보이는 착각을 준다.
                val c = JSONArray()
                walk(f, c, depth + 1)
                if (c.length() > 0) o.put("children", c)
            }
            out.put(o)
        }
    }

    /** 노이즈 정리. 루트의 앱 내부 파일(.log/.termrc/.venv.done/...) 도 숨긴다. */
    private fun keep(f: File, isRoot: Boolean): Boolean = when {
        f.name == ".git" || f.name == "__pycache__" || f.name == ".idea" ||
            f.name == "node_modules" || f.name == ".mypy_cache" || f.name == ".pytest_cache" -> false
        f.name == ".venv" || f.name == "venv" -> true
        f.isDirectory && f.name.startsWith(".") -> false
        isRoot && f.name.startsWith(".") -> false
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
        val rcGuest = "$guest/.termrc"
        // script(script util) 가 PTY 를 잡아 대화가 유지된다. script 를 못 쓰면
        // bash -i(pipe 모드라 echo만 되는 반쪽)라도 살려둔다. exec 금지: 마지막
        // exec 가 죽으면 폴백 없이 세션이 종결된다.
        val inner =
            "cd " + UserlandRuntime.q(guest) + " || cd ~\n" +
                "{ [ -f .venv/bin/activate ] && . .venv/bin/activate; }\n" +
                "export PS1=" + UserlandRuntime.q("$ ") + "\n" +
                "stty rows " + rows + " cols " + cols + " 2>/dev/null\n" +
                // 크기 동기화: host 가 .termsize 에 창 크기를 쓰고, bash rc 가 백그라운드
                // 폴러로 read+stty 한다(폴러는 interactive bash 의 잡으로만 살수 있음).
                // rc 는 host 파일에서 직접 쓰지 — printf 이스케이프 사달 prevents.
                "script -qc 'bash --rcfile " + rcGuest + " -i' /dev/null 2>&1\n" +
                "exec bash -i 2>&1"
        val rcHost = java.io.File(UserlandRuntime.projectsHostDir(context), "$project/.termrc")
        runCatching {
            rcHost.parentFile?.mkdirs()
            // read 는 개행 없는 EOF 에서 rc=1 을 반환하므로 && 로 게이트하지 않는다.
            // stty 는 stdin 을 tty 로 써야 하므로 stdin 을 /dev/tty 로 리다이렉트한다.
            rcHost.writeText(
                "[ -f /etc/bash.bashrc ] && . /etc/bash.bashrc\n" +
                    "[ -f ~/.bashrc ] && . ~/.bashrc\n" +
                    "PROMPT_COMMAND='read -r _c _r </home/projects/.termsize 2>/dev/null; " +
                    "[ -n \"\$_c\" ] && stty cols \$_c rows \$_r 0</dev/tty 2>/dev/null'\n"
            )
        }
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
            val d = Base64.getDecoder().decode(b64)
            t.input.write(d)
            t.input.flush()
            t.lastInput = System.currentTimeMillis()
            if (d.isNotEmpty() && (d.last() == '\r'.code.toByte() || d.last() == '\n'.code.toByte())) {
                t.pending?.let { t.pending = null; t.applyResize(it.first, it.second) }
            }
        }
    }

    /** stty 로 창 크기 반영. 입력 중인 프롬프트에 끼어들지 않도록 유휴(>600ms) 일 때만
     *  즉시 적용하고, 그 외에는 Enter 를 기다려 pending 으로 처리한다. */
    @JavascriptInterface
    fun termResize(id: Int, cols: Int, rows: Int) {
        val t = terms[id] ?: return
        val alive = runCatching { t.process.exitValue() }.isFailure
        if (!alive) return
        writeSizeFile(cols, rows)
    }

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
