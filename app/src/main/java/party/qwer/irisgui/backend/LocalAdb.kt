package party.qwer.irisgui.backend

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
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
import java.util.zip.CRC32

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
 *     [0:4]   cmd — ASCII 4자 (CNXN / AUTH / OPEN / OKAY / CLSE / WRTE / ...)
 *     [4:8]   arg0   [8:12]  arg1
 *     [12:16] data 길이
 *     [16:20] checksum (CNXN은 payload 합, 그 외 0 — adbd는 검증하지 않음)
 *     [20:24] magic = cmd 4바이트의 비트 반전 (NOT)
 *     [24:]   payload
 *   클라이언트가 먼저 CNXN을 보낸다(payload: "host::features=...").
 *   adbd는 클라이언트 CNXN을 받을 때까지 아무것도 보내지 않는다.
 *
 * CLASSIC (AOSP system/core/adb/protocol.h):
 *   즉시 "0016ANDROID!" 바너(12B, hex length + "ANDROID!")를 보낸다.
 *   이후 헤더는 big-endian 24바이트:
 *     [0:4]   type   = A_OP(x) = x + 0x01000001
 *             (SYNC=2, CNXN=3, OPEN=4, OKAY=5, CLSE=6, WRTE=7, AUTH=8, CLOSE=9)
 *     [4:8]   arg0   [8:12]  arg1
 *     [12:16] data 길이 (헤더 미포함)
 *     [16:20] crc32(payload)
 *     [20:24] magic = NOT(type)
 *   AUTH 는 AOSP 규격 그대로 두 개의 메시지: AUTH(arg0=2, sig) →
 *   (키 불인정 시) AUTH(arg0=3, adb_public_key struct + "user@host\0").
 *
 * ISSUE-30: 과거 구현은 CLASSIC opcode 를 int 그대로(0=CNXN...) 쓰거나 28바이트
 * 헤더로 감쌌고, AUTH 응답을 sig+pubkey 단일 메시지로 보내 어긋났다. 전부 protocol.h
 * 규격으로 바로잡았다. (실패 열거형/공개 API 는 불변)
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
        private const val PROTOCOL_VERSION = 0x01000001
        private const val MAX_MESSAGE = 262144 // 256KB
        private const val BANNER_LEN = 12
        private const val BANNER_TAIL = "ANDROID!"

        // CLASSIC opcode: A_OP(x) = x + 0x01000001 (AOSP protocol.h)
        private const val A_OP_BASE = 0x01000001
        private const val A_SYNC = 1 + A_OP_BASE    // 0x01000002
        private const val A_CNXN = 2 + A_OP_BASE    // 0x01000003
        private const val A_OPEN = 3 + A_OP_BASE    // 0x01000004
        private const val A_OKAY = 4 + A_OP_BASE    // 0x01000005
        private const val A_CLSE = 5 + A_OP_BASE    // 0x01000006
        private const val A_WRTE = 6 + A_OP_BASE    // 0x01000007
        private const val A_AUTH = 7 + A_OP_BASE    // 0x01000008
        private const val A_CLOSE = 8 + A_OP_BASE   // 0x01000009
        private const val CLASSIC_HDR = 24

        // AUTH subtype (AOSP)
        private const val AUTHTYPE_TOKEN = 1        // adbd → host: challenge
        private const val AUTHTYPE_SIGNATURE = 2    // host → adbd: RSA signature
        private const val AUTHTYPE_PUBKEY = 3       // host → adbd: pubkey struct + text

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

        /** AUTH 공개키 wire text: "user@host\0" */
        private const val AUTH_USER_LABEL = "irisgui@irisgui\u0000"
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

            // ISSUE-30:握手 도중 throw 나 예상치 못한 페이로드로 빠지면 소켓이 유출된다 —
            // candidate 시도마다 try/finally 로 결속한다.
            try {
                // 1. 프로토콜 감지.
                //    CLASSIC adbd: 즉시 12B 바너("0016ANDROID!")를 보낸다.
                //    NEW adbd: 클라이언트 CNXN을 받을 때까지 아무것도 보내지 않는다 → 타임아웃.
                val probe = readAvailable(ins, BANNER_LEN, PROBE_TIMEOUT_MS)
                proto = when {
                    probe.size == BANNER_LEN &&
                        String(probe, 4, 8, Charsets.ISO_8859_1) == BANNER_TAIL -> Proto.CLASSIC
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
            } catch (e: Exception) {
                // ISSUE-30: 프레이밍 예외도 소켓 유출 없이 회수한다.
                close()
                println("LocalAdb: handshake error: ${e.javaClass.simpleName}: ${e.message}")
                return Failure.NoAdbd("handshake error: ${e.javaClass.simpleName}: ${e.message}")
            }
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
                // CLASSIC CNXN: type=A_CNXN, arg0=version, arg1=maxdata, payload=banner
                val payload = "host::".toByteArray(Charsets.US_ASCII)
                sendClassic(A_CNXN, PROTOCOL_VERSION, MAX_MESSAGE, payload)
            }
        }
        println("LocalAdb: CNXN sent (${proto.name})")
    }

    /**
     * ISSUE-30 AUTH 규격:
     *   adbd → AUTH(arg0=1[TOKEN], data=20B challenge)
     *   host → AUTH(arg0=2, data=signature)   …승인된 키면 이후 CNXN
     *   서명 불승인이 반복되면 host → AUTH(arg0=3, data=adb_public_key struct + "user@host\0")
     *
     * 또한 CLASSIC/보안 adbd 는 승인 대기 상태에서 세션을 닫는다 — 메시지 하나라도
     * 받은 뒤 닫혔으면 NoAdbd 가 아니라 AuthFailed 로 분류해야 올바른 안내가 나간다.
     */
    private fun handshake(): Failure? {
        var sigCount = 0
        var sentPubKey = false
        var gotAnyMessage = false
        while (true) {
            val msg = receive() ?: run {
                close()
                return if (gotAnyMessage) {
                    Failure.AuthFailed("adbd closed during handshake (unauthorized RSA key?)")
                } else {
                    noAdbd("closed during handshake")
                }
            }
            gotAnyMessage = true
            when (msg.cmd) {
                "AUTH" -> when (msg.arg0) {
                    AUTHTYPE_TOKEN -> {
                        if (msg.data.isEmpty() || msg.data.size > 64) {
                            close()
                            return Failure.AuthFailed("AUTH challenge size ${msg.data.size}")
                        }
                        val sig = signToken(msg.data)
                            ?: return Failure.AuthFailed("no RSA key or signing failed")
                        // 서명 먼저 (키를 아는 경우 통과), 실패 반복시에만 공개키 전송
                        if (!sentPubKey) {
                            sendAuthMsg(AUTHTYPE_SIGNATURE, sig)
                            sigCount++
                            if (sigCount >= 2) {
                                val pub = buildAuthPublicKey()
                                    ?: run {
                                        close()
                                        return Failure.AuthFailed("public key struct build failed")
                                    }
                                sendAuthMsg(AUTHTYPE_PUBKEY, pub)
                                sentPubKey = true
                                println("LocalAdb: AUTH public key sent (RSA-2048)")
                            }
                        } else {
                            sendAuthMsg(AUTHTYPE_SIGNATURE, sig)
                        }
                        println("LocalAdb: AUTH signature sent (n=$sigCount, pubKeySent=$sentPubKey)")
                    }
                    AUTHTYPE_SIGNATURE, AUTHTYPE_PUBKEY -> Unit // adbd 는 보내지 않음 — 무시
                    else -> {
                        close()
                        return Failure.AuthFailed("unknown AUTH subtype arg0=${msg.arg0}")
                    }
                }
                "CNXN" -> {
                    println("LocalAdb: device CNXN: ${String(msg.data, Charsets.UTF_8).take(160)}")
                    println("LocalAdb: handshake complete")
                    return null
                }
                "OKAY" -> {
                    // 일부 빌드에서 CNXN 대신/이후 OKAY를 보내는 경우 대비(관측되지 않음)
                    println("LocalAdb: handshake complete via OKAY")
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
     *
     * ISSUE-30: EOF/CLSE 만으로 성공 처리하면 OPEN 거절(su 거부 등)이 성공으로 위장한다 —
     * OKAY(실제 서비스 open) 없이 종료되면 ExecFailed 로 되돌린다. WRTE 에는 flow-control
     * ACK(OKAY) 를 회신한다 — CLASSIC adbd 는 ACK 없이 다음 데이터를 보내지 않는다.
     */
    fun execService(service: String): Failure? {
        if (closed) return Failure.NoAdbd("not connected")
        lastOutput = ""
        val localId = ++sessionId
        val payload = service.toByteArray(Charsets.UTF_8)
        println("LocalAdb: OPEN $service")
        when (proto) {
            Proto.NEW -> sendNew("OPEN", localId, 0, payload, 0)
            Proto.CLASSIC -> sendClassic(A_OPEN, localId, 0, payload)
        }
        val sb = StringBuilder()
        var remoteId = -1
        try {
            while (true) {
                val msg = receive() ?: run {
                    // adbd가 연결을 닫았음 (서비스 종료)
                    lastOutput = sb.toString()
                    if (remoteId == -1) {
                        println("LocalAdb: service rejected (no OKAY before close): $service")
                        return Failure.ExecFailed(service, "adbd closed before OKAY: out=" + sb.toString().take(200))
                    }
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
                        // flow-control ACK (adbd 가 다음 WRTE/종료를 진행하기 위해 필요)
                        val ackRemote = if (remoteId != -1) remoteId else msg.arg0
                        when (proto) {
                            Proto.NEW -> sendNew("OKAY", localId, ackRemote, ByteArray(0), 0)
                            Proto.CLASSIC -> sendClassic(A_OKAY, localId, ackRemote, ByteArray(0))
                        }
                    }
                    "CLSE" -> {
                        lastOutput = sb.toString()
                        if (remoteId == -1) {
                            println("LocalAdb: service rejected ($service): CLSE without OKAY")
                            return Failure.ExecFailed(service, "adbd rejected service: out=" + sb.toString().take(200))
                        }
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

    /** AUTH 응답 메시지 전송 (규격: arg0=subtype, arg1=data length) */
    private fun sendAuthMsg(subtype: Int, payload: ByteArray) {
        when (proto) {
            Proto.NEW -> sendNew("AUTH", subtype, payload.size, payload, 0)
            Proto.CLASSIC -> sendClassic(A_AUTH, subtype, payload.size, payload)
        }
    }

    /**
     * CLASSIC: 24B big-endian 헤더 + payload (AOSP protocol.h)
     *   [type][arg0][arg1][data_length][crc32][magic=~type]
     */
    private fun sendClassic(type: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val os = output ?: return
        val buf = ByteArray(CLASSIC_HDR + payload.size)
        putBe(buf, 0, type)
        putBe(buf, 4, arg0)
        putBe(buf, 8, arg1)
        putBe(buf, 12, payload.size)
        putBe(buf, 16, crc32Of(payload))
        putBe(buf, 20, type.inv())
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
                val typeCode = beInt(hdr, 0)
                val arg0 = beInt(hdr, 4)
                val arg1 = beInt(hdr, 8)
                val dlen = beInt(hdr, 12)
                if (dlen < 0 || dlen > MAX_MESSAGE) {
                    println("LocalAdb: bad CLASSIC data len=$dlen type=$typeCode")
                    close()
                    return null
                }
                val data = if (dlen > 0) readN(ins, dlen) ?: return null else ByteArray(0)
                Msg(classicTypeName(typeCode), arg0, arg1, data)
            }
        }
    }

    /** protocol.h opcode → 정규화 이름 */
    private fun classicTypeName(code: Int): String = when (code) {
        A_SYNC -> "SYNC"
        A_CNXN -> "CNXN"
        A_OPEN -> "OPEN"
        A_OKAY -> "OKAY"
        A_CLSE -> "CLSE"
        A_WRTE -> "WRTE"
        A_AUTH -> "AUTH"
        A_CLOSE -> "CLOSE"
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

    private fun crc32Of(data: ByteArray): Int {
        val crc = CRC32()
        crc.update(data)
        return crc.value.toInt()
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
     * adbd 도전자(token) 서명 — ADB AUTH 규격 (digest 없는 RSA, NONEwithRSA/PKCS#1 v1.5).
     */
    private fun signToken(challenge: ByteArray): ByteArray? = try {
        val key = loadOrGenerateKeyPair()
        val sig = Signature.getInstance("NONEwithRSA").apply {
            initSign(key.private)
            update(challenge)
        }.sign()
        sig
    } catch (e: Exception) {
        println("LocalAdb: AUTH sign failed: ${e.javaClass.simpleName}: ${e.message}")
        null
    }

    /**
     * AUTH_RSAPUBLICKEY wire struct (AOSP adb_public_key):
     *   int32 len       — modulus 의 uint32 word 수 (2048-bit = 64)
     *   int32 n0inv     — -(n^-1 mod 2^32) mod 2^32
     *   int32 bit_count — modulus bit 수 (2048)
     *   uint32 words[]  — modulus, little-endian (host order) word 나열
     * 마지막에 "user@host\0" 텍스트.
     */
    private fun buildAuthPublicKey(): ByteArray? = try {
        val key = loadOrGenerateKeyPair()
        val pub = key.public as? RSAPublicKey ?: return null
        val n = pub.modulus
        val bitCount = n.bitLength()
        val words = (bitCount + 31) / 32

        val two32 = BigInteger.ONE.shiftLeft(32)
        val nLow = n.mod(two32)                       // odd (RSA)
        val inv = nLow.modInverse(two32)              // n^-1 mod 2^32
        val n0inv = two32.subtract(inv).mod(two32)    // -inv mod 2^32

        val out = ByteArray(12 + words * 4) + AUTH_USER_LABEL.toByteArray(Charsets.US_ASCII)
        putLe(out, 0, words)
        putLe(out, 4, n0inv.toInt())
        putLe(out, 8, bitCount)
        for (i in 0 until words) {
            putLe(out, 12 + i * 4, n.shiftRight(32 * i).toInt())
        }
        out
    } catch (e: Exception) {
        println("LocalAdb: pubkey struct failed: ${e.javaClass.simpleName}: ${e.message}")
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
