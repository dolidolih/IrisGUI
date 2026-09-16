package party.qwer.irisgui

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * AppTypography — "zoomed-out" 형 scale.
 *
 * 기본 Material3 scale 대비 ~10% 작게 잡아 한 화면에 더 많은 정보를 담는다.
 * card 안의 dense 한 stat row/label 에 알맞도록 line height 도 살짝 타이트하게 조정.
 */
object AppTypography {
    val app = Typography(
        displayLarge = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
            fontSize = 24.sp, lineHeight = 30.sp
        ),
        displayMedium = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
            fontSize = 21.sp, lineHeight = 27.sp
        ),
        displaySmall = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
            fontSize = 18.sp, lineHeight = 24.sp
        ),
        headlineLarge = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
            fontSize = 19.sp, lineHeight = 24.sp
        ),
        headlineMedium = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
            fontSize = 17.sp, lineHeight = 22.sp
        ),
        headlineSmall = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp, lineHeight = 21.sp
        ),
        titleLarge = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,
            fontSize = 16.sp, lineHeight = 21.sp
        ),
        titleMedium = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp, lineHeight = 18.sp
        ),
        titleSmall = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp, lineHeight = 17.sp
        ),
        bodyLarge = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
            fontSize = 14.sp, lineHeight = 19.sp
        ),
        bodyMedium = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
            fontSize = 13.sp, lineHeight = 17.sp
        ),
        bodySmall = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Normal,
            fontSize = 12.sp, lineHeight = 16.sp
        ),
        labelLarge = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
            fontSize = 12.sp, lineHeight = 15.sp
        ),
        labelMedium = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
            fontSize = 11.sp, lineHeight = 14.sp
        ),
        labelSmall = TextStyle(
            fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium,
            fontSize = 10.sp, lineHeight = 13.sp
        )
    )
}
