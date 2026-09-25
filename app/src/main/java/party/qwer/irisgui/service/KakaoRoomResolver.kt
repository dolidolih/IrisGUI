package party.qwer.irisgui.service

import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.backend.AdbProcessClient
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * KakaoRoomResolver — KakaoTalk 알림의 방 이름(conversationId → 방 이름) 해결.
 *
 * 최신 카톡(new_message_v3)은 알림 extras에 방 이름을 담지 않는다:
 * title = 최신 발신자 닉네임, subText/summaryText 부재.
 * (실측: P26–P30, messagingStyleUser/messagingUser에도 방 이름 없음)
 *
 * 모드가 해결 소스를 결정한다 (규칙: 논루팅은 절대 root/adb 연결로 방 이름을 구하지 않는다):
 *  - NON_ROOT: 캐시 → API 34+ NLS Ranking 대화 쇼트컷 레이블(인프로세스) → null(닉네임 폴백).
 *    34 미만은 알림만으로는 그룹 방 이름을 구할 수 없으므로 닉네임으로 폴백된다.
 *  - ROOT_ADB: 캐시 → 백그라운드 데몬 room fetch(deemon 이 루트로 해석한 chat_rooms/open_link)
 *    → null. emit 경로를 위해 블로킹 HTTP 를 하지 않는다 (ISSUE-27).
 *
 * ISSUE-21/27: resolve() 는 더 이상 emit 코루틴에서 fetchRooms() 를 기다리지 않는다 — 미ss는
 * 백그라운드 refresh 를 깨우고 바로 null(호출측은 발신자 닉네임으로 폴백)을 돌려주며, refresh 가
 * 끝난 다음 이벤트부터 이름이 붙는다.
 */
object KakaoRoomResolver {
    private const val PREFS_NAME = "kakao_room_names"
    private const val KEY_MAP = "room_map"

    /** LRU 의 max entries — 꽉 차면 오래된 항목을 내보낸다 (예전처럼 새 방을 버리지 않는다). */
    private const val MAX_ENTRIES = 512

    /** 데몬 room list refresh 의 최소 주기 — notification 마다 HTTP 는 금지. */
    private const val REFRESH_MIN_MS = 30_000L

    /** cache → SharedPreferences 저장 throttle. */
    private const val PERSIST_MIN_MS = 5_000L

