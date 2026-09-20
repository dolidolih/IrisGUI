package party.qwer.irisgui.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.scripting.ScriptManager
import party.qwer.irisgui.scripting.ScriptStore
import party.qwer.irisgui.scripting.WheelInstaller

/**
 * ScriptScreen — Python 스크립트 편집/실행 탭.
 *
 * 목록 ↔ 에디터. 라이브러리 화면은 filesDir/scripts 를 상시 스캔하고,
 * 런타임 상태(status_all) 와 합쳐 렌더한다.
 */
private data class EditorState(val id: String, val source: String)

@Composable
fun ScriptScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scripts by remember { mutableStateOf<List<ScriptManager.Script>>(emptyList()) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val refresh = suspend {
        val list = withContext(Dispatchers.Default) { ScriptManager.list(context) }
        scripts = list
    }
    // 목록 화면에서는 상태 폴링. 에디터에서는 refresh() 가 스냅샷을 갱신.
    LaunchedEffect(editor) {
        while (editor == null) {
            runCatching { refresh() }
            delay(2500)
        }
    }

    if (editor != null) {
        ScriptEditor(
            state = editor!!,
            onBack = {
                scope.launch { runCatching { refresh() } }
                editor = null
            },
            onMessage = { message = it }
        )
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 24.dp, top = 14.dp)
    ) {
        item {
            SurfaceCard {
                Row(
                    Modifier
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("스크립트", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "파이썬 스크립트가 IrisGUI 안에서 직접 동작합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSub
                        )
                    }
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                val id = "script${System.currentTimeMillis() % 100000}"
                                val src = ScriptManager.newTemplate(id)
                                runCatching {
                                    withContext(Dispatchers.Default) {
                                        ScriptManager.save(context, id, src)
                                    }
                                }
                                runCatching { refresh() }
                                editor = EditorState(id, src)
                                busy = false
                            }
                        }
                    ) { Text("신규") }
                }
            }
        }
        if (!message.isNullOrBlank()) {
            item {
                SurfaceCard {
                    Text(message ?: "", style = MaterialTheme.typography.bodySmall,
                        color = AppColors.TextSub)
                }
            }
        }

        val list = scripts
        if (list.isEmpty()) {
            item {
                SurfaceCard {
                    Text(
                        "등록된 스크립트가 없습니다. '신규'로 추가하세요.",
                        color = AppColors.TextSub,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
        items(count = list.size) { idx ->
            val s = list[idx]
            ScriptCard(
                s = s,
                busy = busy,
                onEdit = {
                    scope.launch {
                        runCatching { refresh() }
                        editor = EditorState(s.id, s.source)
                    }
                },
                onRun = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.Default) { ScriptManager.start(context, s.id) } }
                        runCatching { refresh() }
                        busy = false
                    }
                },
                onStop = {
                    busy = true
                    scope.launch {
                        runCatching { withContext(Dispatchers.Default) { ScriptManager.stop(context, s.id) } }
                        runCatching { refresh() }
                        busy = false
                    }
                },
                onDelete = {
                    busy = true
                    scope.launch {
                        runCatching {
                            withContext(Dispatchers.Default) {
                                ScriptManager.stop(context, s.id)
                                ScriptStore.delete(context, s.id)
                            }
                        }
                        runCatching { refresh() }
                        busy = false
                    }
                }
            )
        }

        item {
            PackagesSection(
                busy = busy,
                onChanged = { message = it },
                setBusy = { busy = it }
            )
        }
    }
}

/**
 * PackagesSection — 앱에 설치된 순수 파이썬 wheel 과 추가 설치.
 * C 확장(numpy 등) 은 설치되지 않으며, 그 경우 메시지로 안내한다.
 */
@Composable
private fun PackagesSection(
    busy: Boolean,
    onChanged: (String) -> Unit,
    setBusy: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var spec by remember { mutableStateOf("") }
    var installed by remember { mutableStateOf<List<String>>(emptyList()) }
    val load = suspend {
        installed = withContext(Dispatchers.Default) {
            ScriptManager.installedPackages(context).installed
        }
    }
    LaunchedEffect(Unit) { runCatching { load() } }

    SurfaceCard {
        Text("패키지 (pip)", fontWeight = FontWeight.SemiBold, color = AppColors.TextMain)
        Text(
            "순수 파이썬 wheel 만 설치됩니다. (numpy 등 C 확장은 Chaquopy 설정 필요)",
            color = AppColors.TextSub,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = spec,
            onValueChange = { spec = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("rich  또는 rich==13.7.1") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                enabled = !busy && spec.isNotBlank(),
                onClick = {
                    setBusy(true)
                    scope.launch {
                        val r = runCatching {
                            withContext(Dispatchers.Default) {
                                ScriptManager.installPackage(context, spec.trim())
                            }
                        }.getOrElse { WheelInstaller.Result(false, it.message ?: "실패") }
                        onChanged(r.message)
                        runCatching { load() }
                        setBusy(false)
                    }
                }
            ) { Text("설치") }
            if (installed.isNotEmpty()) {
                Text(
                    installed.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall,
                    color = AppColors.TextSub
                )
            }
        }
    }
}

