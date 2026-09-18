package party.qwer.irisgui.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.AppState
import party.qwer.irisgui.RuntimeLog
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.models.NotificationEvent

/**
 * LogsScreen — 모드 공용 로그 화면.
 *   행1: 메시지 발송 테스트
 *   행2~: 수신된 메시지 목록
 *
 * 수신 메시지 소스는 모드별로 다르지만 동일 형태(카드)로 렌더링한다.
 *   ROOT_ADB : DB 로그(AppState.lastChatLogs)
 *   NON_ROOT : 수신 알림(AppState.notificationHistory)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen() {
    val mode = AppModeManager.currentMode
    val scope = rememberCoroutineScope()
    var testRoom by rememberSaveable { mutableStateOf("") }
    var testMessage by rememberSaveable { mutableStateOf("") }
    var testResult by rememberSaveable { mutableStateOf<String?>(null) }
    var rooms by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    // ROOT_ADB: daemon 에서 방 목록 + DB 로그 폴링. NON_ROOT: 저장 방/알림 사용(동기).
    //
    // 주의: ROOT_ADB 의 채팅 로그는 app_process(별개 프로세스)의 AppState.lastChatLogs 에 담기므로
    // UI 프로세스의 AppState.lastChatLogs 는 항상 비어 있다. 반드시 daemon HTTP로 가져와야 한다.
    val messages = remember { mutableStateListOf<AppState.AppMessage>() }
    var runtimeLogs by remember { mutableStateOf<List<RuntimeLog.Entry>>(RuntimeLog.snapshot(80)) }
    /** 실행 로그 통의 펼침/접힘. 탭 전환/회전에서도 유지. */
    var logsExpanded by rememberSaveable { mutableStateOf(true) }

    if (mode == AppMode.ROOT_ADB) {
        LaunchedEffect(Unit) {
            while (true) {
                rooms = AdbProcessClient.fetchRooms().map { it.id to (it.name ?: "") }
                // DB 로그와 데몬 실행 로그는 같은 응답(/process-status)에 함께 온다.
                val processStatus = AdbProcessClient.queryStatus()
                messages.clear()
                messages.addAll(
                    (processStatus?.last_logs ?: emptyList()).map { log ->
                        AppState.AppMessage(
                            id = log["_id"] ?: (log["created_at"] ?: "").toString(),
                            roomName = log["room_name"]?.takeIf { it.isNotBlank() } ?: log["chat_id"] ?: "?",
                            senderName = log["user_name"]?.takeIf { it.isNotBlank() } ?: log["user_id"] ?: "?",
                            text = log["message"] ?: "",
                            timeMs = (log["created_at"]?.toLongOrNull() ?: 0L),
                            isGroup = false
                        )
                    }.sortedByDescending { it.timeMs }
                )
                runtimeLogs = (RuntimeLog.snapshot(60) + (processStatus?.logs ?: emptyList()))
                    .sortedByDescending { it.timeMs }
                    .take(120)
                delay(3000)
            }
        }
    } else {
        LaunchedEffect(Unit) {
            while (true) {
                rooms = AppState.storedRooms.map { it.id.ifBlank { it.name } to it.name }
                messages.clear()
                messages.addAll(
                    AppState.notificationHistory.map {
                        AppState.AppMessage(
                            id = "${it.timestamp}:${it.roomId.ifBlank { it.room }}",
                            roomName = it.room,
                            senderName = it.senderName,
                            text = it.text,
                            timeMs = it.timestamp,
                            isGroup = it.isGroupChat
                        )
                    }.sortedByDescending { it.timeMs }
                )
                runtimeLogs = RuntimeLog.snapshot(100)
                delay(1500)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 14.dp)
    ) {
        item(key = "send_test") {
            SendTestCard(
                room = testRoom,
                onRoomChange = { testRoom = it },
                message = testMessage,
                onMessageChange = { testMessage = it },
                roomOptions = rooms,
                result = testResult,
                onSend = {
                    scope.launch {
                        testResult = null
                        val code = AdbProcessClient.sendReply(testRoom, testMessage)
                        testResult = when {
                            code in 200..299 -> "✅ 전송 완료"
                            code == -1 -> "❌ 연결 실패 (서비스 실행 중인지 확인)"
                            else -> "❌ 실패: HTTP $code"
                        }
                    }
                }
            )
        }

        // ── 실행 로그 — 서비스/데몬의 출력 로그 전체. 고정 높이 통에서 스크롤. ─────
        // 수신 메시지(DB 로그)보다 먼저(두 번째) 배치한다. 최근 50줄만 노출한다.
        // 헤더 탭으로 통 전체를 펼침/접힘 할 수 있다.
        item(key = "runtime_header") {
            SectionTitle(
                "실행 로그",
                icon = Icons.Default.Info,
                count = runtimeLogs.size.coerceAtMost(50),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { logsExpanded = !logsExpanded },
                trailing = {
                    Icon(
                        if (logsExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (logsExpanded) "접기" else "펼치기",
                        tint = AppColors.TextSub,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )
        }
        if (logsExpanded) {
            if (runtimeLogs.isEmpty()) {
                item(key = "runtime_empty") {
                    Text(
                        "남겨진 로그가 없습니다. 서비스를 켜면 동작 정보가 여기에 기록됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSub
                    )
                }
            } else {
                item(key = "runtime_block") {
                    RuntimeLogBlock(runtimeLogs.take(50))
                }
            }
        }

        // ── 수신 메시지(DB 로그) — 마지막 ────────────────
        item(key = "recv_header") {
            SectionTitle("수신 메시지", icon = Icons.Default.Chat, count = messages.size)
        }
        if (messages.isEmpty()) {
            item(key = "recv_empty") {
                Text("수신된 메시지가 없습니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            }
        } else {
            items(messages, key = { "${it.id}_${it.roomName}" }) { msg ->
                MessageCard(msg)
            }
        }
    }
}

/**
 * 실행 로그 콘솔 블록 — 통 하나가 곧 하나의 라벨.
 * 고정이미(260dp) 안에서 스크롤하며, 최근 50줄을 각 행으로 표시한다(줄마다 시각 · 소스 · 메시지).
 * 우측에 스크롤바 썸을 항상 노출한다(접을 수 있는 블록의 크기/위치 표식).
 */
@Composable
private fun RuntimeLogBlock(entries: List<RuntimeLog.Entry>) {
    val scroll = rememberScrollState()
    SurfaceCard(contentPadding = PaddingValues(14.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            Text(
                buildAnnotatedString {
                    entries.forEachIndexed { i, e ->
                        val levelColor = when (e.level) {
                            "ERROR" -> AppColors.ErrorVivid
                            "WARN" -> AppColors.WarningVivid
                            else -> AppColors.TextSub
                        }
                        withStyle(SpanStyle(color = AppColors.TextSub)) { append(timeLabel(e.timeMs)) }
                        append(" ")
                        withStyle(SpanStyle(color = levelColor, fontWeight = FontWeight.Bold)) { append(e.source) }
                        append(" ")
                        withStyle(SpanStyle(color = AppColors.TextMain)) { append(e.message) }
                        if (i != entries.lastIndex) append("\n")
                    }
                },
                modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            )

            // 스크롤바 — 내용량이 화면보다 많을 때만. 썸 높이로 남은 분량을, 위치로 현재 지점을 나타낸다.
            // value/maxValue 는 Canvas 의 드로잉 람다에서 읽으면 스냅샷 관찰이 안 되어
            // recomposition 이 안 일어난다. composable 스코프에서 먼저 읽는다.
            val scrollMax = scroll.maxValue
            val scrollValue = scroll.value
            if (scrollMax > 0) {
                Canvas(modifier = Modifier.matchParentSize()) {
                    val track = 5.dp.toPx()
                    val x = size.width - track
                    val viewport = size.height
                    val thumbH = (viewport * viewport / (viewport + scrollMax)).coerceAtLeast(28.dp.toPx())
                    val thumbY = (viewport - thumbH) * (scrollValue.toFloat() / scrollMax)
                    drawRoundRect(
                        color = AppColors.CardBorder,
                        topLeft = Offset(x, 0f),
                        size = Size(track, viewport)
                    )
                    drawRoundRect(
                        color = AppColors.TextSub.copy(alpha = 0.55f),
                        topLeft = Offset(x, thumbY),
                        size = Size(track, thumbH)
                    )
                }
            }
        }
    }
}

/** 메시지 발송 테스트 카드. */
@Composable
private fun SendTestCard(
    room: String,
    onRoomChange: (String) -> Unit,
    message: String,
    onMessageChange: (String) -> Unit,
    roomOptions: List<Pair<String, String>>,
    result: String?,
    onSend: () -> Unit
) {
    SectionTitle("메시지 발송 테스트", icon = Icons.Default.Send)
    Spacer(modifier = Modifier.height(10.dp))
    SurfaceCard(contentPadding = PaddingValues(16.dp)) {
        RoomDropdownField(selectedId = room, rooms = roomOptions, onSelect = onRoomChange)
        Spacer(modifier = Modifier.height(10.dp))
        SettingsField(label = "메시지", value = message, onValueChange = onMessageChange)
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = onSend,
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = Color.White)
        ) {
            Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("보내기", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
        if (result != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                result,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.startsWith("✅")) AppColors.SuccessVivid else AppColors.ErrorVivid
            )
        }
    }
}

/** 수신 메시지 카드. */
@Composable
private fun MessageCard(msg: AppState.AppMessage) {
    SurfaceCard(contentPadding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier.size(32.dp).clip(CircleShape)
                    .background(if (msg.isGroup) AppColors.SuccessVivid.copy(alpha = 0.15f) else AppColors.InputBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (msg.isGroup) Icons.Default.Groups else Icons.Default.Person,
                    contentDescription = null,
                    tint = if (msg.isGroup) AppColors.SuccessVivid else AppColors.PrimaryAccent,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(msg.roomName, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    color = AppColors.TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(msg.senderName, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                timeLabel(msg.timeMs),
                style = MaterialTheme.typography.labelSmall,
                color = AppColors.TextSub
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(msg.text, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMain,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

private fun timeLabel(ms: Long): String =
    if (ms <= 0) "" else android.text.format.DateFormat.format("HH:mm", ms).toString()
