package party.qwer.irisgui.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.backend.DaemonLauncher
import party.qwer.irisgui.backend.IrisServer
import party.qwer.irisgui.models.ConfigRequest
import party.qwer.irisgui.service.IrisService

/**
 * StatusScreen — ROOT_ADB / NON_ROOT 공용 상태 화면.
 *
 *   행1: 서비스 모드 전환
 *   행2: 서비스 ON / OFF
 *   행3: 값 카드 2×2 그리드 — 현재 값 표시, 탭하면 값 변경 팝업
 *
 * 표시 값은 모드별 백엔드(daemon / AppConfig)에서 읽으며, 값 변경은 항상
 * AppConfig(로컬) 저장 + 가능 시 서버 반영으로 동일 동작을 한다.
 */
@Composable
fun StatusScreen(permission: PermissionStatus) {
    val mode = AppModeManager.currentMode
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var port by remember { mutableIntStateOf(AppConfig.serverPort) }
    var endpoint by remember { mutableStateOf(AppConfig.webEndpoint) }
    var dbPoll by remember { mutableLongStateOf(AppConfig.dbPollingRate) }
    var send by remember { mutableLongStateOf(AppConfig.sendRate) }
    val daemonRunning = remember { mutableStateOf(false) }

    // 모드 전환/진입 시 모드 백엔드 상태 동기화 + 루팅 모드 폴링 갱신
    if (mode == AppMode.ROOT_ADB) {
        LaunchedEffect(Unit) {
            while (true) {
                AdbProcessClient.queryStatus()?.let { daemonRunning.value = it.server_running }
                delay(3000)
            }
        }
    } else {
        daemonRunning.value = IrisServer.isStarted
    }

    val running = when (mode) {
        AppMode.ROOT_ADB -> daemonRunning.value
        AppMode.NON_ROOT -> IrisServer.isStarted
    }

    // 값 변경 팝업 대상
    var editor by remember { mutableStateOf<ValueEditor?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 12.dp)
    ) {
        item { ServiceModeRow() }
        item {
            ServiceToggleRow(
                running = running,
                needsAttention = permission.needsAttention(mode),
                onToggle = { on ->
                    if (mode == AppMode.ROOT_ADB) {
                        if (on) scope.launch { DaemonLauncher.startDaemon(context) }
                        else scope.launch { DaemonLauncher.stopDaemon(context) }
                    } else {
                        val intent = android.content.Intent(context, IrisService::class.java)
                            .setAction(if (on) IrisService.ACTION_START_SERVICE else IrisService.ACTION_STOP_SERVICE)
                        context.startForegroundService(intent)
                    }
                }
            )
        }
        item {
            ValueGridCard(
                values = listOf(
                    GridValue("포트", port.toString(), Icons.Default.Memory) {
                        editor = ValueEditor("포트", port.toString(), numeric = true) { v ->
                            val p = v.toIntOrNull() ?: return@ValueEditor false
                            AppConfig.serverPort = p; port = p
                            scope.launch { AdbProcessClient.updateConfig("botport", ConfigRequest(port = p)) }
                            true
                        }
                    },
                    GridValue("엔드포인트", endpoint.ifBlank { "미설정" }, Icons.Default.Cable) {
                        editor = ValueEditor("엔드포인트", endpoint) { v ->
                            AppConfig.webEndpoint = v; endpoint = v
                            scope.launch { AdbProcessClient.updateConfig("endpoint", ConfigRequest(endpoint = v)) }
                            true
                        }
                    },
                    GridValue("DB 폴링", "${dbPoll}ms", Icons.Default.Timer) {
                        editor = ValueEditor("DB 폴링 (ms)", dbPoll.toString(), numeric = true) { v ->
                            val r = v.toLongOrNull() ?: return@ValueEditor false
                            AppConfig.dbPollingRate = r; dbPoll = r
                            scope.launch { AdbProcessClient.updateConfig("dbrate", ConfigRequest(rate = r)) }
                            true
                        }
                    },
                    GridValue("발송 주기", "${send}ms", Icons.Default.Send) {
                        editor = ValueEditor("발송 주기 (ms)", send.toString(), numeric = true) { v ->
                            val r = v.toLongOrNull() ?: return@ValueEditor false
                            AppConfig.sendRate = r; AppConfig.messageSendRate = r; send = r
                            scope.launch { AdbProcessClient.updateConfig("sendrate", ConfigRequest(rate = r)) }
                            true
                        }
                    }
                )
            )
        }
    }

    editor?.let { ed ->
        ValueEditorDialog(ed, onDismiss = { editor = null })
    }
}

