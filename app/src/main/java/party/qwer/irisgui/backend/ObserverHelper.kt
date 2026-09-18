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
    private val httpRequestExecutor = Executors.newFixedThreadPool(8)
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
                        val v = JSONObject(cursor.getString(columnNames.indexOf("v")))
                        val enc = v.getInt("enc")
                        val origin = v.getString("origin")
                        val typeVal = cursor.getString(columnNames.indexOf("type")).toIntOrNull() ?: 0

                        // origin/type 브로드캐스트 필터 (filter=null 이면 현행: SYNCMSG/MCHATLOGS 제외).
                        val filter = AppConfig.broadcastTypes
                        if (!KakaoMessageType.shouldBroadcast(typeVal, origin, filter, AppConfig.includeSystemEvents)) {
                            lastLogId = currentLogId
                            continue
                        }

                        val chatId = cursor.getLong(columnNames.indexOf("chat_id"))
                        val userId = cursor.getLong(columnNames.indexOf("user_id"))

                        var message = cursor.getString(columnNames.indexOf("message"))
                        var attachment = cursor.getString(columnNames.indexOf("attachment"))
                        val messageType = cursor.getString(columnNames.indexOf("type"))

                        val threadId: String? = if (columnNames.indexOf("thread_id") != -1) {
                            cursor.getString(columnNames.indexOf("thread_id"))
                        } else {
                            null
                        }

                        var supplement = "{}"
                        try {
                            supplement = cursor.getString(columnNames.indexOf("supplement"))
                            if(supplement.isNotEmpty() && supplement != "{}")
                                supplement = KakaoDecrypt.decrypt(enc, supplement, userId)
                        } catch(_: Exception) {}

                        try {
                            if (message.isNotEmpty() && message != "{}") message =
                                KakaoDecrypt.decrypt(enc, message, userId)
                        } catch (e: Exception) {
                            println("failed to decrypt message: $e")
                        }

                        try {
                            if ((message.contains("선물") && messageType == "71") or (attachment == null)) {
                                attachment = "{}"
                            } else if (attachment.isNotEmpty() && attachment != "{}") {
                                attachment =
                                    KakaoDecrypt.decrypt(enc, attachment, userId)
                            }
                        } catch (e: Exception) {
                            println("failed to decrypt attachment: $e")
                        }

                        storeDecryptedLog(cursor, message)

                        lastLogId = currentLogId

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
                            advancedPlainSerialized["supplement"]!!.getOrDefault("threadId", "") != ""
                            &&
                            advancedPlainSerialized["attachment"]!!.getOrDefault("src_logId", "") == ""
                            &&
                            messageType == "1"
                        ) {
                            advancedPlainSerialized["attachment"]!!["src_logId"] =
                                advancedPlainSerialized["supplement"]!!.getOrDefault("threadId", "")
                            advancedPlainSerialized["attachment"]!!["src_isThread"] = true
                        } else if(threadId != null && messageType == "1") {
                            advancedPlainSerialized["attachment"]!!["src_logId"] = threadId.toLong()
                            advancedPlainSerialized["attachment"]!!["src_isThread"] = true
                        }

                        raw["attachment"] = JSONObject(advancedPlainSerialized["attachment"]!!).toString()

                        val chatInfo = db.getChatInfo(chatId, userId)
                        var roomName = chatInfo[0]
                        var senderName = chatInfo[1]

                        val frame = mutableMapOf<String, Any?>(
                            "msg" to message,
                            "room" to roomName,
                            "sender" to senderName,
                            "json" to raw
                        )
                        if (AppConfig.enableExtension) {
                            buildExtension(cursor, columnNames, v, enc, userId, chatId, typeVal)?.let {
                                frame["extension"] = it
                            }
                        }

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
        enc: Int,
        userId: Long,
        chatId: Long,
        type: Int
    ): Map<String, Any?>? {
        val out = LinkedHashMap<String, Any?>()
        out["type_code"] = type
        out["type_base"] = KakaoMessageType.baseType(type)
        out["is_openchat"] = KakaoMessageType.isOpenChat(type)
        out["type_name"] = KakaoMessageType.classify(type, v.optString("origin", ""))
        if (userId == AppConfig.botId) out["is_mine"] = true

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
    }
}