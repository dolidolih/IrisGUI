package party.qwer.irisgui.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
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
import party.qwer.irisgui.RuntimeLog
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
    // ON/OFF 를 탭한 직후 — 백엔드 상태가 확인될까지 되돌아가지 않도록 표기-only
    // 상태를 유지한다(pending). 확인되면 실제 백엔드 값으로 확정한다.
    var pendingOffAt by remember { mutableLongStateOf(0L) }
    var pendingOff by remember { mutableStateOf(false) }
    var startingAt by remember { mutableLongStateOf(0L) }
    var starting by remember { mutableStateOf(false) }

    // ON 은 백엔드 확인까지 기다리는 유예 시간 — 데몬 기동은 root 셸 확보 + DB 초기화 +
    // 포트 바인딩까지 최대 20 초 가량 걸릴 수 있다.
    val startBudgetMs = 20_000L
    // OFF 확인 유예 — 정지 명령이 백엔드에 실제로 닿는지 확인한다.
    val stopBudgetMs = 10_000L

    // 백엔드(데몬 / 인프로세스 서버) 생존 여부와 값을 폴링 — 토글 결과를 실제 상태로 확정한다.
    //
    // 세 상태(살아있음/정지/미확인)를 구분한다. ROOT_ADB 의 status HTTP 조회는 데몬이
    // 한창 바쁜(또는 막 뜨는) 구간에서 예외를 내보내는데, 그때를 '정지'로 오인하면
    // 토글 ON 직후 스위치가 즉시 OFF 로 되돌아간다(작동 안 하는 것처럼 보이는 원인).
    // 그래서 HTTP 응답이 없을 때는 TCP 포트 개방 여부로 살아있음/정지를 가린다.
    LaunchedEffect(mode) {
        while (true) {
            val probed: BackendProbe = when (mode) {
                AppMode.ROOT_ADB -> {
                    val status = AdbProcessClient.queryStatus()
                    if (status != null) {
                        port = status.bot_http_port ?: port
                        endpoint = status.web_server_endpoint ?: endpoint
                        dbPoll = status.db_polling_rate ?: dbPoll
                        send = status.message_send_rate ?: send
                    }
                    when {
                        status?.server_running == true -> BackendProbe.Up
                        // 응답은 왔는데 서버가 안 쉼 → 확인된 정지
                        status != null -> BackendProbe.Down
                        // 무응답 — 포트가 열려 있으면 기동 중/한창 바쁨, 거절되면 내러간 것.
                        AdbProcessClient.isHttpPortOpen() -> BackendProbe.Busy
                        else -> BackendProbe.Down
                    }
                }
                AppMode.NON_ROOT ->
                    if (IrisServer.isStarted) BackendProbe.Up else BackendProbe.Down
            }
            val confirmed = probed != BackendProbe.Busy
            val alive = probed == BackendProbe.Up
            val now = System.currentTimeMillis()
            var state = AppState.running
            if (starting) {
                // 기동 커맨드 전송 후 — 살아남이 확인될 때까지 켜진 채 유지한다.
                // 데몬은 root 셸 확보 + DB 초기화 + 포트 바인딩까지 최대 startBudgetMs.
                if (alive) {
                    starting = false
                    state = true
                } else if (confirmed && now - startingAt > startBudgetMs) {
                    starting = false
                    state = false
                    AppState.postFeedback("백그라운드 기동이 확인되지 않았습니다. 로그 탭의 실행 로그를 확인하세요.", isError = true)
                }
            }
            if (pendingOff) {
                // 백엔드가 실제로 내려간 것이 확인될 때까지 ON 으로 되살아나지 않는다.
                if (!alive) {
                    pendingOff = false
                    state = false
                } else if (now - pendingOffAt > stopBudgetMs) {
                    pendingOff = false
                    state = true
                    AppState.postFeedback("정지 명령에도 백엔드가 남아 있습니다.", isError = true)
                }
            }
            if (!starting && !pendingOff) {
                if (alive) state = true
                else if (confirmed) state = false
            }
            if (state != AppState.running) AppState.running = state
            running = AppState.running
            delay(if (alive) 3000L else 800L)
        }
    }

    // 값 변경 팝업 대상
    var editor by remember { mutableStateOf<ValueEditor?>(null) }

    // DB 폴링은 DBObserver(루팅 모드) 전용 항목 — 논루팅 모드에서는 보여주지 않는다.
    val values = buildList {
        // 서비스 상태는 위 히어로 카드가 이미 보여주므로settings 타일에서는 중복을
        // 피하고, 읽고 수정하는 값만 남긴다.
        add(GridValue("포트", port.toString(), Icons.Default.Memory) {
            // ISSUE-12: 범위 검증 — 죽던 ConfigScreen 에만 있던 가드를 live 경로에 붙였다.
            // 오타 하나가 AppConfig.serverPort 에 영구 저장되어 status 서브시스템 전체를
            // 내는(self-inflicted outage) 일을 막는다.
            editor = ValueEditor("포트", port.toString(), numeric = true) { v ->
                val p = v.toIntOrNull() ?: return@ValueEditor "숫자를 입력하세요"
                if (!isValidPort(p)) return@ValueEditor "포트는 1–65535 사이어야 합니다"
                AppConfig.serverPort = p; port = p
                scope.launch { AdbProcessClient.updateConfig("botport", ConfigRequest(port = p)) }
                null
            }
        })
        add(GridValue("엔드포인트", endpoint.ifBlank { "미설정" }, Icons.Default.Cable) {
            editor = ValueEditor("엔드포인트", endpoint) { v ->
                if (v.isBlank()) return@ValueEditor "엔드포인트 값을 입력하세요"
                AppConfig.webEndpoint = v; endpoint = v
                scope.launch { AdbProcessClient.updateConfig("endpoint", ConfigRequest(endpoint = v)) }
                null
            }
        })
        if (mode == AppMode.ROOT_ADB) {
            add(GridValue("DB 폴링", "${dbPoll}ms", Icons.Default.Timer) {
                editor = ValueEditor("DB 폴링 (ms)", dbPoll.toString(), numeric = true) { v ->
                    val r = v.toLongOrNull() ?: return@ValueEditor "숫자를 입력하세요"
                    if (r !in 50L..600_000L) return@ValueEditor "50–600000 ms 범위가 적절합니다"
                    AppConfig.dbPollingRate = r; dbPoll = r
                    scope.launch { AdbProcessClient.updateConfig("dbrate", ConfigRequest(rate = r)) }
                    null
                }
            })
        }
        add(GridValue("발송 주기", "${send}ms", Icons.Default.Send) {
            editor = ValueEditor("발송 주기 (ms)", send.toString(), numeric = true) { v ->
                val r = v.toLongOrNull() ?: return@ValueEditor "숫자를 입력하세요"
                if (r !in 10L..3_600_000L) return@ValueEditor "10–3600000 ms 범위가 적절합니다"
                AppConfig.sendRate = r; AppConfig.messageSendRate = r; send = r
                scope.launch { AdbProcessClient.updateConfig("sendrate", ConfigRequest(rate = r)) }
                null
            }
        })
    }

    // 위 블록(서비스 카드 + 권한 안내 행)이 늘어나도 설정 카드가 잘리지 않도록
    // 화면 전체를 스크롤 가능하게 둔다. 콘텐츠가 남을 때는 그냥 위부터 쌓인다.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionTitle("서비스 상태", icon = Icons.Default.PowerSettingsNew)
        ServiceCard(
            running = running,
            startPending = starting,
            stopPending = pendingOff,
            needsAttention = permission.needsAttention(mode),
            mode = mode,
            onToggle = { on ->
                if (on) {
                    running = true
                    AppState.running = true
                    starting = true
                    startingAt = System.currentTimeMillis()
                    pendingOff = false
                } else {
                    running = false
                    AppState.running = false
                    pendingOff = true
                    pendingOffAt = System.currentTimeMillis()
                    starting = false
                }
                AppState.postFeedback(
                    if (on) {
                        if (mode == AppMode.ROOT_ADB) "루팅(ADB) 백그라운드를 시작합니다. 최대 20초 가량 소요됩니다."
                        else "백그라운드 서비스를 시작합니다."
                    } else "서비스를 종료합니다.",
                    isError = false
                )
                // ForegroundService 기동 — 예외가 UI 스레드에서 삼켜지면 아무것도 일어나지
                // 않으므로(토글이 "작동 안 함"으로 보인다) 결과를 로그에 남긴다.
                val action = if (on) IrisService.ACTION_START_SERVICE else IrisService.ACTION_STOP_SERVICE
                runCatching {
                    context.startForegroundService(
                        android.content.Intent(context, IrisService::class.java).setAction(action)
                    )
                }.onFailure {
                    RuntimeLog.error("StatusScreen", "startForegroundService($action) 실패: ${it.javaClass.simpleName}: ${it.message}")
                    runCatching { context.startService(android.content.Intent(context, IrisService::class.java).setAction(action)) }
                        .onFailure { RuntimeLog.error("StatusScreen", "startService 폴백도 실패: ${it.message}") }
                }
            }
        )
        SectionTitle("설정", icon = Icons.Default.Tune)
        ValueGridCard(values = values)
    }

    editor?.let { ed ->
        ValueEditorDialog(ed, onDismiss = { editor = null })
    }
}

