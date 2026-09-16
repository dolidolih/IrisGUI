package party.qwer.irisgui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * AppColors — 파스텔 스티커 테마.
 *
 * 참고안과 같은 레이어 규칙:
 *   배경 (분홍→라일락→하늘 삼중 그래디언트 + 블롭)
 *   -> 스티커 블록 (흰 면 + 잉크색 외곽선)
 *   -> 내용 (파스텔 틴트 / 잉크 텍스트)
 *
 * 흰색 위 채움은 또렷한 파스텔 틴트를 쓰고, 텍스트는 웜 잉크(보라빛 도는 갈색) 를 쓴다.
 * Pure white/text black 은 안 어울리므로 쓰지 않는다.
 *
 * 기존 명칭(CardBg / InputBg / CardBorder) 은 별칭으로 유지.
 */
object AppColors {
    // 배경
    val CanvasBase = Color(0xFFFDF4FB)       // 캔버스 시트 (블롭/그래디언트 beneath)
    val DarkBg = CanvasBase                  // 후방 호환

    // 배경 상단~하단 그래디언트 stops (분홍 -> 라일락 -> 하늘)
    val Gradient1 = Color(0xFFFFD3E6)
    val Gradient2 = Color(0xFFE3D9FF)
    val Gradient3 = Color(0xFFC2E9FF)

    // 블록 / 필
    val StickerFill = Color(0xFFFFFFFF)      // 블록 면 — 흰 (외곽선과 조합)
    val StickerOutline = Color(0xFF4A3550)   // 모든 블록/아이콘 외곽선 — 웜 잉크
    val StickerOutlineSoft = Color(0x334A3550)
    val BlockShadow = Color(0x334A3550)

    // 호환용: 파스텔 틴트 채움 / 필
    val GlassFill = Color(0xFFFFFFFF)
    val GlassFillStrong = Color(0xFFEDE6FB)
    val GlassStroke = Color(0x144A3550)
    val GlassInputBg = Color(0xFFF2EEFB)
    val GlassInputSolid = Color(0xFFFFFFFF)
    val ConfigTile = Color(0xFFE4E0FB)       // config 아이템 면 — 파스텔 틴트
    val TileFillAlt = Color(0xFFF5E8FA)
    val BottomNavBg = Color(0xFFFFFFFF)
    val CardBg = GlassFill
    val InputBg = GlassInputBg
    val CardBorder = StickerOutline

    // 강조 / 상태
    val PrimaryAccent = Color(0xFF8B7BE8)    // 라일락 — 버튼/칩/액센트
    val PrimaryDeep = Color(0xFF6E5BD6)
    val TextMain = Color(0xFF4A3550)         // 웜 잉크
    val TextSub = Color(0xFF8A7A96)
    val TextMute = Color(0xFFB4A8BF)
    val TextMainOnAlpha = Color(0xFF4A3550)
    val TextSubOnAlpha = Color(0xFF8A7A96)
    val TextMainSolid = Color(0xFFFFFFFF)
    val TextSubSolid = Color(0xCCFFFFFF)

    val SuccessVivid = Color(0xFF4FBF85)
    val ErrorVivid = Color(0xFFE56A93)
    val WarningVivid = Color(0xFFF0A94E)

    val Mint = Color(0xFF74C98E)
    val MintDeep = Color(0xFF3FA76F)
    val Butter = Color(0xFFFFD976)
    val Blush = Color(0xFFFFA9C6)

    // 라운드
    val BlockRadius = 26.dp
    val TileRadius = 18.dp
    val FieldRadius = 16.dp
}
