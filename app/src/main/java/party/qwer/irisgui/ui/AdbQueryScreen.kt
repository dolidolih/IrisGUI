package party.qwer.irisgui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.models.QueryResponse

/**
 * ADB 모드 DB 쿼리 화면
 *
 * AdbServer의 /query API를 통해 KakaoTalk.db에 SQL 쿼리를 실행하고 결과를 표시한다.
 * 원본 Iris의 /query 엔드포인트를 앱 UI로 제공.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbQueryScreen() {
    val scope = rememberCoroutineScope()

    var queryText by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<QueryResponse?>(null) }
    var isQuerying by remember { mutableStateOf(false) }
    var queryError by remember { mutableStateOf<String?>(null) }

    // 빠른 쿼리 템플릿
    val quickQueries = listOf(
        "SELECT * FROM chat_logs ORDER BY _id DESC LIMIT 20",
        "SELECT * FROM chat_rooms LIMIT 10",
        "SELECT COUNT(*) as count FROM chat_logs",
        "SELECT * FROM chat_logs WHERE message LIKE '%text%' ORDER BY _id DESC LIMIT 10"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 헤더 ──────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Storage, contentDescription = null, tint = AppColors.PrimaryAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("DB 쿼리", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }
            Text("KakaoTalk.db에 SQL 쿼리를 직접 실행합니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
        }

        // ── 쿼리 입력 ─────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("SQL 쿼리", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)

                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        modifier = Modifier.fillMaxWidth().height(100.dp),
                        shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = AppColors.PrimaryAccent,
                            unfocusedBorderColor = AppColors.TextSub.copy(alpha = 0.3f),
                            focusedTextColor = AppColors.PrimaryAccent,
                            unfocusedTextColor = AppColors.PrimaryAccent,
                            cursorColor = AppColors.PrimaryAccent
                        )
                    )

                    Button(
                        onClick = {
                            if (queryText.isNotBlank()) {
                                scope.launch {
                                    isQuerying = true
                                    queryError = null
                                    queryResult = null
                                    val result = AdbProcessClient.executeQuery(queryText)
                                    queryResult = result
                                    isQuerying = false
                                    if (result?.error != null) {
                                        queryError = "에러: ${result.error}"
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isQuerying && queryText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
                    ) {
                        if (isQuerying) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("실행 중...", fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("쿼리 실행", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // ── 빠른 쿼리 ─────────────────────────────────
        item {
            Text("빠른 쿼리", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.PrimaryAccent)
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                quickQueries.forEach { sql ->
                    FilterChip(
                        onClick = {
                            queryText = sql
                        },
                        label = {
                            Text(
                                sql.take(50) + if (sql.length > 50) "..." else "",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold
                            )
                        },
                        shape = RoundedCornerShape(8.dp),
                        selected = queryText == sql,
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = AppColors.PrimaryAccent.copy(alpha = 0.3f),
                            selectedLabelColor = AppColors.PrimaryAccent,
                            containerColor = AppColors.InputBg,
                            labelColor = AppColors.TextMain
                        )
                    )
                }
            }
        }

        // ── 에러 메시지 ───────────────────────────────
        item {
            if (queryError != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AppColors.ErrorVivid, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(queryError!!, style = MaterialTheme.typography.bodySmall, color = AppColors.ErrorVivid)
                    }
                }
            }
        }

        // ── 쿼리 결과 ─────────────────────────────────
        if (queryResult != null && queryResult!!.data.isNotEmpty()) {
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "결과 (${queryResult!!.data.size}개 행)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = AppColors.PrimaryAccent
                    )
                }
            }

            items(queryResult!!.data) { row ->
                QueryResultCard(row)
            }
        } else if (queryResult != null && queryResult!!.data.isEmpty() && queryError == null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
                ) {
                    Text(
                        "쿼리 결과: 0개 행",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSub
                    )
                }
            }
        }
    }
}

@Composable
private fun QueryResultCard(row: Map<String, String?>) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 첫 번째 열을 요약으로 표시
            row.entries.firstOrNull()?.let { (key, value) ->
                Text(
                    "$key: $value",
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextMain,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(AppColors.InputBg)
                        .padding(8.dp)
                ) {
                    Text(
                        text = row.entries.joinToString("\n") { "${it.key}: ${it.value ?: "null"}" },
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