/** 백엔드 생존 조회의 세 결과 — Busy 는 살아있음/정지를 구분할 수 없음을 뜻한다. */
private enum class BackendProbe { Up, Down, Busy }

/** 행1: 서비스 카드 — ON/OFF 토글 + 모드 전환 칩(원 카드). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceCard(
    modifier: Modifier = Modifier,
    running: Boolean,
    startPending: Boolean = false,
    stopPending: Boolean = false,
    needsAttention: Boolean,
    mode: AppMode,
    onToggle: (Boolean) -> Unit
) {
    val dropdownContext = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    val pulse = rememberInfiniteTransition(label = "halo")
    val halo by pulse.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), RepeatMode.Restart),
        label = "haloScale"
    )

    SurfaceCard(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            // ExposedDropdownMenuBox 의 content 는 BoxScope — 행 정렬은 안쪽 Column 이 맡는다.
            // 구성: 헤더(아이콘·타이틀·모드 칩) → 히어로(큰 상태·스위치) → 구분선 → 상태 표식.
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // 모드 전환 — "서비스 모드" 워드마크 + 선택 트리거(한 덩어리 전체가 앵커).
                // menuAnchor(PrimaryNotEditable) 은 앵커 자체 탭으로 메뉴가 열리므로,
                // 별도 clickable 을 더 걸면 토글이 두 번 실행되어 열리자마자 닫힌다.
                Row(
                    modifier = Modifier
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, enabled = true)
                        .clip(RoundedCornerShape(AppColors.TileRadius))
                        .background(AppColors.PrimaryAccent.copy(alpha = 0.11f))
                        .border(1.dp, AppColors.PrimaryAccent.copy(alpha = 0.24f), RoundedCornerShape(AppColors.TileRadius))
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .padding(start = 14.dp, end = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                        Text(
                            "서비스 모드",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.Bold),
                            color = AppColors.PrimaryAccent.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(1.dp))
                        Text(
                            modeLabelOf(mode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.TextMain
                        )
                    }
                    Icon(Icons.Default.ExpandMore, contentDescription = null, tint = AppColors.PrimaryAccent, modifier = Modifier.size(22.dp))
                }

                // 행2: 히어로 — 큰 상태 · 스위치 · 백엔드 표식을 세로로 촘촘하게 채운다.
                // 카드 높이를 콘텐츠에 맡기므로 남은 여백을 채우는 weight 는 쓰지 않는다.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 동작 중(또는 전환 중)일 때만 숨쉬는 광환 — 조용한 정지 시에는 잉크 도트.
                            Box(modifier = Modifier.size(12.dp), contentAlignment = Alignment.Center) {
                                if (running || startPending || stopPending) {
                                    Box(
                                        modifier = Modifier
                                            .size((12 + halo * 14).dp)
                                            .background(AppColors.SuccessVivid.copy(alpha = 0.28f * (1f - halo)), CircleShape)
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(
                                            if (running) AppColors.SuccessVivid else AppColors.TextMute,
                                            CircleShape
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            val state = when {
                                startPending -> "시작 중"
                                stopPending -> "종료 중"
                                running -> "작동 중"
                                else -> "정지됨"
                            }
                            Text(
                                state,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (running || startPending || stopPending) AppColors.TextMain else AppColors.TextSub
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            if (running) {
                                if (mode == AppMode.ROOT_ADB) "기기 안 app_process 데몬이 DB 를 관찰하고 답장을 보냅니다."
                                else "카카오톡 알림을 읽어서 답장을 보냅니다."
                            } else "스위치를 켜면 백그라운드 동작을 시작합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSub,
                            maxLines = 2
                        )
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Switch(
                        checked = running || startPending || stopPending,
                        onCheckedChange = onToggle,
                        modifier = Modifier.scale(1.15f)
                    )
                }

                HorizontalDivider(color = AppColors.CardBorder)

                // 백엔드 표식行 — 살아있음/대기 하나만 남긴다. (모드·포트·pid 표식은 위
                // 모드 전환부·설정 카드가 이미 보여주므로 이 행에서는 중복을 덜어낸다.)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StatusPill(
                        ok = running,
                        label = when {
                            startPending -> "기동 대기"
                            stopPending -> "정지 대기"
                            running -> "백그라운드 실행"
                            else -> "백그라운드 미실행"
                        }
                    )
                }

                // 필요 시에만 표식行 — 조용한 상태에서는 카드에 행을 더 얹지 않는다.
                if (needsAttention) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        StatusPill(ok = false, label = "권한 필요")
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "권한 탭에서 처리하세요",
                            style = MaterialTheme.typography.labelMedium,
                            color = AppColors.WarningVivid
                        )
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
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (opt == AppMode.ROOT_ADB) Icons.Default.PhoneAndroid
                                    else Icons.Default.NotificationsActive,
                                    contentDescription = null,
                                    tint = AppColors.PrimaryAccent,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(modeLabelOf(opt), fontWeight = if (opt == mode) FontWeight.Bold else FontWeight.Normal)
                            }
                        },
                        trailingIcon = {
                            if (opt == mode) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = AppColors.SuccessVivid)
                            }
                        },
                        onClick = {
                            expanded = false
                            if (opt != mode) {
                                // 직전 모드 백엔드 상태가 새 모드 표식에 잠시 남지 않도록 즉시 비운다.
                                AppState.running = false
                                AppModeManager.setMode(opt)
                                AppConfig.appMode = opt
                                // 전환 안내 — 현재 서비스는 종료하고 새 모드로 자동 재시작된다.
                                AppState.postFeedback(
                                    "서비스 모드를 ${modeLabelOf(opt)} 으로 전환합니다. " +
                                        "현재 서비스는 종료하고 새 모드로 자동 실행됩니다.",
                                    isError = false
                                )
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

/** 행3: 값 카드 — 2×2 그리드. 스크롤 가능한 스크린에서 콘텐츠 높이 그대로 배치된다. */
@Composable
private fun ValueGridCard(
    values: List<GridValue>,
    modifier: Modifier = Modifier
) {
    SurfaceCard(modifier = modifier, contentPadding = PaddingValues(16.dp)) {
        StatTiles(
            *values.map {
                StatItem(
                    it.label,
                    it.value,
                    it.icon,
                    valueColor = it.valueColor
                        ?: if (it.value == "미설정") AppColors.TextSub else AppColors.PrimaryAccent,
                    onClick = it.onClick
                )
            }.toTypedArray(),
            columns = 2,
            compact = true
        )
    }
}

