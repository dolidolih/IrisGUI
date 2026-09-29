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
     * 자동완성 멤버 목록 — ISSUE-24 규칙은 그대로: 모듈을 *import 하지 않는다*
     * (import 는 사용자 코드 최상단을 실행한다). 대신 이름 딕셔너리가 아니라
     * 환경 자체를 programmatically 해석한다:
     *   · dotted 전체 경로(a.b.c / a.b.Class)를 마지막부터 속성 체인으로 소거하며
     *     모듈 → 클래스 멤버까지 내려간다,
     *   · 프로젝트 파일은 host 에서 정적 주사, 그 밖의 전부(표준lib/builtin/venv)
     *     는 guest python 의 import 없는 sys.path 주사+ast 스캔(백그라운드, 콜백 회신).
     * 응답: {"members":[[name,kind],...]} + pending 안내.
     */
    @JavascriptInterface
    fun members(module: String): String = runCatching {
        val mod = module.trim().take(64)
        if (!Regex("^[A-Za-z0-9_.]+$").matches(mod)) return err("bad module")
        val key = project + ":" + mod
        memberCache[key]?.let { (at, list) ->
            if (System.currentTimeMillis() - at < 30000) return memJson(list)
        }
        // 프로젝트 안은 host 정적 주사(실행 없음, 즉각).
        resolveLocal(mod)?.let { list ->
            memberCache[key] = System.currentTimeMillis() to list
            return memJson(list)
        }
        // 프로젝트 밖 전부(표준 라이브러리/builtin/venv/그 외 환경) —
        // 백그라운드에서 import 없는 sys.path 주사 + ast 스캔 → 콜백.
        if (probing.add(key)) {
            Thread {
                try {
                    val list = scanImportable(mod)
                    memberCache[key] = System.currentTimeMillis() to list
                    emit("window.IrisEditor && window.IrisEditor.onMembers && " +
                        "window.IrisEditor.onMembers(" + JSONObject.quote(module) + ", " +
                        memJson(list) + ")")
                } catch (e: Exception) {
                    RuntimeLog.info("Editor", "$project: members($mod) scan failed: " +
                        e.javaClass.simpleName + ": " + (e.message ?: "").take(160))
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

    private data class Member(val name: String, val kind: String)

    private val memberCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Member>>>()

    /** stdlib/builtin 이름 사전(프로세스-영속: venv 와 무관한 python 의 자기 서술). */
    private val catalog = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val CATALOG_KEY = project + ":__catalog__"

    private fun memJson(list: List<Member>): String {
        val arr = JSONArray()
        list.forEach { arr.put(JSONArray().put(it.name).put(it.kind)) }
        return JSONObject().put("members", arr).toString()
    }

    /**
     * 프로젝트 트리 안에서 dotted 이름을 정적 해석한다. 최장 경로부터 접고 내려가며
     * 모듈 파일/패키지 디렉터리를 찾고, 남는 마지막 조각은 속성(Class)으로 파고든다.
     * 하나도 못 찾으면 null — guest 스캔에게 바통을 넘긴다.
     */
    private fun resolveLocal(mod: String): List<Member>? {
        val parts = mod.split('.')
        var j = parts.size
        while (j >= 1) {
            val hit = localModuleFile(parts.take(j))
            if (hit != null) {
                val (f, dir) = hit
                val rest = parts.drop(j)
                if (rest.isEmpty()) return moduleMembers(f, dir)
                if (f != null) parseClassNamesLocal(f, rest[0])?.let { return it }
                return moduleMembers(f, dir)   // 속성 미식별 — 모듈 멤버로 강등(음표 현상은 없다)
            }
            j--
        }
        return null
    }

    private fun localModuleFile(parts: List<String>): Pair<File?, File?>? {
        val rel = parts.joinToString("/")
        val py = File(root(), "$rel.py")
        if (py.isFile) return py to null
        val dir = File(root(), rel)
        if (dir.isDirectory) {
            val init = File(dir, "__init__.py")
            return (if (init.isFile) init else null) to dir
        }
        return null
    }

    /** 모듈 멤버 = 파일 최상위 이름 + (패키지면) 서브모듈/서브패키지 디렉터리. */
    private fun moduleMembers(f: File?, dir: File?): List<Member> {
        val map = LinkedHashMap<String, Member>()
        f?.let { src ->
            runCatching {
                src.bufferedReader().use { br ->
                    parseNames(br.lineSequence().take(20_000))
                        .forEach { map.putIfAbsent(it.name, it) }
                }
            }
        }
        dir?.listFiles()?.forEach { sub ->
            if (sub.name == "__pycache__" || sub.name.startsWith(".")) return@forEach
            if (sub.isDirectory) map.putIfAbsent(sub.name, Member(sub.name, "pkg"))
            else if (sub.isFile && sub.name.endsWith(".py") && sub.name != "__init__.py") {
                val n = sub.name.removeSuffix(".py")
                map.putIfAbsent(n, Member(n, "mod"))
            }
        }
        return map.values.take(500).toList()
    }

    /** 최상위 정의의 이름+종류(def/class/var/imp)만. 실행 없음. */
    private fun parseNames(lines: Sequence<String>): List<Member> {
        val map = LinkedHashMap<String, Member>()
        val reDef = Regex("^(?:async\\s+)?(def|class)\\s+([A-Za-z_]\\w*)")
        val reSet = Regex("^([A-Za-z_]\\w*)\\s*(?::[^=]+)?=")
        val reFrom = Regex("^from\\s+[\\w.]+\\s+import\\s+(.+)\\s*$")
        val reImp = Regex("^import\\s+(.+)\\s*$")
        val word = Regex("^\\w+$")
        val alias = { a: String ->
            if (a.contains(" as ")) a.substringAfterLast(" as ").trim() else a.trim().substringBefore('.')
        }
        lines.forEach { raw ->
            val line = raw.take(200)
            if (line.isBlank() || line.startsWith("#") || line.startsWith("\t")) return@forEach
            reDef.find(line)?.let {
                map.putIfAbsent(it.groupValues[2], Member(it.groupValues[2], it.groupValues[1]))
                return@forEach
            }
            reSet.find(line)?.let {
                map.putIfAbsent(it.groupValues[1], Member(it.groupValues[1], "var"))
                return@forEach
            }
            reFrom.find(line)?.let {
                it.groupValues[1].split(",").forEach { a ->
                    val n = alias(a)
                    if (word.matches(n)) map.putIfAbsent(n, Member(n, "imp"))
                }
                return@forEach
            }
            reImp.find(line)?.let {
                it.groupValues[1].split(",").forEach { a ->
                    val n = alias(a)
                    if (word.matches(n)) map.putIfAbsent(n, Member(n, "imp"))
                }
            }
        }
        return map.values.take(500).toList()
    }

    /** 파일에 class Name 이 있으면 멤버(메서드/self 속성/클래스 속성)를 돌려준다. */
    private fun parseClassNamesLocal(f: File, name: String): List<Member>? {
        val map = LinkedHashMap<String, Member>()
        var inside = false
        var found = false
        var baseIndent = -1
        val reHead = Regex("^class\\s+" + Regex.escape(name) + "\\b")
        val reDef = Regex("^(?:async\\s+)?def\\s+([A-Za-z_]\\w*)")
        val reSelf = Regex("self\\s*\\.\\s*([A-Za-z_]\\w*)\\s*[:=]")
        runCatching {
            f.bufferedReader().use { br ->
                br.lineSequence().take(20_000).forEach { raw ->
                    val t = raw.trim()
                    if (t.isEmpty()) return@forEach
                    val indent = raw.takeWhile { it == ' ' }.length
                    if (!inside) {
                        if (reHead.matches(t)) { inside = true; found = true; baseIndent = indent }
                    } else {
                        if (indent <= baseIndent) { inside = false; return@forEach }
                        reDef.find(t)?.let {
                            map.putIfAbsent(it.groupValues[1], Member(it.groupValues[1], "meth"))
                        }
                        reSelf.find(t)?.let {
                            map.putIfAbsent(it.groupValues[1], Member(it.groupValues[1], "prop"))
                        }
                    }
                }
            }
        }
        return if (found) map.values.take(500).toList() else null
    }

    /**
     * guest python 으로 환경 전체(표준 라이브러리/builtin/venv/프로젝트 포함 sys.path)를
     * import 없이 해석한다: 디렉터리 왕복으로 모듈을 찾고, ast 로 멤버를 딴다. 클래스
     * 재수출(from x import Class, 상대 import)도 따라간다. stdout 은 "kind\\tname" 줄.
     */
    private fun scanImportable(mod: String): List<Member> {
        val py = """
import sys, os, ast
name = sys.argv[1]
parts = name.split('.')
if not all(parts):
    sys.exit(0)

def safe_tree(p):
    try:
        if os.path.getsize(p) > 6000000:
            return None
        return ast.parse(open(p, encoding='utf-8', errors='replace').read())
    except BaseException:
        return None

def cands(p):
    res = []
    for root in [''] + [x for x in sys.path if x]:
        root = root or '.'
        try:
            base = os.path.join(root, *p)
            if os.path.isfile(base + '.py'):
                res.append((base + '.py', None))
            if os.path.isdir(base):
                init = os.path.join(base, '__init__.py')
                res.append((init if os.path.isfile(init) else None, base))
        except OSError:
            pass
    return res

def find_module(p):
    c = cands(p) if p else []
    return c if c else None

def sub_cands(d, head):
    # d 패키지 디렉터리의 하위 모듈/서브패키지
    res = []
    if not d:
        return res
    try:
        base = os.path.join(d, head)
        if os.path.isfile(base + '.py'):
            res.append((base + '.py', None))
        if os.path.isdir(base):
            init = os.path.join(base, '__init__.py')
            res.append((init if os.path.isfile(init) else None, base))
    except OSError:
        pass
    return res

def members(py, d):
    res = []

    def collect(nodes):
        for n in nodes:
            if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef)):
                res.append(('def', n.name))
            elif isinstance(n, ast.ClassDef):
                res.append(('class', n.name))
            elif isinstance(n, (ast.Import, ast.ImportFrom)):
                for a in n.names:
                    res.append(('imp', (a.asname or a.name.split('.')[0])))
            elif isinstance(n, (ast.Assign, ast.AnnAssign)):
                tg = n.targets if isinstance(n, ast.Assign) else [getattr(n, 'target', None)]
                for t in tg:
                    if isinstance(t, ast.Name):
                        res.append(('var', t.id))
            elif isinstance(n, (ast.Try, ast.If, ast.With, ast.AsyncWith)) or \
                    (hasattr(ast, 'TryStar') and isinstance(n, ast.TryStar)):
                # 모듈 레벨 가드(try/except ImportError, if sys.platform, with) 안의
                # def/class/import 도 공개 이름이다 — 통과시켜 수집한다.
                for part in (getattr(n, 'body', []), getattr(n, 'orelse', []),
                             getattr(n, 'finalbody', [])):
                    collect(part)
                for h in getattr(n, 'handlers', []):
                    collect(h.body)
    if py:
        tree = safe_tree(py)
        if tree is not None:
            collect(getattr(tree, 'body', []))
    if d:
        try:
            for f in os.listdir(d):
                if f.startswith('.'):
                    continue
                p = os.path.join(d, f)
                if f.endswith('.py') and f != '__init__.py':
                    res.append(('mod', f[:-3]))
                elif os.path.isdir(p) and f != '__pycache__':
                    res.append(('pkg', f))
        except OSError:
            pass
    return [(k, n) for k, n in res if n and n.isidentifier() and not n.startswith('__')]

def class_members(cd):
    res = []
    for b in cd.body:
        if isinstance(b, (ast.FunctionDef, ast.AsyncFunctionDef)):
            res.append(('meth', b.name))
            for bb in ast.walk(b):
                if isinstance(bb, ast.Assign):
                    for t in bb.targets:
                        if isinstance(t, ast.Attribute) and isinstance(t.value, ast.Name) and t.value.id in ('self', 'cls'):
                            res.append(('prop', t.attr))
        elif isinstance(b, ast.Assign):
            for t in b.targets:
                if isinstance(t, ast.Name):
                    res.append(('var', t.id))
    return res

def dot_parts(node):
    if isinstance(node, ast.Name):
        return [node.id]
    if isinstance(node, ast.Attribute):
        b = dot_parts(node.value)
        return b + [node.attr] if b else None
    return None

def aliases(tree, pkg, head):
    # head 가 이 파일에서 어디서 오는 이름인지: (모듈 경로, 속성 또는 None)
    res = []
    for n in ast.walk(tree):
        if isinstance(n, ast.Import):
            for a in n.names:
                alias = a.asname or a.name.split('.')[0]
                if alias == head:
                    res.append((a.name.split('.'), None))
        elif isinstance(n, ast.ImportFrom):
            go = pkg[:max(0, len(pkg) - (n.level - 1))] if n.level > 1 else list(pkg)
            if n.module:
                mod2 = (go + n.module.split('.')) if n.level else n.module.split('.')
            elif n.level:
                mod2 = list(go)
            else:
                continue
            for a in n.names:
                alias = a.asname or a.name.split('.')[-1]
                if alias == head:
                    res.append((mod2, a.name))
    for n in getattr(tree, 'body', []):
        if isinstance(n, ast.Assign) and len(n.targets) == 1 and isinstance(n.targets[0], ast.Name) \
                and n.targets[0].id == head:
            v = n.value
            if isinstance(v, ast.Name):
                res.append((v.id.split('.'), None))
            elif isinstance(v, ast.Attribute):
                p = dot_parts(v)
                if p and len(p) > 1:
                    res.append((p[:-1], p[-1]))
    return res

def resolve(c, parts_v, attrs, depth):
    if depth > 5:
        return None
    if not attrs:
        merged = []
        for py, d in c:
            merged += members(py, d)
        return merged
    head, rest = attrs[0], attrs[1:]
    for py, d in c:
        sub = sub_cands(d, head)
        if sub:
            r = resolve(sub, parts_v + [head], rest, depth + 1)
            if r is not None:
                return r
    for py, d in c:
        if not py:
            continue
        tree = safe_tree(py)
        if tree is None:
            continue
        if not rest:
            for n in ast.walk(tree):
                if isinstance(n, ast.ClassDef) and n.name == head:
                    return class_members(n)
        is_init = os.path.basename(py) == '__init__.py'
        pkg = list(parts_v) if is_init else parts_v[:-1]
        for mod2, nm in aliases(tree, pkg, head):
            c2 = find_module(mod2)
            if c2 is None:
                continue
            r = resolve(c2, mod2, ([nm] + list(rest)) if nm else list(rest), depth + 1)
            if r is not None:
                return r
    return None

def emit(res):
    seen = set()
    for k, n in res:
        if (k, n) in seen:
            continue
        seen.add((k, n))
        print(n + '\t' + k)
        if len(seen) >= 500:
            break
    sys.exit(0)

for j in range(len(parts), 0, -1):
    c = find_module(parts[:j])
    if c is None:
        continue
    r = resolve(c, parts[:j], parts[j:], 0)
    if r is not None:
        emit(r)
    merged = []
    for py, d in c:
        merged += members(py, d)
    emit(merged)
sys.exit(0)
"""
        val r = execPython(py, " " + UserlandRuntime.q(mod), 9000)
        if (r.exitCode != 0) RuntimeLog.info("Editor", "$project: scan($mod) rc=" +
            r.exitCode + " " + r.output.take(150).replace('\n', ' '))
        if (r.exitCode != 0) return emptyList()
        val map = LinkedHashMap<String, Member>()
        r.output.lineSequence().forEach { ln ->
            val t = ln.indexOf('\t')
            if (t > 0) {
                val n = ln.substring(0, t).trim()
                val k = ln.substring(t + 1).trim()
                if (Regex("^[A-Za-z_]\\w*$").matches(n)) map.putIfAbsent(n, Member(n, k))
            }
        }
        return map.values.take(500).toList()
    }

    /**
     * 환경의 import 가능 이름 — 표준 라이브러리/builtin 을 python 자기 서술(sys) 로
     * programmatically 수집한다. 사전은 더 이상 편집기 안의 하드코딩이 아니다.
     * 첫 호출은 백그라운드 스캔 후 pending, 완료는 window.IrisEditor.onCatalog. 
     */
    @JavascriptInterface
    fun importCatalog(): String {
        catalog.get()?.let { return it }
        if (probing.add(CATALOG_KEY)) {
            Thread {
                val json = runCatching {
                    val py = """
import sys, os, json
st = sorted(getattr(sys, 'stdlib_module_names', []) or [])
if not st:
    base = getattr(sys, 'stdlib_dir', None) or os.path.dirname(os.__file__)
    try:
        for f in sorted(os.listdir(base)):
            p = os.path.join(base, f)
            if f.endswith('.py'):
                st.append(f[:-3])
            elif os.path.isdir(os.path.join(p, '__init__.py')):
                st.append(f)
    except OSError:
        pass
print(json.dumps({'stdlib': st, 'builtin': sorted(getattr(sys, 'builtin_module_names', []) or [])}))
"""
                    val r = execPython(py, "", 9000)
                    r.output.trim().lines().first { it.trim().startsWith("{") }.trim()
                }.getOrElse { """{"stdlib":[],"builtin":[]}""" }
                catalog.set(json)
                probing.remove(CATALOG_KEY)
                runCatching {
                    emit("window.IrisEditor && window.IrisEditor.onCatalog && " +
                        "window.IrisEditor.onCatalog(" + json + ")")
                }
            }.apply { isDaemon = true }.start()
        }
        return JSONObject().put("pending", true).toString()
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

    /** introspection 기본 python — venv. \.venv/bin/python 는 host 에서 broken symlink 로
     * 보이는 경우가 있어 isFile 검사로 판단하면 안 된다 (guest 에서는 실행 가능).
     * 없는 프로젝션은 guestPythonOr fallback. */
    private fun guestPython(): String = guestVenvPython()

    /** execPython: venv python 실행, rc!=0 이면 시스템 python3 로 재시도.
     * import 없이 sys.path 로만 도는 introspection 스크립트용. */
    private fun execPython(py: String, args: String, timeoutMs: Long): UserlandRuntime.Exec {
        val guest = UserlandRuntime.guestProject(context, project)
        val code = UserlandRuntime.q("import sys;exec(sys.stdin.read())")
        val first = UserlandRuntime.exec(
            context,
            "printf %s " + UserlandRuntime.q(py) + " | " + guestVenvPython() +
                " -c " + code + args,
            timeoutMs, guest
        )
        if (first.exitCode == 0) return first
        return UserlandRuntime.exec(
            context,
            "printf %s " + UserlandRuntime.q(py) + " | python3 -c " + code + args,
            timeoutMs, guest
        )
    }

    // ── jedi 문맥 완료 (completeAtAsync) ────────────────────────────

    private val jediExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r).apply { isDaemon = true; name = "jedi-$project" }
    }
    @Volatile private var jediInstallTried = false

    /** payload 버전: /tmp/ig_rpc.json 에 json 을 먼저 흘리고, 스크립트는 sys.argv 로 받는다. */
    private fun execPythonPayload(py: String, payload: String, timeoutMs: Long): UserlandRuntime.Exec {
        val guest = UserlandRuntime.guestProject(context, project)
        val code = UserlandRuntime.q("import sys;exec(sys.stdin.read())") + " /tmp/ig_rpc.json"
        val head = "printf %s " + UserlandRuntime.q(payload) + " > /tmp/ig_rpc.json && "
        val first = UserlandRuntime.exec(
            context, head + "printf %s " + UserlandRuntime.q(py) + " | " +
                guestVenvPython() + " -c " + code, timeoutMs, guest
        )
        if (first.exitCode == 0 && !first.output.contains("No such file")) return first
        return UserlandRuntime.exec(
            context, head + "printf %s " + UserlandRuntime.q(py) + " | python3 -c " + code,
            timeoutMs, guest
        )
    }

    /**
     * jedi.Script.complete(line, col) — 통폐섭 python 프로세스에서 (import 는 거기서
     * 끝나고 사이드이펙트는 그 안에서 죽는다) 실행하고, window.IrisEditor.onJedi 로
     * token 을 넣고 회신한다. jedi 가 없으면 token=0, jediInstall 결과 통보와 함께
     * 1 회 백그라운드 pip 설치를 시도한다.
     */
    @JavascriptInterface
    fun completeAtAsync(rel: String, srcB64: String, line: Int, col: Int, token: Int): String {
        if (closed) return err("에디터가 닫혔습니다")
        val source = runCatching { String(java.util.Base64.getDecoder().decode(srcB64), Charsets.UTF_8) }
            .getOrElse { return err("소스 디코딩 실패") }
        val guestFile = UserlandRuntime.guestProject(context, project) + "/" + rel.trim().trimStart('/')
        val payload = JSONObject().put("source", source).put("path", guestFile)
            .put("line", line).put("col", col).toString()
        val py = """
import sys, json
try:
    import jedi
except ImportError:
    print('###JEDI' + json.dumps({'jedi': False, 'err': 'jedi no installed'}))
    sys.exit(0)
try:
    p = json.load(open(sys.argv[1], encoding='utf-8'))
    try:
        sc = jedi.Script(code=p['source'], path=p['path'])
    except TypeError:
        sc = jedi.Script(source=p['source'], path=p['path'])
    out = []
    lno = int(p['line'])
    col = int(p['col'])
    try:
        comps = sc.complete(lno, col)
    except Exception as e:
        # jedi >= 0.20 는 column 을 line 길이(0..len) 로 재단한다 — Monaco 의
        # 커서열(1+k) 은 len+1 에서 거부되므로 원조(off-by-one)로 재시도.
        if 'not in a valid range' not in str(e):
            raise
        line_txt = (p['source'].split('\n') + [''] * lno)[lno - 1]
        comps = sc.complete(lno, max(0, min(col - 1, len(line_txt))))
    for c in comps[:150]:
        try:
            d = (c.description or '')[:150]
        except BaseException:
            d = ''
        out.append([c.name, c.type, d])
    print('###JEDI' + json.dumps({'jedi': True, 'items': out}))
except BaseException as e:
    print('###JEDI' + json.dumps({'jedi': True, 'items': [], 'err': str(e)[:160]}))
"""
        jediExecutor.execute {
            val out = try {
                val r = execPythonPayload(py, payload, 25_000)
                val marker = r.output.lines().firstOrNull { it.startsWith("###JEDI") }
                if (marker != null) runCatching { JSONObject(marker.substring(7)) }
                    .getOrDefault(JSONObject().put("jedi", false).put("err", "marker parse"))
                else JSONObject().put("jedi", false)
                    .put("err", "exec rc=" + r.exitCode + " " + r.output.take(120))
            } catch (e: Exception) {
                JSONObject().put("jedi", false).put("err", (e.message ?: "").take(120))
            }
            out.put("token", token)
            runCatching {
                emit("window.IrisEditor && window.IrisEditor.onJedi && " +
                    "window.IrisEditor.onJedi(" + out + ")")
            }
            if (!out.optBoolean("jedi") && out.optString("err").contains("jedi") &&
                !jediInstallTried
            ) {
                jediInstallTried = true
                RuntimeLog.info("Editor", "$project: jedi 없음 — pip 설치 시도")
                Thread {
                    val ok = runCatching {
                        UserlandRuntime.exec(
                            context,
                            UserlandRuntime.guestProject(context, project) +
                                "/.venv/bin/pip install -q --only-binary :all: jedi 2>&1",
                            180_000, UserlandRuntime.guestProject(context, project)
                        ).exitCode == 0
                    }.getOrDefault(false)
                    RuntimeLog.info("Editor", "$project: jedi pip 설치 " + (if (ok) "성공" else "실패"))
                    runCatching {
                        emit("window.IrisEditor && window.IrisEditor.onJedi && " +
                            "window.IrisEditor.onJedi({token:0,jediInstall:" + ok + "})")
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        return JSONObject().put("queued", true).toString()
    }

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

    /** 터치 복붙 브리지 — WebView 의 navigator.clipboard 은 권한/포커스 유령이
     * 있어 Android 클립보드와 직접 다닌다. 반환: Get 은 텍스트, Set 은 상태 문자열. */
    @JavascriptInterface
    fun clipboardGet(): String = runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.primaryClip?.let {
            if (it.itemCount > 0) it.getItemAt(0).coerceToText(context).toString() ?: "" else ""
        } ?: ""
    }.getOrDefault("")

    @JavascriptInterface
    fun clipboardSet(text: String?): String = runCatching {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("iris-editor", text ?: ""))
        "ok"
    }.getOrElse { "err:" + (it.message ?: it.toString()) }

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
                // venv 활성 표시: 셸만 보고도 프로젝트 venv 안에 있는지 알 수 있게.
                "if [ -n \"\$VIRTUAL_ENV\" ]; then export PS1=" + UserlandRuntime.q("(venv) $ ") +
                "; else export PS1=" + UserlandRuntime.q("$ ") + "; fi\n" +
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
                    "[ -n \"\$_c\" ] && stty cols \$_c rows \$_r 0</dev/tty 2>/dev/null; " +
                    // venv 는 터미널 시작 후에도 조용히 생성된다 (create→venv async) —
                    // 매 prompt 마다 아직 미활성이면 그때 activate 한다.
                    "{ [ -z \"\$VIRTUAL_ENV\" ] && [ -f .venv/bin/activate ] && . .venv/bin/activate 2>/dev/null && " +
                    "export PS1=\"(venv) $ \"; }; true'\n"
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
