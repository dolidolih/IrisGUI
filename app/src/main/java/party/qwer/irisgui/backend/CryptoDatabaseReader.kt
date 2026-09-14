package party.qwer.irisgui.backend

import net.zetetic.database.sqlcipher.SQLiteDatabase
import java.io.File
import java.io.RandomAccessFile

/**
 * Reader for KakaoTalk's `crypto_database` (SQLCipher).
 *
 * Key derivation (offline + live verified):
 *   rawKey = AndroidKeyStore HMAC key held under alias "crypto_db_passphrase_key"
 *            (software-backed KM blob, read only as root — see KeystoreRawKey)
 *   K      = HMAC-SHA256(rawKey, "crypto_database")   // 32 bytes
 *   Open with sqlite3_key_v2(db,"main",K,32) — passphrase mode, SQLCipher-4 defaults.
 *   Feeding K as a raw key (`PRAGMA key="x'…'"`) fails with "file is not a database".
 *
 * The whole file is encrypted at the page level, so once opened every column is
 * plaintext — there is no per-field `enc` step (unlike db1.chat_logs / db2.open_chat_member).
 *
 * The table `chat_log_search` holds the same snowflake `id` as db1.chat_logs.id plus
 * `searchable_text` = the decrypted full-text of each message. This makes full-text
 * message lookup possible: match on chat_log_search, then tie back to chat_logs.id.
 * `room_master_table` is Room's migration-check table (single row).
 *
 * Graceful: if the keystore blob is unreadable (hardware-backed keystore) or the DB is
 * absent, open() returns false and callers fall back silently — no crash.
 */
object CryptoDatabaseReader {
    private const val DB_NAME = "crypto_database"
    private const val KEY_ALIAS = "crypto_db_passphrase_key"

    @Volatile
    private var db: SQLiteDatabase? = null

    @Volatile
    private var loaded = false

    /** Derives the 32-byte passphrase key K from the keystore raw key for a given alias. */
    @Throws(Exception::class)
    fun deriveKey(): ByteArray {
        val key = KeystoreRawKey.hmacKeyFor(KEY_ALIAS, DB_NAME)
            ?: throw IllegalStateException("keystore raw key unavailable")
        return key
    }

    /** Opens crypto_database read-only, deriving the key from the AndroidKeyStore dump. */
    @Synchronized
    fun open(): Boolean {
        if (db != null) return true
        if (!loaded && !SqlCipherNative.ensureLoaded()) {
            loaded = false
            return false
        }
        loaded = true
        return try {
            val key = deriveKey()
            val workFile = copyToWorkingCopy() ?: return false
            db = SQLiteDatabase.openDatabase(
                workFile.absolutePath, key, null,
                SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
                null
            )
            true
        } catch (e: Throwable) {
            System.err.println("CryptoDatabaseReader: open failed: $e")
            close()
            false
        }
    }

    /**
     * Copies crypto_database (+ -wal) into a private working dir, same rationale as
     * CryptoUserDatabaseReader: Kakao keeps the DB in WAL mode, so read-only access to
     * the live files fails without write access to the -shm/-wal. Stale copies are
     * refreshed when the source mtime advances.
     */
    private fun copyToWorkingCopy(): File? {
        return try {
            val srcMain = File(PathUtils.getAppPath(), "databases/$DB_NAME")
            if (!srcMain.isFile) {
                System.err.println("CryptoDatabaseReader: source not found: $srcMain")
                return null
            }
            val workDir = File(cacheDir(), "crypto_db")
            workDir.mkdirs()
            val dstMain = File(workDir, DB_NAME)
            val dstWal = File(workDir, "$DB_NAME-wal")
            val srcWal = File(srcMain.parentFile, "$DB_NAME-wal")
            val srcNewer = dstMain.lastModified() < srcMain.lastModified() ||
                (srcWal.isFile && srcWal.lastModified() > dstMain.lastModified())
            if (!dstMain.isFile || srcNewer) {
                copyInto(srcMain, dstMain)
                if (srcWal.isFile) copyInto(srcWal, dstWal) else dstWal.delete()
            }
            dstMain
        } catch (e: Throwable) {
            System.err.println("CryptoDatabaseReader: copyToWorkingCopy failed: $e")
            null
        }
    }

    private fun copyInto(src: File, dst: File) {
        RandomAccessFile(src, "r").use { raf ->
            dst.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                val size = raf.length()
                var pos = 0L
                while (pos < size) {
                    val n = raf.read(buf, 0, minOf(buf.size.toLong(), size - pos).toInt())
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    pos += n
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
            val files = app!!.javaClass.getMethod("getFilesDir").invoke(app)
            files as? File
        }.getOrNull()
        return dir ?: File("/data/local/tmp")
    }

    /**
     * Full-text lookup over `chat_log_search`: returns the matched log ids (==
     * db1.chat_logs.id snowflake) with a preview of searchable_text, most recent first.
     * Empty when the DB can't be opened or the term matches nothing.
     */
    fun searchMessages(query: String, limit: Int = 50): List<Pair<Long, String>> =
        if (!open()) emptyList() else runCatching {
            db?.rawQuery(
                "SELECT id, substr(searchable_text, 1, 120) FROM chat_log_search" +
                    " WHERE searchable_text LIKE ? ORDER BY id DESC LIMIT ?",
                arrayOf("%$query%", limit.toString())
            )?.use { c ->
                val out = ArrayList<Pair<Long, String>>()
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val text = c.getString(1) ?: ""
                    if (id != 0L) out.add(id to text)
                }
                out
            } ?: emptyList()
        }.getOrElse {
            System.err.println("CryptoDatabaseReader: searchMessages failed: $it")
            emptyList()
        }

    /** Names resolvable from crypto_database (chat_log_search is not a name source; kept
     *  for API symmetry with CryptoUserDatabaseReader — always null). */
    fun queryUserName(userId: Long): String? = null

    @Synchronized
    fun close() {
        db?.close()
        db = null
    }
}
