package com.vm.laser

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

const val PORT_OUT = 50200
const val PORT_IN = 40200

/** Codifica del protocollo Ruida: byte "rimescolati" con una chiave (magic) e checksum a 16 bit. */
object Codec {
    fun sw(b: Int, m: Int): Int {
        var x = b and 0xFF
        x = x xor ((x shr 7) and 0xFF)
        x = x xor ((x shl 7) and 0xFF)
        x = x xor ((x shr 7) and 0xFF)
        x = x xor m
        return (x + 1) and 0xFF
    }

    fun un(b: Int, m: Int): Int {
        var x = (b - 1) and 0xFF
        x = x xor m
        x = x xor ((x shr 7) and 0xFF)
        x = x xor ((x shl 7) and 0xFF)
        x = x xor ((x shr 7) and 0xFF)
        return x
    }

    /** Intero a 35 bit in 5 byte da 7 bit (complemento a due). */
    fun enc35(v: Long): ByteArray {
        val u = v and ((1L shl 35) - 1)
        return ByteArray(5) { ((u shr (28 - 7 * it)) and 0x7F).toByte() }
    }

    fun dec35(b: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 5) v = (v shl 7) or (b[off + i].toLong() and 0x7F)
        if (v and (1L shl 34) != 0L) v -= 1L shl 35
        return v
    }

    fun packet(data: ByteArray, m: Int): ByteArray {
        val p = ByteArray(data.size) { sw(data[it].toInt(), m).toByte() }
        val sum = p.sumOf { it.toInt() and 0xFF } and 0xFFFF
        return byteArrayOf((sum shr 8).toByte(), sum.toByte()) + p
    }

    fun decode(d: ByteArray, len: Int, m: Int) = ByteArray(len) { un(d[it].toInt(), m).toByte() }
}

fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

fun udpSocket(timeoutMs: Int): DatagramSocket = DatagramSocket(null).apply {
    reuseAddress = false
    bind(InetSocketAddress(PORT_IN))
    soTimeout = timeoutMs
}

/** Client per un controller: ogni comando attende l'ACK, le letture anche la risposta. */
class Ruida(val host: InetAddress, val magic: Int) : Closeable {
    private val sock = udpSocket(500)
    private val buf = ByteArray(2048)

    @Synchronized
    fun send(data: ByteArray, wantReply: Boolean = false): ByteArray? {
        drain()
        val p = Codec.packet(data, magic)
        sock.send(DatagramPacket(p, p.size, host, PORT_OUT))
        recv()
        if (!wantReply) return null
        return try {
            recv()
        } catch (e: SocketTimeoutException) {
            null
        }
    }

    /** Legge un valore dalla memoria del controller (DA 00 addr -> DA 01 addr valore). */
    fun read(addr: Int): Long? {
        val r = send(bytes(0xDA, 0x00, addr shr 8, addr and 0xFF), true) ?: return null
        if (r.size >= 9 && r[0] == 0xDA.toByte() && r[1] == 0x01.toByte()) return Codec.dec35(r, 4)
        return null
    }

    private fun recv(): ByteArray {
        val dp = DatagramPacket(buf, buf.size)
        while (true) {
            sock.receive(dp)
            if (dp.address == host) return Codec.decode(dp.data, dp.length, magic)
        }
    }

    /** Scarta risposte vecchie rimaste nel buffer. */
    private fun drain() {
        sock.soTimeout = 1
        try {
            val dp = DatagramPacket(buf, buf.size)
            while (true) sock.receive(dp)
        } catch (_: SocketTimeoutException) {
        } finally {
            sock.soTimeout = 500
        }
    }

    override fun close() = sock.close()
}
