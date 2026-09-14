package party.qwer.irisgui.backend

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonPrimitive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import party.qwer.irisgui.AppConfig

class KakaoDB {
    lateinit var connection: SQLiteDatabase

    init {
        try {
            connection =
                SQLiteDatabase.openDatabase(":memory:", null, SQLiteDatabase.OPEN_READWRITE)
            connection.execSQL("ATTACH DATABASE '$DB_PATH/KakaoTalk.db' AS db1")
            connection.execSQL("ATTACH DATABASE '$DB_PATH/KakaoTalk2.db' AS db2")
            connection.execSQL("ATTACH DATABASE '$DB_PATH/multi_profile_database.db' AS db3")
            AppConfig.botId = botUserId
        } catch (e: SQLiteException) {
            System.err.println("SQLiteException: " + e.message)
            System.err.println("You don't have a permission to access KakaoTalk Database.")
            System.exit(1)
        }
    }

    val botUserId: Long
        get() {
            return connection.rawQuery(
                "SELECT user_id FROM chat_logs WHERE v LIKE '%\"isMine\":true%' ORDER BY _id DESC LIMIT 1;",
                null
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    val botUserId = cursor.getLong(0)
                    println("Bot user_id is detected: $botUserId")

                    botUserId
                } else {
                    System.err.println("Warning: Bot user_id not found in chat_logs with isMine:true. Decryption might not work correctly.")

                    0
                }
            }
        }


    /**
     * user_id에 대한 표시 이름을 DB 복호화로 반환한다.
     *
     * KakaoTalk 26.7.2부터 friends 테이블이 제거되었고, 1:1 친구/DirectChat의
     * 프로필까지 모두 db2.open_chat_member(nickname+enc)에 저장된다.
     * 닉네임 필드 복호화의 시드는 sender id가 아니라 계정 주인의 user_id이므로
     * AppConfig.botId를 시드로 사용한다. recommended_friends는 open_chat_member에
     * 없을 때 사용하는 보조 저장소다.
     */
    /** Bounded cache of user id → plaintext nickname resolved from crypto_user_database. */
    private val cryptoNameCache = LinkedHashMap<Long, String?>()
    private var cryptoCacheSize = 0

    private fun queryUserNameFromCrypto(userId: Long): String? {
        synchronized(cryptoNameCache) {
            if (cryptoNameCache.containsKey(userId)) return cryptoNameCache[userId]
            if (cryptoCacheSize > 512) { cryptoNameCache.clear(); cryptoCacheSize = 0 }
        }
        val name = runCatching { CryptoUserDatabaseReader.queryUserName(userId) }.getOrNull()
        synchronized(cryptoNameCache) {
            cryptoNameCache[userId] = name
            cryptoCacheSize = cryptoNameCache.size
        }
        return name
    }

    fun queryUserName(chatId: Long, userId: Long): String? {
        val stringUserId = arrayOf(userId.toString())
        val isOpenLink = (chatId and (2 shl 53)) != 0L

        // open_chat_member carries the vast majority of members in practice (measured
        // 802/806 live room-member ids) — but only under the long 19-digit id, so the
        // crypto side still has to be consulted for plus friends / Kakao channels /
        // talk accounts (crypto_user_database.user or .talk_channel), which is why it
        // is tried first for non-OpenLink chats to avoid a wasted encrypted query.
        if (!isOpenLink) {
            queryUserNameFromCrypto(userId)?.let { return it }
        }

        val candidates = mutableListOf<Pair<String, String>>()
        if (hasTable("db2", "open_chat_member")) {
            val sql =
                if (isOpenLink)
                    "SELECT nickname AS name, enc FROM db2.open_chat_member WHERE user_id = ? ORDER BY _id DESC LIMIT 1"
                else
                    "SELECT nickname AS name, enc FROM db2.open_chat_member WHERE user_id = ?"
            candidates.add("open_chat_member" to sql)
        }
        if (hasTable("db2", "recommended_friends")) {
            candidates.add(
                "recommended_friends" to
                    "SELECT nick_name AS name, enc FROM db2.recommended_friends WHERE user_id = ?"
            )
        }

        val result = candidates.firstNotNullOfOrNull { (source, sql) ->
            runCatching {
                connection.rawQuery(sql, stringUserId).use { cursor ->
                    cursor.firstOrNull {
                        getString(getColumnIndexOrThrow("name")) to getInt(getColumnIndexOrThrow("enc"))
                    }
                }
            }.onFailure {
                System.err.println("queryUserName($source): query error $it")
            }.getOrNull()
        }

        val name = result?.let { (encryptedName, enc) ->
            runCatching {
                KakaoDecrypt.decrypt(enc, encryptedName, AppConfig.botId)
            }.onFailure {
                System.err.println("queryUserName: decrypt error $it")
            }.getOrElse {
                encryptedName
            }
        }
        if (!name.isNullOrEmpty()) return name

        // Last resort: a plaintext nickname from crypto_user_database.
        return queryUserNameFromCrypto(userId)
    }


    fun getChatInfo(chatId: Long, userId: Long): Array<String?> {
        val sender = if (userId == AppConfig.botId) {
            AppConfig.botName
        } else {
            queryUserName(chatId, userId)
        }

        connection.rawQuery(
            "SELECT private_meta FROM chat_rooms WHERE id = ?", arrayOf(chatId.toString())
        ).use { cursor ->
            if (cursor.moveToNext()) {
                val value = cursor.getString(0)
                if (!value.isNullOrEmpty()) {
                    try {
                        val meta = Json.decodeFromString<Map<String, JsonElement>>(value)
                        val name = meta["name"]

                        if (name != null) {
                            return arrayOf(name.jsonPrimitive.content, sender)
                        }
                    } catch (_: Exception) {
                    }
                }
            }
        }

        connection.rawQuery(
            "SELECT name FROM db2.open_link WHERE id = (SELECT link_id FROM chat_rooms WHERE id = ?)",
            arrayOf(chatId.toString())
        ).use { cursor ->
            if (cursor.moveToNext()) {
                return arrayOf(cursor.getString(0), sender)
            }
        }

        return arrayOf(sender, sender)
    }

    /**
     * 최근 채팅방 목록 (한글 방이름 해석 포함).
     * 1:1 방은 상대방 이름, 그룹/오픈채팅은 방 이름으로 표시된다.
     */
    fun getRecentRooms(limit: Int = 30): List<Map<String, String?>> {
        val rooms = mutableListOf<Map<String, String?>>()
        try {
            connection.rawQuery(
                "SELECT id, active_member_ids, last_updated_at FROM chat_rooms WHERE last_log_id IS NOT NULL ORDER BY last_updated_at DESC LIMIT ?",
                arrayOf(limit.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val roomId = cursor.getString(0) ?: continue
                    val memberIds = cursor.getString(1)
                    val updatedAt = cursor.getString(2)
                    val chatIdLong = roomId.toLongOrNull() ?: continue
                    val repUserId = firstNonBotMember(memberIds)
                    val name = try { getChatInfo(chatIdLong, repUserId)[0] } catch (e: Exception) { null }
                    rooms.add(mapOf("id" to roomId, "name" to name, "updated_at" to updatedAt))
                }
            }
        } catch (e: Exception) {
            System.err.println("getRecentRooms error: ${e.message}")
        }
        return rooms
    }

    /** active_member_ids(JSON 배열 문자열)에서 봇이 아닌 첫 멤버 id를 반환(1:1 방이름 해석용) */
    private fun firstNonBotMember(memberIdsJson: String?): Long {
        if (memberIdsJson.isNullOrEmpty()) return AppConfig.botId
        return try {
            val arr = JSONArray(memberIdsJson)
            for (i in 0 until arr.length()) {
                val id = arr.getLong(i)
                if (id != AppConfig.botId) return id
            }
            AppConfig.botId
        } catch (e: Exception) {
            AppConfig.botId
        }
    }

    fun logToDict(logId: Long): Map<String, String?> {
        val dict: MutableMap<String, String?> = HashMap()

        connection.rawQuery("SELECT * FROM chat_logs ORDER BY _id DESC LIMIT 1", null)
            .use { cursor ->
                if (cursor.moveToNext()) {
                    val columnNames = cursor.columnNames
                    for (columnName in columnNames) {
                        val columnIndex = cursor.getColumnIndexOrThrow(columnName)
                        dict[columnName] = cursor.getString(columnIndex)
                    }
                }
            }

        return dict
    }

    fun hasTable(database: String, tableName: String): Boolean {
        return connection.rawQuery(
            "SELECT name FROM ${database}.sqlite_master WHERE type = 'table' AND name = ?",
            arrayOf(tableName)
        ).use { cursor ->
            cursor.count > 0
        }
    }

    fun closeConnection() {
        if (connection.isOpen) {
            connection.close()
            println("Database connection closed.")
        }
    }

    fun executeQuery(
        sqlQuery: String, bindArgs: Array<String?>?
    ): List<Map<String, String?>> {
        val resultList: MutableList<Map<String, String?>> = ArrayList()
        connection.rawQuery(sqlQuery, bindArgs).use { cursor ->
            val columnNames = cursor.columnNames
            while (cursor.moveToNext()) {
                val row: MutableMap<String, String?> = HashMap()
                for (columnName in columnNames) {
                    val columnIndex = cursor.getColumnIndexOrThrow(columnName)
                    row[columnName] = cursor.getString(columnIndex)
                }
                resultList.add(row)
            }
        }
        return resultList
    }

    companion object {
        private val DB_PATH: String by lazy {
            "${PathUtils.getAppPath()}databases"
        }
        fun decryptRow(row: Map<String, String?>): Map<String, String?> {
            @Suppress("NAME_SHADOWING") var row = row.toMutableMap()

            try {
                if (row.contains("message") || row.contains("attachment")) {
                    val vStr = row.getOrDefault("v", "")
                    if (vStr?.isNotEmpty() == true) {
                        try {
                            val vJson = JSONObject(vStr)
                            val enc = vJson.optInt("enc", 0)
                            val userId = row.get("user_id")?.toLongOrNull() ?: AppConfig.botId
                            val keysToDecrypt = arrayOf("message", "attachment")
                            row = decryptRowValues(row, enc, userId, keysToDecrypt)
                        } catch (e: JSONException) {
                            System.err.println("Error parsing 'v' for decryption: $e")
                        }
                    }
                }

                val keywords = listOf("name", "nick_name", "nickname", "profile_image_url", "full_profile_image_url", "original_profile_image_url", "status_message", "contact_name", "board_v")

                if (row.contains("enc") && keywords.any { row.contains(it) }) {
                    val botId = AppConfig.botId
                    val enc = row["enc"]?.toIntOrNull() ?: 0
                    val keysToDecrypt = arrayOf("nick_name", "name", "nickname", "profile_image_url", "full_profile_image_url", "original_profile_image_url", "status_message","contact_name","v","board_v")
                    row = decryptRowValues(row, enc, botId, keysToDecrypt)
                }
            } catch (e: Exception) {
                System.err.println("JSON processing error during decryption: $e")
            }

            return row
        }
        private fun decryptRowValues(row: MutableMap<String, String?>, enc: Int, botId: Long, keysToDecrypt: Array<String>): MutableMap<String, String?> {
            for (key in keysToDecrypt) {
                if (row.containsKey(key)) {
                    try {
                        val encryptedValue = row.getOrDefault(key, "") as? String
                        if (encryptedValue != "{}" && encryptedValue != "[]"){
                            encryptedValue?.let {
                                row[key] = KakaoDecrypt.decrypt(enc, it, botId)
                            }
                        }
                    } catch (e: Exception) {
                        System.err.println("Decryption error for $key: $e")
                    }
                }
            }
            return row
        }
    }
}

inline fun <T> Cursor.firstOrNull(
    block: Cursor.() -> T
): T? {
    if (!moveToFirst()) return null
    return block()
}