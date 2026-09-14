package party.qwer.irisgui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.AppState
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

    // ROOT_ADB: daemon 에서 방 목록 폴링. NON_ROOT: 저장 방 사용(동기).
    if (mode == AppMode.ROOT_ADB) {
        LaunchedEffect(Unit) {
            while (true) {
                rooms = AdbProcessClient.fetchRooms().map { it.id to (it.name ?: "") }
                delay(5000)
            }
        }
    } else {
        LaunchedEffect(AppState.storedRooms.size) {
            rooms = AppState.storedRooms.map { it.id.ifBlank { it.name } to it.name }
        }
    }

    // 수신 메시지: 모드별 소스를 AppMessage 로 정규화
    val messages = remember { mutableStateListOf<AppState.AppMessage>() }
    LaunchedEffect(mode, AppState.notificationHistory.size, AppState.lastChatLogs.size) {
        messages.clear()
        when (mode) {
            AppMode.NON_ROOT -> messages.addAll(
                AppState.notificationHistory.map {
                    AppState.AppMessage(
                        id = "${it.timestamp}:${it.roomId.ifBlank { it.room }}",
                        roomName = it.room,
                        senderName = it.senderName,
                        text = it.text,
                        timeMs = it.timestamp,
                        isGroup = it.isGroupChat
                    )
                }
            )
            AppMode.ROOT_ADB -> messages.addAll(
                AppState.lastChatLogs.map { log ->
                    val roomName = log["room_name"]?.takeIf { it.isNotBlank() } ?: log["chat_id"] ?: "?"
                    val sender = log["user_name"]?.takeIf { it.isNotBlank() } ?: log["user_id"] ?: "?"
                    AppState.AppMessage(
                        id = log["_id"] ?: (log["created_at"] ?: "").toString(),
                        roomName = roomName,
                        senderName = sender,
                        text = log["message"] ?: "",
                        timeMs = (log["created_at"]?.toLongOrNull() ?: 0L),
                        isGroup = false
                    )
                }
            )
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 12.dp)
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

        item(key = "recv_header") {
            SectionHeader(icon = Icons.Default.Chat, title = "수신 메시지 (${messages.size})")
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
    SurfaceCard(contentPadding = PaddingValues(16.dp)) {
        SectionHeader(icon = Icons.Default.Send, title = "메시지 발송 테스트")
        Spacer(modifier = Modifier.height(10.dp))
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
