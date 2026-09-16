package party.qwer.irisgui.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Dashboard
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
        AppMode.ROOT_ADB -> listOf("상태", "로그", "도구", "권한")
        AppMode.NON_ROOT -> listOf("상태", "로그", "권한")
    }
    val icons = when (currentMode) {
        AppMode.ROOT_ADB -> listOf(Icons.Default.Dashboard, Icons.Default.History, Icons.Default.Storage, Icons.Default.Shield)
        AppMode.NON_ROOT -> listOf(Icons.Default.Dashboard, Icons.Default.History, Icons.Default.Shield)
    }


    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
        bottomBar = {
            NavigationBar(
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
        },
        containerColor = Color.Transparent,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (selectedTabIndex) {
                0 -> StatusScreen(permission)
                1 -> LogsScreen()
                else -> {
                    if (currentMode == AppMode.ROOT_ADB && selectedTabIndex == 2) ToolsScreen()
                    else PermissionScreen(permission)
                }
            }
        }
    }

}

/** 모드별 표기명 — 상태 탭 모드 드롭다운/타이틀이 공용으로 사용한다. */
internal fun modeLabelOf(mode: AppMode): String = when (mode) {
    AppMode.ROOT_ADB -> "루팅(ADB)"
    AppMode.NON_ROOT -> "논루팅(알림)"
}
