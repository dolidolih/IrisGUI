package party.qwer.irisgui.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import android.widget.Toast
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppState
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.backend.DaemonLauncher
import party.qwer.irisgui.models.*

/**
 * ADB 모드 대시보드 화면
 *
 * app_process(AdbServer)의 실제 상태를 표시하고,
 * 설정 요약, DB 로그, 알림 히스토리, 답장 테스트를 제공한다.
 * 원본 Iris의 대시보드를 앱 UI로 재현한 것.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbDashboardScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val permission = rememberPermissionStatus()
    val mode = AppMode.ROOT_ADB

    // 상태 정보
    var processStatus by remember { mutableStateOf<AdbProcessStatusResponse?>(null) }
    var dashboardStatus by remember { mutableStateOf<DashboardStatusResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var lastUpdated by remember { mutableStateOf<String>("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var rooms by remember { mutableStateOf<List<RoomInfo>>(emptyList()) }
    // 방 목록은 기본 접힘(드롭다운) — 화면을 불필요하게 차지하지 않도록
    var roomsExpanded by rememberSaveable { mutableStateOf(false) }

    // 답장 테스트 입력은 화면 레벨로 hoist — 3초 폴링 재구성에도 입력이 보존되고,
    // 방 목록에서 방을 누르면 방 ID가 여기로 채워진다(rememberSaveable: 회전/이탈에도 유지).
    var testRoom by rememberSaveable { mutableStateOf("") }
    var testMessage by rememberSaveable { mutableStateOf("") }

    // ── 자율 기동(graceful launch) ────────────────────────────────────
    // 데몬 미실행 상태를 폴링 첫 응답에서 감지하면 화면 진입당 1회 startDaemon을
    // 자동 시도한다. (redroid는 외부 PC 없이 LocalAdb PATH2로만 root 확보가
    // 가능하므로, 사용자가 매번 시작 버튼을 누르지 않아도 autonomous하게 띄운다.)
    // 이미 올라와 있으면 startDaemon이 AlreadyRunning이라 안전하고,
    // 최초 응답에서 이미 running이면 autoLaunchTried만 세팅하고 시도하지 않는다 —
    // 그래야 이후 사용자가 수동 정지해도 다시 되살리지 않는다.
    var autoLaunchTried by remember { mutableStateOf(false) }
    var autoLaunching by remember { mutableStateOf(false) }

    // 3초마다 상태 폴링
    LaunchedEffect(Unit) {
        while (true) {
            val status = AdbProcessClient.queryStatus()
            processStatus = status

            if (!autoLaunchTried) {
                if (status?.server_running == true) {
                    autoLaunchTried = true   // 이미 떠 있음 → 이후 수동 정지를 되살리지 않음
                } else if (!autoLaunching) {
                    autoLaunchTried = true
                    autoLaunching = true
                    scope.launch {
                        val result = DaemonLauncher.startDaemon(context)
                        autoLaunching = false
                        if (result is DaemonLauncher.StartResult.Failed) {
                            errorMessage = DaemonLauncher.failureMessage(result.reason, result.detail)
                        }
                    }
                }
            }

            dashboardStatus = AdbProcessClient.fetchDashboardStatus()
            isLoading = false
            lastUpdated = "마지막 확인: ${android.text.format.DateFormat.format("kk:mm:ss", System.currentTimeMillis())}"
            delay(3000)
        }
    }

    // 방 목록은 비용이 커서(방마다 이름 해석) 폴링과 분리 — 진입 시 1회 로드.
    // 헤더의 새로고침 버튼으로 갱신한다.
    suspend fun loadRooms() { rooms = AdbProcessClient.fetchRooms() }
    LaunchedEffect(Unit) { loadRooms() }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 헤더 ──────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Dashboard, contentDescription = null, tint = AppColors.PrimaryAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ADB 대시보드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }
            Text("app_process(AdbServer)의 실시간 상태", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
        }

        // ── 서버 상태 ─────────────────────────────────
        item {
            ServerStatusCard(
                status = processStatus,
                isLoading = isLoading,
                lastUpdated = lastUpdated,
                onToggle = {
                    scope.launch {
                        if (processStatus?.server_running == true) {
                            AdbProcessClient.stopProcess()
                        } else {
                            AdbProcessClient.restartProcess()
                        }
                    }
                },
                onStart = {
                    scope.launch {
                        val result = DaemonLauncher.startDaemon(context)
                        Toast.makeText(
                            context,
                            when (result) {
                                is DaemonLauncher.StartResult.AlreadyRunning -> "데몬은 이미 실행 중입니다."
                                is DaemonLauncher.StartResult.Started -> "데몬을 시작했습니다."
                                is DaemonLauncher.StartResult.Failed ->
                                    DaemonLauncher.failureMessage(result.reason, result.detail)
                            },
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            )
        }

        // ── 권한 배지 (미해결 시에만 등장) ────────────────
        item {
            PermissionStrip(status = permission, mode = mode)
        }
        item {
            DbObservingCard(dashboardStatus)
        }

        // ── 방 목록 (chat_rooms, 한글 방이름) — 접이식 드롭다운, 최근 업데이트순 ──
        item(key = "rooms_header") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { roomsExpanded = !roomsExpanded }
            ) {
                Icon(
                    if (roomsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (roomsExpanded) "접기" else "펼치기",
                    tint = AppColors.PrimaryAccent
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("방 목록 (${rooms.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                Spacer(modifier = Modifier.weight(1f))
                if (roomsExpanded) {
                    TextButton(onClick = { scope.launch { loadRooms() } }) {
                        Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = AppColors.PrimaryAccent, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("새로고침", color = AppColors.PrimaryAccent, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (roomsExpanded) {
            if (rooms.isEmpty()) {
                item(key = "rooms_empty") {
                    Text("방 목록이 없습니다. app_process 실행 후 [새로고침]을 누르세요.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                }
            } else {
                items(rooms, key = { "room_${it.id}" }) { room ->
                    RoomCard(room) { testRoom = room.id }
                }
            }
        }

        // ── 답장 테스트 (입력 상태는 화면 레벨 hoist → 폴링에도 보존) ──
        item(key = "replytest") {
            ReplyTestCard(
                room = testRoom,
                onRoomChange = { testRoom = it },
                message = testMessage,
                onMessageChange = { testMessage = it },
                roomOptions = rooms.map { Pair(it.id, it.name ?: "") }
            )
        }

        // ── DB 로그 (가변 길이 → 맨 아래 배치, 안정 key로 입력 보존) ──
        if (!dashboardStatus?.lastLogs.isNullOrEmpty()) {
            item(key = "logs_header") {
                Text("DB 로그 (${dashboardStatus?.lastLogs?.size ?: 0}개)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }
            items(dashboardStatus?.lastLogs ?: emptyList(), key = { it["_id"] ?: it.hashCode().toString() }) { log ->
                DbLogCard(log)
            }
        }

        // ── 에러 메시지 ───────────────────────────────
        item {
            if (errorMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AppColors.ErrorVivid, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(errorMessage!!, style = MaterialTheme.typography.bodySmall, color = AppColors.ErrorVivid)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerStatusCard(
    status: AdbProcessStatusResponse?,
    isLoading: Boolean,
    lastUpdated: String,
    onToggle: () -> Unit,
    onStart: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
        border = BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 상태 표시등
                Box(
                    modifier = Modifier.size(12.dp)
                        .background(
                            color = if (status?.server_running == true) AppColors.SuccessVivid else AppColors.ErrorVivid,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (status?.server_running != true) {
                        Icon(
                            Icons.Default.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(8.dp),
                            tint = Color.White
                        )
                    } else {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(8.dp),
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = if (status?.server_running == true) "app_process 실행 중" else "app_process 미실행",
                    fontWeight = FontWeight.Bold,
                    color = if (status?.server_running == true) AppColors.SuccessVivid else AppColors.ErrorVivid
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(lastUpdated, style = MaterialTheme.typography.labelSmall, color = AppColors.TextSub)
            }

            if (status != null) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = AppColors.TextSub.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))

                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusRow("포트", "${status.port}")
                    StatusRow("DB 관찰", if (status.db_observing) "✅ 활성" else "❌ 비활성")
                    StatusRow("봇 ID", if (status.bot_id > 0) status.bot_id.toString() else "미감지")
                    StatusRow("봇 이름", status.bot_name)
                }

                // 정지/시작/재시작 버튼
                Spacer(modifier = Modifier.height(8.dp))
                ControlButtons(status.server_running, onToggle, onStart)
            } else if (isLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("상태 확인 중...", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "상태 확인 불가 — app_process가 실행 중이지 않거나 포트가 다릅니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.ErrorVivid.copy(alpha = 0.8f)
                )
                Spacer(modifier = Modifier.height(8.dp))
                ControlButtons(false, onToggle, onStart)
            }
        }
    }
}

/**
 * 데몬 제어 버튼 — 실행 중: [정지] / 미실행: [시작(자체 ADB 기동)] + [재시작(HTTP)]
 */
