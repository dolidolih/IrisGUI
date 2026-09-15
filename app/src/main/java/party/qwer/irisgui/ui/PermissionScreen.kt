package party.qwer.irisgui.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.backend.AdbProcessClient

/**
 * PermissionScreen — 모드 공용 권한 탭(마지막 탭).
 *
 * 모드별로 실제로 필요한 항목만 카드한다:
 *   NON_ROOT : 알림 표시 · 배터리 예외 · 알림 접근(NLS) · 다른 앱 위에 그리기
 *   ROOT_ADB : 알림 표시 · 배터리 예외 · 백그라운드 데몬 상태
 *
 * "다른 앱 위에 그리기"는 NON_ROOT 의 사진 발송(IrisServer)에서만 사용하므로
 * ROOT_ADB 에서는 노출하지 않는다.
 */
@Composable
fun PermissionScreen(permission: PermissionStatus) {
    val mode = AppModeManager.currentMode
    val context = LocalContext.current

    // Android 13+ 런타임 권한 — ON_RESUME 시 rememberPermissionStatus 를 재조회하므로
    // 런처 종료 직후 카드의 허용/필요 표기가 갱신된다.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 상태 갱신은 ON_RESUME 에서 */ }

    // ROOT_ADB — 백그라운드 데몬(app_process) 생존 상태. 정지된 채로 권한만 허용되어도
    // 동작하지 않으므로 상태 표시를 함께 제공한다.
    var daemonRunning by remember { mutableStateOf(false) }
    var daemonPort by remember { mutableStateOf(0) }
    if (mode == AppMode.ROOT_ADB) {
        LaunchedEffect(Unit) {
            while (true) {
                val status = AdbProcessClient.queryStatus()
                daemonRunning = status?.server_running == true
                daemonPort = status?.bot_http_port ?: 0
                delay(3000)
            }
        }
    }

    val attention = permission.needsAttention(mode)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 12.dp)
    ) {
        item {
            SectionHeader(
                icon = Icons.Default.Shield,
                title = "권한",
                trailing = {
                    StatusPill(ok = !attention, label = if (attention) "처리 필요" else "모두 허용됨")
                }
            )
        }

        // ── 공통 ────────────────────────────────────────────────
        item {
            PermissionCard(
                title = "알림 표시",
                description = "전면 알림·채팅 알림을 표시합니다. Android 13 이후 허용이 필요합니다.",
                isGranted = permission.postNotificationsOk,
                icon = Icons.Default.NotificationsActive,
                onClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            )
        }
        item {
            PermissionCard(
                title = "배터리 최적화 예외",
                description = "서비스가 백그라운드에서 끊기지 않도록 배터리 최적화를 우회합니다.",
                isGranted = permission.batteryOk,
                icon = Icons.Default.BatteryAlert,
                onClick = { requestBatteryPermission(context) }
            )
        }

        if (mode == AppMode.NON_ROOT) {
            item {
                PermissionCard(
                    title = "알림 접근(NLS)",
                    description = "카카오톡 알림을 읽어 메시지 수신 상태를 확인합니다.",
                    isGranted = permission.nlsOk,
                    icon = Icons.Default.Notifications,
                    onClick = { requestNlsPermission(context) }
                )
            }
            item {
                PermissionCard(
                    title = "다른 앱 위에 그리기",
                    description = "사진 응답 발송에 필요합니다. 사진 발송을 사용하지 않으면 설정하지 않아도 됩니다.",
                    isGranted = permission.overlayOk,
                    icon = Icons.Default.Layers,
                    onClick = { requestOverlayPermission(context) }
                )
            }
        } else {
            item {
                DaemonStatusCard(running = daemonRunning, port = daemonPort)
            }
        }
    }
}

/** 루팅 모드 — 백그라운드 프로세스(deemon) 상태 카드. 처리 버튼 없이 상태만 표시한다. */
@Composable
private fun DaemonStatusCard(running: Boolean, port: Int) {
    SurfaceCard {
        SectionHeader(
            icon = Icons.Default.Shield,
            title = "백그라운드 프로세스(ADB)",
            trailing = { StatusPill(ok = running, label = if (running) "실행 중" else "정지됨") }
        )
        Text(
            if (running) "app_process 데몬이 포트 $port 에서 DB 관찰·발송을 담당합니다."
            else "데몬이 내려간 상태입니다. 상태 탭의 서비스를 켜면 함께 기동됩니다.",
            style = MaterialTheme.typography.bodySmall,
            color = if (running) AppColors.TextSub else AppColors.WarningVivid
        )
    }
}
