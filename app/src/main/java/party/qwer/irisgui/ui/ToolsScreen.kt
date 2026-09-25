package party.qwer.irisgui.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
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
    // ISSUE-39#4: "텍스트 검색" 이 literal word 'text' 를 넣던 것 — 검색어를 먼저 묻는다.
    var searchPrompt by remember { mutableStateOf(false) }
    var searchTerm by remember { mutableStateOf("") }

    val quickQueries = listOf(
        "chat_logs 최근" to "SELECT * FROM chat_logs ORDER BY _id DESC LIMIT 20",
        "chat_rooms" to "SELECT * FROM chat_rooms LIMIT 10",
        "로그 개수" to "SELECT COUNT(*) as count FROM chat_logs",
        "텍스트 검색" to "SELECT * FROM chat_logs WHERE message LIKE '%text%' ORDER BY _id DESC LIMIT 10"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 14.dp)
    ) {
        item {
            SectionTitle("SQL 쿼리 (KakaoTalk.db)", icon = Icons.Default.Search)
            Spacer(modifier = Modifier.height(10.dp))
            SurfaceCard(contentPadding = PaddingValues(16.dp)) {
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        modifier = Modifier.fillMaxWidth().height(130.dp),
                        shape = RoundedCornerShape(AppColors.FieldRadius),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        placeholder = { Text("SELECT * FROM chat_logs ORDER BY id DESC LIMIT 50", color = AppColors.TextMute) },
                        // 공용 필 채움과 똑같이 — 다른 탭 입력 필과 동일한 면/테두리/잉크.
                        colors = irisFieldColors()
                    )
                    Button(
                        onClick = {
                            if (queryText.isNotBlank()) {
                                scope.launch {
                                    isQuerying = true
                                    queryError = null
                                    val res = AdbProcessClient.executeQuery(queryText)
                                    isQuerying = false
                                    queryResult = res
                                    if (res == null) {
                                        // ISSUE-39#4: transport 실패가 빈 결과처럼
                                        // 보이던 것 — 명시 오류로 드러낸다.
                                        queryError = "쿼리 전송 실패 — 서비스 실행/ 연결 상태 확인"
                                    } else {
                                        res.error?.let { queryError = "에러: $it" }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = !isQuerying && queryText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = Color.White)
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
            SectionTitle("빠른 쿼리", icon = Icons.Default.Bolt)
            Spacer(modifier = Modifier.height(10.dp))
            SurfaceCard(contentPadding = PaddingValues(8.dp)) {
                quickQueries.forEachIndexed { i, (label, sql) ->
                    if (i > 0) Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                // 텍스트 검색은 검색어 입력을 받고 삽입한다.
                                if (label == "텍스트 검색") {
                                    searchTerm = ""
                                    searchPrompt = true
                                } else {
                                    queryText = sql
                                }
                            }
                            .background(AppColors.ConfigTile)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                            color = AppColors.PrimaryAccent,
                            modifier = Modifier.width(84.dp),
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
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
        }

        if (queryError != null) {
            item {
                SurfaceCard(contentPadding = PaddingValues(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconChip(icon = Icons.Default.Warning, tint = AppColors.ErrorVivid)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(queryError!!, style = MaterialTheme.typography.bodySmall, color = AppColors.ErrorVivid)
                    }
                }
            }
        }

        if (queryResult != null && queryResult!!.data.isNotEmpty()) {
            item {
                SectionTitle("결과", icon = Icons.Default.Article, count = queryResult!!.data.size)
            }
            items(queryResult!!.data) { row -> QueryResultCard(row) }
        } else if (queryResult != null && queryError == null) {
            item {
                SectionTitle("결과", icon = Icons.Default.Article, count = 0)
            }
        }
    }

    if (searchPrompt) {
        SearchTermDialog(
            term = searchTerm,
            onTermChange = { searchTerm = it },
            onApply = {
                val t = searchTerm.replace("%", "").replace("'", "").ifBlank { "search" }
                queryText = "SELECT * FROM chat_logs WHERE message LIKE '%$t%' " +
                    "ORDER BY _id DESC LIMIT 10"
                searchPrompt = false
            },
            onDismiss = { searchPrompt = false }
        )
    }
}

/** ISSUE-39#4: 빠른 "텍스트 검색"용 검색어 입력 dialogs. */
@Composable
private fun SearchTermDialog(
    term: String,
    onTermChange: (String) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("메시지 검색어", fontWeight = FontWeight.Bold) },
        text = {
            OutlinedTextField(
                value = term,
                onValueChange = onTermChange,
                singleLine = true,
                placeholder = { Text("찾을 단어" ) },
                shape = RoundedCornerShape(14.dp),
                colors = irisFieldColors()
            )
        },
        confirmButton = { TextButton(onClick = onApply) { Text("삽입") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

/** 한 행을 요약 + 펼침 상세로 표시하는 공용 결과 카드 */
@Composable
internal fun QueryResultCard(row: Map<String, String?>) {
    var expanded by remember { mutableStateOf(false) }

    SurfaceCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        contentPadding = PaddingValues(14.dp)
    ) {
        Column {
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
                        .background(AppColors.GlassInputBg, RoundedCornerShape(AppColors.FieldRadius))
                        .border(BorderStroke(1.dp, AppColors.GlassStroke), RoundedCornerShape(AppColors.FieldRadius))
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
