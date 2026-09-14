package party.qwer.irisgui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuDefaults.TrailingIcon
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import android.content.Intent
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.service.IrisService
import party.qwer.irisgui.models.ConfigRequest
import party.qwer.irisgui.models.ConfigResponse

/**
 * ADB 모드 설정 관리 화면
 *
 * AdbServer의 /config API를 통해 설정을 읽고 쓴다.
 * 원본 Iris의 Configurable.kt에 해당하는 기능을 앱 UI로 제공.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbConfigScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var config by remember { mutableStateOf<ConfigResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    // 로컬 에디트 상태
    var editBotName by remember { mutableStateOf("") }
    var editDbRate by remember { mutableStateOf("") }
    var editSendRate by remember { mutableStateOf("") }
    var editPort by remember { mutableStateOf(AppConfig.serverPort.toString()) }
    var editEndpoint by remember { mutableStateOf("") }

    // 모드 선택
    var modeSelection by remember { mutableStateOf(AppConfig.appMode?.name ?: "ROOT_ADB") }
    var modeDropdownExpanded by remember { mutableStateOf(false) }
    var modeApplyResult by remember { mutableStateOf<String?>(null) }
    val modeOptions = listOf("ROOT_ADB", "NON_ROOT")
    val elementShape = RoundedCornerShape(12.dp)
    val seamlessTextFieldColors = TextFieldDefaults.colors(
        focusedContainerColor = AppColors.InputBg,
        unfocusedContainerColor = AppColors.InputBg,
        disabledContainerColor = AppColors.InputBg,
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        disabledIndicatorColor = Color.Transparent,
        focusedTextColor = AppColors.TextMain,
        unfocusedTextColor = AppColors.TextMain,
        focusedLabelColor = AppColors.PrimaryAccent,
        unfocusedLabelColor = AppColors.TextSub,
        cursorColor = AppColors.PrimaryAccent
    )

    fun loadConfig() {
        scope.launch {
            val c = AdbProcessClient.fetchConfig()
            config = c
            if (c != null) {
                editBotName = c.bot_name
                editDbRate = c.db_polling_rate.toString()
                editSendRate = c.message_send_rate.toString()
                editPort = c.bot_http_port.toString()
                editEndpoint = c.web_server_endpoint
            }
            isLoading = false
        }
    }

    fun modeLabel(name: String): String = when (name) {
        "ROOT_ADB" -> "루팅 (ADB)"
        else -> "논루팅 (NLS)"
    }

    // 초기 로드
    LaunchedEffect(Unit) {
        loadConfig()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 헤더 ──────────────────────────────────────
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = AppColors.PrimaryAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("설정 관리", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }
            Text("AdbServer의 설정을 읽고 수정합니다. 변경 후 각 항목별 [저장] 버튼을 누르세요.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
        }

        // ── 현재 설정 요약 ────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("현재 설정", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                    Spacer(modifier = Modifier.height(8.dp))
                    if (config != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            ConfigStatusRow("봇 이름", config!!.bot_name)
                            ConfigStatusRow("포트", config!!.bot_http_port.toString())
                            ConfigStatusRow("엔드포인트", config!!.web_server_endpoint.ifEmpty { "없음" })
                            ConfigStatusRow("DB 폴링", "${config!!.db_polling_rate}ms")
                            ConfigStatusRow("발송 주기", "${config!!.message_send_rate}ms")
                        }
                    } else {
                        Text("로딩 중...", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                    }
                }
            }
        }

        // ── 모드 선택 ─────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("실행 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
                    Text("이 앱의 동작 모드를 선택합니다. 변경 후 [적용] 버튼을 누르세요.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                    ExposedDropdownMenuBox(
                        expanded = modeDropdownExpanded,
                        onExpandedChange = { modeDropdownExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            value = modeLabel(modeSelection),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("모드 선택") },
                            trailingIcon = { TrailingIcon(expanded = modeDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = elementShape,
                            singleLine = true,
                            colors = seamlessTextFieldColors
                        )
                        ExposedDropdownMenu(
                            expanded = modeDropdownExpanded,
                            onDismissRequest = { modeDropdownExpanded = false },
                            modifier = Modifier.background(AppColors.CardBg)
                        ) {
                            modeOptions.forEach { option ->
                                DropdownMenuItem(
                                    text = { Text(modeLabel(option), color = AppColors.TextMain) },
                                    onClick = {
                                        modeSelection = option
                                        modeDropdownExpanded = false
                                        modeApplyResult = null
                                    }
                                )
                            }
                        }
                    }

                    // 모드 설명
                    when (modeSelection) {
                        "ROOT_ADB" -> Text("루팅된 ADB 환경에서 adb shell로 프로세스를 실행합니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                        else -> Text("NLS(Notification Listener Service) 기반 논루팅(알림) 모드입니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
                    }

                    // 적용 버튼
                    Button(
                        onClick = {
                            val newMode = AppMode.valueOf(modeSelection)
                            AppConfig.appMode = newMode
                            AppModeManager.setMode(newMode)

                            // 모드 변경 시 인앱 서비스를 정지한다(이전 모드 잔존 방지).
                            context.startForegroundService(
                                Intent(context, IrisService::class.java).apply {
                                    action = IrisService.ACTION_STOP_SERVICE
                                }
                            )

                            when (modeSelection) {
                                "ROOT_ADB" -> {
                                    modeApplyResult = "ADB 모드가 선택되었습니다. 아래 가이드를 따라주세요."
                                }
                                else -> {
                                    modeApplyResult = "논루팅(알림) 모드로 설정되었습니다."
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = elementShape,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("적용", fontWeight = FontWeight.Bold)
                    }

                    // 모드 적용 결과
                    if (modeApplyResult != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (modeApplyResult!!.contains("실패")) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (modeApplyResult!!.contains("실패")) AppColors.ErrorVivid else AppColors.PrimaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(modeApplyResult!!, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMain)
                            }
                        }
                    }
                }
            }
        }

        // ── 설정 편집 ─────────────────────────────────
        item {
            Text("설정 편집", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.PrimaryAccent)
        }

        // 봇 이름
        item {
            ConfigEditField(
                label = "봇 이름",
                value = editBotName,
                onValueChange = { editBotName = it },
                onSave = {
                    scope.launch {
                        val ok = AdbProcessClient.updateConfig("botname", ConfigRequest(botname = editBotName))
                        saveMessage = if (ok) "✅ 봇 이름 저장 완료" else "❌ 저장 실패"
                    }
                }
            )
        }

        // 포트
        item {
            ConfigEditFieldNumeric(
                label = "포트",
                value = editPort,
                onValueChange = { editPort = it },
                onSave = {
                    val port = editPort.toIntOrNull()
                    if (port != null && port in 1..65535) {
                        // 런치 포트(가이드 명령·상태 조회가 읽는 값)를 로컬에 반영.
                        // app_process는 이 값을 실행 인자로 받아 바인딩한다.
                        AppConfig.serverPort = port
                        scope.launch {
                            // 실행 중인 app_process에도 best-effort로 반영(다음 재시작 시 적용)
                            AdbProcessClient.updateConfig("botport", ConfigRequest(port = port))
                            saveMessage = "✅ 포트 저장 완료 — [가이드]의 시작 명령을 다시 복사해 app_process를 재시작하세요"
                        }
                    } else {
                        saveMessage = "❌ 유효한 포트 번호(1-65535)를 입력하세요"
                    }
                }
            )
        }

        // 엔드포인트
        item {
            ConfigEditField(
                label = "웹서버 엔드포인트",
                value = editEndpoint,
                onValueChange = { editEndpoint = it },
                onSave = {
                    scope.launch {
                        val ok = AdbProcessClient.updateConfig("endpoint", ConfigRequest(endpoint = editEndpoint))
                        saveMessage = if (ok) "✅ 엔드포인트 저장 완료" else "❌ 저장 실패"
                    }
                }
            )
        }

        // DB 폴링 주기
        item {
            ConfigEditFieldNumeric(
                label = "DB 폴링 주기(ms)",
                value = editDbRate,
                onValueChange = { editDbRate = it },
                onSave = {
                    scope.launch {
                        val rate = editDbRate.toLongOrNull()
                        if (rate != null && rate > 0) {
                            val ok = AdbProcessClient.updateConfig("dbrate", ConfigRequest(rate = rate))
                            saveMessage = if (ok) "✅ DB 폴링 주기 저장 완료" else "❌ 저장 실패"
                        } else {
                            saveMessage = "❌ 0보다 큰 값을 입력하세요"
                        }
                    }
                }
            )
        }

        // 발송 주기
        item {
            ConfigEditFieldNumeric(
                label = "발송 주기(ms)",
                value = editSendRate,
                onValueChange = { editSendRate = it },
                onSave = {
                    scope.launch {
                        val rate = editSendRate.toLongOrNull()
                        if (rate != null && rate > 0) {
                            val ok = AdbProcessClient.updateConfig("sendrate", ConfigRequest(rate = rate))
                            saveMessage = if (ok) "✅ 발송 주기 저장 완료" else "❌ 저장 실패"
                        } else {
                            saveMessage = "❌ 0보다 큰 값을 입력하세요"
                        }
                    }
                }
            )
        }

        // ── 저장 메시지 ───────────────────────────────
        item {
            if (saveMessage != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (saveMessage!!.startsWith("✅")) Icons.Default.CheckCircle else Icons.Default.Warning,
                            contentDescription = null,
                            tint = if (saveMessage!!.startsWith("✅")) AppColors.SuccessVivid else AppColors.ErrorVivid,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(saveMessage!!, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMain)
                    }
                }
            }
        }

        // ── 새로고침 버튼 ─────────────────────────────
        item {
            Button(
                onClick = {
                    saveMessage = null
                    loadConfig()
                },
                modifier = Modifier.fillMaxWidth().height(44.dp),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("설정 새로고침", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ConfigEditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.PrimaryAccent,
                        unfocusedBorderColor = AppColors.TextSub.copy(alpha = 0.3f),
                        focusedTextColor = AppColors.TextMain,
                        unfocusedTextColor = AppColors.TextMain
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("저장", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun ConfigEditFieldNumeric(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AppColors.PrimaryAccent,
                        unfocusedBorderColor = AppColors.TextSub.copy(alpha = 0.3f),
                        focusedTextColor = AppColors.TextMain,
                        unfocusedTextColor = AppColors.TextMain
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onSave,
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("저장", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold))
                }
            }
        }
    }
}

@Composable
private fun ConfigStatusRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
        Text(value, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextMain)
    }
}
