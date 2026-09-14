package party.qwer.irisgui.ui

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.service.IrisService

/**
 * ConfigScreen — NON_ROOT 설정 탭.
 *
 * 모드 선택은 TopAppBar 의 모드 시트로 이동했으므로, 여기서는 서비스 토글과
 * 연결 설정만 다룬다. (모드 변경은 MainScreen 의 ModePickerSheet 에서 처리)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen() {
    val context = LocalContext.current
    var isEnabled by remember { mutableStateOf(AppConfig.isServiceEnabled) }
    var endpoint by remember { mutableStateOf(AppConfig.webEndpoint) }
    var sendRate by remember { mutableStateOf(AppConfig.sendRate.toString()) }
    var port by rememberSaveable { mutableStateOf(AppConfig.serverPort.toString()) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 서비스 ─────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "백그라운드 서비스",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = AppColors.TextMain
                        )
                        Text(
                            if (isEnabled) "작동중" else "정지됨",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isEnabled) AppColors.SuccessVivid else AppColors.TextSub
                        )
                    }
                    Switch(
                        checked = isEnabled,
                        onCheckedChange = { checked ->
                            val action = if (checked) IrisService.ACTION_START_SERVICE else IrisService.ACTION_STOP_SERVICE
                            context.startForegroundService(
                                Intent(context, IrisService::class.java).apply { this.action = action }
                            )
                            isEnabled = checked
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = AppColors.TextMain,
                            checkedTrackColor = AppColors.PrimaryAccent,
                            uncheckedThumbColor = AppColors.TextSub,
                            uncheckedTrackColor = AppColors.InputBg,
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }
            }
        }

        // ── 연결 ───────────────────────────────────────
        item {
            Text("연결", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.PrimaryAccent)
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsField(
                        label = "웹서버 엔드포인트",
                        value = endpoint,
                        onValueChange = { endpoint = it; AppConfig.webEndpoint = it },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = AppColors.TextSub) }
                    )
                    SettingsField(
                        label = "발송 주기(ms)",
                        value = sendRate,
                        numeric = true,
                        onValueChange = {
                            sendRate = it
                            AppConfig.sendRate = it.toLongOrNull() ?: 100L
                        },
                        leadingIcon = { Icon(Icons.Default.Timer, contentDescription = null, tint = AppColors.TextSub) }
                    )
                    SettingsField(
                        label = "서버 포트 (127.0.0.1)",
                        value = port,
                        numeric = true,
                        onValueChange = {
                            port = it
                            val p = it.toIntOrNull()
                            if (p != null && p in 1..65535) AppConfig.serverPort = p
                        },
                        leadingIcon = { Icon(Icons.Default.Storage, contentDescription = null, tint = AppColors.TextSub) }
                    )
                    Text(
                        "포트 변경은 서비스 재시작 시 반영됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSub
                    )
                }
            }
        }

        item {
            Text(
                "변경값은 즉시 저장됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = AppColors.TextSub
            )
        }
    }
}