    /** 백그라운드 fetch 대기용(성공/실패를 로그로 남기기 위한 지연 상한은 아님). */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** conversationId → 방 이름 (access-order LRU, evict oldest). */
    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            if (size > MAX_ENTRIES) {
                evictions.incrementAndGet()
                true
            } else false
    }

    @Volatile
    private var persistedLoaded = false

    @Volatile
    private var lastFetchAtMs = 0L

    @Volatile
    private var lastPersistAtMs = 0L

    @Volatile
    private var refreshInFlight: kotlinx.coroutines.Job? = null

    private val evictions = AtomicInteger()
    private val misses = AtomicLong()
    private val fetches = AtomicLong()

    /** cache 상태 노출 (ISSUE-27 — 상태 화면/디버그에서 entries/cap 을 보게 하기 위한 값). */
    fun cacheStats(): String = synchronized(cache) {
        "roomCache=${cache.size}/$MAX_ENTRIES evicted=${evictions.get()} " +
            "misses=${misses.get()} fetches=${fetches.get()} loaded=$persistedLoaded"
    }

    /** cache 에 담긴 방 이름 수 (상태 패널 표시용). */
    val entryCount: Int get() = synchronized(cache) { cache.size }

    /** cache 상한 — UI 가 "포화" 표시에 쓸 수 있게 열어둔 값. */
    val capacity: Int get() = MAX_ENTRIES

    /**
     * @param nls 알림 리스너 (API 34+ ranking 경로, 논루팅 전용)
     * @param context SharedPreferences 영속화용
     * @param sbnKey StatusBarNotification.key — ranking 조회 키
     * @param conversationId sbn.tag (= conversationId)
     * @return 방 이름, 해결 실패 시 null
     */
    suspend fun resolve(
        nls: NotificationListenerService,
        context: Context,
        sbnKey: String,
        conversationId: String,
        /**
         * ISSUE-35: tag(conversationId) 없이 오는 알림용 identity — ranking 조회만 하고
         * cache 에는 넣지 않는다 (sbn.key 를 방 id 로 착각해 cache 를 오염시키지 않게).
         */
        identityFallback: String? = null
    ): String? {
        if (conversationId.isEmpty()) {
            // tag 가 비어있으면 conversationId 로는 아무것도 확정할 수 없다. ranking 은 key
            // 로 조회되므로 논루팅에서는 여전히 방 이름을 얻을 수 있지만, 결과는 cache 하지
            // 않는다 — 호출부는 닉네임 폴백을 탄다.
            if (identityFallback == null) return null
            misses.incrementAndGet()
            return when (AppModeManager.ensureDetected()) {
                AppMode.NON_ROOT -> rankingLabel(nls, sbnKey)
                AppMode.ROOT_ADB -> {
                    requestRoomRefresh(context)
                    null
                }
            }
        }
        loadPersisted(context)
        cachedName(conversationId)?.let { return it }
        misses.incrementAndGet()

        return when (AppModeManager.ensureDetected()) {
            AppMode.NON_ROOT -> {
                // root/adb 연결 금지 — 인프로세스 NLS Ranking(34+)만 사용한다.
                val fromRanking = rankingLabel(nls, sbnKey)
                if (fromRanking != null) {
                    remember(context, conversationId, fromRanking)
                    fromRanking
                } else {
                    // 34 미만 또는 ranking 부재: 알림만으로는 그룹 방 이름 불문 → 호출부 닉네임 폴백.
                    null
                }
            }

            AppMode.ROOT_ADB -> {
                // 데몬 DB로 방 이름을 해결하되, emit 코루틴에서 HTTP 를 기다리지 않는다.
                // 백그라운드 refresh 를 깨우고 이번엔 null(호출측은 발신자 닉네임으로 폴백),
                // 다음 이벤트부터 이름이 붙는다.
                requestRoomRefresh(context)
                null
            }
        }
    }

    /** cache 조회 — LRU 순서를 위해 synchronized. */
    private fun cachedName(id: String): String? = synchronized(cache) { cache[id] }

    /**
     * 백그라운드 room cache refresh. 이미 실행 중이거나 최근 refresh 가 있었으면 아무것도 하지
     * 않는다 (notification 폭주로 HTTP 를 도배하지 않게).
     */
    private fun requestRoomRefresh(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastFetchAtMs < REFRESH_MIN_MS) return
        val current = refreshInFlight
        if (current != null && current.isActive) return
        refreshInFlight = scope.launch {
            try {
                val rooms = runCatching { AdbProcessClient.fetchRooms() }.getOrElse {
                    println("KakaoRoomResolver: fetchRooms failed: ${it.javaClass.simpleName}: ${it.message}")
                    emptyList()
                }
                lastFetchAtMs = System.currentTimeMillis()
                fetches.incrementAndGet()
                var added = 0
                rooms.forEach { r ->
                    val id = r.id
                    val name = r.name
                    if (id.isNotEmpty() && !name.isNullOrBlank()) {
                        val isNew = synchronized(cache) {
                            if (cache.containsKey(id)) {
                                cache[id] = name
                                false
                            } else {
                                cache[id] = name
                                true
                            }
                        }
                        if (isNew) added++
                    }
                }
                if (added > 0 || synchronized(cache) { cache.size } == 0) {
                    persist(context)
                }
                if (added > 0) println("KakaoRoomResolver: $added room name(s) loaded — ${cacheStats()}")
            } finally {
                refreshInFlight = null
            }
        }
    }

    /** API 34+ — NLS Ranking → 대화 쇼트컷 레이블 */
    private fun rankingLabel(nls: NotificationListenerService, sbnKey: String): String? {
        if (Build.VERSION.SDK_INT < 34) return null
        return try {
            @Suppress("NewApi")
            val rankingMap = nls.getCurrentRanking() ?: return null
            @Suppress("NewApi")
            val ranking = NotificationListenerService.Ranking()
            if (!rankingMap.getRanking(sbnKey, ranking)) return null
            @Suppress("NewApi")
            val shortcut = ranking.conversationShortcutInfo ?: return null
            @Suppress("NewApi")
            val longLabel = shortcut.longLabel?.toString()?.trim()
            @Suppress("NewApi")
            val shortLabel = shortcut.shortLabel?.toString()?.trim()
            longLabel?.ifBlank { null } ?: shortLabel?.ifBlank { null }
        } catch (e: Exception) {
            println("KakaoRoomResolver: ranking failed: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun remember(context: Context, id: String, title: String) {
        val isNew = synchronized(cache) {
            if (cache.containsKey(id)) {
                false
            } else {
                cache[id] = title
                true
            }
        }
        if (isNew) persist(context)
    }

    /** `|` 와 `;` 는 직렬화 구분자이므로 *항상* 이스케이프한다 (키도 값도 마찬가지). */
    private fun escapePart(value: String): String = value
        .replace("%", "%25")
        .replace("|", "%7C")
        .replace(";", "%3B")

    private fun unescapePart(value: String): String {
        if (value.indexOf('%') < 0) return value
        val sb = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3)
                val decoded = hex.toIntOrNull(16)
                if (decoded != null) {
                    sb.append(decoded.toChar())
                    i += 3
                    continue
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun persist(context: Context) {
        val now = System.currentTimeMillis()
        if (now - lastPersistAtMs < PERSIST_MIN_MS) return
        lastPersistAtMs = now
        try {
            val raw = synchronized(cache) {
                cache.entries.joinToString(";") { (k, v) ->
                    "${escapePart(k)}|${escapePart(v)}"
                }
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_MAP, raw)
                .apply()
        } catch (e: Exception) {
            println("KakaoRoomResolver: persist failed: ${e.message}")
        }
    }

    private fun loadPersisted(context: Context) {
        if (persistedLoaded) return
        synchronized(this) {
            if (persistedLoaded) return
            try {
                val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .getString(KEY_MAP, "") ?: ""
                val restored = raw.split(';').mapNotNull { entry ->
                    val i = entry.indexOf('|')
                    if (i <= 0) return@mapNotNull null
                    unescapePart(entry.substring(0, i)) to unescapePart(entry.substring(i + 1))
                }
                synchronized(cache) {
                    restored.forEach { (k, v) -> cache[k] = v }
                }
            } catch (_: Exception) {
            }
            persistedLoaded = true
        }
    }
}