/** 행1: 서비스 모드 전환 — 현재 모드 chip + 모드 변경 드롭다운. */
@Composable
private fun ServiceModeRow() {
    SurfaceCard(contentPadding = PaddingValues(16.dp)) {
        SectionHeader(icon = Icons.Default.Devices, title = "서비스 모드")
        Spacer(modifier = Modifier.height(10.dp))
        ModeDropdown()
    }
}

/** 행2: 서비스 ON / OFF 토글. */
@Composable
private fun ServiceToggleRow(running: Boolean, needsAttention: Boolean, onToggle: (Boolean) -> Unit) {
    SurfaceCard(contentPadding = PaddingValues(16.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            IconChip(icon = if (running) Icons.Default.NotificationsActive else Icons.Default.PowerSettingsNew)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("서비스", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                Text(
                    if (running) "작동 중" else "정지됨",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) AppColors.SuccessVivid else AppColors.TextSub
                )
            }
            Switch(checked = running, onCheckedChange = onToggle)
        }
        if (needsAttention) {
            Spacer(modifier = Modifier.height(8.dp))
            StatusPill(ok = false, label = "권한 필요")
        }
    }
}

/** 행3: 값 카드 — 2×2 그리드. */
@Composable
private fun ValueGridCard(values: List<GridValue>) {
    SurfaceCard(contentPadding = PaddingValues(16.dp)) {
        SectionHeader(icon = Icons.Default.Cable, title = "동작 설정")
        Spacer(modifier = Modifier.height(10.dp))
        StatTiles(
            *values.map { StatItem(it.label, it.value, it.icon, onClick = it.onClick) }.toTypedArray(),
            columns = 2
        )
    }
}

/** 모드 선택 드롭다운(재구현 없이 ExposedDropdownMenuBox). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModeDropdown() {
    val current = AppModeManager.currentMode
    val options = listOf(AppMode.ROOT_ADB, AppMode.NON_ROOT)
    val labels = mapOf(
        AppMode.ROOT_ADB to "루팅(ADB)",
        AppMode.NON_ROOT to "논루팅(알림)"
    )
    var expanded by remember { mutableStateOf(false) }
    val dropdownContext = LocalContext.current
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = labels[current] ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text("실행 모드") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true).fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            colors = irisFieldColors()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { mode ->
                DropdownMenuItem(
                    text = { Text(labels[mode] ?: "", fontWeight = if (mode == current) FontWeight.Bold else FontWeight.Normal) },
                    onClick = {
                        expanded = false
                        AppModeManager.setMode(mode)
                        AppConfig.appMode = mode
                        dropdownContext.startForegroundService(
                            android.content.Intent(dropdownContext, IrisService::class.java)
                                .setAction(IrisService.ACTION_RESTART_SERVICE)
                        )
                    }
                )
            }
        }
    }
}

// ── 값 편집 팝업 ──────────────────────────────────────────────

data class GridValue(val label: String, val value: String, val icon: ImageVector?, val onClick: () -> Unit)

private class ValueEditor(
    val title: String,
    initial: String,
    val numeric: Boolean = false,
    val onConfirm: (String) -> Boolean
) {
    var text by mutableStateOf(initial)
}

@Composable
private fun ValueEditorDialog(editor: ValueEditor, onDismiss: () -> Unit) {
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(editor.title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = editor.text,
                onValueChange = { editor.text = it },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (editor.numeric) KeyboardType.Number else KeyboardType.Uri
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = irisFieldColors()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (editor.onConfirm(editor.text.trim())) onDismiss()
                else error = "유효한 값을 입력하세요"
            }) { Text("저장", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
