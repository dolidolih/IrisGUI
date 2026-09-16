package party.qwer.irisgui.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.AppState
import party.qwer.irisgui.backend.AdbProcessClient
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
    var running by remember { mutableStateOf(AppState.running) }
    // ON/OFF 를 탭한 직후 — 백엔드 응답을 기다리는 동안 토글이 되돌아가지 않도록
    // 표기-only 상태를 유지한다(pending). 백엔드가 실제로 내려간 것이 확인되면 해제된다.
    var pendingOff by remember { mutableStateOf(false) }
    var pendingOffAt by remember { mutableLongStateOf(0L) }

    // 백엔드(데몬 / 인프로세스 서버) 생존 여부와 값을 폴링 — 토글 결과를 실제 상태로 확정한다.
    // (토글 Press 시점의 낙관적 표시가 아니라 데몬/NLS가 실제로 내려간 값을 반영한다.)
    LaunchedEffect(mode) {
        while (true) {
            val reachable: Boolean
            val alive: Boolean = when (mode) {
                AppMode.ROOT_ADB -> {
                    val status = AdbProcessClient.queryStatus()
                    status?.let {
                        port = it.bot_http_port
                        endpoint = it.web_server_endpoint
                        dbPoll = it.db_polling_rate
                        send = it.message_send_rate
                    }
                    reachable = status != null
                    status?.server_running == true
                }
                AppMode.NON_ROOT -> {
                    reachable = true
                    IrisServer.isStarted
                }
            }
            // OFF 를 탭한 직후: 백엔드가 실제로 내려간 것이 확인될 때까지 ON으로
            // 되살아나지 않는다. (무응답이어도 정지 실패로 보이므로 표기는 OFF 유지)
            if (pendingOff) {
                if (!alive) pendingOff = false
                else if (System.currentTimeMillis() - pendingOffAt > 6_000) {
                    // 백엔드가 내려가지 않은 채 무응답 — 실제 상태로 되돌린다.
                    pendingOff = false
                    running = alive
                    AppState.running = alive
                }
            } else {
                running = alive
                AppState.running = alive
            }
            delay(if (alive) 3000L else 800L)
        }
    }

    // 값 변경 팝업 대상
    var editor by remember { mutableStateOf<ValueEditor?>(null) }

    // DB 폴링은 DBObserver(루팅 모드) 전용 항목 — 논루팅 모드에서는 보여주지 않는다.
    val values = buildList {
        add(GridValue("포트", port.toString(), Icons.Default.Memory) {
            editor = ValueEditor("포트", port.toString(), numeric = true) { v ->
                val p = v.toIntOrNull() ?: return@ValueEditor false
                AppConfig.serverPort = p; port = p
                scope.launch { AdbProcessClient.updateConfig("botport", ConfigRequest(port = p)) }
                true
            }
        })
        add(GridValue("엔드포인트", endpoint.ifBlank { "미설정" }, Icons.Default.Cable) {
            editor = ValueEditor("엔드포인트", endpoint) { v ->
                AppConfig.webEndpoint = v; endpoint = v
                scope.launch { AdbProcessClient.updateConfig("endpoint", ConfigRequest(endpoint = v)) }
                true
            }
        })
        if (mode == AppMode.ROOT_ADB) {
            add(GridValue("DB 폴링", "${dbPoll}ms", Icons.Default.Timer) {
                editor = ValueEditor("DB 폴링 (ms)", dbPoll.toString(), numeric = true) { v ->
                    val r = v.toLongOrNull() ?: return@ValueEditor false
                    AppConfig.dbPollingRate = r; dbPoll = r
                    scope.launch { AdbProcessClient.updateConfig("dbrate", ConfigRequest(rate = r)) }
                    true
                }
            })
        }
        add(GridValue("발송 주기", "${send}ms", Icons.Default.Send) {
            editor = ValueEditor("발송 주기 (ms)", send.toString(), numeric = true) { v ->
                val r = v.toLongOrNull() ?: return@ValueEditor false
                AppConfig.sendRate = r; AppConfig.messageSendRate = r; send = r
                scope.launch { AdbProcessClient.updateConfig("sendrate", ConfigRequest(rate = r)) }
                true
            }
        })
    }

    // 상태 탭은 스크롤 없이 화면 전체를 채우도록 블록을 벌린다 (LazyColumn → 가변 Column).
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ServiceCard(
            modifier = Modifier.weight(1f),
            running = running,
            needsAttention = permission.needsAttention(mode),
            onToggle = { on ->
                // 항상 IrisService 경유 — 백엔드(데몬/서버) 기동·정지와 결과 토스트가
                // UI와 무관하게 서비스에서 확정된다.
                if (!on) {
                    // OFF 는 기다리지 않고 즉시 표기(정지 확인은 폴링에서 반영)
                    running = false
                    AppState.running = false
                    pendingOff = true
                    pendingOffAt = System.currentTimeMillis()
                }
                val intent = android.content.Intent(context, IrisService::class.java)
                    .setAction(if (on) IrisService.ACTION_START_SERVICE else IrisService.ACTION_STOP_SERVICE)
                context.startForegroundService(intent)
            }
        )
        ValueGridCard(
            values = values,
            modifier = Modifier.weight(1.8f),
            fillHeight = true
        )
    }

    editor?.let { ed ->
        ValueEditorDialog(ed, onDismiss = { editor = null })
    }
}

