package party.qwer.irisgui.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.AppState
import party.qwer.irisgui.models.StoredRoom
import party.qwer.irisgui.service.IrisService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigScreen(mode: AppMode) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var isEnabled by remember { mutableStateOf(AppConfig.isServiceEnabled) }
    var endpoint by remember { mutableStateOf(AppConfig.webEndpoint) }
    var sendRate by remember { mutableStateOf(AppConfig.sendRate.toString()) }
    var port by remember { mutableStateOf(AppConfig.serverPort.toString()) }

    // 루팅 모드 전용 설정
    var botName by remember { mutableStateOf(AppConfig.botName) }
    var dbPollingRate by remember { mutableStateOf(AppConfig.dbPollingRate.toString()) }

    var testRoom by remember { mutableStateOf("") }
    var testRoomExpanded by remember { mutableStateOf(false) }
    var testMessage by remember { mutableStateOf("") }
    var configChanged by remember { mutableStateOf(false) }

    // 모드 선택 — mode 파라미터 변경 시 동기화
    var modeSelection by remember { mutableStateOf(mode.name) }
    val modeOptions = listOf("ROOT_ADB", "NON_ROOT")
    var modeDropdownExpanded by remember { mutableStateOf(false) }

    // 모드 적용 결과 피드백
    var applyResult by remember { mutableStateOf<String?>(null) }
    var showAdbGuide by remember { mutableStateOf(false) }

    // 모드 파라미터 변경 시 modeSelection 동기화
    LaunchedEffect(mode) {
        modeSelection = mode.name
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                isEnabled = AppConfig.isServiceEnabled
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

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

    val elementShape = RoundedCornerShape(12.dp)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
        contentPadding = PaddingValues(bottom = 32.dp, top = 8.dp)
    ) {
        // ── 모드 선택 ──────────────────────────────────────
        item {
            Text("실행 모드", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modeDropdownExpanded) },
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
                                        applyResult = null
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

                            // 모드 변경 시 실행 중인 서비스를 정지한다.
                            // 이전 모드(NLS/서버)가 남지 않도록 하고, 새 모드에서는
                            // 사용자가 의도적으로 다시 시작하게 한다(자동 시작 안 함).
                            context.startForegroundService(
                                Intent(context, IrisService::class.java).apply {
                                    action = IrisService.ACTION_STOP_SERVICE
                                }
                            )

                            when (modeSelection) {
                                "ROOT_ADB" -> {
                                    showAdbGuide = true
                                    applyResult = "ADB 모드가 선택되었습니다. 아래 가이드를 따라주세요."
                                }
                                else -> {
                                    applyResult = "논루팅(알림) 모드로 설정되었습니다."
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

                    // 적용 결과
                    if (applyResult != null) {
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
                                    if (applyResult!!.contains("실패")) Icons.Default.Warning else Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = if (applyResult!!.contains("실패")) AppColors.ErrorVivid else AppColors.PrimaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(applyResult!!, style = MaterialTheme.typography.bodySmall, color = AppColors.TextMain)
                            }
                        }
                    }

                    // ADB 가이드
                    if (showAdbGuide) {
                        AdbGuideScreen()
                    }
                }
            }
        }

        // ── 서비스 설정 ──────────────────────────────────
        item {
            Text("서비스 설정", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("백그라운드 서비스", fontWeight = FontWeight.SemiBold, color = AppColors.TextMain)
                            Text(
                                text = if (isEnabled) "작동중" else "정지됨",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isEnabled) AppColors.SuccessVivid else AppColors.TextSub
                            )
                        }
                        if (configChanged) {
                            Text("재시작 필요", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = AppColors.ErrorVivid, modifier = Modifier.padding(end = 12.dp))
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = { checked ->
                                val action = if (checked) IrisService.ACTION_START_SERVICE else IrisService.ACTION_STOP_SERVICE
                                val serviceIntent = Intent(context, IrisService::class.java).apply { this.action = action }
                                context.startForegroundService(serviceIntent)
                                isEnabled = checked
                                configChanged = false
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = AppColors.TextMain,
                                checkedTrackColor = AppColors.PrimaryAccent,
                                uncheckedThumbColor = AppColors.TextSub,
                                uncheckedTrackColor = AppColors.InputBg,
                                uncheckedBorderColor = Color.Transparent
                            )
                        )
                    }

                    TextField(
                        value = endpoint,
                        onValueChange = { endpoint = it; AppConfig.webEndpoint = it; configChanged = true },
                        label = { Text("웹서버 엔드포인트") },
                        leadingIcon = { Icon(Icons.Default.Link, contentDescription = null, tint = AppColors.TextSub) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = elementShape,
                        singleLine = true,
                        colors = seamlessTextFieldColors
                    )

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextField(
                            value = sendRate,
                            onValueChange = { sendRate = it; AppConfig.sendRate = it.toLongOrNull() ?: 500L; configChanged = true },
                            label = { Text("발송주기(ms)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            shape = elementShape,
                            singleLine = true,
                            colors = seamlessTextFieldColors
                        )
                        TextField(
                            value = port,
                            onValueChange = { port = it; AppConfig.serverPort = it.toIntOrNull() ?: 3000; configChanged = true },
                            label = { Text("서비스포트") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f),
                            shape = elementShape,
                            singleLine = true,
                            colors = seamlessTextFieldColors
                        )
                    }

                    // ── 루팅 모드 전용 설정 ──────────────────────
                    if (modeSelection != "NON_ROOT") {
                        Divider(color = AppColors.TextSub.copy(alpha = 0.2f))
                        Text("루팅 모드 설정", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = AppColors.PrimaryAccent)

                        TextField(
                            value = botName,
                            onValueChange = { botName = it; AppConfig.botName = it; configChanged = true },
                            label = { Text("봇 이름") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = elementShape,
                            singleLine = true,
                            colors = seamlessTextFieldColors
                        )

                        TextField(
                            value = dbPollingRate,
                            onValueChange = { dbPollingRate = it; AppConfig.dbPollingRate = it.toLongOrNull() ?: 100L; configChanged = true },
                            label = { Text("DB 폴링 주기(ms)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            shape = elementShape,
                            singleLine = true,
                            colors = seamlessTextFieldColors
                        )
                    }
                }
            }
        }

        // ── 답장 테스트 ──────────────────────────────────
        item {
            Text("답장 테스트", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
            ) {
                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = testRoomExpanded,
                        onExpandedChange = { testRoomExpanded = it },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextField(
                            value = testRoom,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("방 이름 또는 ID") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = testRoomExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            shape = elementShape,
                            singleLine = true,
                            colors = seamlessTextFieldColors
                        )
                        ExposedDropdownMenu(
                            expanded = testRoomExpanded,
                            onDismissRequest = { testRoomExpanded = false },
                            modifier = Modifier.background(AppColors.CardBg)
                        ) {
                            if (AppState.storedRooms.isEmpty()) {
                                DropdownMenuItem(text = { Text("저장된 방 없음", color = AppColors.TextSub) }, onClick = { testRoomExpanded = false })
                            } else {
                                AppState.storedRooms.forEach { room: StoredRoom ->
                                    DropdownMenuItem(
                                        text = { Text(room.name, color = AppColors.TextMain) },
                                        onClick = { testRoom = room.name; testRoomExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(room.id, color = AppColors.TextMain) },
                                        onClick = { testRoom = room.id; testRoomExpanded = false }
                                    )
                                }
                            }
                        }
                    }

                    TextField(
                        value = testMessage,
                        onValueChange = { testMessage = it },
                        label = { Text("메시지") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = elementShape,
                        colors = seamlessTextFieldColors
                    )

                    Button(
                        onClick = {
                            focusManager.clearFocus()
                            CoroutineScope(Dispatchers.IO).launch {
                                val client = OkHttpClient()
                                // C4: JSON 인젝션 방지 — 특수문자 명시적 이스케이프
                                val safeRoom = testRoom.replace("\\", "\\\\").replace("\"", "\\\"")
                                val safeMsg = testMessage.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
                                val jsonString = """{"type":"text","room":"$safeRoom","data":"$safeMsg"}"""
                                val body = jsonString.toRequestBody("application/json".toMediaType())
                                val req = Request.Builder().url("http://127.0.0.1:${AppConfig.serverPort}/reply").post(body).build()
                                try { client.newCall(req).execute().close() } catch (e: Exception) { e.printStackTrace() }
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        shape = elementShape,
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("메시지 보내기", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun modeLabel(name: String): String = when (name) {
    "ROOT_ADB" -> "루팅 (ADB)"
    else -> "논루팅 (NLS)"
}
