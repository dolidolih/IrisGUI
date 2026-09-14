package party.qwer.irisgui.backend

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File

/**
 * Reader for KakaoTalk's `crypto_user_database` (SQLCipher).
 *
 * Key derivation (verified against the live device and reproducing the captured key
 * a84bf2c8…c192de exactly, see docs / daily log 2026-09-13):
 *   salt = zero-pad(("se" + seed + "ed") in UTF-8) to 16 bytes
 *   K1   = PBKDF2WithHmacSHA256(pass = char[16]{4,15,81,123,77,5,23,99,2,111,10,31,54,29,109,97},
 *                              salt, iters = 4096, keyBits = 256)
 *   seed = Long held under key `userDbPassPhraseSalt` in
 *          files/datastore/Feature_DataStore.pref.preferences_pb  (see UserDbSaltStore)
 *
 * K1 is a *passphrase*, not a raw page key: the app passes it to
 * net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(path, K1, …) which invokes
 * sqlite3_key_v2 → passphrase mode with SQLCipher-4 defaults (kdf_iter 256000, HMAC sha512).
 * Feeding K1 as a raw key (PRAGMA key="x'…'") fails with "file is not a database".
 *
 * The `user` table stores `nickname` in plaintext, so once opened the rows are directly
 * readable — no per-field AES step is required here (unlike open_chat_member's enc columns).
 */
object CryptoUserDatabaseReader {
    private const val DB_NAME = "crypto_user_database"
    private val SALT_CHARS = charArrayOf(4.toChar(),15.toChar(),81.toChar(),123.toChar(),77.toChar(),5.toChar(),23.toChar(),99.toChar(),2.toChar(),111.toChar(),10.toChar(),31.toChar(),54.toChar(),29.toChar(),109.toChar(),97.toChar())

    @Volatile
    private var db: SQLiteDatabase? = null

    @Volatile
    private var loaded = false

    /** Derives the 32-byte passphrase key K1 for a given `userDbPassPhraseSalt` seed. */
    @Throws(Exception::class)
    fun deriveKey(seed: Long): ByteArray {
        val raw = ("se" + seed + "ed").toByteArray(Charsets.UTF_8)
        val salt = ByteArray(16)
        System.arraycopy(raw, 0, salt, 0, minOf(16, raw.size))
        val spec = javax.crypto.spec.PBEKeySpec(SALT_CHARS, salt, 4096, 256)
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
    }

    /** Opens crypto_user_database read-only, deriving the key from the stored seed. */
    @Synchronized
    fun open(): Boolean {
        if (db != null) return true
        if (!loaded && !SqlCipherNative.ensureLoaded()) {
            loaded = false
            return false
        }
        loaded = true
        val seed = UserDbSaltStore.getSeed() ?: run {
            System.err.println("CryptoUserDatabaseReader: seed unavailable")
            return false
        }
        return try {
            val key = deriveKey(seed)
            val workFile = copyToWorkingCopy() ?: return false
            db = SQLiteDatabase.openDatabase(
                workFile.absolutePath, key, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                null
            )
            true
        } catch (e: Throwable) {
            System.err.println("CryptoUserDatabaseReader: open failed: $e")
            close()
            false
        }
    }

    /**
     * Copies crypto_user_database (+ -wal) into a private working dir and returns the
     * main file. Kakao keeps the DB in WAL mode with a large -wal sidecar; opening the
     * live files in read-only mode fails if SQLite cannot create/write a -shm, and
     * opening live files read-write risks corrupting a DB Kakao is actively writing.
     * Reading a consistent snapshot copy is the safe, proven path. Returns null if the
     * source cannot be read. Stale copies are re-copied when the source mtime advances.
     */
    private fun copyToWorkingCopy(): File? {
        return try {
            val srcMain = File(PathUtils.getAppPath(), "databases/$DB_NAME")
            if (!srcMain.isFile) {
                System.err.println("CryptoUserDatabaseReader: source not found: $srcMain")
                return null
            }
            val workDir = File(cacheDir(), "crypto_user_db")
            workDir.mkdirs()
            val dstMain = File(workDir, DB_NAME)
            val dstWal = File(workDir, "$DB_NAME-wal")

            val srcWal = File(srcMain.parentFile, "$DB_NAME-wal")
            val srcNewer = dstMain.lastModified() < srcMain.lastModified() ||
                (srcWal.isFile() && srcWal.lastModified() > dstMain.lastModified())
            if (!dstMain.isFile || srcNewer) {
                copyInto(srcMain, dstMain)
                if (srcWal.isFile) copyInto(srcWal, dstWal)
                else dstWal.delete()
            }
            dstMain
        } catch (e: Throwable) {
            System.err.println("CryptoUserDatabaseReader: copyToWorkingCopy failed: $e")
            null
        }
    }

    private fun copyInto(src: File, dst: File) {
        // use RandomAccessFile-free stream copy; -1 byte chunks avoid huge allocations.
        src.inputStream().use { input ->
            dst.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                var n = input.read(buf)
                while (n > 0) {
                    output.write(buf, 0, n)
                    n = input.read(buf)
                }
            }
        }
        dst.setLastModified(src.lastModified())
    }

    private fun cacheDir(): File {
        val app = runCatching {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null)
        }.getOrNull()
        val dir = runCatching {
            val info = app!!.javaClass.getMethod("getFilesDir").invoke(app)
            (info as? File)
        }.getOrNull()
        return dir ?: File("/data/local/tmp")
    }

    /**
     * Resolves a plaintext profile name for a KakaoTalk user id. Two tables in the
     * decrypted DB carry names: `user` (friends) and `talk_channel` (plus-friend /
     * Kakao-channel / talk accounts that have no `user` row). Both key the name by the
     * member's user id — in `talk_channel` that id is also linked to the room's chat_id.
     * Returns null when the user is unknown or the encrypted DB cannot be opened.
     */
    fun queryUserName(userId: Long): String? {
        var database = db
        if (database == null) {
            if (!open()) return null
            database = db ?: return null
        }
        database.rawQuery(
            "SELECT nickname FROM user WHERE id = ? AND nickname <> '' LIMIT 1",
            arrayOf(userId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        database.rawQuery(
            "SELECT name FROM talk_channel WHERE id = ? AND name <> '' LIMIT 1",
            arrayOf(userId.toString())
        ).use { cursor ->
            if (cursor.moveToFirst()) return cursor.getString(0)
        }
        return null
    }

    /** Bulk map of user id → nickname, for priming a name cache. Note: only `user`
     *  rows; unlike queryUserName it omits talk_channel names. Not used by the
     *  hot path, which calls queryUserName per-id instead. */
    fun queryAllNicknames(limit: Int = 4096): Map<Long, String> =
        if (!open()) emptyMap() else runCatching {
            db?.rawQuery(
                "SELECT id, nickname FROM user WHERE nickname IS NOT NULL AND nickname <> '' LIMIT ?",
                arrayOf(limit.toString())
            )?.use { cursor ->
                val out = LinkedHashMap<Long, String>()
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val name = cursor.getString(1)
                    if (id != 0L && !name.isNullOrEmpty()) out[id] = name
                }
                out
            } ?: emptyMap()
        }.getOrElse {
            System.err.println("CryptoUserDatabaseReader: queryAllNicknames failed: $it")
            emptyMap()
        }

    @Synchronized
    fun close() {
        db?.close()
        db = null
    }
}
