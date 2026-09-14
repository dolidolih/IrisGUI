package party.qwer.irisgui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import android.content.Intent
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppConfig
import party.qwer.irisgui.backend.AdbProcessClient
import party.qwer.irisgui.backend.IrisControl
import party.qwer.irisgui.models.AdbProcessStatusResponse

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdbGuideScreen() {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    // 가이드 명령/대시보드 URL에 사용할 서비스 포트 (app_process 실행 인자로 전달됨)
    val configuredPort = AppConfig.serverPort

    fun shareScript(filename: String, content: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, filename)
            putExtra(Intent.EXTRA_SUBJECT, filename)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        runCatching {
            context.startActivity(
                Intent.createChooser(intent, "$filename 내보내기").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }

    // P22: app_process 상태 실시간 조회
    var processStatus by remember { mutableStateOf<AdbProcessStatusResponse?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var lastUpdated by remember { mutableStateOf<String>("") }

    // 3초마다 상태 조회
    LaunchedEffect(Unit) {
        while (true) {
            val status = AdbProcessClient.queryStatus()
            processStatus = status
            isLoading = false
            lastUpdated = "마지막 확인: ${android.text.format.DateFormat.format("kk:mm:ss", System.currentTimeMillis())}"
            delay(3000)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.CardBg)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Terminal, contentDescription = null, tint = AppColors.PrimaryAccent)
                Spacer(modifier = Modifier.width(8.dp))
                Text("ADB 루팅 모드", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = AppColors.TextMain)
            }

            // P22: app_process 실시간 상태 표시
            AdbProcessStatusCard(processStatus, isLoading, lastUpdated)

            Text("IrisGUI를 백그라운드 서비스로 실행합니다. 아래 명령은 모두 '루트 ADB 쉘 안'에서 실행합니다. 터미널을 닫아도 계속 동작합니다.", style = MaterialTheme.typography.bodyMedium, color = AppColors.TextSub)
            Text("현재 설정된 서비스 포트: $configuredPort", style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = AppColors.PrimaryAccent)

            // Step 1
            GuideStep(number = 1, title = "루트 쉘 진입")
            CodeBlock("adb shell", onCopy = { clipboardManager.setText(AnnotatedString(it)) })
            Text("접속한 뒤 쉘에서 su 를 입력해 루트 쉘로 전환합니다(프롬프트가 # 로 바뀜). whoami 결과가 'root' 여야 합니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            CodeBlock("su", onCopy = { clipboardManager.setText(AnnotatedString(it)) })

            // Step 2
            GuideStep(number = 2, title = "서비스 시작 (경로 자동 인식)")
            Text("APK 경로를 pm path 로 자동으로 찾아 포트 $configuredPort 로 실행합니다. (루트 쉘 안에서 그대로 붙여넣기)", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            CodeBlock(
                "CLASSPATH=\$(pm path party.qwer.irisgui | cut -d: -f2) app_process / party.qwer.irisgui.Main $configuredPort &",
                onCopy = { clipboardManager.setText(AnnotatedString(it)) }
            )
            Text("※ 포트를 바꾸려면 [설정] 탭에서 포트를 저장한 뒤 이 명령을 다시 복사하세요. 명령 끝의 숫자가 바인딩 포트입니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)

            // Step 3
            GuideStep(number = 3, title = "실행 상태 확인")
            CodeBlock("ps -ef | grep party.qwer.irisgui | grep -v grep", onCopy = { clipboardManager.setText(AnnotatedString(it)) })
            Text("party.qwer.irisgui.Main 프로세스(PID)가 보이면 정상 실행 중입니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)

            // Step 4
            GuideStep(number = 4, title = "서비스 정지")
            CodeBlock("pkill -f party.qwer.irisgui.Main", onCopy = { clipboardManager.setText(AnnotatedString(it)) })

            // Step 5
            GuideStep(number = 5, title = "웹 대시보드 접근")
            Text("브라우저에서 다음 주소로 접속:", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            CodeBlock("http://<기기IP>:$configuredPort/dashboard", onCopy = { clipboardManager.setText(AnnotatedString(it)) })
            Text("예: http://172.30.10.100:$configuredPort/dashboard", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)

            // PC 제어 스크립트 내보내기 (iris_control)
            GuideStep(number = 6, title = "PC 제어 스크립트 내보내기")
            Text("PC(Linux/Windows)에서 start/stop/status/restart 로 제어하는 iris_control 스크립트를 내보냅니다. 포트 $configuredPort 가 기본값으로 들어갑니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { shareScript("iris_control.sh", IrisControl.bash(context, configuredPort)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Linux/macOS (.sh)", fontSize = MaterialTheme.typography.bodySmall.fontSize)
                }
                Button(
                    onClick = { shareScript("iris_control.ps1", IrisControl.powershell(context, configuredPort)) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.PrimaryAccent, contentColor = AppColors.TextMain)
                ) {
                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Windows (.ps1)", fontSize = MaterialTheme.typography.bodySmall.fontSize)
                }
            }

            // Info
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Info, contentDescription = null, tint = AppColors.PrimaryAccent, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "이 모드에서는 앱이 가이드와 상태 모니터링을 제공합니다. 실제 프로세스는 adb shell에서 백그라운드 서비스로 실행됩니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSub
                    )
                }
            }
        }
    }
}

