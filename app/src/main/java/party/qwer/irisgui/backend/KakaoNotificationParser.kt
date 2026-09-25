package party.qwer.irisgui.backend

import android.app.Notification
import android.os.Bundle

/**
 * KakaoNotificationParser — 카카오톡 알림 번들을 채팅 필드(발신자/본문/방/그룹 여부)로 변환한다.
 *
 * NLS(논루팅) 전용 소스 — 파싱 규칙은 여기 하나뿐이다.
 * 사용하는 API는 전부 공개 API이며 reflection/hidden API/정규식 파서는 쓰지 않는다:
 *
 *  | API                                              | 레벨 |
 *  |--------------------------------------------------|------|
 *  | Notification.MessagingStyle.Message
 *  |     .getMessagesFromBundleArray(EXTRA_MESSAGES)   | 30   |
 *  | Message.getSenderPerson() / getSender() / getText()
 *  |     / getTimestamp()                              | 28/24 |
 *  | EXTRA_CONVERSATION_TITLE / EXTRA_IS_GROUP_CONVERSATION | 24/28 |
 *
 * 발신자 id의 출처: AOSP MessagingStyle 스펙은 메시지 번들의 발신자 Person을
 * EXTRA_PERSON_KEY("android.person")에 담도록 규정하며, Person.key가 곧 프로필 식별자이다.
 * sender_person는 이 규격 번들에서 Person을 복원하는 하위 호환용 키일 뿐이므로
 * getMessagesFromBundleArray()가 복원한 person.key를 최종 기준으로 사용한다.
 *
 * 지원 정책 (의도적으로 좁힘):
 *  - OS: Android 11(API 30) 이상만. minSdk == 30이라 API 레벨 게이트 없이 전 필드가
 *    컴파일 타임에 보장된다. minSdk 미만 기기는 지원하지 않는다.
 *  - 카카오톡: 최신 빌드(new_message_v3)만. 구형 카톡(extras에 subText/summaryText를
 *    넣던 형식) 및 버전 분기 게이트는 지원하지 않는다.
 */
object KakaoNotificationParser {

    /** 메시지 스택 번들의 발신자 Person 호환용 키 (AOSP 하위 호환 경로) */
    private const val LEGACY_SENDER_PERSON_KEY = "sender_person"

    /**
     * Parsed — 알림 1건에서 뽑은 해석 결과. 누락 필드는 빈 문자열/0/null.
     *
     * @param senderName   최근(최신) 메시지 발신자 표시 이름
     * @param senderId     Person.key (동일인 식별자), 없음 시 ""
     * @param text         최근 메시지 텍스트
     * @param roomTitle    그룹/개별 대화 제목 (EXTRA_CONVERSATION_TITLE), 없음 시 null
     * @param isGroupConversation EXTRA_IS_GROUP_CONVERSATION, 없음 시 roomTitle == null
     * @param timestamp    최근 메시지 도착 시각 (millis since epoch), 없음 시 0
     */
    data class Parsed(
        val senderName: String,
        val senderId: String,
        val text: String,
        val roomTitle: String?,
        val isGroupConversation: Boolean,
        val timestamp: Long
    )

    /**
     * @return 알림이 해석 가능한 채팅이 아니면 null (스레드·공지 등 알림 스킵 신호)
     *
     * 참고: 카카오톡(new_message_v3)는 메시지 스택은 넣되 EXTRA_CONVERSATION_TITLE은
     * 넣지 않는다(실측: hiddenConversationTitle 만 존재, subText/summaryText 없음).
     * 따라서 roomTitle은 보통 null이며, 방 이름은 호출자가 모드별(논루팅: NLS ranking
     * 쇼트컷 레이블 / 루팅: 데몬 DB)로 해결해야 한다.
     */
    fun parse(notification: Notification): Parsed? {
        val extras = notification.extras ?: return null

        val messages = Notification.MessagingStyle.Message
            .getMessagesFromBundleArray(extras.getParcelableArray(Notification.EXTRA_MESSAGES))
        if (messages.isEmpty()) return null

        // ISSUE-35: stack/summary 알림은 배열 순서가 "최근" garantie 가 아니다 — 요약
        // 재구성이 끼어들면 messages.last() 이 예전 메시지를 가리켜 남남의 이름·본문이 답장으로
        // 간다. timestamp(=Message.getTimestamp()/postTime) 로 명시 정렬하고, 타임스탬프가
        // 모두 없는 빌드만 배열 순서를 따른다.
        val newest = newestOf(messages)
        val person = newest.senderPerson ?: legacySenderPerson(extras, messages)
        val senderName = person?.name?.toString()?.trim().orEmpty()
        val senderId = person?.key?.toString()?.trim().orEmpty()
        val text = newest.text?.toString().orEmpty()
        val timestamp = newest.timestamp

        // 메시지 stack 이 요약으로 바뀐 형태(EXTRA_TEXT 만 채워지는 경우)에서는 빈 text 로
        // 해석되므로 요약 라인을 fallback 으로 쓴다. 단 person 이 확인된 경우만 — 그래야
        // (공지·업데이트 등 사람 태그가 없는 알림이) 예전처럼 "해석 불가 → skip" 으로 남는다.
        val effectiveText = if (text.isEmpty() && person != null) {
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        } else text

        val roomTitle = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.ifBlank { null }

        val isGroupConversation = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)

        if (senderName.isEmpty() && senderId.isEmpty() && effectiveText.isEmpty()) return null

        return Parsed(
            senderName = senderName,
            senderId = senderId,
            text = effectiveText,
            roomTitle = roomTitle,
            isGroupConversation = isGroupConversation,
            timestamp = timestamp
        )
    }

    /**
     * getMessagesFromBundleArray()가 person을 복원하지 못한 빌드용 백업 경로.
     * ISSUE-35: 배열 마지막 원소를 그대로 쓰는 대신, 위쪽 newest 와 같은 논리(postTime)
     * 로 고른다 — stack 요약 때문에 배열 끝이 과거 메시지일 수 있다.
     */
    @Suppress("DEPRECATION")
    private fun legacySenderPerson(
        extras: Bundle,
        messages: List<Notification.MessagingStyle.Message>
    ): android.app.Person? {
        val bundles = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val index = newestIndexOf(messages, fallbackLast = bundles.size - 1)
        val target = (bundles.getOrNull(index) ?: bundles.lastOrNull()) as? Bundle ?: return null
        return target.getParcelable(LEGACY_SENDER_PERSON_KEY)
    }

    /** stack 안에서 가장 최신 메시지 (타임스탬프가 전부 0 이면 배열 순서 = 마지막). */
    private fun newestOf(
        messages: List<Notification.MessagingStyle.Message>
    ): Notification.MessagingStyle.Message = messages[newestIndexOf(messages, messages.size - 1)]

    private fun newestIndexOf(
        messages: List<Notification.MessagingStyle.Message>,
        fallbackLast: Int
    ): Int {
        var bestIndex = -1
        var bestTs = 0L
        messages.forEachIndexed { i, m ->
            val ts = try {
                m.timestamp
            } catch (_: Throwable) {
                0L
            }
            if (ts >= bestTs) {
                bestTs = ts
                bestIndex = i
            }
        }
        if (bestIndex < 0 || bestTs <= 0L) return fallbackLast.coerceIn(0, messages.size - 1)
        return bestIndex
    }
}