@Composable
private fun ScriptCard(
    s: ScriptManager.Script,
    busy: Boolean,
    onEdit: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    val running = s.state == "running" || s.state == "starting"
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val color = when {
                    running -> AppColors.SuccessVivid
                    s.state == "error" -> AppColors.ErrorVivid
                    else -> AppColors.TextSub
                }
                Text(s.state, color = color, style = MaterialTheme.typography.bodySmall)
                if (s.requires.isNotEmpty()) {
                    Text(
                        "requires: " + s.requires.joinToString(", "),
                        color = AppColors.TextSub,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (s.grants.isNotEmpty()) {
                    Text(
                        "grants: " + s.grants.joinToString(", "),
                        color = AppColors.TextSub,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (!s.error.isNullOrBlank()) {
                    Text(s.error ?: "", color = AppColors.ErrorVivid,
                        style = MaterialTheme.typography.bodySmall, maxLines = 3)
                }
            }
            if (running) {
                FilledIconButton(onClick = onStop, enabled = !busy) {
                    Icon(Icons.Default.Stop, contentDescription = "정지")
                }
            } else {
                FilledIconButton(onClick = onRun, enabled = !busy) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "실행")
                }
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = onEdit, enabled = !busy) {
                Icon(Icons.Default.Edit, contentDescription = "편집")
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.Delete, contentDescription = "삭제")
            }
        }
    }
}

@Composable
private fun ScriptEditor(
    state: EditorState,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf(state.source) }
    var statusState by remember { mutableStateOf("stopped") }
    var runningLog by remember { mutableStateOf<List<ScriptManager.LogLine>>(emptyList()) }
    var pollError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val running = statusState == "running" || statusState == "starting"

    val refresh = suspend {
        val st = withContext(Dispatchers.Default) { ScriptManager.list(context) }
            .firstOrNull { it.id == state.id }
        if (st != null) {
            statusState = st.state
            pollError = st.error
            runningLog = withContext(Dispatchers.Default) {
                ScriptManager.logsFor(context, st.id, limit = 200)
            }
        }
    }
    LaunchedEffect(state.id) {
        while (true) {
            runCatching { refresh() }
            delay(1500)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 8.dp)
    ) {
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = !busy, onClick = onBack) { Text("← 목록") }
                Spacer(Modifier.weight(1f))
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        scope.launch {
                            val ok = runCatching {
                                withContext(Dispatchers.Default) {
                                    ScriptManager.save(context, state.id, source)
                                }
                            }.isSuccess
                            onMessage(if (ok) "저장했습니다" else "저장 실패")
                            runCatching { refresh() }
                            busy = false
                        }
                    }
                ) { Text("저장") }
                TextButton(
                    enabled = !busy,
                    colors = if (running) {
                        ButtonDefaults.textButtonColors(contentColor = AppColors.ErrorVivid)
                    } else {
                        ButtonDefaults.textButtonColors(contentColor = AppColors.SuccessVivid)
                    },
                    onClick = {
                        busy = true
                        scope.launch {
                            val res = runCatching {
                                withContext(Dispatchers.Default) {
                                    if (running) ScriptManager.stop(context, state.id)
                                    else ScriptManager.start(context, state.id)
                                }
                            }
                            onMessage(if (res.isSuccess) (if (running) "정지" else "실행") else "토글 실패")
                            runCatching { refresh() }
                            busy = false
                        }
                    }
                ) { Text(if (running) "정지" else "실행") }
            }
        }

        Spacer(Modifier.height(12.dp))

        SurfaceCard(Modifier.weight(1f)) {
            OutlinedTextField(
                value = source,
                onValueChange = { source = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(12.dp)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace
                )
            )
        }

        Spacer(Modifier.height(12.dp))

        SurfaceCard {
            Text("실행 로그", fontWeight = FontWeight.SemiBold, color = AppColors.TextMain)
            Spacer(Modifier.height(6.dp))
            if (pollError != null) {
                Text(pollError ?: "", color = AppColors.ErrorVivid,
                    style = MaterialTheme.typography.bodySmall)
            }
            val logs = runningLog
            if (logs.isEmpty()) {
                Text("로그 없음", color = AppColors.TextSub,
                    style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    logs.joinToString("\n") { "[${it.level}] ${it.text}" },
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace
                    ),
                    maxLines = 10
                )
            }
        }
    }
}
