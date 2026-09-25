package party.qwer.irisgui.backend

import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import party.qwer.irisgui.backend.Replier.Companion.SendMessageRequest
import party.qwer.irisgui.AdbConfig
import party.qwer.irisgui.AppConfig

// SendMsg : ye-seola/go-kdb

internal const val IMAGE_DIR_PATH = "/sdcard/Android/data/com.kakao.talk/files"

class Replier {
    companion object {
        private val messageChannel = Channel<SendMessageRequest>(Channel.UNLIMITED)
        private val coroutineScope = CoroutineScope(Dispatchers.IO)
        private var messageSenderJob: Job? = null
        private val mutex = Mutex()

        init {
            startMessageSender()
        }

        fun startMessageSender() {
            // ISSUE-04: 기동 시 설정 스냅샷으로 handshake 로그를 남긴다 — "UI에서는 바꿨는데
            // 데몬은 그대로"류의 문제를 로그 한 줄로 추적하기 위한 발판.
            if (!AppConfig.isInitialized) runCatching {
                AdbConfig.reloadOverlayIfNeeded()
                println("IrisGUI: daemon config ${AdbConfig.describe()}")
            }
            // P19: 바깥 launch 제거 — messageSenderJob만 직접 관리하여 중첩 방지
            if (messageSenderJob?.isActive == true) {
                messageSenderJob?.cancel()  // cancelAndJoin는 suspend 함수이므로 cancel() 사용
            }
            messageSenderJob = coroutineScope.launch {
                for (request in messageChannel) {
                    try {
                        mutex.withLock {
                            request.send()
                            // ISSUE-04: 데몬는 UI가 바꾼 전송레이트/필터를 재시작 없이
                            // 따라간다 (앱이 발행한 live-config 오버레이 점검).
                            if (!AppConfig.isInitialized) runCatching { AdbConfig.reloadOverlayIfNeeded() }
                            delay(AppConfig.messageSendRate)
                        }
                    } catch (e: Exception) {
                        System.err.println("Error sending message from channel: $e")
                    }
                }
            }
        }

        fun restartMessageSender() {
            startMessageSender()
        }

        private fun sendMessageInternal(
            referer: String,
            chatId: Long,
            msg: String,
            threadId: Long?
        ) {
            val intent = Intent().apply {
                component = ComponentName(
                    "com.kakao.talk", "com.kakao.talk.notification.NotificationActionService"
                )
                putExtra("noti_referer", referer)
                putExtra("chat_id", chatId)

                putExtra("is_chat_thread_notification", threadId != null)
                if (threadId != null) {
                    putExtra("thread_id", threadId)
                }

                action = "com.kakao.talk.notification.REPLY_MESSAGE"

                val results = Bundle().apply {
                    putCharSequence("reply_message", msg)
                }

                val remoteInput = RemoteInput.Builder("reply_message").build()
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), this, results)
            }

            AndroidHiddenApi.startService(intent)
        }

        fun sendMessage(referer: String, chatId: Long, msg: String, threadId: Long?) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMessageInternal(
                        referer, chatId, msg, threadId
                    )
                })
            }
        }


        fun sendPhoto(room: Long, base64ImageDataString: String) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendPhotoInternal(
                        room, base64ImageDataString
                    )
                })
            }
        }

        fun sendMultiplePhotos(room: Long, base64ImageDataStrings: List<String>) {
            coroutineScope.launch {
                messageChannel.send(SendMessageRequest {
                    sendMultiplePhotosInternal(
                        room, base64ImageDataStrings
                    )
                })
            }
        }

        private fun sendPhotoInternal(room: Long, base64ImageDataString: String) {
            sendMultiplePhotosInternal(room, listOf(base64ImageDataString))
        }

        private fun sendMultiplePhotosInternal(room: Long, base64ImageDataStrings: List<String>) {
            val picDir = File(IMAGE_DIR_PATH)
            if (!picDir.exists()) {
                picDir.mkdirs()
            }

            val uris = base64ImageDataStrings.mapIndexed { idx, base64ImageDataString ->
                val decodedImage = Base64.decode(base64ImageDataString, Base64.DEFAULT)
                val timestamp = System.currentTimeMillis().toString()

                val imageFile = File(picDir, "${timestamp}_${idx}.png")
                imageFile.writeBytes(decodedImage)

                val imageUri = Uri.fromFile(imageFile)
                mediaScan(imageUri)
                imageUri
            }

            if (uris.isEmpty()) {
                System.err.println("No image URIs created, cannot send multiple photos.")
                return
            }

            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                setPackage("com.kakao.talk")
                type = "image/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                putExtra("key_id", room)
                putExtra("key_type", 1)
                putExtra("key_from_direct_share", true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

            try {
                AndroidHiddenApi.startActivity(intent)
            } catch (e: Exception) {
                System.err.println("Error starting activity for sending multiple photos: $e")
                throw e
            }
        }


        internal fun interface SendMessageRequest {
            suspend fun send()
        }

        private fun mediaScan(uri: Uri) {
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE).apply {
                data = uri
            }
            AndroidHiddenApi.broadcastIntent(mediaScanIntent)
        }
    }
}