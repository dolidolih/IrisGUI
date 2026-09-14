package party.qwer.irisgui.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode

@Composable
fun PermissionScreen(mode: AppMode) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var hasNotificationPermission by remember { mutableStateOf(false) }
    var hasOverlayPermission by remember { mutableStateOf(false) }
    var hasBatteryPermission by remember { mutableStateOf(false) }
    var hasPostNotificationPermission by remember { mutableStateOf(false) }

    var showNotificationDialog by remember { mutableStateOf(false) }
    var showOverlayDialog by remember { mutableStateOf(false) }
    var showBatteryDialog by remember { mutableStateOf(false) }
    var showPostNotificationDialog by remember { mutableStateOf(false) }

    val postNotifLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { isGranted -> hasPostNotificationPermission = isGranted }
    )

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(context)
                hasNotificationPermission = enabledPackages.contains(context.packageName)
                hasOverlayPermission = Settings.canDrawOverlays(context)
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                hasBatteryPermission = pm.isIgnoringBatteryOptimizations(context.packageName)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    hasPostNotificationPermission = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                } else {
                    hasPostNotificationPermission = true
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val requestPermission = { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
    val requestOverlayPermission = {
        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
        context.startActivity(intent)
    }
    val requestBatteryPermission = {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply { data = Uri.parse("package:${context.packageName}") }
        context.startActivity(intent)
    }

    // ── 다이얼로그들 ──────────────────────────────────
    if (showNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showNotificationDialog = false },
            containerColor = AppColors.CardBg,
            titleContentColor = AppColors.TextMain,
            textContentColor = AppColors.TextSub,
            shape = RoundedCornerShape(24.dp),
            title = { Text(text = "[필수] 알림 읽기 권한", fontWeight = FontWeight.Bold) },
            text = { Text("IrisGUI는 알림을 읽고 처리하기 위해 알림 읽기 권한이 필요합니다.\n\n권한 설정 화면으로 이동하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = { showNotificationDialog = false; requestPermission() }) {
                    Text("동의 및 설정", color = AppColors.PrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showNotificationDialog = false }) { Text("미동의", color = AppColors.TextSub) }
            }
        )
    }

    if (showBatteryDialog) {
        AlertDialog(
            onDismissRequest = { showBatteryDialog = false },
            containerColor = AppColors.CardBg,
            titleContentColor = AppColors.TextMain,
            textContentColor = AppColors.TextSub,
            shape = RoundedCornerShape(24.dp),
            title = { Text(text = "[필수] 백그라운드 배터리", fontWeight = FontWeight.Bold) },
            text = { Text("앱이 백그라운드에서 절전 모드에 진입하지 않고 끊김 없이 동작하기 위해 배터리 최적화 예외 설정이 필요합니다.\n\n설정 화면으로 이동하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = { showBatteryDialog = false; requestBatteryPermission() }) {
                    Text("동의 및 설정", color = AppColors.PrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showBatteryDialog = false }) { Text("미동의", color = AppColors.TextSub) }
            }
        )
    }

    if (showPostNotificationDialog) {
        AlertDialog(
            onDismissRequest = { showPostNotificationDialog = false },
            containerColor = AppColors.CardBg,
            titleContentColor = AppColors.TextMain,
            textContentColor = AppColors.TextSub,
            shape = RoundedCornerShape(24.dp),
            title = { Text(text = "[선택] 알림 전송", fontWeight = FontWeight.Bold) },
            text = { Text("앱의 동작 상태 및 결과를 알림으로 받기 위해 알림 전송 권한이 필요합니다.\n\n권한을 허용하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = {
                    showPostNotificationDialog = false
                    postNotifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) {
                    Text("동의 및 설정", color = AppColors.PrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPostNotificationDialog = false }) { Text("미동의", color = AppColors.TextSub) }
            }
        )
    }

    if (showOverlayDialog) {
        AlertDialog(
            onDismissRequest = { showOverlayDialog = false },
            containerColor = AppColors.CardBg,
            titleContentColor = AppColors.TextMain,
            textContentColor = AppColors.TextSub,
            shape = RoundedCornerShape(24.dp),
            title = { Text(text = "[선택] 다른 앱 위에 표시", fontWeight = FontWeight.Bold) },
            text = { Text("사진 공유 후 원활하게 홈 화면으로 이동하고, 알림 수신을 지속적으로 하기 위해 필요한 권한입니다.\n\n설정 화면으로 이동하시겠습니까?") },
            confirmButton = {
                TextButton(onClick = { showOverlayDialog = false; requestOverlayPermission() }) {
                    Text("동의 및 설정", color = AppColors.PrimaryAccent, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showOverlayDialog = false }) { Text("미동의", color = AppColors.TextSub) }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 모드별 안내 ─────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Terminal, contentDescription = null, tint = AppColors.PrimaryAccent, modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("현재 모드", fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    val modeDesc = when (mode) {
                        AppMode.ROOT_ADB -> "ADB 루팅 모드 — root ADB로 프로세스 실행"
                        AppMode.NON_ROOT -> "논루팅(알림) 모드 — NLS(알림 리스너) 기반"
                    }
                    Text(modeDesc, color = AppColors.TextSub)
                }
            }
        }

        // 논루팅 모드일 때만 NLS 관련 권한 표시
        if (mode == AppMode.NON_ROOT) {
            item {
                PermissionCard(
                    title = "[필수] 알림 수신",
                    description = "앱의 알림을 읽고 처리합니다.",
                    isGranted = hasNotificationPermission,
                    icon = Icons.Default.Notifications,
                    onClick = { showNotificationDialog = true }
                )
            }
            item {
                PermissionCard(
                    title = "[필수] 백그라운드 배터리",
                    description = "백그라운드에서 끊김 없이 실행됩니다.",
                    isGranted = hasBatteryPermission,
                    icon = Icons.Default.BatteryAlert,
                    onClick = { showBatteryDialog = true }
                )
            }
        }

        // 공통 선택 권한
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            item {
                PermissionCard(
                    title = "[선택] 알림 전송",
                    description = "앱의 동작 상태 알림을 표시합니다.",
                    isGranted = hasPostNotificationPermission,
                    icon = Icons.Default.NotificationsActive,
                    onClick = { showPostNotificationDialog = true }
                )
            }
        }
        item {
            PermissionCard(
                title = "[선택] 앱 위에 그리기",
                description = "화면 전환 및 지속적인 알림 수신을 돕습니다.",
                isGranted = hasOverlayPermission,
                icon = Icons.Default.Layers,
                onClick = { showOverlayDialog = true }
            )
        }

    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AppColors.PrimaryAccent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (isGranted) Icons.Default.CheckCircle else Icons.Default.BatteryAlert,
                        contentDescription = null,
                        tint = if (isGranted) AppColors.SuccessVivid else AppColors.ErrorVivid,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = if (isGranted) "권한 허용됨" else "권한 필요",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isGranted) AppColors.SuccessVivid else AppColors.ErrorVivid,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (!isGranted) {
                    Button(
                        onClick = onClick,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text("설정하기", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
