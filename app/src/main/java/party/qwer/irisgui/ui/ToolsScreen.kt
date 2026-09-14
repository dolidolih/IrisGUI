package party.qwer.irisgui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.models.QueryResponse

/**
 * ToolsScreen — ROOT_ADB 의 도구 탭.
 *
 * SQL 쿼리(AdbServer /query, KakaoTalk.db) + 빠른 쿼리 + 결과 뷰를 한 화면에 담는다.
 * 기존 AdbQueryScreen 의 후계 화면.
 */
@Composable
fun ToolsScreen() {
    val scope = rememberCoroutineScope()
    var queryText by remember { mutableStateOf("") }
    var queryResult by remember { mutableStateOf<QueryResponse?>(null) }
    var queryError by remember { mutableStateOf<String?>(null) }
    var isQuerying by remember { mutableStateOf(false) }

    val quickQueries = listOf(
        "chat_logs 최근" to "SELECT * FROM chat_logs ORDER BY _id DESC LIMIT 20",
        "chat_rooms" to "SELECT * FROM chat_rooms LIMIT 10",
        "로그 개수" to "SELECT COUNT(*) as count FROM chat_logs",
        "텍스트 검색" to "SELECT * FROM chat_logs WHERE message LIKE '%text%' ORDER BY _id DESC LIMIT 10"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = AppColors.PrimaryAccent)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "SQL 쿼리 (KakaoTalk.db)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.TextMain
                        )
                    }
                    TextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        modifier = Modifier.fillMaxWidth().height(110.dp),
                        shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = AppColors.InputBg,
                            unfocusedContainerColor = AppColors.InputBg,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
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
                                    queryResult = AdbProcessClient.executeQuery(queryText)
                                    isQuerying = false
                                    queryResult?.error?.let { queryError = "에러: $it" }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(8.dp),
                        enabled = !isQuerying && queryText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
                    ) {
                        if (isQuerying) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("실행 중...", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("쿼리 실행", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Text("빠른 쿼리", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.PrimaryAccent)
        }
        items(quickQueries) { (label, sql) ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { queryText = sql },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
                        color = AppColors.PrimaryAccent,
                        modifier = Modifier.width(80.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        sql,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = AppColors.TextSub,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

        if (queryError != null) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = AppColors.ErrorVivid, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(queryError!!, style = MaterialTheme.typography.bodySmall, color = AppColors.ErrorVivid)
                    }
                }
            }
        }

        if (queryResult != null && queryResult!!.data.isNotEmpty()) {
            item {
                Text(
                    "결과 (${queryResult!!.data.size}행)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = AppColors.PrimaryAccent
                )
            }
            items(queryResult!!.data) { row -> QueryResultCard(row) }
        } else if (queryResult != null && queryError == null) {
            item {
                Text("쿼리 결과: 0행", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            }
        }
    }
}

/** 한 행을 요약 + 펼침 상세로 표시하는 공용 결과 카드 */
@Composable
internal fun QueryResultCard(row: Map<String, String?>) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.InputBg, RoundedCornerShape(8.dp))
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
