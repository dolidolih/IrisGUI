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
 */
object SqlCipherNative {
    @Volatile
    private var loaded = false

    fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true

            if (runCatching { System.loadLibrary("sqlcipher") }.isSuccess) {
                loaded = true
                return true
            }

            val path = findNativeLibPath()
            if (path != null && runCatching { System.load(path) }.isSuccess) {
                loaded = true
                return true
            }

            System.err.println("SqlCipherNative: could not load native libsqlcipher.so")
            return false
        }
    }

    /**
     * Locate an extracted libsqlcipher.so. Prefers our own package, then any other
     * package (KakaoTalk bundles the same SQLCipher build, so it decrypts the same files).
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
                val f = File(dir, "libsqlcipher.so")
                if (f.isFile) return f.absolutePath
            }
        }

        // 2. Our own package first, then any other package.
        runCatching {
            val found = File("/data/app").walkTopDown().maxDepth(5)
                .filter { it.isFile && it.name == "libsqlcipher.so" }
                .map { it.absolutePath }
            val ours = found.firstOrNull { "party.qwer.irisgui" in it }
            if (ours != null) return ours
            return found.firstOrNull { "com.kakao.talk" in it } ?: found.firstOrNull()
        }
        return null
    }
}
