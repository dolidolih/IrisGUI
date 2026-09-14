package party.qwer.irisgui.backend

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

/**
 * LocalAdb — 기기 내 adbd에 raw 소켓으로 연결하는 최소 ADB 클라이언트 (host 측)
 *
 * host PC의 `adb`와 동일한 ADB 와이어 프로토콜을 127.0.0.1:5555(자신의 adbd)로
 * 수행한다. host PC 연결이 없어도 shell 세션(→ su)을 열 수 있어
 * 데몬(app_process) 자율 기동이 가능해진다.
 *
 * 지원 환경:
 * - redroid(userdebug): `ro.adb.secure=0` → AUTH 없이 즉시 연결
 * - 실물(Magisk, user build): `ro.adb.secure=1` → 최초 1회 "USB 디버깅 승인"
 *   다이얼로그 허용 필요. RSA 키쌍은 앱 파일에 영구 저장되어 이후에는 승인 불필요.
 *
 * ── 프로토콜: NEW / CLASSIC 듀얼 자동 감지 ──────────────────────────
 * 최근 adbd(2023~, android-13+ 기반)는 NEW 프레이밍을 사용한다:
 *   헤더 24바이트(little-endian u32):
 *     [0:4]   cmd — ASCII 4자 (CNXN / AUTH / OPEN / OKAY / CLSE / RTOK / WRTE)
 *     [4:8]   arg0   [8:12]  arg1
 *     [12:16] data 길이
 *     [16:20] checksum (CNXN은 payload 합, 그 외 0 — adbd는 검증하지 않음)
 *     [20:24] magic = cmd 4바이트의 비트 반전 (NOT)
 *     [24:]   payload
 *   클라이언트가 먼저 CNXN을 보낸다(payload: "host::features=...").
 *   adbd는 클라이언트 CNXN을 받을 때까지 아무것도 보내지 않는다.
 *   실측 근거: host adb 37.0.1 ↔ redroid adbd의 실제 패킷 캡처
 *
 * 옛 adbd는 CLASSIC: 즉시 "0016ANDROID!"(12B) 바너를 보내고,
 * 28바이트 big-endian 헤더 [length, cmd, arg0, arg1, data_start, checksum, magic].
 *
 * 연결 직후 800ms 동안 바너를 대기하며 프로토콜을 자동 감지한다.
 * (P25: 모든 단계가 stdout에 로깅된다 — logcat으로 진단)
 */
class LocalAdb(private val context: Context, private val port: Int = 5555) {

    sealed class Failure {
        class NoAdbd(val detail: String = "") : Failure()
        class AuthFailed(val detail: String = "") : Failure()
        class ExecFailed(val service: String, val detail: String = "") : Failure()
    }

    private enum class Proto { NEW, CLASSIC }

    /** cmd 정규화: NEW=ASCII 명칭, CLASSIC int 코드를 동일 명칭으로 변환해 사용 */
    private class Msg(val cmd: String, val arg0: Int, val arg1: Int, val data: ByteArray)

    companion object {
        // CLASSIC 상수 (AOSP system/core/adb/adb_io.h)
        private const val ADB_HOST_MAGIC = 0x504F4E41 // "APNO"
        private const val PROTOCOL_VERSION = 0x01000004
        private const val MAX_MESSAGE = 262144 // 256KB
        private const val BANNER_LEN = 12
        private const val BANNER_TAIL = "ANDROID!"
        private const val CLASSIC_HDR = 28

        // NEW 상수 (실측)
        private const val NEW_HDR = 24
        private const val NEW_CN_XN_ARG0 = 0x01000001
        private const val NEW_CN_XN_ARG1 = 0x00100000
        private const val NEW_FEATURES = "host::features=cmd"
        private const val MAX_DATA = 4 * 1024 * 1024

        private const val SO_TIMEOUT_MS = 30_000
        private const val CONNECT_TIMEOUT_MS = 3_000
        private const val PROBE_TIMEOUT_MS = 800

        private const val KEY_FILE_NAME = "iris_adb_key.bin"
    }

    private var socket: Socket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var proto: Proto = Proto.NEW
    private var sessionId = 0

    @Volatile
    private var closed = false

    /** 마지막 execService 출력 */
    var lastOutput: String = ""
        private set

