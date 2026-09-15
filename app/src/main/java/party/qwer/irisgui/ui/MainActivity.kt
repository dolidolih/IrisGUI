package party.qwer.irisgui.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppTypography

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = AppColors.PrimaryAccent,
                    background = Color.Transparent,
                    surface = Color.White, // 메뉴/다이얼로그 컨테이너 — 블록은 전부 자체 glass fill 을 쓴다
                    onBackground = AppColors.TextMain,
                    onSurface = AppColors.TextMain,
                    onSurfaceVariant = AppColors.TextSub,
                    outline = AppColors.GlassStroke
                ),
                typography = AppTypography.app
            ) {
                GlassBackground(Modifier.fillMaxSize()) {
                    MainScreen()
                }
            }
        }
    }
}
