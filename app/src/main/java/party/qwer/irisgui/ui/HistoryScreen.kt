package party.qwer.irisgui.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppState
import party.qwer.irisgui.models.NotificationEvent
import party.qwer.irisgui.models.StoredRoom

@Composable
fun HistoryScreen() {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 답장 가능한 방 목록 ─────────────────────────
        item {
            Text("답장 가능한 방 목록", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
        }

        if (AppState.storedRooms.isEmpty()) {
            item { Text("아직 저장된 방이 없습니다.", color = AppColors.TextSub) }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
                ) {
                    Column(modifier = Modifier.padding(vertical = 8.dp)) {
                        AppState.storedRooms.forEach { room: StoredRoom ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier.size(40.dp).clip(CircleShape).background(AppColors.InputBg),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(room.name.take(1).uppercase(), color = AppColors.PrimaryAccent, fontWeight = FontWeight.Bold)
                                }
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(room.name, fontWeight = FontWeight.Bold, color = AppColors.TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("ID: ${room.id}", color = AppColors.TextSub, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── DB 로그 (루팅 모드) ─────────────────────────
        if (AppState.lastChatLogs.isNotEmpty()) {
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Text("DB 로그 (${AppState.lastChatLogs.size}개)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }

            items(AppState.lastChatLogs) { log ->
                var expanded by remember { mutableStateOf(false) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize()
                        .clickable { expanded = !expanded },
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("ID: ${log["_id"] ?: "?"}", fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                        Text("Room: ${log["chat_id"] ?: "?"}", color = AppColors.TextSub)
                        Text("User: ${log["user_id"] ?: "?"}", color = AppColors.TextSub)
                        log["message"]?.let { msg ->
                            Text("Message: $msg", color = AppColors.TextMain, maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis)
                        }
                        if (expanded) {
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppColors.InputBg).padding(16.dp)) {
                                Text(
                                    text = log.entries.joinToString("\n") { "${it.key}: ${it.value}" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = AppColors.TextSub,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 100
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── 알림 히스토리 ───────────────────────────────
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text("알림 히스토리", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
        }

        items<NotificationEvent>(AppState.notificationHistory) { event: NotificationEvent ->
            var expanded by remember { mutableStateOf(false) }

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize()
                    .clickable { expanded = !expanded },
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).background(if (event.isGroupChat) AppColors.SuccessVivid.copy(alpha=0.2f) else AppColors.PrimaryAccent.copy(alpha=0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(event.room.take(1).uppercase(), color = if (event.isGroupChat) AppColors.SuccessVivid else AppColors.PrimaryAccent, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(event.room, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(event.senderName, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = event.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = AppColors.TextMain,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 56.dp)
                    )

                    if (expanded) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Box(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(AppColors.InputBg).padding(16.dp)) {
                            Text(
                                text = event.rawDump,
                                style = MaterialTheme.typography.bodySmall,
                                color = AppColors.TextSub,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 100
                            )
                        }
                    }
                }
            }
        }
    }
}
