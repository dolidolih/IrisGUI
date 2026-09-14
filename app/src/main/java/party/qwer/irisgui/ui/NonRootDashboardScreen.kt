package party.qwer.irisgui.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppState

/**
 * NonRootDashboardScreen — NON_ROOT 모드 대시보드.
 *
 * 권한 스트립 + NLS 수신 상태 + 답장 테스트. (모드 변경은 TopAppBar 의 모드 시트에서 수행)
 */
@Composable
fun NonRootDashboardScreen(permission: PermissionStatus, mode: AppMode) {
    val context = LocalContext.current
    var testRoom by rememberSaveable { mutableStateOf("") }
    var testMessage by remember { mutableStateOf("") }
    var testResult by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 답장 가능 방 목록: 최근 알림에서 확보한 StoredRoom 리스트.
    // StoredRoom.name = 방이름, id = chatid(알림 tag가 비면 있으면 빈칸). 답장 전송키는
    // ReplyManager.replyActions 에 방이름/방id 양쪽 키가 걸려 있으므로 id→없으면 name을 쓴다.
    val roomOptions = remember { derivedStateOf { AppState.storedRooms.map { Pair(it.id.ifBlank { it.name }, it.name) } } }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 상태 요약 ──────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
                border = BorderStroke(1.dp, AppColors.CardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = AppColors.PrimaryAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("NLS 알림 수신", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            if (permission.nlsOk) "허용됨" else "필요",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (permission.nlsOk) AppColors.SuccessVivid else AppColors.ErrorVivid
                        )
                    }
                    Text(
                        "엔드포인트 ${AppConfig.webEndpoint.ifEmpty { "미설정" }} · 발송 주기 ${AppConfig.sendRate}ms",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSub
                    )
                }
            }
        }

        // ── 권한 스트립 (미해결 시에만) ─────────────────
        item {
            PermissionStrip(status = permission, mode = mode)
        }

        // ── 답장 테스트 ────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg),
                border = BorderStroke(1.dp, AppColors.CardBorder)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, tint = AppColors.PrimaryAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("답장 테스트", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                    }
                    RoomDropdownField(
                        selectedId = testRoom,
                        rooms = roomOptions.value,
                        onSelect = { testRoom = it }
                    )
                    SettingsField(
                        label = "메시지",
                        value = testMessage,
                        onValueChange = { testMessage = it }
                    )
                    Button(
                        onClick = {
                            if (testRoom.isNotBlank() && testMessage.isNotBlank()) {
                                scope.launch {
                                    testResult = null
                                    val code = withContext(Dispatchers.IO) {
                                        runCatching {
                                            // C4: JSON 인젝션 방지 — 명시 이스케이프
                                            val safeRoom = testRoom.replace("\\", "\\\\").replace("\"", "\\\"")
                                            val safeMsg = testMessage.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                                            val body = """{"type":"text","room":"$safeRoom","data":"$safeMsg"}"""
                                                .toRequestBody("application/json".toMediaType())
                                            OkHttpClient().newCall(
                                                Request.Builder()
                                                    .url("http://127.0.0.1:${AppConfig.serverPort}/reply")
                                                    .post(body)
                                                    .build()
                                            ).execute().use { it.code }
                                        }
                                    }.getOrDefault(-1)
                                    testResult = when {
                                        code in 200..299 -> "✅ 전송 완료"
                                        code == -1 -> "❌ 연결 실패 (서비스 실행 중인지 확인)"
                                        else -> "❌ 실패: HTTP $code"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = Color.White)
                    ) {
                        Text("메시지 보내기", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                    testResult?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (it.startsWith("✅")) AppColors.SuccessVivid else AppColors.ErrorVivid
                        )
                    }
                }
            }
        }
    }
}
