package party.qwer.irisgui

import androidx.compose.ui.graphics.Color

/**
 * AppColors — 밝고 현대적인(light) 테마 팔레트.
 *
 * 캔버스는 밝은 회색, 표면은 흰색 카드이며 잉크(본문 텍스트)는 무색-블랙에 가깝다.
 * 카드는 CardBorder 로 은은한 구분선을 얹어 흰색 위에서 경계를 부드럽게 드러낸다.
 */
object AppColors {
    val DarkBg = Color(0xFFF6F7FB)       // 캔버스 배경
    val CardBg = Color(0xFFFFFFFF)       // 카드 표면
    val InputBg = Color(0xFFEFF2F7)      // 입력 필드·칩·보조 버튼 채움
    val PrimaryAccent = Color(0xFF3B82F6)
    val TextMain = Color(0xFF10121A)     // 잉크
    val TextSub = Color(0xFF8A90A2)
    val SuccessVivid = Color(0xFF16A34A)
    val ErrorVivid = Color(0xFFEF4444)
    val WarningVivid = Color(0xFFF59E0B)
    val BottomNavBg = Color(0xFFFFFFFF)
    val CardBorder = Color(0xFFE9EBF0)   // 카드 구분선
}
