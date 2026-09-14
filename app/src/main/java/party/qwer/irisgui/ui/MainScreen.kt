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
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Badge
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
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
import party.qwer.irisgui.service.IrisService

/**
 * MainScreen — IrisGUI 셸.
 *
 * 탭 구성은 모드마다 다르지만 항상 3개이며, 모드 전환은 탭 안이 아니라
 * TopAppBar 의 모드 라벨(드릴다운 트리거)에서만 수행한다.
 *
 *   ROOT_ADB : 상태 / 설정 / 도구
 *   NON_ROOT : 대시보드 / 히스토리 / 설정
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    val currentMode = AppModeManager.currentMode
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var modeSheetOpen by remember { mutableStateOf(false) }
    val permission = rememberPermissionStatus()

    LaunchedEffect(Unit) { AppModeManager.detectMode() }
    LaunchedEffect(currentMode) { selectedTabIndex = 0 }

    val scope = rememberCoroutineScope()

    val tabs = when (currentMode) {
        AppMode.ROOT_ADB -> listOf("상태", "설정", "도구")
        AppMode.NON_ROOT -> listOf("대시보드", "히스토리", "설정")
    }
    val icons = when (currentMode) {
        AppMode.ROOT_ADB -> listOf(Icons.Default.Dashboard, Icons.Default.Settings, Icons.Default.Storage)
        AppMode.NON_ROOT -> listOf(Icons.Default.Dashboard, Icons.Default.History, Icons.Default.Settings)
    }

    val modeLabel = when (currentMode) {
        AppMode.ROOT_ADB -> "루팅(ADB)"
        AppMode.NON_ROOT -> "논루팅(알림)"
    }

    // 모드 전환: 이전 모드 서비스를 정지한 뒤 새 모드로 확정한다.
    fun applyMode(mode: AppMode) {
        AppConfig.appMode = mode
        AppModeManager.setMode(mode)
        context.startForegroundService(
            android.content.Intent(context, IrisService::class.java)
                .setAction(IrisService.ACTION_STOP_SERVICE)
        )
        modeSheetOpen = false
        selectedTabIndex = 0
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(onTap = { focusManager.clearFocus() })
            },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("IrisGUI", fontWeight = FontWeight.ExtraBold, color = AppColors.TextMain)
                        TextButton(
                            onClick = { modeSheetOpen = true },
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp, 0.dp),
                            colors = androidx.compose.material3.ButtonDefaults.textButtonColors(
                                contentColor = AppColors.TextSub
                            )
                        ) {
                            Text(modeLabel, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.DarkBg)
            )
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
                            Box {
                                Icon(icons[index], contentDescription = title)
                                if (warnDot) {
                                    Badge(
                                        containerColor = AppColors.ErrorVivid,
                                        modifier = Modifier.padding(14.dp).padding(12.dp)
                                    ) {}
                                }
                            }
                        },
                        label = { Text(title, fontWeight = FontWeight.SemiBold) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = AppColors.TextMain,
                            selectedTextColor = AppColors.TextMain,
                            indicatorColor = AppColors.PrimaryAccent,
                            unselectedIconColor = AppColors.TextSub,
                            unselectedTextColor = AppColors.TextSub
                        )
                    )
                }
            }
        },
        containerColor = AppColors.DarkBg
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (currentMode) {
                AppMode.ROOT_ADB -> when (selectedTabIndex) {
                    0 -> AdbDashboardScreen()
                    1 -> AdbConfigScreen()
                    else -> ToolsScreen()
                }
                AppMode.NON_ROOT -> when (selectedTabIndex) {
                    0 -> NonRootDashboardScreen(permission, currentMode)
                    1 -> HistoryScreen()
                    else -> ConfigScreen()
                }
            }
        }
    }

    if (modeSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { modeSheetOpen = false },
            containerColor = AppColors.CardBg,
            sheetState = rememberModalBottomSheetState()
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("실행 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                Spacer(Modifier.padding(top = 8.dp))
                listOf(AppMode.ROOT_ADB, AppMode.NON_ROOT).forEach { mode ->
                    val desc = when (mode) {
                        AppMode.ROOT_ADB -> "adb shell + app_process 로 백그라운드 동작"
                        AppMode.NON_ROOT -> "NLS(알림 수신) 기반 논루팅 동작"
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { applyMode(mode) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = AppColors.PrimaryAccent,
                                unselectedColor = AppColors.TextSub
                            )
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(modeLabelOf(mode), fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                            Text(desc, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                        }
                    }
                }
                Spacer(Modifier.padding(top = 8.dp))
                Text(
                    "모드를 바꾸면 실행 중인 서비스가 정지됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.WarningVivid
                )
                Spacer(Modifier.padding(bottom = 16.dp))
            }
        }
    }
}

private fun modeLabelOf(mode: AppMode): String = when (mode) {
    AppMode.ROOT_ADB -> "루팅(ADB)"
    AppMode.NON_ROOT -> "논루팅(알림)"
}
