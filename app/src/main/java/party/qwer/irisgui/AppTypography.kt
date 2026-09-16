package party.qwer.irisgui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * AppTypography — 파스텔 스티커 테마용 라운드 타이포.
 *
 * 서체는 OFL(Google Fonts) 로 받은 뒤 정적화해 APK 에 직접 번들한다.
 *   R.font.rounded       Jua — 국산 라운드 산세리프 (한글 + Latin)
 *   R.font.rounded_latin Baloo 2 Bold 정적 인스턴스 — 숫자/라틴 전용
 *
 * 한글은 오직 rounded 로만 쓴다. rounded_latin 은 한글 glyph 가 없어 한글과 섞이면
 * 상자가 깨져 표시되므로 순수 숫자/라틴 문자열에만 적용한다.
 *
 * line height 는 한글 상·하방 정렬이 흔들리지 않도록 glyph 실세로보다 넉넉하게 뒀다.
 */
object AppTypography {

    /** 본문·타이틀 공용 — 한글/라틴 모두 존재. */
    val Round: FontFamily = FontFamily(
        Font(R.font.rounded, FontWeight.Normal, FontStyle.Normal)
    )

    /** 숫자 전용. 한글을 포함하는 문자열에는 절대 적용하지 않는다. */
    val RoundNumeric: FontFamily = FontFamily(
        Font(R.font.rounded_latin, FontWeight.Bold, FontStyle.Normal)
    )

    val app = Typography(
        displayLarge = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 30.sp, lineHeight = 38.sp
        ),
        displayMedium = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 26.sp, lineHeight = 34.sp
        ),
        displaySmall = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 22.sp, lineHeight = 30.sp
        ),
        headlineLarge = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 22.sp, lineHeight = 30.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 19.sp, lineHeight = 26.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 18.sp, lineHeight = 25.sp
        ),
        titleLarge = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 17.sp, lineHeight = 23.sp
        ),
        titleMedium = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 15.sp, lineHeight = 21.sp
        ),
        titleSmall = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 14.sp, lineHeight = 19.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Normal,
            fontSize = 15.sp, lineHeight = 21.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Normal,
            fontSize = 14.sp, lineHeight = 20.sp
        ),
        bodySmall = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Normal,
            fontSize = 13.sp, lineHeight = 18.sp
        ),
        labelLarge = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 13.sp, lineHeight = 17.sp
        ),
        labelMedium = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 12.sp, lineHeight = 16.sp
        ),
        labelSmall = TextStyle(
            fontFamily = Round, fontWeight = FontWeight.Bold,
            fontSize = 11.sp, lineHeight = 15.sp
        )
    )
}
