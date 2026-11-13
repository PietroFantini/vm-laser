package com.vm.laser

import android.content.Context
import android.net.ConnectivityManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException

data class Machine(val ip: String, val name: String, val magic: Int, val w: Long, val h: Long) {
    fun json(): JSONObject = JSONObject().put("ip", ip).put("name", name).put("magic", magic).put("w", w).put("h", h)

    companion object {
        fun of(o: JSONObject) = Machine(o.getString("ip"), o.getString("name"), o.getInt("magic"), o.getLong("w"), o.getLong("h"))
    }
}

/** Macchine salvate sul dispositivo e macchina in uso. */
class Machines(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("machines", Context.MODE_PRIVATE)

    fun list(): List<Machine> {
        val a = JSONArray(prefs.getString("list", "[]"))
        return (0 until a.length()).map { Machine.of(a.getJSONObject(it)) }
    }

    private fun store(l: List<Machine>) = prefs.edit().putString("list", JSONArray(l.map { it.json() }).toString()).apply()

    fun save(m: Machine) = store(list().filter { it.ip != m.ip } + m)

    fun delete(ip: String) {
        store(list().filter { it.ip != ip })
        if (selected == ip) selected = null
    }

    var selected: String?
        get() = prefs.getString("selected", null)
        set(v) = prefs.edit().putString("selected", v).apply()

    fun current(): Machine? = list().firstOrNull { it.ip == selected }
}

/** Ricerca dei controller Ruida nella rete locale. */
object Discovery {
    // Chiavi di codifica note dei controller Ruida (634x, 644x e altri)
    private val MAGICS = listOf(0x11, 0x88, 0x38)
    private const val ADDR_W = 0x0026
    private const val ADDR_H = 0x0036

    /** Indirizzi da interrogare: la rete del dispositivo, al massimo una /24. */
    fun subnetHosts(ctx: Context): List<InetAddress> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: error("Nessuna rete attiva: collega il tablet al Wi-Fi")
        val la = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: error("Nessun indirizzo IPv4")
        val own = la.address.address
        val prefix = maxOf(la.prefixLength, 24)
        val ip = own.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
        val mask = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val base = ip and mask
        val count = 1L shl (32 - prefix)
        return (1 until count - 1).map { base + it }.filter { it != ip }.map { a ->
            InetAddress.getByAddress(ByteArray(4) { i -> (a shr (24 - 8 * i)).toByte() })
        }
    }

    /** Interroga tutti gli host con ogni chiave e ritorna le macchine che rispondono. */
    fun scan(ctx: Context, onProgress: (String) -> Unit): List<Machine> {
        val hosts = subnetHosts(ctx)
        onProgress("Cerco su ${hosts.size} indirizzi…")
        val found = LinkedHashMap<String, Pair<Int, Long>>()
        udpSocket(200).use { s ->
            for (m in MAGICS) {
                val p = Codec.packet(bytes(0xDA, 0x00, ADDR_W shr 8, ADDR_W and 0xFF), m)
                for (h in hosts) s.send(DatagramPacket(p, p.size, h, PORT_OUT))
                Thread.sleep(30)
            }
            val buf = ByteArray(2048)
            val end = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < end) {
                val dp = DatagramPacket(buf, buf.size)
                try {
                    s.receive(dp)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                for (m in MAGICS) {
                    val d = Codec.decode(dp.data, dp.length, m)
                    if (d.size >= 9 && d[0] == 0xDA.toByte() && d[1] == 0x01.toByte() && d[2] == 0.toByte() && d[3] == ADDR_W.toByte()) {
                        found[dp.address.hostAddress!!] = m to Codec.dec35(d, 4)
                    }
                }
            }
        }
        return found.map { (ip, v) ->
            val (magic, w) = v
            val h = Ruida(InetAddress.getByName(ip), magic).use { it.read(ADDR_H) } ?: 0L
            Machine(ip, "Laser ${w / 1000}×${h / 1000}", magic, w, h)
        }
    }

    /** Verifica un IP inserito a mano provando le chiavi note. */
    fun probe(ip: String): Machine? {
        val addr = InetAddress.getByName(ip)
        for (m in MAGICS) {
            Ruida(addr, m).use { r ->
                val w = try {
                    r.read(ADDR_W)
                } catch (_: SocketTimeoutException) {
                    null
                } ?: return@use
                val h = r.read(ADDR_H) ?: 0L
                return Machine(ip, "Laser ${w / 1000}×${h / 1000}", m, w, h)
            }
        }
        return null
    }
}
