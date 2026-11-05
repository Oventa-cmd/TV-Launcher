package de.oventa.tvlauncher

import android.content.Context
import android.util.Base64
import java.io.DataInputStream
import java.io.File
import java.io.OutputStream
import java.math.BigInteger
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * Minimaler ADB-Client, der sich mit dem eigenen Geraet verbindet (127.0.0.1:5555).
 * Damit kann der Launcher Befehle mit Shell-Rechten ausfuehren, z. B. die
 * Google-TV-Startseite ab- und wieder einschalten. Voraussetzung: USB-Debugging an.
 * Beim ersten Mal fragt der Fernseher „USB-Debugging zulassen?“ – „Immer zulassen“ waehlen.
 */
object AdbLocal {
    private const val CNXN = 0x4e584e43
    private const val AUTH = 0x48545541
    private const val OPEN = 0x4e45504f
    private const val OKAY = 0x59414b4f
    private const val CLSE = 0x45534c43
    private const val WRTE = 0x45545257
    private const val VERSION = 0x01000000
    private const val MAXDATA = 256 * 1024

    class Result(val ok: Boolean, val output: String)

    private class Msg(val cmd: Int, val a0: Int, val a1: Int, val data: ByteArray)

    /** Fuehrt einen Shell-Befehl aus. Blockiert – nicht auf dem Haupt-Thread aufrufen. */
    fun shell(ctx: Context, command: String, authTimeoutMs: Int = 60_000): Result = try {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", 5555), 3000)
            s.soTimeout = authTimeoutMs
            s.tcpNoDelay = true
            val inp = DataInputStream(s.getInputStream().buffered())
            val out = s.getOutputStream()
            val keys = keyPair(ctx)

            send(out, CNXN, VERSION, MAXDATA, "host::\u0000".toByteArray())
            var sentSig = false
            var m = read(inp)
            while (m.cmd != CNXN) {
                if (m.cmd != AUTH || m.a0 != 1) return Result(false, "Unerwartete Antwort")
                if (!sentSig) {
                    send(out, AUTH, 2, 0, sign(keys, m.data)); sentSig = true
                } else {
                    // Schluessel noch nicht erlaubt: Fernseher zeigt jetzt die Abfrage
                    send(out, AUTH, 3, 0, publicKey(keys))
                }
                m = read(inp)
            }

            s.soTimeout = 30_000
            val local = 1
            send(out, OPEN, local, 0, "shell:$command\u0000".toByteArray())
            val sb = StringBuilder()
            var remote = 0
            while (true) {
                val r = read(inp)
                when (r.cmd) {
                    OKAY -> remote = r.a0
                    WRTE -> { sb.append(String(r.data)); send(out, OKAY, local, r.a0, ByteArray(0)) }
                    CLSE -> { if (remote != 0) send(out, CLSE, local, remote, ByteArray(0)); break }
                }
            }
            Result(true, sb.toString().trim())
        }
    } catch (e: java.net.ConnectException) {
        Result(false, "USB-Debugging ist aus (Entwickleroptionen)")
    } catch (e: java.net.SocketTimeoutException) {
        Result(false, "Keine Freigabe – am TV „Immer zulassen“ wählen und erneut versuchen")
    } catch (e: Exception) {
        Result(false, e.message ?: e.javaClass.simpleName)
    }

    private fun send(out: OutputStream, cmd: Int, a0: Int, a1: Int, data: ByteArray) {
        val b = ByteBuffer.allocate(24 + data.size).order(ByteOrder.LITTLE_ENDIAN)
        var sum = 0
        for (x in data) sum += (x.toInt() and 0xff)
        b.putInt(cmd).putInt(a0).putInt(a1).putInt(data.size).putInt(sum).putInt(cmd.inv()).put(data)
        out.write(b.array()); out.flush()
    }

    private fun read(inp: DataInputStream): Msg {
        val h = ByteArray(24); inp.readFully(h)
        val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        val cmd = b.int; val a0 = b.int; val a1 = b.int; val len = b.int
        val data = ByteArray(len); if (len > 0) inp.readFully(data)
        return Msg(cmd, a0, a1, data)
    }

    // --- Schluessel (einmal erzeugt, im App-Speicher) ---

    private fun keyPair(ctx: Context): KeyPair {
        val priv = File(ctx.filesDir, "adbkey"); val pub = File(ctx.filesDir, "adbkey.pub")
        val kf = KeyFactory.getInstance("RSA")
        if (priv.exists() && pub.exists()) try {
            return KeyPair(kf.generatePublic(X509EncodedKeySpec(pub.readBytes())), kf.generatePrivate(PKCS8EncodedKeySpec(priv.readBytes())))
        } catch (_: Exception) { }
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        priv.writeBytes(kp.private.encoded); pub.writeBytes(kp.public.encoded)
        return kp
    }

    private val SHA1_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    )

    /** ADB signiert das Token wie einen SHA-1-Hash (PKCS#1 v1.5). */
    private fun sign(kp: KeyPair, token: ByteArray): ByteArray =
        Cipher.getInstance("RSA/ECB/PKCS1Padding").apply { init(Cipher.ENCRYPT_MODE, kp.private) }.doFinal(SHA1_PREFIX + token)

    /** Oeffentlicher Schluessel im ADB-Format (wie ~/.android/adbkey.pub). */
    private fun publicKey(kp: KeyPair): ByteArray {
        val k = kp.public as RSAPublicKey
        val words = 64
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n = k.modulus
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).toInt()
        val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
        val b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(words).putInt(n0inv)
        fun putLE(v: BigInteger) { var x = v; repeat(words) { b.putInt(x.mod(r32).toInt()); x = x.shiftRight(32) } }
        putLE(n); putLE(rr)
        b.putInt(k.publicExponent.toInt())
        val txt = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " tvlauncher@tv\u0000"
        return txt.toByteArray()
    }
}
