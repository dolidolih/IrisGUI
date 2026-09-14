package party.qwer.irisgui.ui

import android.content.Context
import android.os.PowerManager
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var hasRequiredPermissions by remember { mutableStateOf(true) }

    // 관찰 가능한 전역 모드 상태를 직접 읽는다 — 모드 변경 시 즉시 재구성되어
    // 재시작 없이 탭 메뉴가 바로 바뀐다.
    val currentMode = AppModeManager.currentMode

    // 최초 1회 모드 자동 감지(저장된 모드가 있으면 그대로 사용)
    LaunchedEffect(Unit) {
        AppModeManager.detectMode()
    }

    // 모드가 바뀌면 탭 구성이 달라지므로 선택 인덱스를 0으로 초기화(범위 초과 방지)
    LaunchedEffect(currentMode) {
        selectedTabIndex = 0
    }

    // 권한 상태 체크
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
                val hasNotif = enabledPackages.contains(context.packageName)
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val hasBatt = pm.isIgnoringBatteryOptimizations(context.packageName)

                // 논루팅 모드일 때만 NLS 권한 체크
                hasRequiredPermissions = if (currentMode == AppMode.NON_ROOT) {
                    hasNotif && hasBatt
                } else {
                    true  // 루팅 모드는 권한 체크 안함
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val modeLabel = when (currentMode) {
        AppMode.ROOT_ADB -> "루팅(ADB)"
        AppMode.NON_ROOT -> "논루팅(알림)"
    }

    // ADB 모드: 대시보드/설정/쿼리 탭
    // 논루팅(알림) 모드: 설정&테스트/히스토리/권한 탭
    val (tabs, icons) = when (currentMode) {
        AppMode.ROOT_ADB -> {
            listOf("대시보드", "설정", "DB 쿼리", "가이드") to
            listOf(Icons.Default.Dashboard, Icons.Default.Settings, Icons.Default.Storage, Icons.Default.Terminal)
        }
        AppMode.NON_ROOT -> {
            listOf("설정&테스트", "히스토리", "권한") to
            listOf(Icons.Default.Settings, Icons.Default.History, Icons.Default.Security)
        }
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
                        Text(modeLabel, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSub)
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
                            BadgedBox(
                                badge = {
                                    if (index == 2 && !hasRequiredPermissions) {
                                        Badge(
                                            containerColor = AppColors.ErrorVivid,
                                            modifier = Modifier.offset(x = 6.dp, y = (-2).dp).size(10.dp)
                                        )
                                    }
                                }
                            ) {
                                Icon(icons[index], contentDescription = title)
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
                AppMode.ROOT_ADB -> {
                    when (selectedTabIndex) {
                        0 -> AdbDashboardScreen(
                            onNavigateToConfig = { selectedTabIndex = 1 },
                            onNavigateToQuery = { selectedTabIndex = 2 }
                        )
                        1 -> AdbConfigScreen()
                        2 -> AdbQueryScreen()
                        3 -> androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) { item { AdbGuideScreen() } }
                    }
                }
                AppMode.NON_ROOT -> {
                    when (selectedTabIndex) {
                        0 -> ConfigScreen(mode = currentMode)
                        1 -> HistoryScreen()
                        2 -> PermissionScreen(mode = currentMode)
                    }
                }
            }
        }
    }
}