    // ── 연결/핸드셰이크 ─────────────────────────────────────

    /**
     * adbd에 연결, 프로토콜 자동 감지, 핸드셰이크(CNXN, 필요 시 RSA AUTH) 완료.
     * @return 실패 원인(null = 성공)
     */
    fun connect(): Failure? {
        println("LocalAdb: connect() start port=$port")
        var lastError = "no adbd"
        // redroid 등 일부 환경은 앱 프로세스에서 loopback(127.0.0.1) 연결이
        // 차단되어 있어도 같은 netns의 eth0 IPv4 주소로는 adbd에 도달할 수 있다.
        // 루프백을 먼저, 실패하면 기기 자신의 비루프백 IPv4로 재시도한다.
        for (addr in candidateEndpoints()) {
            val host = addr.address.hostAddress
            val s = Socket().apply { soTimeout = SO_TIMEOUT_MS }
            try {
                s.connect(addr, CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                lastError = "$host:$port -> ${e.javaClass.simpleName}: ${e.message}"
                println("LocalAdb: candidate $lastError")
                runCatching { s.close() }
                continue
            }

            val ins = s.getInputStream()
            val outs = s.getOutputStream()
            socket = s
            input = ins
            output = outs
            println("LocalAdb: TCP connected to $host:$port")

            // 1. 프로토콜 감지.
            //    CLASSIC adbd: 즉시 12B 바너("0016ANDROID!")를 보낸다.
            //    NEW adbd: 클라이언트 CNXN을 받을 때까지 아무것도 보내지 않는다 → 타임아웃.
            val probe = readAvailable(ins, BANNER_LEN, PROBE_TIMEOUT_MS)
            proto = when {
                probe.size == BANNER_LEN &&
                    String(probe, 0, BANNER_TAIL.length, Charsets.ISO_8859_1) == BANNER_TAIL -> Proto.CLASSIC
                probe.isEmpty() -> Proto.NEW
                else -> {
                    println("LocalAdb: unexpected pre-banner data: ${String(probe, Charsets.ISO_8859_1).take(40)}")
                    close()
                    return Failure.NoAdbd("adbd가 아닌 서비스 응답: ${String(probe, Charsets.ISO_8859_1).take(40)}")
                }
            }
            println("LocalAdb: protocol detected = ${proto.name} ($host:$port)")

            // 2. CNXN
            sendCnxn()

            // 3. 핸드셰이크 루프
            return handshake()
        }
        println("LocalAdb: all candidate endpoints failed: $lastError")
        close()
        return Failure.NoAdbd(lastError)
    }

    /**
     * adbd 도달 후보 주소. 루프백 + 비루프백 IPv4(address 기준 중복 제거).
     * redroid 는 eth0 IPv4 가 adbd 청취 주소와 동일하다.
     */
    private fun candidateEndpoints(): List<InetSocketAddress> {
        val list = mutableListOf(InetSocketAddress("127.0.0.1", port))
        runCatching {
            NetworkInterface.getNetworkInterfaces().toList().forEach { nif ->
                if (nif.isLoopback || !nif.isUp) return@forEach
                for (a in nif.inetAddresses.toList()) {
                    if (a is Inet4Address && !a.isLoopbackAddress) {
                        list.add(InetSocketAddress(a, port))
                    }
                }
            }
        }
        return list.distinctBy { it.address?.hostAddress ?: "" }
    }

    private fun sendCnxn() {
        when (proto) {
            Proto.NEW -> {
                val payload = NEW_FEATURES.toByteArray(Charsets.US_ASCII)
                sendNew("CNXN", NEW_CN_XN_ARG0, NEW_CN_XN_ARG1, payload, checksum = sumCksum(payload))
            }
            Proto.CLASSIC -> {
                val payload = intToBytes(PROTOCOL_VERSION) +
                    intToBytes(MAX_MESSAGE) +
                    intToBytes(0) +
                    intToBytes(ADB_HOST_MAGIC)
                sendClassic(0, 0, 0, payload, sumCksum(payload)) // cmd 0 = A_CNXN
            }
        }
        println("LocalAdb: CNXN sent (${proto.name})")
    }

    private fun handshake(): Failure? {
        // 실측(172.30.10.100 adbd): 클라이언트 CNXN 직후 adbd는
        // - 비보안(ro.adb.secure=0): 즉시 device CNXN 하나를 보내고 더 이상 핸드셰이크 메시지를
        //   보내지 않는다(OPEN만 대기). 따라서 "CNXN 수신 = 연결 완료".
        // - 보안(ro.adb.secure=1): AUTH 챌린지를 보내고, 유효한 서명 응답 후 device CNXN.
        // 어떤 adbd도 CNXN/AUTH 뒤에 OKAY를 보내지 않으므로 OKAY를 기다리면 30초 타임아웃으로 죽는다.
        var didAuth = false
        while (true) {
            val msg = receive() ?: return noAdbd("closed during handshake")
            when (msg.cmd) {
                "AUTH" -> {
                    if (msg.data.size != 4) {
                        close()
                        return Failure.AuthFailed("AUTH challenge size ${msg.data.size}")
                    }
                    val signed = signAuthChallenge(msg.data)
                        ?: return Failure.AuthFailed("no RSA key or signing failed")
                    val payload = signed.first + signed.second
                    when (proto) {
                        Proto.NEW -> sendNew("AUTH", 1, signed.first.size, payload, 0)
                        Proto.CLASSIC -> sendClassic(1, 1, signed.first.size, payload, sumCksum(payload))
                    }
                    didAuth = true
                    println("LocalAdb: AUTH response sent (RSA-2048)")
                    // 계속 루프: adbd는 거부 시 다시 AUTH, 승인 시 device CNXN을 보낸다
                }
                "CNXN" -> {
                    println("LocalAdb: device CNXN: ${String(msg.data, Charsets.UTF_8).take(160)}")
                    println("LocalAdb: handshake complete (auth=$didAuth)")
                    return null
                }
                "OKAY" -> {
                    // 일부 빌드에서 CNXN 대신/이후 OKAY를 보내는 경우 대비(관측되지 않음)
                    println("LocalAdb: handshake complete via OKAY (auth=$didAuth)")
                    return null
                }
                else -> {
                    close()
                    return Failure.AuthFailed("unexpected handshake msg: ${msg.cmd}")
                }
            }
        }
    }

    /**
     * 서비스(예: "shell:<cmd>")를 열고 CLSE까지 출력을 수집한다.
     * @return 실패 원인(null = 성공). 출력이 [lastOutput]에 수집된다.
     */
    fun execService(service: String): Failure? {
        if (closed) return Failure.NoAdbd("not connected")
        lastOutput = ""
        val localId = ++sessionId
        val payload = service.toByteArray(Charsets.UTF_8)
        println("LocalAdb: OPEN $service")
        when (proto) {
            Proto.NEW -> sendNew("OPEN", localId, 0, payload, 0)
            Proto.CLASSIC -> sendClassic(2, localId, 0, payload, sumCksum(payload))
        }
        val sb = StringBuilder()
        var remoteId = -1
        try {
            while (true) {
                val msg = receive() ?: run {
                    // adbd가 연결을 닫았으면(서비스 종료) 수집된 출력을 그대로 반환
                    lastOutput = sb.toString()
                    println("LocalAdb: connection closed during service ($service), out=${sb.length}B")
                    return null
                }
                when (msg.cmd) {
                    "OKAY" -> remoteId = msg.arg0
                    "RTOK" -> Unit
                    "WRTE", "WRAP" -> {
                        if (remoteId == -1 || msg.arg0 == remoteId) {
                            sb.append(String(msg.data, Charsets.UTF_8))
                        }
                    }
                    "CLSE" -> {
                        lastOutput = sb.toString()
                        println("LocalAdb: service closed ($service), out=${lastOutput.length}B")
                        return null
                    }
                    else -> println("LocalAdb: unexpected service msg: ${msg.cmd}")
                }
            }
        } catch (e: Exception) {
            lastOutput = sb.toString()
            println("LocalAdb: exec failed ($service): ${e.javaClass.simpleName}: ${e.message}")
            return Failure.ExecFailed(service, e.message ?: e.javaClass.simpleName)
        }
    }

    fun close() {
        if (closed) return
        closed = true
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }

    private fun noAdbd(detail: String): Failure? {
        close()
        return Failure.NoAdbd(detail)
    }

    // ── 프로토콜 프레이밍 ───────────────────────────────────

    /** NEW: 24B LE 헤더 + payload. magic = NOT(cmd) */
    private fun sendNew(cmd: String, arg0: Int, arg1: Int, payload: ByteArray, checksum: Int) {
        val os = output ?: return
        val cmdBytes = cmd.toByteArray(Charsets.US_ASCII)
        // magic = cmd 4바이트의 비트 반전(wire 순서 그대로, big-endian).
        // NOTE: byte-wise NOT of the on-wire command bytes, NOT a LE-encoded int.
        val magic = ((cmdBytes[0].toInt() xor 0xFF) shl 24) or
            ((cmdBytes[1].toInt() xor 0xFF) shl 16) or
            ((cmdBytes[2].toInt() xor 0xFF) shl 8) or
            (cmdBytes[3].toInt() xor 0xFF)
        val buf = ByteArray(NEW_HDR + payload.size)
        System.arraycopy(cmdBytes, 0, buf, 0, 4)
        putLe(buf, 4, arg0)
        putLe(buf, 8, arg1)
        putLe(buf, 12, payload.size)
        putLe(buf, 16, checksum)
        putBe(buf, 20, magic) // wire order = ~cmd byte order (e.g. CNXN -> BC B1 A7 B1)
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, buf, NEW_HDR, payload.size)
        os.write(buf)
        os.flush()
    }