@Composable
private fun AdbProcessStatusCard(
    status: AdbProcessStatusResponse?,
    isLoading: Boolean,
    lastUpdated: String
) {
    val scope = rememberCoroutineScope()
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status?.server_running == true) AppColors.InputBg.copy(alpha = 0.5f) else AppColors.InputBg
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                // 상태 표시등
                Box(
                    modifier = Modifier.size(12.dp)
                        .background(
                            color = if (status?.server_running == true) Color(0xFF00E676) else Color(0xFFFF5252),
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
                Spacer(modifier = Modifier.height(8.dp))
                Divider(color = AppColors.TextSub.copy(alpha = 0.2f))
                Spacer(modifier = Modifier.height(8.dp))

                // 상태 정보 그리드
                Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusRow("포트", "${status.port}")
                    StatusRow("알림 폴링", if (status.notification_polling) "✅ 활성" else "❌ 비활성")
                    StatusRow("DB 관찰", if (status.db_observing) "✅ 활성" else "❌ 비활성")
                    StatusRow("봇 ID", if (status.bot_id > 0) status.bot_id.toString() else "미감지")
                    StatusRow("봇 이름", status.bot_name)
                    StatusRow("DB 폴링", "${status.db_polling_rate}ms")
                    StatusRow("발송 주기", "${status.message_send_rate}ms")

                    // P23: 정지 버튼
                    if (status.server_running) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                scope.launch { AdbProcessClient.stopProcess() }
                                // 상태는 3초 후 자동 갱신됨
                            },
                            modifier = Modifier.fillMaxWidth().height(36.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = AppColors.ErrorVivid,
                                contentColor = Color.White
                            )
                        ) {
                            Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("app_process 정지", fontWeight = FontWeight.SemiBold, fontSize = MaterialTheme.typography.bodySmall.fontSize)
                        }
                    }
                }
            } else if (isLoading) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("상태 확인 중...", style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub)
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                Text("상태 확인 불가 — app_process가 실행 중이지 않거나 포트가 다릅니다.", style = MaterialTheme.typography.bodySmall, color = AppColors.ErrorVivid.copy(alpha = 0.8f))
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

@Composable
private fun GuideStep(number: Int, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = AppColors.PrimaryAccent,
            modifier = Modifier.size(24.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text("$number", style = MaterialTheme.typography.labelSmall.copy(color = AppColors.TextMain, fontWeight = FontWeight.Bold))
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), color = AppColors.TextMain)
    }
}

@Composable
private fun CodeBlock(code: String, onCopy: (String) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = AppColors.InputBg)
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                code,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = AppColors.PrimaryAccent,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(onClick = { onCopy(code) }) {
                Icon(Icons.Default.ContentCopy, contentDescription = "복사", tint = AppColors.TextSub, modifier = Modifier.size(16.dp))
            }
        }
    }
}
