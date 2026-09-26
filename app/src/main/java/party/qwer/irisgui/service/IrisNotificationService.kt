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
import party.qwer.irisgui.RuntimeLog
import party.qwer.irisgui.AppState
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.backend.IrisServer
import party.qwer.irisgui.backend.KakaoNotificationParser
import party.qwer.irisgui.models.IrisJsonData
import party.qwer.irisgui.models.NotificationEvent
import party.qwer.irisgui.models.NotificationPayload
import party.qwer.irisgui.models.ReplyAction
import party.qwer.irisgui.models.StoredRoom
import java.io.ByteArrayOutputStream

class IrisNotificationService : NotificationListenerService() {
    private companion object {
        const val DEDUP_MAX_ENTRIES = 1024
        const val DEDUP_WINDOW_CHATLOG_MS = 30L * 60L * 1000L
        const val DEDUP_WINDOW_CONTENT_MS = 90_000L
        const val DAEMON_LIVENESS_TTL_MS = 30_000L
    }

    private val httpClient = OkHttpClient()
    private val coroutineScope = CoroutineScope(Dispatchers.IO)

    /** A1: NLS 리스너 시작 상태 — 외부에서 확인용 */
    @Volatile
    var isStarted: Boolean = false

    /** ISSUE-14: 재브로드캐스트 중복 창 (roomId,chatLogId) / content 기준 */
    private val dedupSeen = java.util.Collections.synchronizedMap(
        object : java.util.LinkedHashMap<String, Long>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
                size > DEDUP_MAX_ENTRIES
        }
    )

    @Volatile
    private var cachedDaemonLive: Boolean = false

    @Volatile
    private var cachedDaemonLiveAt: Long = 0

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!AppConfig.isServiceEnabled) {
            throttleWarn("설정에서 서비스가 꺼짐 — 알림이 와도 무시 (상태 탭에서 실행 필요)")
            return
        }
        if (sbn.packageName != "com.kakao.talk") return

        val notification = sbn.notification
        val extras = notification.extras ?: return

        // 해석은 shared 파서 하나(KakaoNotificationParser)에만 위임한다 — NLS와 루팅 폴러가 동일 규칙.
        val parsed = runCatching { KakaoNotificationParser.parse(notification) }.getOrNull()
        if (parsed == null) {
            parsedNullCounter++
            if (parsedNullCounter % 10L == 1L) {
                RuntimeLog.info("NLS", "카톡 알림 도착 — 파싱 불가로 건너뜀 (누적 $parsedNullCounter)")
            }
            return
        }

        val senderName = parsed.senderName
        val senderId = parsed.senderId
        val text = parsed.text
        val conversationTitleExtra = parsed.roomTitle

        // ISSUE-35: thread/구형 카톡 build 는 tag 없이 온다 — 그러면 chat_id="" 페이로드가
        // 되고 이름 기반 답장 조회가 이름 충돌 시 다른 방을 집는다. tag 부재를 조용히 넘기지
        // 말고 key 기반 ranking 조회로 방 이름을 해결하고, 로그에 원인을 남긴다.
        val roomId = sbn.tag ?: ""
        if (roomId.isEmpty()) {
            RuntimeLog.warn("NLS", "sbn.tag 없음(thread/구형 build?) — key 로 방 해결 시도")
        }
        val chatLogId = extras.getLong("chatLogId", 0L)

        val largeIconExtra = try { extras.get(Notification.EXTRA_LARGE_ICON) } catch (e: Exception) { null }
        val rawDump = dumpBundle(extras).toString()

        // ISSUE-14: 같은 메시지의 재표시(요약 재구성/화면 재노출)마다 /ws 와 webhook 이
        // 한 번씩 더 나간다. (roomId, chatLogId) 기준 bounded LRU 로 중복을 막는다.
        if (isDuplicateEvent(roomId, chatLogId, text, sbn.key)) {
            RuntimeLog.info("NLS", "중복 이벤트 무시(room=$roomId, id=$chatLogId) — 브로드캐스트 생략")
            extractAndStoreReplyAction(notification, (conversationTitleExtra ?: senderName), roomId)
            return
        }

        coroutineScope.launch {
            // 방 이름: shared 파서의 roomTitle → 모드별(KakaoRoomResolver) → 발신자 닉네임.
            // 발신자 닉네임을 폴백으로 쓰는 경우에도 그룹 대화의 방 이름을 resolver에 queries 한다.
            val resolverRoom = if (conversationTitleExtra == null) {
                // tag 가 빈 경우 sbn.key 로 ranking 조회만 시도(cache 하지 않음) — 닉네임 폴백보다 낫다.
                KakaoRoomResolver.resolve(
                    this@IrisNotificationService,
                    this@IrisNotificationService,
                    sbn.key,
                    roomId,
                    identityFallback = if (roomId.isEmpty()) sbn.key else null
                )
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

            RuntimeLog.info(
                "NLS",
                "이벤트: room=\"$room\" src=$roomSource group=$isGroupChat " +
                    "sender=\"$senderName\" text=\"${text.take(60)}\""
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

            // IrisLite 규격: 알림 기반 이벤트는 DB 열이 없으므로 client 의 라우팅 근거
            // (json.v.origin 또는 is_lite) 중 하나는 반드시 실려야 `message` 이벤트가
            // 발생한다. 이 두 장식이 없으면 클라이언트는 unknown 만.emit하고
            // on_event("message") 는 조용히 스루가 된다 — NON_ROOT 의 !hhhi 무응답
            // 실측 원인 (2026-09, ws 접속/브로드캐스트는 정상이었을 때).
            val liteCreatedAt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(System.currentTimeMillis())
            val innerJson = IrisJsonData(
                id = chatLogId.toString(),
                chat_id = roomId,
                user_id = senderId,
                message = text,
                created_at = (System.currentTimeMillis() / 1000).toString(),
                v = """{"notDecoded":false,"origin":"MSG","c":"$liteCreatedAt","modifyRevision":0}""",
            )
            val payload = NotificationPayload(msg = text, room = room, sender = senderName, isLite = true, is_group_chat = isGroupChat, profile_image = profileImageBase64, json = innerJson)
            val json = Json { encodeDefaults = true }
            val jsonPayload = json.encodeToString(payload)

            // NLS 기반 NotificationPayload(is_lite/profile_image 등 포함)는 IrisLite 규격이며
            // 논루팅(알림) 모드에서만 이벤트 소스로 사용한다. ADB 루팅 모드에서는 채팅 이벤트가
            // 데몬의 DBObserver → ObserverHelper가 원본 Iris와 동일한 형식으로 전송하므로,
            // 루팅 모드에서 NLS가 바인딩되어 있더라도 여기서 브로드캐스트/전송하지 않는다
            // (그래야 원본 Iris용 irispy-client와 호환되고 이벤트 중복도 방지된다).
            // ISSUE-14: 모드 캐시가 stale(NON_ROOT 가 기본값인 fresh process)이면 NLS 이벤트를
            // 내보내는데에도 daemon DBObserver 가 같은 메시지를 스트림에 올려 이중 이벤트가
            // 된다. 그래서 캐시된 모드문자열만 보지 않고 daemon 생존 여부까지 본다.
            if (!daemonOwnsEventStream()) {
                val drops = IrisServer.broadcastToClients(jsonPayload)
                RuntimeLog.info(
                    "NLS",
                    "ws 브로드캐스트: 접속수=${IrisServer.wsSubscribers}" +
                        if (drops > 0) " — 일부 유실(drop 누=$drops)" else ""
                )

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
        // ISSUE-06: 바인딩이 실제로 잡힌 시점만 state 객체에 남긴다 — startService 성공
        // 만으로는 "실행 중"을 판단할 수 없기 때문.
        NotificationListenerState.onConnected()
        // ISSUE-14: 첫 알림이 들어오기 전에 모드를 확정한다 — currentMode 의 기본값이
        // NON_ROOT 라서 detectMode 되기 전까지 ROOT_ADB 에서도 NLS 가 스트림에 중복으로
        // 올라탈 수 있다.
        val mode = AppModeManager.ensureDetected()
        RuntimeLog.info("NLS", "알림리서비스 연결됨 mode=$mode " + NotificationListenerState.describe(this))
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isStarted = false
        NotificationListenerState.onDisconnected()
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

    /**
     * ISSUE-14: (roomId, chatLogId) 기준 bounded LRU 로 같은 이벤트의 재브로드캐스트을 막는다.
     * chatLogId 가 없는 알림은 (roomId, 본문, key) 조합으로 보되 재전송 창을 짧게 잡아 정상
     * 중복 발신(같은 문구를 두 번 보내는 경우)까지 삼키지 않도록 한다.
     */
    private fun isDuplicateEvent(roomId: String, chatLogId: Long, text: String?, sbnKey: String): Boolean {
        val (key, windowMs) = if (chatLogId != 0L) {
            ("$roomId|$chatLogId") to DEDUP_WINDOW_CHATLOG_MS
        } else {
            ("$roomId|t=${text?.hashCode() ?: 0}|$sbnKey") to DEDUP_WINDOW_CONTENT_MS
        }
        val now = System.currentTimeMillis()
        val previous = dedupSeen.putIfAbsent(key, now)
        if (previous == null) return false
        if (now - previous <= windowMs) return true
        dedupSeen[key] = now
        return false
    }

    /**
     * 이벤트 스트림의 주인이 NLS 인가, daemon(DBObserver) 인가.
     * ROOT_ADB 이면 daemon, NON_ROOT 이면 NLS. 판별이 밀려서 모드는 NON_ROOT 인데 daemon 이
     * 이미 도는 상태(기동 직후 / 자동감지 실패)에서는 중복을 막기 위해 생존 확인을 한다 —
     * local loopback 의 /process-status GET 한 번만, throttled 로. root/adb 명령은 쓰지 않는다.
     */
    private suspend fun daemonOwnsEventStream(): Boolean {
        if (AppModeManager.ensureDetected() == AppMode.ROOT_ADB) return true
        val now = System.currentTimeMillis()
        val last = cachedDaemonLiveAt
        if (last != 0L && now - last < DAEMON_LIVENESS_TTL_MS) return cachedDaemonLive
        val live = withContext(Dispatchers.IO) {
            runCatching { AdbProcessClient.queryStatus() != null }.getOrDefault(false)
        }
        cachedDaemonLiveAt = now
        cachedDaemonLive = live
        if (live) RuntimeLog.warn("NLS", "NON_ROOT 인데 daemon 생존 — 이벤트 스트림은 daemon 차지 (중복 방지 의도적 생략)")
        return live
    }

    private var lastThrottleMs = 0L
    private fun throttleWarn(msg: String) {
        val now = System.currentTimeMillis()
        if (now - lastThrottleMs > 30_000) {
            lastThrottleMs = now
            RuntimeLog.warn("NLS", msg)
        }
    }

    private var parsedNullCounter = 0L

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