@Composable
private fun ControlButtons(running: Boolean, onToggle: () -> Unit, onStart: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (running) {
            Button(
                onClick = onToggle,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.ErrorVivid,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "app_process 정지",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize
                )
            }
        } else {
            Button(
                onClick = onStart,
                modifier = Modifier.weight(1f).height(36.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.PrimaryAccent,
                    contentColor = Color.White
                )
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "app_process 시작",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize
                )
            }
            Button(
                onClick = onToggle,
                modifier = Modifier.height(36.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = AppColors.InputBg,
                    contentColor = AppColors.TextSub
                )
            ) {
                Icon(Icons.Default.Replay, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    "재시작",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = MaterialTheme.typography.bodySmall.fontSize
                )
            }
        }
    }
}

@Composable
private fun DbObservingCard(dashboardStatus: DashboardStatusResponse?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
        border = BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.History, contentDescription = null, tint = AppColors.PrimaryAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("DB 관찰 상태", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }

            if (dashboardStatus != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(10.dp)
                            .background(
                                color = if (dashboardStatus.isObserving) AppColors.SuccessVivid else AppColors.ErrorVivid,
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            modifier = Modifier.size(6.dp),
                            tint = Color.White
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        if (dashboardStatus.isObserving) "DB 관찰 중" else "DB 관찰 중지",
                        fontWeight = FontWeight.SemiBold,
                        color = if (dashboardStatus.isObserving) AppColors.SuccessVivid else AppColors.ErrorVivid
                    )
                }
                Text(dashboardStatus.statusMessage, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text("상태 조회 중...", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            }
        }
    }
}

