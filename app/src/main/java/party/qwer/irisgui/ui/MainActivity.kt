package party.qwer.irisgui.ui

import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
        // targetSdk 35 는 Android 15+ 에서 edge-to-edge 강제 — 그보다 낮은 OS 와 동작을
        // 맞추기 위해 명시 적용한다(이후 setDecorFitsSystemWindows 로 되돌리지 않는다;
        // inset 은 Compose(Scaffold contentWindowInsets)에서만 소비한다).
        enableEdgeToEdge()
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

    /**
     * 편집 화면(WebView)에서 백 반복(롱프레스)을 삼킨다. 시스템 롱프레스 백은
     * ASSIST(음성) 를 띄워 편집 화면 밖으로 빠져나간다 — 코드서버 동작이 아니라
     * 플랫폼 동작이라 입력 단계에서만 막는다. 단일 백은 그대로 통과(BackHandler 로 목록 복귀).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && codeEditorOpen.value &&
            (event.repeatCount > 0 || event.isLongPress)
        ) return true
        return super.dispatchKeyEvent(event)
    }
}
