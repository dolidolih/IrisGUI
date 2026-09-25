package party.qwer.irisgui.backend

import java.io.File

/**
 * Loads libsqlcipher.so.
 *
 * net.zetetic:sqlcipher-android does NOT auto-load its native library; the caller must
 * load it before using net.zetetic.database.sqlcipher.SQLiteDatabase.
 *
 * Two contexts must work:
 *   - the normal app process: System.loadLibrary resolves via the app's ABI lib dir.
 *   - the ROOT_ADB daemon (Main run through app_process): there is no app classloader
 *     namespace, so loadLibrary fails; find the extracted .so by absolute path instead.
 *
 * ISSUE-32: the filesystem fallback used to be a `walkTopDown(maxDepth=5)` over /data/app
 * under a lock on the DB-open path (multi-second stalls) and it happily dlopen'd KakaoTalk's
 * own SQLCipher copy, which can be a different build → "file is not a database" dead ends.
 * The search is now bounded (package dirs only), ordered (our package first) and any
 * foreign copy is loaded only as a last resort, with a loud warning naming the ABI risk.
 */
object SqlCipherNative {
    @Volatile
    private var loaded = false

    /** Which file we ended up loading — for status/diagnostics. */
    @Volatile
    var loadedPath: String? = null
        private set

    private val NATIVE_LIB_NAME = "libsqlcipher.so"

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            if (runCatching { System.loadLibrary("sqlcipher") }.isSuccess) {
                loaded = true
                loadedPath = "loadLibrary(sqlcipher)"
                return true
            }
            val path = findNativeLibPath()
            if (path != null && runCatching { System.load(path) }.isSuccess) {
                loaded = true
                loadedPath = path
                println("SqlCipherNative: loaded $path")
                return true
            }
            System.err.println("SqlCipherNative: could not load native libsqlcipher.so")
            return false
        }
    }

    /** True when the loaded library came from another package (KakaoTalk), i.e. unverified. */
    fun isForeignLibrary(): Boolean =
        loadedPath?.let { "party.qwer.irisgui" !in it && "loadLibrary" !in it } ?: false

    /**
     * Locate an extracted libsqlcipher.so. Our own package always wins; another package's
     * copy (KakaoTalk bundles the same project) is a last resort and is announced, because an
     * ABI/version mismatch shows up as a bogus key error rather than a load error.
     */
    private fun findNativeLibPath(): String? {
        // 1. This process's own nativeLibraryDir (normal app process).
        runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null)
            val info = app.javaClass.getMethod("getApplicationInfo").invoke(app)
            val dir = info.javaClass.getField("nativeLibraryDir").get(info) as? String
            if (dir != null) {
                val f = File(dir, NATIVE_LIB_NAME)
                if (f.isFile) return f.absolutePath
            }
        }

        // 2. Bounded scan of the installed package lib dirs (no recursive tree walk).
        val found = runCatching { scanPackageLibDirs() }.getOrElse {
            System.err.println("SqlCipherNative: scan failed: ${it.message}")
            emptyList()
        }
        val ours = found.firstOrNull { "party.qwer.irisgui" in it }
        if (ours != null) return ours
        val kakao = found.firstOrNull { "com.kakao.talk" in it }
        if (kakao != null) {
            System.err.println(
                "SqlCipherNative: our own $NATIVE_LIB_NAME is missing — falling back to the " +
                    "bundled copy at $kakao. A different SQLCipher build here means key errors " +
                    "are indistinguishable from wrong-key errors."
            )
            return kakao
        }
        return found.firstOrNull()
    }

    /**
     * `/data/app/<pkg>-<hash>/lib/<abi>/libsqlcipher.so` (and the flat `lib/` layout).
     * Depth-bounded listFiles instead of walkTopDown: at most a few hundred stat() calls and
     * it never enters the app-private trees.
     */
    private fun scanPackageLibDirs(): List<String> {
        val root = File("/data/app")
        val packages = root.listFiles { f: File -> f.isDirectory } ?: return emptyList()
        val hits = ArrayList<String>(8)
        for (pkgDir in packages) {
            // pkg dir may nest one more level (base.apk layout); keep it bounded.
            val libHosts = listOf(pkgDir) +
                (pkgDir.listFiles { f: File -> f.isDirectory }?.take(2) ?: emptyList())
            for (host in libHosts) {
                val libDir = File(host, "lib")
                if (libDir.isDirectory) {
                    collectAbis(libDir, hits)
                } else {
                    val flat = File(host, NATIVE_LIB_NAME)
                    if (flat.isFile) hits += flat.absolutePath
                }
            }
            if (hits.size >= 8) break
        }
        return hits
    }

    private fun collectAbis(libDir: File, hits: MutableList<String>) {
        val abiDirs = libDir.listFiles { f: File -> f.isDirectory } ?: return
        for (abi in abiDirs) {
            val so = File(abi, NATIVE_LIB_NAME)
            if (so.isFile) hits += so.absolutePath
        }
        // old devices keep everything directly under lib/
        File(libDir, NATIVE_LIB_NAME).takeIf { it.isFile }?.let { hits += it.absolutePath }
    }

    /** Reset for tests/diagnostics — the real process never unloads a native lib. */
    internal fun resetForTests() {
        loaded = false
        loadedPath = null
    }
}