// ── 값 편집 팝업 ──────────────────────────────────────────────

data class GridValue(
    val label: String,
    val value: String,
    val icon: ImageVector?,
    /** valueColor 지정이 없으면 ValueGridCard 가 기본(미설정=보조잉크, 값=액센트)으로 정한다. */
    val valueColor: Color? = null,
    val onClick: () -> Unit
)

private class ValueEditor(
    val title: String,
    initial: String,
    val numeric: Boolean = false,
    /** null 이 성공, 그 외 문자열은 입력 아래 빨갛게 띄우는 오류 문구 (ISSUE-12/39). */
    val onConfirm: (String) -> String?
) {
    var text by mutableStateOf(initial)
}

@Composable
private fun ValueEditorDialog(editor: ValueEditor, onDismiss: () -> Unit) {
    // ISSUE-39#1: error 를 set 만 하고 렌더하지 않아 저장 버튼이 조용히 실패하던 것을
    // 필드 아래 오류 문구 + isError 테두리로 드러낸다.
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(editor.title, fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = editor.text,
                onValueChange = { editor.text = it; if (error != null) error = null },
                singleLine = true,
                isError = error != null,
                supportingText = error?.let {
                    { Text(it, color = AppColors.ErrorVivid) }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (editor.numeric) KeyboardType.Number else KeyboardType.Uri
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                colors = irisFieldColors()
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val e = editor.onConfirm(editor.text.trim())
                if (e == null) onDismiss() else error = e
            }) { Text("저장", fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
