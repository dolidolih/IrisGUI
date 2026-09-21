package party.qwer.irisgui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * AppColors — 파스텔 블롭 배경 위에 얹는 하프-투명(glass) 팔레트.
 *
 * 레이어 규칙: 배경 블롭(반투명) → 블록(반투명 흰색) → 내용(반투명 필 / 텍스트).
 * 블록·필이 이미 색 위에 깔려 있으므로, 같은 alpha를 레이어마다 재사용하면 이중 디밍으로
 * 옅어진다.그래서 블록 위에 얹는 필/버튼은 solid* 로 채운다.
 *
 * 접미 설명:
 *   ...OnAlpha — 반투명 블록 위에 얹었을 때 실제 대비를 기준으로 식힌 잉크.
 *   ...Solid   — solid 채움(Button, solid 칩) 위에 얹는 잉크.
 * 기존 명칭(CardBg / InputBg / CardBorder)은 후방 호환을 위해 새 값의 별칭으로 남긴다.
 */
object AppColors {
    val CanvasBase = Color(0xFFFFFFFF)          // 캔버스 시트 (블롭은 이 위로 번진다)
    val DarkBg = CanvasBase                      // 후방 호환

    val GlassFill = Color(0xE3FFFFFF)            // 표준 블록 — 가장자리 그림자로 경계를 만든다
    val GlassFillStrong = Color(0xD6FFFFFF)      // 블록 위 강조 필드/칩
    val GlassStroke = Color(0x1A10121A)          // 필드 가장자리 — 은은한 ink hairline (흰 면 위 흰색은 안 보인다)
    val BlockShadow = Color(0x2410121A)           // 블록 drop shadow — 배경 위 블록 경계를 읽힌다

    val GlassInputBg = Color(0xCCFFFFFF)         // 입력 필 — 비활용 표시용 채움이 아니라 active fill
    val GlassInputSolid = Color(0xFFFFFFFF)      // solid 위 필 (버튼 안 등)
    val ConfigTile = Color(0x66BFD8FF)           // config 아이템 면 — 파스텔 색조 ~40% (흰 카드 위 또렷한 틴트)
    val BottomNavBg = Color(0xE3FFFFFF)          // 하단 내비 (블록과 같은 톤)

    val CardBg = GlassFill                       // 후방 호환
    val InputBg = GlassInputBg                   // 후방 호환
    val CardBorder = GlassStroke                 // 후방 호환

    val PrimaryAccent = Color(0xFF6E8BFF)        // 채도 죽은 파스텔 블룸
    val TextMain = Color(0xFF2C2F3E)             // 블록 위 잉크
    val TextSub = Color(0xFF71768C)              // 블록 위 보조
    val TextMute = Color(0xFF9AA0B5)
    val TextMainOnAlpha = Color(0xFF1A1D2B)
    val TextSubOnAlpha = Color(0xFF5E6376)
    val TextMainSolid = Color(0xFFFFFFFF)
    val TextSubSolid = Color(0xBFFFFFFF)

    val SuccessSoft = Color(0x332E8B63)
    val WarnSoft = Color(0x33CE8A2E)
    val SuccessVivid = Color(0xFF2E8B63)
    val ErrorVivid = Color(0xFFD5656E)
    val WarningVivid = Color(0xFFCE8A2E)

    val BlockRadius = 22.dp
    val TileRadius = 16.dp
    val FieldRadius = 14.dp
}