    /** CLASSIC: 28B BE 헤더 + payload */
    private fun sendClassic(cmd: Int, arg0: Int, arg1: Int, payload: ByteArray, checksum: Int) {
        val os = output ?: return
        val buf = ByteArray(CLASSIC_HDR + payload.size)
        putBe(buf, 0, CLASSIC_HDR + payload.size) // length
        putBe(buf, 4, cmd)
        putBe(buf, 8, arg0)
        putBe(buf, 12, arg1)
        putBe(buf, 16, CLASSIC_HDR) // data_start
        putBe(buf, 20, checksum)
        putBe(buf, 24, 0) // magic (adbd는 검증하지 않음)
        if (payload.isNotEmpty()) System.arraycopy(payload, 0, buf, CLASSIC_HDR, payload.size)
        os.write(buf)
        os.flush()
    }

    private fun receive(): Msg? {
        val ins = input ?: return null
        return when (proto) {
            Proto.NEW -> {
                val hdr = readN(ins, NEW_HDR) ?: return null
                val cmd = String(hdr, 0, 4, Charsets.US_ASCII)
                val arg0 = leInt(hdr, 4)
                val arg1 = leInt(hdr, 8)
                val dlen = leInt(hdr, 12)
                if (dlen < 0 || dlen > MAX_DATA) {
                    println("LocalAdb: bad NEW data len=$dlen cmd=$cmd")
                    close()
                    return null
                }
                val data = if (dlen > 0) readN(ins, dlen) ?: return null else ByteArray(0)
                Msg(cmd, arg0, arg1, data)
            }
            Proto.CLASSIC -> {
                val hdr = readN(ins, CLASSIC_HDR) ?: return null
                val length = beInt(hdr, 0)
                val cmdCode = beInt(hdr, 4)
                val arg0 = beInt(hdr, 8)
                val arg1 = beInt(hdr, 12)
                val dlen = length - CLASSIC_HDR
                if (dlen < 0 || dlen > MAX_MESSAGE) {
                    println("LocalAdb: bad CLASSIC data len=$dlen cmd=$cmdCode")
                    close()
                    return null
                }
                val data = if (dlen > 0) readN(ins, dlen) ?: return null else ByteArray(0)
                Msg(classicCmdName(cmdCode), arg0, arg1, data)
            }
        }
    }

