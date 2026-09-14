package party.qwer.irisgui.backend

import java.io.File

/**
 * KakaoShortcutStore — KakaoTalk 대화별 동적 쇼트컷 스토어(시스템 파일) 파싱.
 *
 * 최신 카톡은 알림 extras에 방 이름을 넣지 않는다 (title = 최신 발신자 닉네임).
 * shade "Conversations" 제목으로 보이는 방 이름은 카톡이 대화마다 등록하는
 * 동적 쇼트컷의 레이블(shortcut id = conversationId = sbn.tag)이며,
 * SystemUI도 이 스토어에서 렌더링한다.
 *
 * /data/system_ce는 앱 uid가 읽을 수 없으므로 루트(데몬 app_process)에서만
 * 사용할 수 있다. (P26: redroid에서 dumpsys shortcut / shade UI 대조로 확인)
 */
object KakaoShortcutStore {
    private const val KAKAO_PKG = "com.kakao.talk"
    private const val STORE_PATH = "/data/system_ce/0/shortcut_service/packages/$KAKAO_PKG.xml"
    private const val TTL_MS = 60_000L

    private val shortcutRegex = Regex("<shortcut\\s+id=\"([^\"]+)\"[^>]*?title=\"([^\"]*)\"")

    @Volatile
    private var cache: Map<String, String> = emptyMap()

    @Volatile
    private var cacheAtMs = 0L

    /**
     * conversationId → 방 이름. 60초 단위 갱신, 파일 읽기 실패 시 이전 캐시를 반환.
     */
    fun load(): Map<String, String> {
        val now = System.currentTimeMillis()
        if (cache.isNotEmpty() && now - cacheAtMs < TTL_MS) return cache
        runCatching {
            val out = parseShortcutXml(File(STORE_PATH).readText())
            if (out.isNotEmpty()) {
                cache = out
                cacheAtMs = now
            }
        }
        return cache
    }

    /**
     * 카톡 쇼트컷 XML → (id → title) 파싱. [KakaoRoomResolver]의 LocalAdb(루트 셸)
     * 읽기 경로와 공유한다. (id = conversationId = sbn.tag)
     */
    fun parseShortcutXml(xml: String): Map<String, String> {
        val out = HashMap<String, String>()
        for (m in shortcutRegex.findAll(xml)) {
            val id = m.groupValues[1]
            val title = m.groupValues[2]
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .trim()
            if (id.isNotEmpty() && title.isNotEmpty()) out[id] = title
        }
        return out
    }
}
