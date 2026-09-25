package party.qwer.irisgui.service

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import java.util.Locale

/**
 * NotificationListenerState — "NLS 가 실제로 붙었는가" 에 대한 단일 진실 (ISSUE-06).
 *
 * `startService(Intent(this, IrisNotificationService::class.java))` 는
 * BIND_NOTIFICATION_LISTENER_SERVICE 권한과 무관하게 **성공**한다. 시스템이 컴포넌트를
 * 바인드하지 않으면 콜백만 조용히 빠질 뿐이고, 기동 경로는 "서비스를 시작했습니다" 라고
 * 보고한다. 워치독도 포그라운드 서비스가 도는 한 잡아내지 못한다. 그래서 신호를 둘로 쪼갠다:
 *
 *  - `isPermissionGranted(context)` — 시스템 설정에 우리 리스너 컴포넌트가 enabled 되어
 *    있는가 (grant 상태).
 *  - `isBound` — 실제 instance가 바인드된 채 콜백을 받고 있는가.
 *    `onListenerConnected/onListenerDisconnected` 가 갱신한다.
 *
 * 조합이 만드는 상태:
 *  GRANTED_BOUND      — 정상 (알림 수신/답장이 실제로 동작)
 *  GRANTED_NOT_BOUND  — 권한은 있는데 binding 이 풀림 (APK 교체, 시스템 킬, 기동 직후 등)
 *  NOT_GRANTED        — 권한 자체가 없음 → 설정 화면으로 보내야 한다 (UI 배선은 batch 3).
 *
 * 구현 메모: 관용 API로 알려진 `NotificationManager.getEnabledListenerPackages` 는 이 SDK
 * 의 public jar 에 없다 (@SystemApi). 그래서 (1) Settings.Secure "enabled_notification_listeners"
 * 문자열과 (2) API 33+ 의 `isNotificationListenerAccessGranted`(reflection, 실패 시 무시)
 * 두 positive 신호를 OR 하여 판단한다.
 */
object NotificationListenerState {
    enum class ListenerStatus { NOT_GRANTED, GRANTED_NOT_BOUND, GRANTED_BOUND }

    /** NLS instance 가 바인드된 채 살아있는가 — 시스템 콜백만 반영하는 one-way 상태. */
    @Volatile
    var isBound: Boolean = false
        private set

    @Volatile
    var lastConnectedAtMs: Long = 0
        private set

    @Volatile
    var lastDisconnectedAtMs: Long = 0
        private set

    /** NLS `onListenerConnected` — 시스템이 실제로 우리를 바인드했을 때. */
    fun onConnected() {
        isBound = true
        lastConnectedAtMs = System.currentTimeMillis()
        lastDisconnectedAtMs = 0
    }

    /** NLS `onListenerDisconnected` — 바인딩이 떨어진 때 (해제/킬/APK 교체 등). */
    fun onDisconnected() {
        isBound = false
        lastDisconnectedAtMs = System.currentTimeMillis()
    }

    /**
     * 사용자가 시스템 설정에서 리스너를 켜둔 상태인가. `isBound` 와 달리 설정만 본다 —
     * 설정은 켜져 있는데 binding 만 죽은 상태를 구별하려면 반드시 `status()` 를 쓰자.
     */
    fun isPermissionGranted(context: Context): Boolean {
        val viaSettings = enabledInSettings(context)
        val viaManager = accessGrantedByManager(context)
        // reflection/신API 측정이 안 되면(null) settings 만 믿는다.
        return if (viaManager == null) viaSettings else (viaSettings || viaManager)
    }

    /** 삼각 상태 — 기동 경로와 상태 화면이 함께 봐야 하는 값. */
    fun status(context: Context): ListenerStatus {
        if (!isPermissionGranted(context)) return ListenerStatus.NOT_GRANTED
        return if (isBound) ListenerStatus.GRANTED_BOUND else ListenerStatus.GRANTED_NOT_BOUND
    }

    /** 권한 누락을 user-facing 문구로 — 설정 화면 라우팅은 UI(batch 3) 책임. */
    fun permissionMissingMessage(context: Context): String =
        "알림 리스너 권한(BIND_NOTIFICATION_LISTENER_SERVICE)이 없습니다 — " +
            "설정 > 알림 접근 > 특별 접근에서 ${context.packageName}을(를) 켜주세요"

    /** GRANTED_NOT_BOUND 사용자용 안내. */
    fun notBoundMessage(): String =
        "권한은 켜져 있지만 알림 리스너가 연결되지 않았습니다 — 리스너를 껐다 켜거나 기기를 재시작하세요"

    /** 상태 로그용 한 줄 — 원인을 로그에서 구분하게 하기 위한 설명자. */
    fun describe(context: Context): String {
        val when1 = when (status(context)) {
            ListenerStatus.NOT_GRANTED -> "NOT_GRANTED"
            ListenerStatus.GRANTED_NOT_BOUND -> "GRANTED_NOT_BOUND"
            ListenerStatus.GRANTED_BOUND -> "GRANTED_BOUND"
        }
        val since = if (isBound) lastConnectedAtMs else lastDisconnectedAtMs
        return "NLS status=$when1 (${if (isBound) "bound" else "unbound"}" +
            (if (since > 0) ", since $since" else "") + ")"
    }

    /** NLS 컴포넌트 이름 (설정/deep-link/권한 확인용). */
    fun component(context: Context): ComponentName =
        ComponentName(context, IrisNotificationService::class.java)

    /** Settings.Secure 에 등록된 알림 리스너 문자열("pkg/class:pkg/class") 에서 우리 판별. */
    private fun enabledInSettings(context: Context): Boolean = runCatching {
        val raw = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS) ?: return false
        val pkg = context.packageName.lowercase(Locale.ROOT)
        raw.split(':').any { entry ->
            val trimmed = entry.trim()
            if (trimmed.isEmpty()) return@any false
            val owner = trimmed.substringBefore('/', trimmed).lowercase(Locale.ROOT)
            owner == pkg
        }
    }.getOrDefault(false)

    /**
     * API 33+ 의 `NotificationManager#isNotificationListenerAccessGranted`. 없으면 null
     * (판단에 관여시키지 않음). true 라면 settings 해석보다 확실한 positive.
     */
    private fun accessGrantedByManager(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        return runCatching {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE)
                ?: return null
            val method = manager.javaClass.getMethod(
                "isNotificationListenerAccessGranted", ComponentName::class.java
            )
            method.invoke(manager, component(context)) as? Boolean
        }.getOrNull()
    }

    private const val ENABLED_LISTENERS = "enabled_notification_listeners"
}
