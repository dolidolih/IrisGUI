package party.qwer.irisgui.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import party.qwer.irisgui.AppColors
import party.qwer.irisgui.AppMode
import party.qwer.irisgui.AppModeManager
import party.qwer.irisgui.scripting.LinuxScripts
import party.qwer.irisgui.scripting.UserlandRuntime

/**
 * ScriptScreen — proot 리눅스 환경 기반 스크립트/코딩 탭.
 *
 * 환경 미설치면 설치 게이트만 보인다. 설치되면 home/projects 의 프로젝트(=스크립트)
 * 목록과 각각의 실행/정지/편집/로그/삭제 버튼 + 상태 badge. 편집은 in-app Monaco
 * 화면(파일 트리 + 터미널 포함)을 연다.
 *
 * 각 main.py 는 이 앱의 ws 포트에 접속하므로 여러 스크립트가 같은 이벤트를 같은 시기에
 * 받는다. 상태는 proot 안에서 도는 python 프로세스의 cwd(=프로젝트 dir) 로 추적.
 */
private data class LogsState(val name: String)
private data class EditorState(val name: String)
private class ScriptUi(
    val name: String,
    val running: Boolean,
    val venvPending: Boolean,
    val error: String?
)

/** code 편집 화면 활성 여부 — MainScreen 이 하단 내비게이션 바를 숨기는 데 참조한다. */
internal val codeEditorOpen = androidx.compose.runtime.mutableStateOf(false)

