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
import java.util.concurrent.TimeUnit

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
        val id: Int,
        /** ISSUE-24: 터미널당 크기 파일 — 다른 프로젝트/터미널의 리사이즈가
         * 이 터미널의 PROMPT_COMMAND 폴러를 오염시키지 않도록 id별 파일. */
        val sizeFile: File
    ) {
        @Volatile var lastInput = 0L
    }

    private fun projectsRoot(): File = UserlandRuntime.projectsHostDir(context)

    private fun writeSize(term: Term, cols: Int, rows: Int) {
        runCatching { term.sizeFile.writeText("$cols $rows\n") }
    }

    private fun cleanupTermFiles(id: Int) {
        runCatching { File(projectsRoot(), "$project/.termsize_$id").delete() }
        runCatching { File(projectsRoot(), "$project/.termrc_$id").delete() }
    }


    private val terms = ConcurrentHashMap<Int, Term>()
    private var termSeq = 0
    @Volatile private var closed = false

    fun close() {
        closed = true
        // SIGTERM 먼저: proot 는 시그널 핸들러로 ptrace 중인 guest 자식을 정리한다.
        // destroyForcibly(SIGHUP/kill) 만 쓰면 bash/python 이 고아로 남아 다음 터미널과
        // 충돌하고 /proc 상태가 잔존한다. 백그라운드에서 보류 후 강제 종료 — main 대기 없음.
        val procs = terms.values.map { it.process }
        val ids = terms.keys.toList()
        terms.clear()
        ids.forEach { cleanupTermFiles(it) }
        procs.forEach { runCatching { it.destroy() } }
        Thread {
            procs.forEach { p ->
                runCatching { p.waitFor(600, TimeUnit.MILLISECONDS) }
                runCatching { p.destroyForcibly() }
            }
        }.apply { isDaemon = true }.start()
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

    /**
     * ISSUE-24: 자동완성용 멤버 목록은 더 이상 모듈을 *import 하지 않는다* —
     * import 는 사용자 코드의 모듈 최상단(서버 접속·스레드 기동)까지 실행하면서
     * bridge 를 최대 5 초 막는다. 대신
     *   · 프로젝트 안 모듈은 host 에서 소스를 그대로 정적 주사(최상위 def/class/
     *     import/대입 이름만),
     *   · venv/사이트패키지 모듈은 find_spec(실행 없음)+ast 파싱을 백그라운드에서
     *     돌고 window.IrisEditor.onMembers 로 회신한다.
     * 응답 형식은 {"members":[...]} 유지(호환) + pending 안내만 추가.
     */
    @JavascriptInterface
    fun members(module: String): String = runCatching {
        val mod = module.trim().take(64)
        if (!Regex("^[A-Za-z0-9_.]+$").matches(mod)) return err("bad module")
        val key = project + ":" + mod
        memberCache[key]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < 30000) return membersJson(list)
        }
        val rel = mod.replace('.', '/')
        val local = listOf(File(root(), "$rel.py"), File(root(), "$rel/__init__.py"))
            .firstOrNull { it.isFile }
        if (local != null) {
            val list = parseTopLevelNames(local)
            memberCache[key] = System.currentTimeMillis() to list
            return membersJson(list)
        }
        // 프로젝트 밖 모듈: exec 하지 말고 백그라운드 ast 스캔 → 콜백.
        if (probing.add(key)) {
            Thread {
                try {
                    val list = scanInstalledModule(mod)
                    memberCache[key] = System.currentTimeMillis() to list
                    emit("window.IrisEditor && window.IrisEditor.onMembers && " +
                        "window.IrisEditor.onMembers(" + JSONObject.quote(module) + ", " +
                        membersJson(list) + ")")
                } finally {
                    probing.remove(key)
                }
            }.apply { isDaemon = true }.start()
        }
        JSONObject().put("members", JSONArray()).put("pending", true).toString()
    }.getOrElse { err(it.message ?: "members failed") }

    private val probing = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    /** 최상위 bar 정의의 이름만 걷어낸다 (def/class/대입/import). 실행 없음. */
    private fun parseTopLevelNames(f: File): List<String> {
        val names = LinkedHashSet<String>()
        val reDef = Regex("^(?:async\\s+)?(?:def|class)\\s+([A-Za-z_]\\w*)")
        val reSet = Regex("^([A-Za-z_]\\w*)\\s*(?::[^=]+)?=")
        val reFrom = Regex("^from\\s+[\\w.]+\\s+import\\s+(.+)\\s*$")
        val reImp = Regex("^import\\s+(.+)\\s*$")
        val word = Regex("^\\w+$")
        runCatching {
            f.bufferedReader().use { br ->
                var n = 0
                br.lineSequence().take(20_000).forEach { raw ->
                    n++
                    val line = raw.take(200)
                    if (line.isBlank() || line.startsWith("#") || line.startsWith("\t")) return@forEach
                    reDef.find(line)?.let { names += it.groupValues[1]; return@forEach }
                    reSet.find(line)?.let { names += it.groupValues[1]; return@forEach }
                    reFrom.find(line)?.let {
                        names += it.groupValues[1].split(",").map { a ->
                            a.substringBefore(" as ").trim()
                        }.filter { a -> word.matches(a) }
                        return@forEach
                    }
                    reImp.find(line)?.let {
                        names += it.groupValues[1].split(",")
                            .map { a -> a.substringBefore(" as ").trim().substringBefore('.') }
                            .filter { a -> word.matches(a) }
                    }
                }
            }
        }
        return names.filter { it.isNotBlank() }.take(500).toList()
    }

    /** find_spec(모듈 실행 없음)+ast 로 venv 설치 모듈 이름을 딴다. exec 과 다르다. */
    private fun scanInstalledModule(mod: String): List<String> {
        val py = "import sys,ast,importlib.util,os\n" +
            "name=sys.argv[1]\npath=None\n" +
            "try:\n" +
            "    sp=importlib.util.find_spec(name)\n" +
            "    o=getattr(sp,'origin',None) or ''\n" +
            "    if o.endswith('.py'):path=o\n" +
            "    elif o.endswith('__init__.py'):path=o\n" +
            "except BaseException:\n" +
            "    pass\n" +
            "if path:\n" +
            "    try:\n" +
            "        t=ast.parse(open(path,encoding='utf-8',errors='replace').read())\n" +
            "        out=[]\n" +

            "        for n in t.body:\n" +
            "            if hasattr(n,'name') and isinstance(n,(ast.FunctionDef,ast.AsyncFunctionDef,ast.ClassDef)):\n" +
            "                out.append(n.name)\n" +
            "        print(chr(10).join(out[:500]))\n" +
            "    except BaseException:\n" +
            "        pass\n"
        val guest = UserlandRuntime.guestProject(context, project)
        val r = UserlandRuntime.exec(
            context,
            "printf %s " + UserlandRuntime.q(py) + " | " + guestVenvPython() + " -c " +
                UserlandRuntime.q("import sys;exec(sys.stdin.read())") + " " +
                UserlandRuntime.q(mod),
            6000, guest
        )
        if (r.exitCode != 0) return emptyList()
        return r.output.trim().split("\n")
            .filter { it.isNotBlank() && Regex("^[A-Za-z_]\\w*$").matches(it.trim()) }
            .map { it.trim() }
    }
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

    /** ISSUE-24: tmp + atomic move — 크래시/정전時に main.py 가 잘린 채로 남지 않는다.
     * 실행 직전 runner 가 읽는 중이어도 partial 은 절대 노출되지 않는다. */
    @JavascriptInterface
    fun write(rel: String, text: String): String = try {
        val f = resolve(rel) ?: return err("경로 밖 접근")
        f.parentFile?.mkdirs()
        val tmp = java.io.File.createTempFile(f.name + ".tmp", null, f.parentFile)
        try {
            tmp.writeText(text, Charsets.UTF_8)
            java.nio.file.Files.move(
                tmp.toPath(), f.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE
            )
        } catch (mv: Exception) {
            runCatching { tmp.delete() }
            // ATOMIC_MOVE 미지원 fs 폴백: 기존처럼 직접 쓰기.
            f.writeText(text, Charsets.UTF_8)
        }
        ok("saved")
    } catch (e: Exception) {
        err(e.message ?: "write failed")
    }

    @JavascriptInterface
    fun create(rel: String, isDir: Boolean): String {
        val f = resolve(rel) ?: return err("경로 밖 접근")
        if (f.exists()) return err("이미 존재")
        return runCatching {
            if (isDir) {
                // ISSUE-24: mkdirs() 결과를 무시하던 것 — 실패 없이 "created" 를
                // 돌려주면 빈 화면에 심는 저장까지 성공으로 위장한다.
                if (!f.mkdirs() && !f.isDirectory) return@runCatching err("디렉터리 생성 실패")
            }
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
        // ISSUE-24: remove() 에 있던 루트 가드를 rename 도 same. 루트를 대상으로 한
        // "" / "." 이동은 프로젝트 디렉터리 통째로 /home/projects bind 아래에서
        // 떼어내는 무음 프로젝트 파괴였다.
        if (s == root().canonicalFile) return err("프로젝트 자체는 리네임 불가")
        if (d == root().canonicalFile) return err("루트로의 리네임은 불가")
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

    /**
     * ISSUE-24: runScript 의 first-run venv ensure 은 30 분까지 걸릴 수 있다 — 그 동안
     * bridge 터미널/저장이 통째로 멈춘다. 비동기 버전은 즉시 queued 를 돌려주고 결과가
     * 준비되면 window.IrisEditor.onRunResult({ok,message}) 를 emit 한다. 동기를 쓰는
     * 기존 editor.html 콜드패스는 그대로 동작한다(runScript 유지).
     */
    @JavascriptInterface
    fun runScriptAsync(): String {
        if (closed) return err("편집기가 닫혔습니다")
        Thread {
            val (ok, msg) = runCatching { LinuxScripts.start(context, project) }
                .fold({ it.ok to it.message }, { false to (it.message ?: "run failed") })
            runCatching {
                emit("window.IrisEditor && window.IrisEditor.onRunResult && " +
                    "window.IrisEditor.onRunResult(" +
                    JSONObject().put("ok", ok).put("message", msg).toString() + ")")
            }
        }.apply { isDaemon = true }.start()
        return JSONObject().put("ok", true).put("queued", true).toString()
    }

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
        val id = ++termSeq
        val rcGuest = "$guest/.termrc_$id"
        // ISSUE-24: 크기 파일을 id(그리고 프로젝트)별로 분리 — 한 프로젝트용 global
        // .termsize 는 다른 터미널/프로젝트의 리사이즈가 이 터미널의 prompt poller 를
        // 오염시켜 프롬프트가 엉뚱한 너비로 찢어진다.
        val sizeHost = java.io.File(projectsRoot(), "$project/.termsize_$id")
        val sizeGuest = UserlandRuntime.guestProject(context, project) + "/.termsize_$id"
        // script(script util) 가 PTY 를 잡아 대화가 유지된다. script 를 못 쓰면
        // bash -i(pipe 모드라 echo만 되는 반쪽)라도 살려둔다. exec 금지: 마지막
        // exec 가 죽으면 폴백 없이 세션이 종결된다.
        val inner =
            "cd " + UserlandRuntime.q(guest) + " || cd ~\n" +
                "{ [ -f .venv/bin/activate ] && . .venv/bin/activate; }\n" +
                "export PS1=" + UserlandRuntime.q("$ ") + "\n" +
                // script 아래 파이프 실행이면 TERM 이 비어 clear 가 죽는다. coreutils 의
                // 'groups: cannot find name' 도 호스트 gid 가 /etc/group 에 없어 나는 소음.
                "export TERM=xterm-256color\n" +
                "{ for g in \$(id -G); do grep -q \"^[^:]*:[^:]*:\$g:\" /etc/group 2>/dev/null || echo \"g\$g:x:\$g:\" >>/etc/group; done; } 2>/dev/null\n" +
                "stty rows " + rows + " cols " + cols + " 2>/dev/null\n" +
                // 크기 동기화: host 가 이 터미널 전용 .termsize_<id> 에 창 크기를 쓰고,
                // bash rc 의 PROMPT_COMMAND 가 prompt 마다 read+stty 한다. 입력 줄에
                // 끼어드는 일도 없다.
                "script -qc 'bash --rcfile " + rcGuest + " -i' /dev/null 2>&1\n" +
                "exec bash -i 2>&1"
        val rcHost = java.io.File(projectsRoot(), "$project/.termrc_$id")
        runCatching {
            rcHost.parentFile?.mkdirs()
            sizeHost.writeText("$cols $rows\n")
            // read 는 개행 없는 EOF 에서 rc=1 을 반환하므로 && 로 게이트하지 않는다.
            // stty 는 stdin 을 tty 로 써야 하므로 stdin 을 /dev/tty 로 리다이렉트한다.
            rcHost.writeText(
                "[ -f /etc/bash.bashrc ] && . /etc/bash.bashrc\n" +
                    "[ -f ~/.bashrc ] && . ~/.bashrc\n" +
                    "PROMPT_COMMAND='read -r _c _r <" + sizeGuest + " 2>/dev/null; " +
                    "[ -n \"\$_c\" ] && stty cols \$_c rows \$_r 0</dev/tty 2>/dev/null'\n"
            )
        }
        val p = UserlandRuntime.spawnBackground(context, inner, guest, null)
            ?: return err("터미널 시작 실패")
        val term = Term(p, java.io.BufferedOutputStream(p.outputStream), id, sizeHost)
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
            cleanupTermFiles(id)
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
        }
    }

        /** ISSUE-24: 이 터미널 전용 크기 파일에 창 크기를 쓴다 — bash rc 의
     * PROMPT_COMMAND poller 가 prompt 마다 반영. 입력 중 끼어드는 stty 도 없다.
     * (old deferred "pending" path: applyResize 가 no-op 이라 죽어있어 삭제.) */
    @JavascriptInterface
    fun termResize(id: Int, cols: Int, rows: Int) {
        val t = terms[id] ?: return
        val alive = runCatching { t.process.exitValue() }.isFailure
        if (!alive) return
        writeSize(t, cols, rows)
    }
    @JavascriptInterface
    fun termStop(id: Int) {
        // close() 와 동일: SIGTERM 으로 proot 로 하여금 guest 자식을 정리하게 하고,
        // 끝나지 않으면 백그라운드에서 강제 종료. 호출자(JS/메인) 블로킹 없음.
        terms.remove(id)?.let { term ->
            cleanupTermFiles(id)
            runCatching { term.process.destroy() }
            Thread {
                runCatching { term.process.waitFor(600, TimeUnit.MILLISECONDS) }
                runCatching { term.process.destroyForcibly() }
            }.apply { isDaemon = true }.start()
        }
    }

    @JavascriptInterface
    fun closeEditor() { requestClose() }

    // ── flush sync (ISSUE-25) ────────────────────────────────────────────────
    private val flushAck = java.util.concurrent.atomic.AtomicReference<java.util.concurrent.CountDownLatch?>(null)

    /** BackHandler 이 flush 를 투입하기 전에 연다 — awaitFlushAck 와 쌍. */
    fun beginFlushWait() { flushAck.set(java.util.concurrent.CountDownLatch(1)) }

    /** JS 의 flush(save) 확인을 최대 [maxMs] 까지 대기. ack 없으면 false (true != 성공 보장). */
    fun awaitFlushAck(maxMs: Long): Boolean {
        val l = flushAck.get() ?: return false
        val got = runCatching { l.await(maxMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        flushAck.compareAndSet(l, null)
        return got
    }

    /** editor.html flush() 의 저장 시도 이후 호출되는 동기 confirmation. */
    @JavascriptInterface
    fun editorFlushed() {
        flushAck.get()?.countDown()
    }

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
