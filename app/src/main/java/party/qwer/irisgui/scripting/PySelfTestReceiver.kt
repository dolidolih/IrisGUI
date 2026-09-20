package party.qwer.irisgui.scripting

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.io.File

/**
 * PySelfTestReceiver — UI 없이 adb 로 CPython 임베딩을 검증하기 위한 스파이크용 receiver.

 *   adb shell am broadcast -n party.qwer.irisgui/.scripting.PySelfTestReceiver
 *   adb shell su -c cat /data/user/0/party.qwer.irisgui/files/pyselftest.log
 *
 * Note: Python.start() 는 main 스레드에서 최대 수 초 블로킹하므로 별도 스레드에서 실행한다.
 */
class PySelfTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val log = File(appContext.filesDir, "pyselftest.log")
        // 프로세스가 prematurely 종료되지 않게 goAsync 로 receiver life 를 연장한다.
        val pending = goAsync()
        Thread {
            val lines = try {
                PySelfTest.run(appContext)
            } catch (t: Throwable) {
                listOf("runner threw: $t")
            }
            log.writeText(lines.joinToString("\n"))
            pending.finish()
        }.start()
    }
}
