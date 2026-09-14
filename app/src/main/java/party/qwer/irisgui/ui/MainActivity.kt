package party.qwer.irisgui.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
                colorScheme = androidx.compose.material3.lightColorScheme(
                    primary = AppColors.PrimaryAccent,
                    background = AppColors.DarkBg,
                    surface = AppColors.CardBg,
                    onBackground = AppColors.TextMain,
                    onSurface = AppColors.TextMain,
                    onSurfaceVariant = AppColors.TextSub,
                    outline = AppColors.CardBorder
                ),
                typography = AppTypography.app
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