@Composable
private fun DbLogCard(log: Map<String, String?>) {
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
        border = BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // id 대신 방이름/발신자명 우선 표시(없으면 id 폴백)
                val roomName = log["room_name"]?.takeIf { it.isNotBlank() } ?: log["chat_id"] ?: "?"
                val userName = log["user_name"]?.takeIf { it.isNotBlank() } ?: log["user_id"] ?: "?"
                Box(
                    modifier = Modifier.size(32.dp).clip(CircleShape).background(AppColors.InputBg),
                    contentAlignment = Alignment.Center
                ) {
                    Text(roomName.take(2), style = MaterialTheme.typography.bodySmall.copy(color = AppColors.PrimaryAccent, fontWeight = FontWeight.Bold))
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(roomName, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold, color = AppColors.TextMain), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(userName, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            log["message"]?.let { msg ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall.copy(color = AppColors.TextMain),
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AppColors.InputBg)
                        .padding(12.dp)
                ) {
                    Text(
                        text = log.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSub,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 50
                    )
                }
            }
        }
    }
}

@Composable
private fun RoomCard(room: RoomInfo, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
        border = BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Row(modifier = Modifier.padding(14.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val display = room.name?.takeIf { it.isNotBlank() } ?: "(이름 없음)"
            Box(
                modifier = Modifier.size(36.dp).clip(CircleShape).background(AppColors.InputBg),
                contentAlignment = Alignment.Center
            ) {
                Text(display.take(2), style = MaterialTheme.typography.bodyMedium.copy(color = AppColors.PrimaryAccent, fontWeight = FontWeight.Bold))
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(display, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold, color = AppColors.TextMain), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(room.id, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.Send, contentDescription = "이 방으로 답장 테스트", tint = AppColors.TextSub, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ReplyTestCard(
    room: String,
    onRoomChange: (String) -> Unit,
    message: String,
    onMessageChange: (String) -> Unit,
    roomOptions: List<Pair<String, String>> = emptyList(),
) {
    val testRoom = room
    val testMessage = message
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
        border = BorderStroke(1.dp, AppColors.CardBorder)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Send, contentDescription = null, tint = AppColors.PrimaryAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("답장 테스트", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }

            RoomDropdownField(
                selectedId = testRoom,
                rooms = roomOptions,
                onSelect = onRoomChange
            )

            TextField(
                value = testMessage,
                onValueChange = onMessageChange,
                label = { Text("메시지") },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppColors.InputBg,
                    unfocusedContainerColor = AppColors.InputBg,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    focusedTextColor = AppColors.TextMain,
                    unfocusedTextColor = AppColors.TextMain,
                    focusedLabelColor = AppColors.PrimaryAccent,
                    unfocusedLabelColor = AppColors.TextSub,
                    cursorColor = AppColors.PrimaryAccent
                )
            )

            Button(
                onClick = {
                    scope.launch {
                        testResult = null
                        // 네트워크 호출은 AdbProcessClient(IO 디스패처)로 위임.
                        // 직접 OkHttp를 Main 스레드(scope.launch 기본)에서 호출하면
                        // NetworkOnMainThreadException(메시지 null)이 나서 "오류: null"로 표시됐다.
                        val code = AdbProcessClient.sendReply(testRoom, testMessage)
                        testResult = when {
                            code in 200..299 -> "✅ 전송 완료"
                            code == -1 -> "❌ 연결 실패 (app_process 실행 중인지 확인)"
                            else -> "❌ 실패: HTTP $code"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = Color.White)
            ) {
                Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("메시지 보내기", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
            }

            if (testResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
                ) {
                    Text(
                        testResult!!,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (testResult!!.startsWith("✅")) AppColors.SuccessVivid else AppColors.ErrorVivid
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextMain)
    }
}
