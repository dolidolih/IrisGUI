package party.qwer.irisgui.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode

data class PermissionStatus(
    val batteryOk: Boolean,
    val overlayOk: Boolean,
    val nlsOk: Boolean,
    val postNotificationsOk: Boolean
) {
    /** 그 모드에서 사용자가 직접 처리해야 하는 항목만 남은 지 */
    fun needsAttention(mode: AppMode): Boolean = when (mode) {
        AppMode.NON_ROOT -> !nlsOk || !batteryOk
        AppMode.ROOT_ADB -> !batteryOk
    }
}

@Composable
fun rememberPermissionStatus(): PermissionStatus {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var status by remember {
        mutableStateOf(
            PermissionStatus(
                batteryOk = false,
                overlayOk = Settings.canDrawOverlays(context),
                nlsOk = false,
                postNotificationsOk = true
            )
        )
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) status = currentPermissionStatus(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) { status = currentPermissionStatus(context) }
    return status
}

private fun currentPermissionStatus(context: Context): PermissionStatus {
    val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val postGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    } else true
    return PermissionStatus(
        batteryOk = pm.isIgnoringBatteryOptimizations(context.packageName),
        overlayOk = Settings.canDrawOverlays(context),
        nlsOk = enabled.contains(context.packageName),
        postNotificationsOk = postGranted
    )
}

fun requestNlsPermission(context: Context) {
    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
}

fun requestOverlayPermission(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    )
}

fun requestBatteryPermission(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .apply { data = Uri.parse("package:${context.packageName}") }
    )
}

/**
 * 상태 화면 상단 배치용 — 허용이 필요한 항목만 행으로 노출하고,
 * 모두 해결되면 통째로 사라진다. 모드별로 필요한 권한만 필터링한다.
 */
@Composable
fun PermissionStrip(status: PermissionStatus, mode: AppMode, modifier: Modifier = Modifier) {
    val missing = buildList {
        if (!status.batteryOk) add("배터리 최적화 예외")
        if (mode == AppMode.NON_ROOT && !status.nlsOk) add("알림 읽기")
    }
    if (missing.isEmpty()) return

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.WarningVivid.copy(alpha = 0.10f))
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = AppColors.WarningVivid, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "설정이 필요합니다 — ${missing.joinToString(", ")}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.TextMain
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!status.batteryOk) PermissionActionButton("배터리 예외", AppMode.ROOT_ADB)
                if (mode == AppMode.NON_ROOT && !status.nlsOk) PermissionActionButton("알림 읽기", AppMode.NON_ROOT)
            }
        }
    }
}

@Composable
private fun PermissionActionButton(label: String, kind: AppMode) {
    val context = LocalContext.current
    Button(
        onClick = {
            when (kind) {
                AppMode.NON_ROOT -> requestNlsPermission(context)
                AppMode.ROOT_ADB -> requestBatteryPermission(context)
            }
        },
        shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 권한 카드 — 상태 화면 목록에 들어가는 공용 위젯.
 * 허용되면 버튼이 숨고 성공 인디케이터만 남는다.
 */
@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = AppColors.PrimaryAccent, modifier = Modifier.size(22.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(
                                color = if (isGranted) AppColors.SuccessVivid else AppColors.ErrorVivid,
                                shape = CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (isGranted) "권한 허용됨" else "권한 필요",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isGranted) AppColors.SuccessVivid else AppColors.ErrorVivid,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (!isGranted) {
                    Button(
                        onClick = onClick,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
                    ) {
                        Text("설정하기", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