@Composable
fun ScriptScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val mode = AppModeManager.currentMode

    var env by remember { mutableStateOf(UserlandRuntime.status(context)) }
    var scripts by remember { mutableStateOf<List<ScriptUi>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var logs by remember { mutableStateOf<LogsState?>(null) }
    var editor by remember { mutableStateOf<EditorState?>(null) }
    var showCreate by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    // 편집 화면이 뜨면(탭 이탈 포함) 전역 플래그를 항상 일치시킨다 — MainScreen 의
    // 바 숨김/복원을 정확히 동기화하기 위해. ISSUE-37: LaunchedEffect+별개 dispose
    // 로는 write/dispose 레이스 창이 남는다 — effect 키를 editor 에 두고 한 쌍으로
    // 관리 (변화 시 dispose→setup 순서가 guaranteed 라 최종 일관).
    DisposableEffect(editor) {
        codeEditorOpen.value = editor != null
        onDispose { codeEditorOpen.value = false }
    }
    // ISSUE-37: scope 이 통째로 취소되면 launch 꼬리의 busy=false 가 못 도는 경우가
    // 있어 버튼이 회색으로 고인다 (composer 는 남아 busy 만 true). dispose 시점에
    // 반드시 되돌린다 — 화면 이탈 시 동작도 취소되므로 상태와 어긋남 없음.
    DisposableEffect(Unit) {
        onDispose { busy = false }
    }

    val envReady = env.state == UserlandRuntime.State.READY ||
        env.state == UserlandRuntime.State.RUNNING

    val refresh = suspend {
        env = withContext(Dispatchers.Default) { UserlandRuntime.status(context) }
        scripts = withContext(Dispatchers.Default) {
            LinuxScripts.statusAll(context).map {
                ScriptUi(it.name, it.running, it.venvPending, it.error)
            }
        }
    }

    // 목록 화면일 때만 폴링. 에디터/로그 화면은 자기 poll 을 가진다.
    // ISSUE-28: StartedPollLoop — STARTED 에서만, /proc 실패 시 2.5s→10s 백오프.
    StartedPollLoop(editor, logs, baseMs = 2500L, maxMs = 10_000L) {
        if (editor == null && logs == null) {
            runCatching { refresh() }
            env.state == UserlandRuntime.State.READY ||
                env.state == UserlandRuntime.State.DISABLED
        } else true
    }

    when {
        logs != null -> {
            BackHandler {
                scope.launch { runCatching { refresh() } }
                logs = null
            }
            ScriptLogs(
                name = logs!!.name,
                onBack = {
                    scope.launch { runCatching { refresh() } }
                    logs = null
                },
                onMessage = { message = it }
            )
            return
        }
        editor != null -> {
            // 시스템 백 = 목록으로. (에디터가 뜨면 MainScreen 의 바가 숨겨지므로
            // 백이 곧 이 화면의 "← 목록" 과 동일 동작해야 앱 종료가 안 된다.)
            BackHandler {
                scope.launch { runCatching { refresh() } }
                editor = null
            }
            MonacoEditorScreen(
                name = editor!!.name,
                onBack = {
                    scope.launch { runCatching { refresh() } }
                    editor = null
                }
            )
            return
        }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("스크립트", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "리눅스(home/projects)에서 python 스크립트가 동작합니다.",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppColors.TextSub
                        )
                    }
                    if (envReady) {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                draft = ""
                                showCreate = true
                            }
                        ) { Text("신규") }
                    }
                }

                // ── 환경 상태 / 설치 게이트 ────────────────────────────
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "리눅스 환경", fontWeight = FontWeight.SemiBold, color = AppColors.TextMain,
                        modifier = Modifier.weight(1f)
                    )
                    val label = when (env.state) {
                        UserlandRuntime.State.READY -> "준비됨"
                        UserlandRuntime.State.NOT_INSTALLED -> "미설치"
                        UserlandRuntime.State.DISABLED -> "미지원 기기"
                        else -> "— "
                    }
                    Text(
                        label,
                        color = if (envReady) AppColors.SuccessVivid else AppColors.TextSub,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                if (!envReady) {
                    Text(
                        "python + pip + venv. 이 기기에서 동작하는 백엔드를 설치합니다 — " +
                            "root 필요한 Ubuntu(proot) 는 SELinux permissive 기기용이고, 실기기 " +
                            "대부분은 Termux bionic + linker 구동 방식(~100MB)으로 설치됩니다. " +
                            "다운로드는 한 번.",
                        style = MaterialTheme.typography.bodySmall, color = AppColors.TextSub
                    )
                }
                if (!message.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        message ?: "", style = MaterialTheme.typography.bodySmall,
                        color = if (busy) AppColors.TextSub else AppColors.TextMain
                    )
                }
                Spacer(Modifier.height(8.dp))
                if (!envReady) {
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            message = null
                            scope.launch {
                                try {
                                    val r = runCatching {
                                        withContext(Dispatchers.Default) {
                                            UserlandRuntime.provision(context)
                                        }
                                    }.getOrElse {
                                        UserlandRuntime.Result(false, it.message ?: "설치 실패")
                                    }
                                    if (r.ok) runCatching {
                                        withContext(Dispatchers.Default) {
                                            LinuxScripts.bootstrapDefault(context)
                                        }
                                    }
                                    message = r.message
                                    runCatching { refresh() }
                                } finally {
                                    busy = false
                                }
                            }
                        }
                    ) { Text(if (busy) if (env.state == UserlandRuntime.State.NOT_INSTALLED)
                        "설치 중… (시간 소요)" else "설치 준비…" else "리눅스 환경 설치") }
                }
            }
        }

        // env 미준비면 헤더 카드만 보인다. 아래 항목들은 envReady 조건.
        if (envReady && showCreate) {
            item {
                SurfaceCard {
                    Text("새 스크립트", fontWeight = FontWeight.SemiBold, color = AppColors.TextMain)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("example") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                        supportingText = { Text("문자/숫자/-/_ 만. main.py + venv 자동 생성.") }
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            enabled = !busy && LinuxScripts.isValidName(draft.trim()),
                            onClick = {
                                busy = true
                                val name = draft.trim()
                                scope.launch {
                                    try {
                                        val r = runCatching {
                                            withContext(Dispatchers.Default) {
                                                LinuxScripts.create(context, name)
                                            }
                                        }.getOrElse {
                                            UserlandRuntime.Result(false, it.message ?: "실패")
                                        }
                                        message = r.message
                                        runCatching { refresh() }
                                        showCreate = false
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        ) { Text("생성") }
                        TextButton(enabled = !busy, onClick = { showCreate = false }) { Text("취소") }
                    }
                }
            }
        }

        val list = if (envReady) scripts else emptyList()
        if (list.isEmpty() && envReady) {
            item {
                SurfaceCard {
                    Text(
                        "스크립트가 없습니다. '신규'로 추가하세요. main.py 는 미리 들어 있습니다.",
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
                    // code-server 없이 — 화면 전환만으로 Monaco 편집기가 열린다.
                    // 파일 IO 는 에디터 화면이 host filesDir 를 직접 읽는다.
                    editor = EditorState(s.name)
                },
                onLogs = { logs = LogsState(s.name) },
                onRun = {
                    busy = true
                    scope.launch {
                        try {
                            val r = runCatching {
                                withContext(Dispatchers.Default) {
                                    LinuxScripts.start(context, s.name)
                                }
                            }.getOrElse { UserlandRuntime.Result(false, it.message ?: "실행 실패") }
                            message = r.message
                            runCatching { refresh() }
                        } finally {
                            busy = false
                        }
                    }
                },
                onStop = {
                    busy = true
                    scope.launch {
                        try {
                            val r = runCatching {
                                withContext(Dispatchers.Default) {
                                    LinuxScripts.stop(context, s.name)
                                }
                            }.getOrElse { UserlandRuntime.Result(false, it.message ?: "정지 실패") }
                            message = r.message
                            runCatching { refresh() }
                        } finally {
                            busy = false
                        }
                    }
                },
                onDelete = {
                    busy = true
                    scope.launch {
                        try {
                            val r = runCatching {
                                withContext(Dispatchers.Default) {
                                    LinuxScripts.delete(context, s.name)
                                }
                            }.getOrElse { UserlandRuntime.Result(false, it.message ?: "삭제 실패") }
                            message = r.message
                            runCatching { refresh() }
                        } finally {
                            busy = false
                        }
                    }
                }
            )
        }
    }
}

