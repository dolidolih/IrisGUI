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

    /**
     * user_id → (표시 이름, 프로필 이미지 URL).
     *
     * `crypto_user_database.user`이 이름/프로필 이미지를 평문으로 모두 들고 있어 최우선으로
     * 쓰고, 없으면 `open_chat_member`(enc 필드 복호화)로 폴백한다. 둘 다 없으면 (null, null).
     */
    fun getUserProfile(userId: Long): Pair<String?, String?> {
        CryptoUserDatabaseReader.getUserProfile(userId)?.let { (n, u) ->
            if (!n.isNullOrEmpty() || !u.isNullOrEmpty()) return n to u
        }
        var name: String? = null
        var url: String? = null
        runCatching {
            if (hasTable("db2", "open_chat_member")) {
                connection.rawQuery(
                    "SELECT nickname, profile_image_url, enc FROM db2.open_chat_member WHERE user_id = ? ORDER BY _id DESC LIMIT 1",
                    arrayOf(userId.toString())
                ).use { c ->
                    if (c.moveToFirst()) {
                        val enc = c.getInt(2)
                        val nm = c.getString(0)
                        val prof = c.getString(1)
                        name = nm?.takeIf { it.isNotEmpty() }?.let {
                            runCatching { KakaoDecrypt.decrypt(enc, it, AppConfig.botId) }.getOrElse { nm }
                        }
                        url = prof?.takeIf { it.isNotEmpty() }?.let {
                            runCatching { KakaoDecrypt.decrypt(enc, it, AppConfig.botId) }.getOrNull()
                        }
                    }
                }
            }
        }.onFailure { System.err.println("getUserProfile: open_chat_member query error $it") }

        if (name.isNullOrEmpty()) name = CryptoUserDatabaseReader.getUserProfile(userId)?.first
        return name to url
    }

    /**
     * OpenChat 방 링크 메타 (`db2.open_link`) 조회.
     * link_id가 null인 1:1/regular 방은 null 반환.
     */
    fun getOpenLink(linkId: Long): Map<String, Any?>? {
        if (!hasTable("db2", "open_link")) return null
        return runCatching {
            connection.rawQuery(
                "SELECT id, name, url, image_url, member_limit, searchable, description " +
                    "FROM db2.open_link WHERE id = ?", arrayOf(linkId.toString())
            ).use { c ->
                if (c.moveToFirst()) {
                    mapOf(
                        "id" to c.getLong(0),
                        "name" to c.getString(1),
                        "url" to c.getString(2),
                        "image_url" to c.getString(3),
                        "member_limit" to if (c.isNull(4)) null else c.getInt(4),
                        "searchable" to if (c.isNull(5)) null else c.getInt(5),
                        "description" to c.getString(6)
                    )
                } else null
            }
        }.getOrElse {
            System.err.println("getOpenLink error: $it"); null
        }
    }

    /**
     * OpenChat 멤버 목록 (`db2.open_chat_member`) — link_id 기준.
     * nickname은 enc=31 복호화. privilege (1=admin, 2=manager/0=normal measured).
     */
    fun getOpenChatMembers(linkId: Long, limit: Int = 256): List<Map<String, Any?>> = runCatching {
        if (!hasTable("db2", "open_chat_member")) return emptyList()
        connection.rawQuery(
            "SELECT user_id, nickname, profile_image_url, privilege, enc FROM db2.open_chat_member WHERE link_id = ? ORDER BY _id DESC LIMIT ?",
            arrayOf(linkId.toString(), limit.toString())
        ).use { c ->
            val list = ArrayList<Map<String, Any?>>()
            while (c.moveToNext()) {
                val userId = c.getLong(0)
                val enc = c.getInt(4)
                val nicknameRaw = c.getString(1)
                val profileRaw = c.getString(2)
                val privilege = if (c.isNull(3)) null else c.getInt(3)
                val nickname = if (nicknameRaw.isNullOrEmpty()) null
                    else runCatching { KakaoDecrypt.decrypt(enc, nicknameRaw, AppConfig.botId) }.getOrElse { nicknameRaw }
                val profile = if (profileRaw.isNullOrEmpty()) null
                    else runCatching { KakaoDecrypt.decrypt(enc, profileRaw, AppConfig.botId) }.getOrElse { null }
                list.add(mapOf("user_id" to userId, "nickname" to nickname, "profile_image_url" to profile, "privilege" to privilege))
            }
            list
        }
    }.getOrElse {
        System.err.println("getOpenChatMembers error: $it"); emptyList()
    }

    /**
     * 리액션 조회 (db2.chat_log_meta type=2).
     * log_id 조인키는 chat_logs.id(snowflake, _id 아님).
     * content = {"rx":[{"k","o","c","a":{locale}}]}, o = 이모티콘 ID(예: "1200509_021").
     */
    fun getReactions(logId: Long): List<Map<String, Any?>> = runCatching {
        if (!hasTable("db2", "chat_log_meta")) return emptyList()
        connection.rawQuery(
            "SELECT content FROM db2.chat_log_meta WHERE log_id = ? AND type = 2 LIMIT 1",
            arrayOf(logId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return emptyList()
            val content = c.getString(0)
            if (content.isNullOrEmpty()) return emptyList()
            val rx = JSONObject(content).optJSONArray("rx") ?: return emptyList()
            val out = ArrayList<Map<String, Any?>>()
            for (i in 0 until rx.length()) {
                val o = rx.getJSONObject(i)
                val labels = o.optJSONObject("a")
                val label = if (labels != null) {
                    val it = labels.keys()
                    if (it.hasNext()) labels.getString(it.next()) else null
                } else null
                out.add(mapOf(
                    "id" to o.opt("k"),
                    "emotion_id" to o.optString("o", ""),
                    "count" to o.optInt("c", 1),
                    "label" to label
                ))
            }
            out
        }
    }.getOrElse {
        System.err.println("getReactions error: $it"); emptyList()
    }

    /** chat_id에 연결된 open_link.link_id (없으면 null). */
    fun linkIdOfChat(chatId: Long): Long? {
        return runCatching {
            connection.rawQuery(
                "SELECT link_id FROM chat_rooms WHERE id = ?", arrayOf(chatId.toString())
            ).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
            }
        }.getOrNull()
    }

    /**
     * 방 멤버 목록 (user_id + nickname + profile_image_url + privilege).
     *
     * OpenChat(link_id가 있는 방)은 `open_chat_member`를 link_id별로, 그 외 일반/1:1 방은
     * `chat_rooms.active_member_ids` JSON 배열의 id를 해석한다.
     */
    fun getRoomMembers(chatId: Long): List<Map<String, Any?>> {
        val linkId = linkIdOfChat(chatId)
        if (linkId != null) return getOpenChatMembers(linkId)

        val memberIds = runCatching {
            connection.rawQuery(
                "SELECT active_member_ids FROM chat_rooms WHERE id = ?", arrayOf(chatId.toString())
            ).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        if (memberIds.isNullOrEmpty()) return emptyList()

        return runCatching {
            val arr = JSONArray(memberIds)
            (0 until arr.length()).map { i ->
                val id = arr.getLong(i)
                val (nm, url) = getUserProfile(id)
                mapOf("user_id" to id, "nickname" to nm, "profile_image_url" to url)
            }
        }.getOrElse {
            System.err.println("getRoomMembers(active_member_ids) error: $it"); emptyList()
        }
    }

    /** chat_rooms 정보 요약 (room_id, member_ids, private_meta.name, link_id 등). */
    fun getChatRoomMeta(chatId: Long): Map<String, Any?> {
        return runCatching {
            connection.rawQuery(
                "SELECT id, active_member_ids, private_meta, link_id, type, last_updated_at " +
                    "FROM chat_rooms WHERE id = ?", arrayOf(chatId.toString())
            ).use { c ->
                if (!c.moveToFirst()) return emptyMap()
                val linkId = if (c.isNull(3)) null else c.getLong(3)
                val name = runCatching {
                    val pm = c.getString(2)
                    if (!pm.isNullOrEmpty()) {
                        val m = Json.decodeFromString<Map<String, JsonElement>>(pm)["name"]
                        m?.jsonPrimitive?.content
                    } else null
                }.getOrNull()
                // OpenChat/regular 방은 private_meta 비어있으므로 db2.open_link.name 로 폴백.
                var resolvedName = if (name.isNullOrEmpty() && linkId != null) {
                    getOpenLink(linkId)?.get("name") as? String
                } else name
                // 1:1(DirectChat)은 private_meta/link_id 없음 → active_member_ids 의 bot 아닌 상대 이름으로 폴백.
                if (resolvedName.isNullOrEmpty()) {
                    val rep = firstNonBotMember(c.getString(1))
                    if (rep != 0L) resolvedName = queryUserName(c.getString(0).toLong(), rep)
                }
                mapOf(
                    "id" to c.getString(0),
                    "name" to resolvedName,
                    "active_member_ids" to c.getString(1),
                    "link_id" to linkId,
                    "type" to if (c.isNull(4)) null else c.getInt(4),
                    "last_updated_at" to c.getString(5)
                )
            }
        }.getOrElse {
            System.err.println("getChatRoomMeta error: $it"); emptyMap()
        }
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