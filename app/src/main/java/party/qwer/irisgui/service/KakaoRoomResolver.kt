package party.qwer.irisgui.service

import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.backend.AdbProcessClient
import java.util.concurrent.ConcurrentHashMap

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
 *  - ROOT_ADB: 캐시 → 데몬 데이터베이스(chat_rooms/open_link, 데몬이 루트로 해석) → null.
 *    알림 extra/쇼트컷 스토어는 쓰지 않고 데이터베이스만 사용한다.
 *
 * 데몬으로의 (블로킹) HTTP 호출이 포함되므로 항상 IO 디스패처 코루틴에서 호출한다.
 */
object KakaoRoomResolver {
    private const val PREFS_NAME = "kakao_room_names"
    private const val KEY_MAP = "room_map"
    private const val MAX_ENTRIES = 512

    /** conversationId → 방 이름 */
    private val cache = ConcurrentHashMap<String, String>()

    @Volatile
    private var persistedLoaded = false

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
        conversationId: String
    ): String? {
        if (conversationId.isEmpty()) return null
        loadPersisted(context)
        cache[conversationId]?.let { return it }

        return when (AppModeManager.currentMode) {
            AppMode.NON_ROOT -> {
                // root/adb 연결 금지 — 인프로세스 NLS Ranking(34+)만 사용.
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
                // 데몬 데이터베이스로 방 이름 해결 (알림 extra/쇼트컷 스토어 미사용).
                val rooms = AdbProcessClient.fetchRooms()
                if (rooms.isNotEmpty()) {
                    for (r in rooms) {
                        if (r.id.isNotEmpty() && !r.name.isNullOrBlank() && cache.size < MAX_ENTRIES) {
                            cache.putIfAbsent(r.id, r.name)
                        }
                    }
                    persist(context)
                }
                cache[conversationId]
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
        if (cache.size >= MAX_ENTRIES) return
        if (cache.putIfAbsent(id, title) == null) persist(context)
    }

    private fun persist(context: Context) {
        try {
            val raw = cache.entries.joinToString(";") { e ->
                "${e.key}|${e.value.replace("|", "").replace(";", "")}"
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
                for (entry in raw.split(";")) {
                    if (cache.size >= MAX_ENTRIES) break
                    val i = entry.indexOf('|')
                    if (i > 0) cache.putIfAbsent(entry.substring(0, i), entry.substring(i + 1))
                }
            } catch (_: Exception) {
            }
            persistedLoaded = true
        }
    }
}
