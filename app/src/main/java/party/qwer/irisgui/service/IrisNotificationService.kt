package party.qwer.irisgui.service

import android.app.Notification
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Icon
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.AppState
import party.qwer.irisgui.backend.IrisServer
import party.qwer.irisgui.backend.KakaoNotificationParser
import party.qwer.irisgui.models.IrisJsonData
import party.qwer.irisgui.models.NotificationEvent
import party.qwer.irisgui.models.NotificationPayload
import party.qwer.irisgui.models.ReplyAction
import party.qwer.irisgui.models.StoredRoom
import java.io.ByteArrayOutputStream

class IrisNotificationService : NotificationListenerService() {
    private val httpClient = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /** A1: NLS 리스너 시작 상태 — 외부에서 확인용 */
    @Volatile
    var isStarted: Boolean = false

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!AppConfig.isServiceEnabled || sbn.packageName != "com.kakao.talk") return

        val notification = sbn.notification
        val extras = notification.extras ?: return

        // 해석은 shared 파서 하나(KakaoNotificationParser)에만 위임한다 — NLS와 루팅 폴러가 동일 규칙.
        val parsed = KakaoNotificationParser.parse(notification) ?: return

        val senderName = parsed.senderName
        val senderId = parsed.senderId
        val text = parsed.text
        val conversationTitleExtra = parsed.roomTitle

        val roomId = sbn.tag ?: ""
        val chatLogId = extras.getLong("chatLogId", 0L)

        val largeIconExtra = try { extras.get(Notification.EXTRA_LARGE_ICON) } catch (e: Exception) { null }
        val rawDump = dumpBundle(extras).toString()

        coroutineScope.launch {
            // 방 이름: shared 파서의 roomTitle → 모드별(KakaoRoomResolver) → 발신자 닉네임.
            // 발신자 닉네임을 폴백으로 쓰는 경우에도 그룹 대화의 방 이름을 resolver에 queries 한다.
            val resolverRoom = if (conversationTitleExtra == null) {
                KakaoRoomResolver.resolve(this@IrisNotificationService, this@IrisNotificationService, sbn.key, roomId)
            } else {
                null
            }
            val room = conversationTitleExtra ?: resolverRoom ?: senderName
            val roomSource = when {
                conversationTitleExtra != null -> "conversationTitle"
                resolverRoom != null -> "resolver"
                else -> "senderName"
            }
            val isGroupChat = parsed.isGroupConversation

            println(
                "IrisNls(P26): room=\"$room\" src=$roomSource isGroup=$isGroupChat " +
                    "tag=$roomId senderId=\"$senderId\" text=\"$text\""
            )

            val event = NotificationEvent(
                timestamp = System.currentTimeMillis(),
                room = room,
                roomId = roomId,
                senderName = senderName,
                senderId = senderId,
                isGroupChat = isGroupChat,
                text = text,
                rawDump = rawDump
            )

            extractAndStoreReplyAction(notification, room, roomId)

            withContext(Dispatchers.Main) {
                AppState.notificationHistory.add(0, event)
                while (AppState.notificationHistory.size > 10) {
                    AppState.notificationHistory.removeAt(AppState.notificationHistory.lastIndex)
                }
            }

            var profileImageBase64: String? = null
            try {
                val bitmapToCompress: Bitmap? = when (largeIconExtra) {
                    is Bitmap -> largeIconExtra
                    is Icon -> {
                        val drawable = largeIconExtra.loadDrawable(this@IrisNotificationService)
                        (drawable as? BitmapDrawable)?.bitmap
                    }
                    else -> null
                }
                if (bitmapToCompress != null) {
                    val baos = ByteArrayOutputStream()
                    bitmapToCompress.compress(Bitmap.CompressFormat.JPEG, 70, baos)
                    profileImageBase64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val innerJson = IrisJsonData(id = chatLogId.toString(), chat_id = roomId, user_id = senderId, message = text)
            val payload = NotificationPayload(msg = text, room = room, sender = senderName, isLite = false, is_group_chat = isGroupChat, profile_image = profileImageBase64, json = innerJson)
            val json = Json { encodeDefaults = true }
            val jsonPayload = json.encodeToString(payload)

            // NLS 기반 NotificationPayload(is_lite/profile_image 등 포함)는 IrisLite 규격이며
            // 논루팅(알림) 모드에서만 이벤트 소스로 사용한다. ADB 루팅 모드에서는 채팅 이벤트가
            // 데몬의 DBObserver → ObserverHelper가 원본 Iris와 동일한 형식으로 전송하므로,
            // 루팅 모드에서 NLS가 바인딩되어 있더라도 여기서 브로드캐스트/전송하지 않는다
            // (그래야 원본 Iris용 irispy-client와 호환되고 이벤트 중복도 방지된다).
            if (AppModeManager.currentMode == AppMode.NON_ROOT) {
                IrisServer.broadcastToClients(jsonPayload)

                val endpoint = AppConfig.webEndpoint
                if (endpoint.isNotBlank()) {
                    try {
                        val body = jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType())
                        val request = Request.Builder().url(endpoint).post(body).build()
                        httpClient.newCall(request).execute().close()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isStarted = true
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isStarted = false
        requestRebind(android.content.ComponentName(this, IrisNotificationService::class.java))
    }

    private fun extractAndStoreReplyAction(notification: Notification, roomName: String, roomId: String) {
        val actions = notification.actions ?: return
        for (action in actions) {
            val remoteInputs = action.remoteInputs ?: continue
            for (remoteInput in remoteInputs) {
                if (remoteInput.allowFreeFormInput) {
                    ReplyManager.replyActions[roomName] = ReplyAction(action.actionIntent, remoteInput)
                    if (roomId.isNotEmpty()) {
                        ReplyManager.replyActions[roomId] = ReplyAction(action.actionIntent, remoteInput)
                    }
                    CoroutineScope(Dispatchers.Main).launch {
                        val exists = AppState.storedRooms.any { room: StoredRoom -> room.name == roomName && room.id == roomId }
                        if (!exists) {
                            AppState.storedRooms.add(StoredRoom(roomName, roomId))
                        }
                    }
                    return
                }
            }
        }
    }

    private fun dumpBundle(bundle: Bundle?): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        if (bundle == null) return map
        for (key in bundle.keySet()) {
            val value = bundle.get(key)
            if (value is Bundle) map[key] = dumpBundle(value)
            else if (value != null && value.javaClass.isArray) map[key] = (value as Array<*>).contentToString()
            else map[key] = value?.toString()
        }
        return map
    }
}
