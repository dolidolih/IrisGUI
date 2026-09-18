package party.qwer.irisgui.backend

import android.database.Cursor
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.LinkedList
import java.util.concurrent.Executors
import kotlin.collections.set
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppState

class ObserverHelper(
    private val db: KakaoDB, private val wsBroadcastFlow: SharedFlow<String>
) {
    private var lastLogId: Long = 0
    private val lastDecryptedLogs = LinkedList<Map<String, String?>>()
    // 읽기 전용 경로(/chat 조회)에서는 절대 쓰지 않는 스레드 풀이 생성자마다 뜨지 않도록 지연 초기화한다.
    private val httpRequestExecutor by lazy { Executors.newFixedThreadPool(8) }
    private val okHttpClient = OkHttpClient()

    fun checkChange(db: KakaoDB) {
        if (lastLogId == 0L) {
            lastLogId = getLastLogIdFromDB()
            println("Initial lastLogId: $lastLogId")
            return
        }

        val newLogCount = getNewLogCountFromDB()

        if (newLogCount > 0) {
            println("Detected $newLogCount new log(s). Processing...")

            db.connection.rawQuery(
                "SELECT * FROM chat_logs WHERE _id > ? ORDER BY _id ASC",
                arrayOf(lastLogId.toString())
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val columnNames = cursor.columnNames
                    val currentLogId = cursor.getLong(columnNames.indexOf("_id"))

                    if (currentLogId > lastLogId) {
                        val typeVal = cursor.getString(columnNames.indexOf("type")).toIntOrNull() ?: 0
                        val origin = runCatching {
                            JSONObject(cursor.getString(columnNames.indexOf("v")) ?: "").getString("origin")
                        }.getOrDefault("")

                        // origin/type 브로드캐스트 필터 (filter=null 이면 현행: SYNCMSG/MCHATLOGS 제외).
                        val filter = AppConfig.broadcastTypes
                        if (!KakaoMessageType.shouldBroadcast(typeVal, origin, filter, AppConfig.includeSystemEvents)) {
                            lastLogId = currentLogId
                            continue
                        }

                        val frame = buildFrame(cursor, columnNames)
                        lastLogId = currentLogId

                        if (frame != null) {
                            val data = JSONObject(frame).toString()

                            runBlocking {
                                (wsBroadcastFlow as kotlinx.coroutines.flow.MutableSharedFlow<String>).emit(data)
                            }

                            if (AppConfig.webEndpoint.isNotEmpty()) {
                                httpRequestExecutor.execute {
                                    sendPostRequest(data)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    /**
     * chat_logs 커서가 가리키는 행 하나를 /ws 브로드캐스트 프레임과 동일한 형태로 조립한다.
     * DB 폴링(`/ws`)과 `/chat` GET 엔드포인트가 같은 포맷을 쓰도록 분리한 것이라,
     * 한쪽 포맷을 바꾸면 자동으로 양쪽 모두에 반영된다.
     *
     * 원본 프레임과 동일하게 맞추기 위해 decrypt 중 예외가 나면(=행이 잘못됨) null을 반환한다.
     */
    private fun buildFrame(cursor: Cursor, columnNames: Array<String>, store: Boolean = true): Map<String, Any?>? {
        val v = try {
            JSONObject(cursor.getString(columnNames.indexOf("v")))
        } catch (e: Exception) {
            return null
        }
        val enc = v.optInt("enc", 0)
        val origin = v.optString("origin", "")
        val typeVal = cursor.getString(columnNames.indexOf("type")).toIntOrNull() ?: 0
        val messageType = cursor.getString(columnNames.indexOf("type"))
        val chatId = cursor.getLong(columnNames.indexOf("chat_id"))
        val userId = cursor.getLong(columnNames.indexOf("user_id"))

        var message = cursor.getString(columnNames.indexOf("message"))
        var attachment = cursor.getString(columnNames.indexOf("attachment"))

        val threadId: String? = if (columnNames.indexOf("thread_id") != -1) {
            cursor.getString(columnNames.indexOf("thread_id"))
        } else {
            null
        }

        var supplement = "{}"
        try {
            supplement = cursor.getString(columnNames.indexOf("supplement"))
            if (supplement.isNotEmpty() && supplement != "{}")
                supplement = KakaoDecrypt.decrypt(enc, supplement, userId)
        } catch (_: Exception) {
        }

        try {
            if (message.isNotEmpty() && message != "{}") message =
                KakaoDecrypt.decrypt(enc, message, userId)
        } catch (e: Exception) {
            println("failed to decrypt message: $e")
            return null
        }

        try {
            if ((message.contains("선물") && messageType == "71") || (attachment == null)) {
                attachment = "{}"
            } else if (attachment.isNotEmpty() && attachment != "{}") {
                attachment = KakaoDecrypt.decrypt(enc, attachment, userId)
            }
        } catch (e: Exception) {
            println("failed to decrypt attachment: $e")
            return null
        }

        if (store) storeDecryptedLog(cursor, message)

        val raw = mutableMapOf<String, String?>()
        val advancedPlainSerialized = mutableMapOf<String, MutableMap<String, Any?>?>()

        for ((idx, columnName) in columnNames.withIndex()) {
            if (columnName == "message") {
                raw[columnName] = message
            } else if (columnName == "attachment") {
                raw[columnName] = attachment
                advancedPlainSerialized[columnName] = getStringJsonToMap(attachment)
                advancedPlainSerialized[columnName]!!["src_isThread"] = false
            } else if (columnName == "supplement") {
                raw["supplement"] = supplement
                advancedPlainSerialized[columnName] = getStringJsonToMap(supplement)
            } else {
                raw[columnName] = cursor.getString(idx)
            }
        }

        if (
            (threadId == null || threadId.isEmpty()) &&
            advancedPlainSerialized["supplement"]!!.getOrDefault("threadId", "") != "" &&
            advancedPlainSerialized["attachment"]!!.getOrDefault("src_logId", "") == "" &&
            messageType == "1"
        ) {
            advancedPlainSerialized["attachment"]!!["src_logId"] =
                advancedPlainSerialized["supplement"]!!.getOrDefault("threadId", "")
            advancedPlainSerialized["attachment"]!!["src_isThread"] = true
        } else if (threadId != null && messageType == "1") {
            advancedPlainSerialized["attachment"]!!["src_logId"] = threadId.toLong()
            advancedPlainSerialized["attachment"]!!["src_isThread"] = true
        }

        raw["attachment"] = JSONObject(advancedPlainSerialized["attachment"]!!).toString()

        val chatInfo = db.getChatInfo(chatId, userId)
        val frame = mutableMapOf<String, Any?>(
            "msg" to message,
            "room" to chatInfo[0],
            "sender" to chatInfo[1],
            "json" to raw
        )
        if (AppConfig.enableExtension) {
            buildExtension(cursor, columnNames, v, userId, chatId, typeVal, origin)?.let {
                frame["extension"] = it
            }
        }
        return frame
    }

    /**
     * `/chat/{id}` GET 조회용. `id` 는 /ws extension 의 `log_id` 와 같은 스노우플레이크 id 이다
     * (`/reactions/{id}` 와 동일 계열). 행이 존재하지 않으면 null.
     */
    fun frameForLogId(id: Long): Map<String, Any?>? =
        connectionQuery("SELECT * FROM chat_logs WHERE id = ?", arrayOf(id.toString()))

    /**
     * 같은 채팅방(chat_id) 기준으로 n번째 이전(offset<0) / n번째 다음(offset>0) 로그의
     * 프레임. 물리 순서(_id)를 기준으로 하며, 이는 id(스노우플레이크)처럼 정확히 단조 증가하지
     * 않는 값에 의존하지 않아도 되기 때문에 안전하다.
     */
    fun frameRelative(id: Long, offset: Int): Map<String, Any?>? {
        if (offset == 0) return frameForLogId(id)
        val row =
            "SELECT _id, chat_id FROM chat_logs WHERE id = ? ORDER BY _id ASC LIMIT 1"
        val seed = db.connection.rawQuery(row, arrayOf(id.toString())).use { c ->
            if (!c.moveToNext()) return null
            c.getLong(0) to c.getLong(1)
        }
        val (seedId, chatId) = seed
        val (cmp, ord) = if (offset < 0) ("<" to "DESC") else (">" to "ASC")
        return connectionQuery(
            "SELECT * FROM chat_logs WHERE _id $cmp ? AND chat_id = ? ORDER BY _id $ord LIMIT 1 OFFSET ?",
            arrayOf(seedId.toString(), chatId.toString(), (kotlin.math.abs(offset) - 1).toString())
        )
    }

    /** 임의 SELECT 한 행을 /ws 프레임 형태로 반환(0~1행 기대). */
    private fun connectionQuery(sql: String, bind: Array<String?>): Map<String, Any?>? =
        db.connection.rawQuery(sql, bind).use { cursor ->
            if (!cursor.moveToNext()) return@use null
            buildFrame(cursor, cursor.columnNames, store = false)
        }

    /**
     * chat_logs 에서 days(0.5 = 12시간)보다 오래된 로그를 삭제한다.
     * created_at(초 단위 epoch) 기준이며, 0.5일처럼 소수일 허용을 위해 float로 계산한다.
     * 반환은 삭제된 행 수.
     *
     * VACUUM은 하지 않는다 — 전체 DB를 재작성하므로 수 초간 다른 쓰기/읽기를 막고,
     * SQLite 는 삭제 후 남는 free page를 이후 INSERT에서 재사용하기 때문에 용량은 저절로 회복된다.
     */
    fun purgeChatLogsOlderThan(days: Double): Int {
        val cutoffSec = System.currentTimeMillis() / 1000.0 - days * 86400.0
        val stmt = db.connection.compileStatement(
            "DELETE FROM chat_logs WHERE created_at > 0 AND created_at < ?"
        )
        return try {
            stmt.bindDouble(1, cutoffSec)
            stmt.executeUpdateDelete()
        } finally {
            stmt.close()
        }
    }


    private fun getLastLogIdFromDB(): Long {
        val lastLog = db.logToDict(0)
        return lastLog["_id"]?.toLongOrNull() ?: 0
    }

    /**
     * /ws 프레임용 `extension` 조립.
     *
     * 기존 `msg/room/sender/json` 은 불변이고, `json` 의 key 유무/형식은 절대 바꾸지 않는다.
     * irispy-client는 top-level 신규 키 `extension`만 ignoreUnknownKeys=true 로 무시하므로 안전.
     *
     * 싼 필드(타코드/OpenChat비트/origin)만 담고, 리액션은 해당 로그에 실제로 reaction이
     * 있는 경우(chat_log_meta type=2 존재)에만 쿼리한다.
     */
    private fun buildExtension(
        cursor: Cursor,
        columnNames: Array<String>,
        v: JSONObject,
        userId: Long,
        chatId: Long,
        type: Int,
        origin: String
    ): Map<String, Any?>? {
        val out = LinkedHashMap<String, Any?>()
        out["type_code"] = type
        out["type_base"] = KakaoMessageType.baseType(type)
        out["is_openchat"] = KakaoMessageType.isOpenChat(type)
        out["type_name"] = KakaoMessageType.classify(type, origin)
        if (userId == AppConfig.botId) out["is_mine"] = true

        // 소프트 삭제로 남은(삭제된) 메시지 여부. deleted_at > 0 는 삭제 마크이므로
        // 폴링 필터에서 origin=MCHATLOGS/SYNCMODMSG 로 걸러진 삭제건을 식별하는 수단.
        val delIdx = columnNames.indexOf("deleted_at")
        if (delIdx != -1) {
            val deletedAt = cursor.getString(delIdx)?.toLongOrNull() ?: 0L
            if (deletedAt > 0) out["is_deleted"] = true
        }

        val logIdIdx = columnNames.indexOf("id")
        if (logIdIdx != -1) {
            val snowflake = cursor.getString(logIdIdx)
            out["log_id"] = snowflake
            try {
                val rx = db.getReactions(snowflake?.toLongOrNull() ?: -1L)
                if (rx.isNotEmpty()) out["reactions"] = rx
            } catch (e: Exception) {
                // reaction 조회 실패는 무시 (extension 는 best-effort)
            }
        }
        return if (out.isEmpty()) null else out
    }

    private fun getStringJsonToMap(data: String?): MutableMap<String, Any?> {
        // null 및 빈 문자열("")은 Object가 없는것으로 취급.
        // attachment/supplement 컬럼은 값이 없는 메시지가 많기 때문에 ""가 전달될 수 있고,
        // 그때 JSONObject("")가 "End of input at character 0"를 던져 DB 폴링 전체가 중단된다.
        if(data == null || data.isEmpty()) return HashMap()
        val object_ = JSONObject(data)
        val map: MutableMap<String, Any?> = HashMap()

        val keys: MutableIterator<String> = object_.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value: Any? = object_.get(key)
            map[key] = value
        }

        return map
    }

    private fun getNewLogCountFromDB(): Int {
        val res = db.executeQuery(
            "select count(*) as cnt from chat_logs where _id > ?", arrayOf(lastLogId.toString())
        )
        return res[0]["cnt"]?.toIntOrNull() ?: 0
    }

    @Synchronized
    private fun storeDecryptedLog(cursor: Cursor, decryptedMessage: String?) {
        val logEntry: MutableMap<String, String?> = HashMap()
        logEntry["_id"] = cursor.getString(cursor.getColumnIndexOrThrow("_id"))
        logEntry["chat_id"] = cursor.getString(cursor.getColumnIndexOrThrow("chat_id"))
        logEntry["user_id"] = cursor.getString(cursor.getColumnIndexOrThrow("user_id"))
        logEntry["message"] = decryptedMessage
        logEntry["created_at"] = cursor.getString(cursor.getColumnIndexOrThrow("created_at"))

        // 방이름/발신자명 해석 — 대시보드에서 id 대신 이름을 보여주기 위함
        try {
            val cId = logEntry["chat_id"]?.toLongOrNull()
            val uId = logEntry["user_id"]?.toLongOrNull()
            if (cId != null && uId != null) {
                val info = db.getChatInfo(cId, uId)
                logEntry["room_name"] = info[0]
                logEntry["user_name"] = info[1]
            }
        } catch (e: Exception) {
            // 이름 해석 실패 시 id로 폴백(아래 UI에서 처리)
        }

        lastDecryptedLogs.addFirst(logEntry)
        if (lastDecryptedLogs.size > MAX_LOGS_STORED) {
            lastDecryptedLogs.removeLast()
        }

        // AppState에도 동기화 — UI 스레드에서 읽을 수 있도록 불변 복사본 전달
        val snapshot = lastDecryptedLogs.toList()
        AppState.lastChatLogs.clear()
        AppState.lastChatLogs.addAll(snapshot)
    }

    private fun sendPostRequest(jsonData: String) {
        val url = AppConfig.webEndpoint
        println("Sending HTTP POST request to: $url")
        println("JSON Data being sent: $jsonData")

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonData.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .post(requestBody)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build()

        try {
            okHttpClient.newCall(request).execute().use { response ->
                val responseCode = response.code
                println("HTTP Response Code: $responseCode")

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    println("HTTP Response Body: $responseBody")
                } else {
                    System.err.println("HTTP Error Response: $responseCode - ${response.message}")
                }
            }
        } catch (e: IOException) {
            System.err.println("Error sending POST request: " + e.message)
        }
    }

    /** @Synchronized on property without backing field → getter로 분리 */
    @Synchronized
    fun getLastChatLogs(): List<Map<String, String?>> = lastDecryptedLogs.toList()

    companion object {
        private const val MAX_LOGS_STORED = 50

        /**
         * DB 폴링을 돌리지 않고 /ws 프레임 조립만 재사용하기 위한 경량 인스턴스.
         * (SharedFlow 는 read-only 경로에서는 쓰여지지 않는다.)
         */
        fun forReads(db: KakaoDB): ObserverHelper =
            ObserverHelper(db, MutableSharedFlow(extraBufferCapacity = 1))
    }
}