@Composable
private fun ScriptCard(
    s: ScriptUi,
    busy: Boolean,
    onEdit: () -> Unit,
    onLogs: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onDelete: () -> Unit
) {
    SurfaceCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                val (label, color) = when {
                    s.venvPending -> "준비 중" to AppColors.TextSub
                    s.running -> "running" to AppColors.SuccessVivid
                    s.error != null -> "venv 실패" to AppColors.ErrorVivid
                    else -> "stopped" to AppColors.TextSub
                }
                Text(label, color = color, style = MaterialTheme.typography.bodySmall)
                if (s.error != null) {
                    Text(s.error ?: "", color = AppColors.TextSub,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            // venv 생성 중에는 실행/편집 불가 — 프로비저닝 중 venv racing 차단.
            if (s.running) {
                FilledIconButton(onClick = onStop, enabled = !busy) {
                    Icon(Icons.Default.Stop, contentDescription = "정지")
                }
            } else {
                FilledIconButton(onClick = onRun, enabled = !busy && !s.venvPending) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "실행")
                }
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = onEdit, enabled = !busy && !s.venvPending) {
                Icon(Icons.Default.Edit, contentDescription = "편집")
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = onLogs, enabled = !busy) {
                Icon(Icons.AutoMirrored.Filled.Article, contentDescription = "실행 로그")
            }
            Spacer(Modifier.width(4.dp))
            FilledTonalIconButton(onClick = onDelete, enabled = !busy) {
                Icon(Icons.Default.Delete, contentDescription = "삭제")
            }
        }
    }
}

/**
 * ScriptLogs — 프로젝트 main.py stdout/stderr 로그. 폴링(1.5s) 갱신.
 * pip/터미널 로그는 편집기의 내장 터미널에서 직접 확인할 수 있다.
 */
@Composable
private fun ScriptLogs(
    name: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val context = LocalContext.current
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var autoScroll by remember { mutableStateOf(true) }
    val scroll = rememberScrollState()

    val refresh = suspend {
        running = withContext(Dispatchers.Default) {
            LinuxScripts.statusAll(context).firstOrNull { it.name == name }?.running == true
        }
        logLines = withContext(Dispatchers.Default) {
            LinuxScripts.logsFor(context, name, limit = 400)
        }
    }
    LaunchedEffect(name) {
        while (true) {
            runCatching { refresh() }
            delay(1500)
        }
    }
    LaunchedEffect(logLines.size, autoScroll) {
        if (autoScroll) scroll.animateScrollTo(scroll.maxValue)
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenPadding, vertical = 8.dp)
    ) {
        SurfaceCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(enabled = true, onClick = onBack) { Text("← 목록") }
                Spacer(Modifier.weight(1f))
                Text(name, fontWeight = FontWeight.SemiBold, color = AppColors.TextMain)
                Spacer(Modifier.width(10.dp))
                Text(
                    if (running) "running" else "stopped",
                    color = if (running) AppColors.SuccessVivid else AppColors.TextSub,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.width(10.dp))
                TextButton(
                    onClick = { autoScroll = !autoScroll },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (autoScroll) AppColors.SuccessVivid else AppColors.TextSub
                    )
                ) { Text(if (autoScroll) "자동 스크롤" else "일시정지") }
            }
        }
        Spacer(Modifier.height(12.dp))
        SurfaceCard {
            val lines = logLines
            if (lines.isEmpty()) {
                Text(
                    "로그 없음. 실행 버튼, 또는 편집기 터미널에서 python main.py 로 실행.",
                    color = AppColors.TextSub,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Column(
                    Modifier
                        .height(460.dp)
                        .verticalScroll(scroll)
                ) {
                    Text(
                        lines.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    )
                }
            }
        }
    }
}
