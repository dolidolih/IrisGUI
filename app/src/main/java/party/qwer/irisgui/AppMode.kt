package party.qwer.irisgui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * AppMode — 모드별 분기 관리
 *
 * ROOT_ADB: root ADB를 통해 app_process로 class 실행 (앱은 가이드/모니터링 제공)
 * NON_ROOT: NLS(Notification Listener Service) 기반 논루팅(알림) 모드
 *
 * (ROOT_MAGISK는 2026-08에 제거됨 — 인프로세스 루팅 데몬 대신 ADB 데몬 사용)
 */
enum class AppMode {
    ROOT_ADB,
    NON_ROOT
}

/**
 * 현재 실행 모드를 감지·관리하는 객체.
 * 수동 설정이 우선되며, 설정이 없으면 자동 감지(하위 호환).
 */
object AppModeManager {
    // Compose 관찰 가능 상태 — 모드 변경 시 이를 읽는 컴포저블이 즉시 재구성된다.
    // (재시작 없이 탭 메뉴가 바로 교체됨)
    var currentMode: AppMode by mutableStateOf(AppMode.NON_ROOT)
        private set
    private var _modeChangeCounter = 0
    val modeChangeCounter: Int get() = _modeChangeCounter

    /**
     * 수동으로 모드를 설정한다. (재시작 후 유지)
     * ConfigScreen에서 호출하면 modeChangeCounter가 증가하여 MainScreen이 갱신됨.
     */
    fun setMode(mode: AppMode) {
        currentMode = mode
        isDetected = true
        _modeChangeCounter++
    }

    /**
     * 현재 환경의 모드를 감지한다.
     * 매번 AppConfig.appMode를 우선 확인 → 저장되어 있으면 그대로 반환.
     * 저장되어 있지 않으면 자동 감지.
     */
    fun detectMode(): AppMode {
        isDetected = true
        // 1. 수동 설정이 있으면 우선 사용
        val saved = AppConfig.appMode
        if (saved != null) {
            currentMode = saved
            return currentMode
        }

        // 2. 수동 설정이 없으면 자동 감지
        currentMode = detectModeInternal()
        return currentMode
    }

    /**
     * ISSUE-14: 첫 알림 분기가 일어나기 전에 한 번은 반드시 호출해야 하는 값싼 판별.
     * currentMode 는 캐시이고 기본값이 NON_ROOT 라서, detectMode 를 부르기 전까지 fresh
     * process 는 ROOT_ADB 데몬이 살아있는데도 NLS 가 이벤트를 중복 브로드캐스트한다.
     * 이미 판별했으면 props AppConfig 도 다시 읽지 않고 그냥 넘긴다.
     */
    fun ensureDetected(): AppMode {
        if (!isDetected) {
            isDetected = true
            return detectMode()
        }
        return currentMode
    }

    @Volatile
    private var isDetected: Boolean = false

    private fun detectModeInternal(): AppMode {
        // Root ADB 체크: Android 시스템 속성(reflection) — System.getProperty는 JVM 속성만
        // 보므로 ro.secure/ro.debuggable을 절대 읽지 못한다(항상 기본값).
        if (SystemPropertyCache.getBoolean("ro.debuggable", false) &&
            !SystemPropertyCache.getBoolean("ro.secure", true)
        ) {
            return AppMode.ROOT_ADB
        }
        // 논루팅(알림) 모드
        return AppMode.NON_ROOT
    }
}

/**
 * android.os.SystemProperties getter 래퍼 — hidden API 게이트 통과 실패 시 안전 폴백.
 */
internal object SystemPropertyCache {
    private val cache = HashMap<String, String?>()
    @Volatile private var accessor: java.lang.reflect.Method? = null

    init {
        accessor = try {
            val cls = Class.forName("android.os.SystemProperties")
            cls.getMethod("get", String::class.java)
        } catch (_: Exception) {
            null
        }
    }

    fun getBoolean(key: String, default: Boolean): Boolean {
        val v = get(key) ?: return default
        return v == "1" || v.equals("true", ignoreCase = true)
    }

    private fun get(key: String): String? = cache.getOrPut(key) {
        try {
            accessor?.invoke(null, key) as? String
        } catch (_: Exception) {
            null
        }
    }
}
