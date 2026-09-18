package party.qwer.irisgui.backend

/**
 * chat_logs.type / v.origin 분류 헬퍼.
 *
 * `type` 단일 필드가 아니라 `type` + `v.origin` + OpenChat 비트(16384)의 조합으로
 * 이벤트 종류를 결정한다 (docs/db-events-and-info-provision.md §2).
 *
 * - OpenChat 로그 = base + 16384 (예: 16385 → base 1 = text in OpenChat).
 * - `origin`은 브로드캐스트 필터에 사용한다. SYNCMSG/MCHATLOGS는 기존 규격대로 제외하고,
 *   origin=MSG가 실질 메시지, origin=NEWMEM/DELMEM/FEED/… 는 시스템(멤버변경·피드) 이벤트.
 */
object KakaoMessageType {
    /** OpenChat 로그 판별 비트. */
    const val OPEN_CHAT_BIT = 16384

    /** 원본 type → OpenChat 비트를 제거한 base 타입. */
    fun baseType(type: Int): Int = type and OPEN_CHAT_BIT.inv()

    fun isOpenChat(type: Int): Boolean = (type and OPEN_CHAT_BIT) != 0

    /** type/origin을 "사람이 알아볼 문자열"로 매핑 (extension.type_name, 필터용). */
    fun classify(type: Int, origin: String): String {
        if (origin.isEmpty()) return "unknown"
        // 시스템(origin 기반)
        if (origin != "MSG" && origin != "MCHATLOGS" && origin != "SYNCMSG" && origin != "WRITE") {
            return "system"
        }
        // WRITE(origin)는 self 발신(내 메시지 저장) -> current_user로 분류
        if (origin == "WRITE") return "current_user"
        if (origin == "MCHATLOGS") return "mchatlog"
        if (origin == "SYNCMSG") return "syncmsg"
        // origin=MSG: base 타입별 분류
        return when (baseType(type)) {
            1 -> "text"
            2 -> "photo"
            3 -> "video"
            5 -> "audio"
            6 -> "file"
            7 -> "contact"
            11 -> "photo_animation"
            12 -> "contact"
            16 -> "long_app"
            18 -> "gif"
            20 -> "emoticon"
            23 -> "talk_memo"
            25 -> "app"
            26 -> "app_feed"
            27 -> "list"
            61 -> "in_link"
            71 -> "feed"
            72 -> "feed_share"
            93 -> "current_user"
            else -> "unknown"
        }
    }

    /**
     * 브로드캐스트 포함 여부 결정.
     *
     * filter = null (현행 동작 유지): origin ∉ {SYNCMSG, MCHATLOGS} 인 로그만 브로드캐스트.
     *   단 origin=NEWMEM/DELMEM/… 시스템 이벤트도 기존 규격대로 포함된다.
     * filter != null: classify 결과가 필터에 속한 로그만 브로드캐스트.
     *   단 SYNCMSG는 항상 제외. MCHATLOGS/system("system")은 필터에 명시된 경우에만 포함.
     */
    fun shouldBroadcast(type: Int, origin: String, filter: List<String>?, includeSystem: Boolean): Boolean {
        if (origin == "SYNCMSG") return false
        if (origin == "MCHATLOGS" && (filter == null || filter.isEmpty())) return false
        val systemOrigin = (origin != "MSG" && origin != "MCHATLOGS" && origin != "SYNCMSG" && origin != "WRITE")
        if (systemOrigin && !includeSystem) return false
        if (filter == null || filter.isEmpty()) return true
        if (origin == "MCHATLOGS") return "mchatlog" in filter
        if (origin == "WRITE") return "current_user" in filter
        if (systemOrigin) return "system" in filter
        return classify(type, origin) in filter
    }

    /** 필터 문자열 유효성 검사 (config /config/{name} 검증용). */
    val VALID_FILTERS = setOf(
        "text", "photo", "video", "audio", "file", "contact", "photo_animation", "gif",
        "emoticon", "list", "app", "app_feed", "feed", "feed_share", "talk_memo",
        "long_app", "current_user", "mchatlog", "syncmsg", "system", "unknown"
    )
}