/** 행1: 서비스 카드 — ON/OFF 토글 + 모드 전환 칩(원 카드). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceCard(
    modifier: Modifier = Modifier,
    running: Boolean,
    needsAttention: Boolean,
    onToggle: (Boolean) -> Unit
) {
    val mode = AppModeManager.currentMode
    val dropdownContext = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val pulse = rememberInfiniteTransition(label = "halo")
    val halo by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "haloScale"
    )

    SurfaceCard(modifier = modifier, fillHeight = true, contentPadding = PaddingValues(16.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            // ExposedDropdownMenuBox 의 content 는 BoxScope — 행 정렬은 안쪽 Column 이 맡는다.
            // 구성: 헤더(아이콘·타이틀·모드 칩) → 히어로(큰 상태·스위치) → 구분선 → 상태 표식.
            Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconChip(
                        icon = Icons.Default.Devices,
                        modifier = Modifier.size(30.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        "백그라운드 서비스",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = AppColors.TextSub,
                        letterSpacing = 0.4.sp
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    // 모드 전환 칩 = 앵커이자 트리거. menuAnchor(PrimaryNotEditable) 은 앵커
                    // 자체를 탭하면 메뉴가 열리므로, 별도 clickable 을 더 걸으면 토글이
                    // 두 번 실행되어 열리자마자 닫힌다.
                    Row(
                        modifier = Modifier
                            .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                            .clip(RoundedCornerShape(999.dp))
                            .background(AppColors.PrimaryAccent.copy(alpha = 0.13f))
                            .heightIn(min = 30.dp)
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Devices, contentDescription = null, tint = AppColors.PrimaryAccent, modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(modeLabelOf(mode), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = AppColors.PrimaryAccent)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = AppColors.PrimaryAccent, modifier = Modifier.size(15.dp))
                    }
                }

                // 행2: 히어로 — 남은 세로 한복판에 묶는다 (위아래 여백이 균등하게 남는다).
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 동작 중일 때만 숨쉬는 광환 — 정지 시에는 잉크색 도트로 조용히.
                                Box(modifier = Modifier.size(10.dp), contentAlignment = Alignment.Center) {
                                    if (running) {
                                        Box(
                                            modifier = Modifier
                                                .size((10 + halo * 14).dp)
                                                .background(AppColors.SuccessVivid.copy(alpha = 0.28f * (1f - halo)), CircleShape)
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(9.dp)
                                            .background(if (running) AppColors.SuccessVivid else AppColors.TextMute, CircleShape)
                                    )
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    if (running) "작동 중" else "정지됨",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = if (running) AppColors.TextMain else AppColors.TextSub
                                )
                            }
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                if (running) "백그라운드에서 계속 동작합니다"
                                else "스위치를 켜면 백그라운드 서비스가 시작됩니다",
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSub
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Switch(
                            checked = running,
                            onCheckedChange = onToggle,
                            modifier = Modifier.scale(1.15f)
                        )
                    }
                }

                // 필요 시에만 표식 행 — 조용한 상태에서는 카드 하단을 비워두지 않고 여백으로 남긴다.
                if (needsAttention) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        StatusPill(ok = false, label = "권한 필요")
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                shape = RoundedCornerShape(AppColors.BlockRadius),
                containerColor = AppColors.GlassFill,
                tonalElevation = 0.dp,
                border = BorderStroke(1.dp, AppColors.GlassStroke)
            ) {
                listOf(AppMode.ROOT_ADB, AppMode.NON_ROOT).forEach { opt ->
                    DropdownMenuItem(
                        text = { Text(modeLabelOf(opt), fontWeight = if (opt == mode) FontWeight.Bold else FontWeight.Normal) },
                        onClick = {
                            expanded = false
                            if (opt != mode) {
                                // 직전 모드 백엔드 상태가 새 모드 표식에 잠시 남지 않도록 즉시 비운다.
                                AppState.running = false
                                AppModeManager.setMode(opt)
                                AppConfig.appMode = opt
                                dropdownContext.startForegroundService(
                                    android.content.Intent(dropdownContext, IrisService::class.java)
                                        .setAction(IrisService.ACTION_RESTART_SERVICE)
                                )
                            }
                        }
                    )
                }
            }
        }
    }
}

/** 행3: 값 카드 — 2×2 그리드. */
@Composable
private fun ValueGridCard(
    values: List<GridValue>,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false
) {
    SurfaceCard(modifier = modifier, fillHeight = fillHeight, contentPadding = PaddingValues(16.dp)) {
        SectionHeader(icon = Icons.Default.Cable, title = "동작 설정")
        Spacer(modifier = Modifier.height(10.dp))
        StatTiles(
            *values.map {
                StatItem(
                    it.label,
                    it.value,
                    it.icon,
                    valueColor = if (it.value == "미설정") AppColors.TextSub else AppColors.PrimaryAccent,
                    onClick = it.onClick
                )
            }.toTypedArray(),
            columns = 2,
            modifier = Modifier.weight(1f),
            fillHeight = fillHeight
        )
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