    private fun classicCmdName(code: Int): String = when (code) {
        0 -> "CNXN"
        1 -> "AUTH"
        2 -> "OPEN"
        3 -> "OKAY"
        4 -> "CLSE"
        5 -> "RTOK"
        6 -> "WRAP"
        else -> "CMD$code"
    }

    /** [count]바이트 정확히 읽기. EOF는 null. (soTimeout은 connect()에서 설정됨) */
    private fun readN(ins: InputStream, count: Int): ByteArray? {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val r = ins.read(buf, off, count - off)
            if (r < 0) {
                println("LocalAdb: readN EOF (got $off/$count)")
                return null
            }
            off += r
        }
        return buf
    }

    /** 바너 탐지용: [count]바이트까지 [timeoutMs] 안에 읽기. 시간 초과 → 0바이트(= NEW 프로토콜) */
    private fun readAvailable(ins: InputStream, count: Int, timeoutMs: Int): ByteArray {
        val s = socket ?: return ByteArray(0)
        s.soTimeout = timeoutMs
        val buf = ByteArray(count)
        var total = 0
        try {
            while (total < count) {
                val r = ins.read(buf, total, count - total)
                if (r < 0) break
                total += r
            }
        } catch (e: SocketTimeoutException) {
            // NEW 프로토콜: adbd가 아무것도 보내지 않음 → 정상 경로
        } catch (e: Exception) {
            println("LocalAdb: probe read error: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            s.soTimeout = SO_TIMEOUT_MS
        }
        return buf.copyOf(total)
    }

    private fun sumCksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum = (sum + (b.toInt() and 0xFF)) and 0xFFFFFFFF.toInt()
        return sum
    }

    private fun putLe(buf: ByteArray, off: Int, v: Int) {
        buf[off] = (v and 0xFF).toByte()
        buf[off + 1] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 3] = ((v ushr 24) and 0xFF).toByte()
    }

    private fun putBe(buf: ByteArray, off: Int, v: Int) {
        buf[off] = ((v ushr 24) and 0xFF).toByte()
        buf[off + 1] = ((v ushr 16) and 0xFF).toByte()
        buf[off + 2] = ((v ushr 8) and 0xFF).toByte()
        buf[off + 3] = (v and 0xFF).toByte()
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun beInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    @Suppress("DEPRECATION")
    private fun intToBytes(v: Int): ByteArray =
        byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

    // ── RSA AUTH 키 (앱 파일에 영구 저장 → 승인 다이얼로그 1회) ──

    /**
     * adbd의 4바이트 챌린지 서명 — ADB AUTH 규격.
     * 서명은 ADB와 동일하게 digest 없는 RSA (NONEwithRSA, PKCS#1 v1.5).
     * @return (256B 서명, 256B 공개모듈 N) — 실패 시 null
     */
    private fun signAuthChallenge(challenge: ByteArray): Pair<ByteArray, ByteArray>? = try {
        val key = loadOrGenerateKeyPair()
        val sig = Signature.getInstance("NONEwithRSA").apply {
            initSign(key.private)
            update(challenge)
        }.sign()
        var pub = (key.public as RSAPublicKey).modulus.toByteArray()
        if (pub.size == 257 && pub[0] == 0.toByte()) pub = pub.copyOfRange(1, 257)
        Pair(sig, pub)
    } catch (e: Exception) {
        println("LocalAdb: AUTH sign failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    private fun loadOrGenerateKeyPair(): KeyPair {
        val file = File(context.filesDir, KEY_FILE_NAME)
        if (file.exists()) {
            try {
                return loadKeyPair(file)
            } catch (e: Exception) {
                println("LocalAdb: key load failed (${e.message}), regenerating")
            }
        }
        val generator = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        val keyPair = generator.generateKeyPair()
        try {
            // [4B 공개키 DER 길이][공개키 DER(X.509)][개인키 DER(PKCS8)]
            val pubDer = keyPair.public.encoded
            val privDer = keyPair.private.encoded
            FileOutputStream(file).use { fos ->
                fos.write(intToBytes(pubDer.size))
                fos.write(pubDer)
                fos.write(privDer)
            }
        } catch (e: Exception) {
            println("LocalAdb: key save failed: ${e.message}")
        }
        println("LocalAdb: new RSA-2048 keypair generated (one-time auth dialog expected)")
        return keyPair
    }

    private fun loadKeyPair(file: File): KeyPair {
        val all = file.readBytes()
        val pubLen = beInt(all, 0)
        val pubDer = all.copyOfRange(4, 4 + pubLen)
        val privDer = all.copyOfRange(4 + pubLen, all.size)
        val rsa = KeyFactory.getInstance("RSA")
        return KeyPair(
            rsa.generatePublic(X509EncodedKeySpec(pubDer)),
            rsa.generatePrivate(PKCS8EncodedKeySpec(privDer))
        )
    }
}
