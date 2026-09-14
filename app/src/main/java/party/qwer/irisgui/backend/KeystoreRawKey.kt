package party.qwer.irisgui.backend

import android.database.sqlite.SQLiteDatabase
import java.io.File
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * KakaoTalk의 SQLCipher DB 키를 AndroidKeyStore HMAC 키로부터 유도한다.
 *
 * 키 유도 (실측 검증):
 *   K = HMAC-SHA256(rawKey, alias)
 *   crypto_database           alias "crypto_db_passphrase_key", message "crypto_database"
 *   cecall_history_database.db alias = message = filename
 *
 * rawKey 출처: root 셸/daemon(uid 0)에서 `/data/misc/keystore/persistent.sqlite`의
 * `keyentry`(namespace=카톡 uid, alias) → `blobentry`(subcomponent_type=0) blob.
 * blob은 software-backed KM blob("PKMblob" 매직 + 4B type + LE uint32 keyLen + rawKey).
 *
 * 주의: hardware-backed(TZB/TEE) keystore 기기에서는 blob에 평문 rawKey가 없어 null을
 * 반환한다. 그 경우 카톡 uid 도메인에서 `Mac.doFinal`을 수행하는 probe가 필요 (§10).
 * null이면 호출측은 조용히 폴백한다 (crash/오류 없음).
 */
object KeystoreRawKey {
    private const val PERSISTENT = "/data/misc/keystore/persistent.sqlite"
    private const val KAKAO_UID = 10089
    private const val BLOB_MAGIC = "PKMblob"

    /** alias의 raw HMAC key(32B), 없거나 파싱 불가 시 null. */
    fun hmacRawKey(alias: String, uid: Int = KAKAO_UID): ByteArray? =
        runCatching { parseFromPersistent(alias, uid) }.getOrNull()

    /** K = HMAC-SHA256(rawKey(alias), message). rawKey 불가 시 null. */
    fun hmacKeyFor(alias: String, message: String, uid: Int = KAKAO_UID): ByteArray? {
        val raw = hmacRawKey(alias, uid) ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(raw, "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8))
    }

    /**
     * offset 하드코딩 대신 KM blob 헤더를 따라간다: 매직("PKMblob" NUL-terminated) 뒤
     * type(4B), keyLen(LE uint32 @ +9), rawKey(+13, keyLen bytes).
     */
    private fun parseFromPersistent(alias: String, uid: Int): ByteArray? {
        val dbFile = File(PERSISTENT)
        if (!dbFile.isFile) {
            System.err.println("KeystoreRawKey: not found: $PERSISTENT")
            return null
        }
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        ).use { db ->
            db.rawQuery(
                "SELECT b.blob FROM blobentry b JOIN keyentry k ON b.keyentryid = k.id" +
                    " WHERE k.namespace = ? AND k.alias = ? AND b.subcomponent_type = 0" +
                    " ORDER BY k.id",
                arrayOf(uid.toString(), alias)
            ).use { c ->
                // key당 blob이 여러 개 등록될 수 있으므로 마지막(최신) 항목을 사용한다.
                var blob: ByteArray? = null
                while (c.moveToNext()) blob = c.getBlob(0)
                return blob?.let { extractRawKey(it) }
            }
        }
    }

    private fun extractRawKey(blob: ByteArray): ByteArray? {
        val magic = BLOB_MAGIC.toByteArray(Charsets.US_ASCII)
        if (blob.size < 13) return null
        // 매직은 NUL terminator 포함이라字节 단위로 앞에 붙어 있을 수 있다 — 뒤에紧跟하는
        // "PKMblob" 위치를 찾고, 없으면 offset 0으로 간주한다.
        var off = indexOf(blob, magic)
        if (off < 0) off = 0
        val keyLenOff = off + 9
        if (keyLenOff + 4 > blob.size) return null
        val keyLen = (blob[keyLenOff].toInt() and 0xff) or
            ((blob[keyLenOff + 1].toInt() and 0xff) shl 8) or
            ((blob[keyLenOff + 2].toInt() and 0xff) shl 16) or
            ((blob[keyLenOff + 3].toInt() and 0xff) shl 24)
        val start = off + 13
        if (keyLen <= 0 || start + keyLen > blob.size) {
            System.err.println("KeystoreRawKey: unexpected KM blob (len=$keyLen, size=${blob.size})")
            return null
        }
        return blob.copyOfRange(start, start + keyLen)
    }

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
