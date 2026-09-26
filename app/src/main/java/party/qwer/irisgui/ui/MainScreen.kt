package party.qwer.irisgui.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.AppState

/**
 * MainScreen — IrisGUI 셸.
 *
 * 탭 구성은 모드마다 다르며, 모드 전환은 상태 탭의 서비스 모드 카드에서만 수행한다.
 *
 *   ROOT_ADB : 상태 / 로그 / 도구
 *   NON_ROOT : 상태 / 로그
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val currentMode = AppModeManager.currentMode
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val permission = rememberPermissionStatus()

    LaunchedEffect(Unit) { AppModeManager.detectMode() }
    LaunchedEffect(currentMode) { selectedTabIndex = 0 }

    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // 서비스 시작/정지 결과 — Toast 는 백그라운드에서 OS 가 폐기하므로 스낵바로 확정 표시한다.
    // id 가 변할 때만 노출하므로 동일 메시지도 다시 뜬다.
    val feedback = AppState.lastFeedback
    LaunchedEffect(feedback?.id) {
        feedback?.let {
            snackbarHostState.showSnackbar(
                message = it.message,
                duration = if (it.isError) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }


    val tabs = when (currentMode) {
        AppMode.ROOT_ADB -> listOf("상태", "로그", "도구", "스크립트", "권한")
        AppMode.NON_ROOT -> listOf("상태", "로그", "스크립트", "권한")
    }
    val icons = when (currentMode) {
        AppMode.ROOT_ADB -> listOf(Icons.Default.Dashboard, Icons.Default.History, Icons.Default.Storage, Icons.Default.Description, Icons.Default.Shield)
        AppMode.NON_ROOT -> listOf(Icons.Default.Dashboard, Icons.Default.History, Icons.Default.Description, Icons.Default.Shield)
    }
    // ISSUE-39#3: 모드 전환 직후 selectedTabIndex 리셋이 한 프레임 늦으면
    // getOrNull(null) 의 else 분기가 PermissionScreen 을 잠깐 비춘다 — 인덱스를
    // 렌더 전에 clamp 에 노멀라이즈해 플래시를 제거한다.
    val safeTabIndex = selectedTabIndex.coerceIn(tabs.indices)
    if (safeTabIndex != selectedTabIndex) selectedTabIndex = safeTabIndex
    val selectedTabName = tabs[safeTabIndex]


    // 창은 항상 edge-to-edge(MainActivity.enableEdgeToEdge) — 시스템 바 inset 은
    // 오직 여기서 한 번만 소비한다. decorFit 으로 되돌리는 방식은 Android 15+
    // (targetSdk 35 강제 E2E) 에서 무시되어 실기기(S26U)의 콘텐츠가 status bar 에
    // 겹쳤고, 오래된 decor-fit 경로와 혼재 시 이중 패딩(레드드로이드 흰 띠)도 냈다.
    val scaffoldInsets = WindowInsets.systemBars
    Scaffold(
        contentWindowInsets = scaffoldInsets,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
        bottomBar = {
            // code 편집 화면(WebView) 중에는 탭 바를 완전히 감춘다 — 화면 전체를 코딩에 쓴다.
            if (!codeEditorOpen.value) {
            NavigationBar(
                // 기본(navigationBars) inset 소비 — redroid 처럼 시스템 navbar 가 실재하는
                // 기기에서 탭 항목이 nav bar 에 가려지는 일을 막는다 (바 배경이 그 위로
                // 그려져 흰 띠 없음). gesture 는 mandatory 기호만 추가 — 기본값 유지가
                // 표준 배치. (과거 windowInsets(0) 은 바 두께만 v0.0.2 와 맞추려다 항목
                // 숨김을 냈다.)
                containerColor = AppColors.BottomNavBg,
                contentColor = AppColors.TextSub,
                tonalElevation = 0.dp
            ) {
                tabs.forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        icon = {
                            val warnDot = index == 0 && permission.needsAttention(currentMode)
                            Box(
                                contentAlignment = Alignment.TopEnd,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(icons[index], contentDescription = title)
                                if (warnDot) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .background(AppColors.ErrorVivid, CircleShape)
                                    )
                                }
                            }
                        },
                        label = { Text(title, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.TextMain,
                            selectedTextColor = AppColors.TextMain,
                            indicatorColor = AppColors.PrimaryAccent.copy(alpha = 0.16f),
                            unselectedIconColor = AppColors.TextSub,
                            unselectedTextColor = AppColors.TextSub
                        )
                    )
                }
            }
            }
        },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        // 편집기(WebView)가 열리면 네비게이션 바 영역까지 WebView 를 내려 흰 창 배경이
        // 비치는 띠를 없앤다 (바가 이미 숨겨져 so bottom padding 은 불필요).
        val boxPad = if (codeEditorOpen.value)
            PaddingValues(top = paddingValues.calculateTopPadding()) else paddingValues
        Box(modifier = Modifier.padding(boxPad).fillMaxSize()) {
            when (selectedTabName) {
                "상태" -> StatusScreen(permission)
                "로그" -> LogsScreen()
                "도구" -> ToolsScreen()
                "스크립트" -> ScriptScreen()
                else -> PermissionScreen(permission)
            }
        }
    }

}

/** 모드별 표기명 — 상태 탭 모드 드롭다운/타이틀이 공용으로 사용한다. */
internal fun modeLabelOf(mode: AppMode): String = when (mode) {
    AppMode.ROOT_ADB -> "루팅(ADB)"
    AppMode.NON_ROOT -> "논루팅(알림)"
}
