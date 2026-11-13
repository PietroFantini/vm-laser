package com.vm.laser

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Comandi verificati sul controller RDC6334G (registrati da RDWorks):
 * - C9 02 <velocità µm/s>   velocità di jog
 * - D8 2n / D8 3n           tasto jog premuto / rilasciato
 *   n: 0 = X+, 1 = X-, 2 = Y-, 3 = Y+, 4/5 = Z (direzione da verificare)
 * - D9 <asse> 02 <delta µm> spostamento relativo, non interrompibile
 * - D9 10 02 <dx> <dy>      spostamento relativo X+Y in linea retta
 * - D8 02 / D8 03 / D8 01   pausa / continua / stop del lavoro
 * - registro 0x0400         stato: bit 0 = in lavoro, bit 1 = in pausa
 * - registro 0x0405         numero di file in memoria
 * - E8 01 00 nn             nome del file nn (risposta: E8 01 00 nn <nome> 00)
 * - E8 03 00 nn             avvia il file nn dalla memoria (verificato)
 * - E8 03 00 00             avvia l'ultimo lavoro inviato (file 0, verificato)
 * Laser mai acceso: nessun comando di emissione viene mai inviato.
 */
class Laser(private val ctx: Context) {
    val machines = Machines(ctx)

    private val jog = mapOf(("x" to 1) to 0, ("x" to -1) to 1, ("y" to -1) to 2, ("y" to 1) to 3, ("z" to 1) to 4, ("z" to -1) to 5)
    private val axisCode = mapOf("x" to 0x00, "y" to 0x01, "z" to 0x02)
    private val posAddr = mapOf("x" to 0x0421, "y" to 0x0431, "z" to 0x0441)
    private val maxSpeed = mapOf("x" to 300.0, "y" to 300.0, "z" to 20.0)

    private val watchdogS = 0.3
    private val activeS = 1.5
    private val latencyS = 0.04
    private val followEndS = 30.0

    private var client: Ruida? = null
    private var machine: Machine? = null
    private var accMmS2 = 2000.0

    @Volatile private var held: Pair<String, Int>? = null
    @Volatile private var beat = 0.0
    @Volatile private var lastActive = 0.0
    @Volatile private var speed = 50.0
    private val pos = HashMap<String, Long?>()

    @Volatile private var followTarget: Map<String, Long>? = null
    @Volatile private var dragging = false
    @Volatile private var endedAt = 0.0
    private val followKeys = HashMap<String, Int?>()

    @Volatile private var status: Long? = null
    private var lastStatus = 0.0

    @Volatile private var scanning = false
    @Volatile private var portBusy = false // ricerca o verifica IP: la porta 40200 serve libera
    private var lastConnectTry = 0.0
    @Volatile private var scanMsg = ""
    @Volatile private var scanFound: List<Machine> = emptyList()

    init {
        thread(isDaemon = true, name = "laser-loop") { loop() }
    }

    private fun now() = System.nanoTime() / 1e9

    // --- connessione -----------------------------------------------------------

    @Synchronized
    private fun connect(m: Machine?) {
        stopAll()
        client?.close()
        client = null
        machine = m
        pos.clear()
        if (m == null) return
        val c = Ruida(InetAddress.getByName(m.ip), m.magic)
        client = c
        // accelerazione jog del controller (parametro 0x0028), per calcolare la frenata
        c.read(0x0028)?.takeIf { it > 0 }?.let { accMmS2 = it / 1000.0 }
        readAll()
    }

    private fun ensure(): Ruida {
        if (client == null && !portBusy) machines.current()?.let { connect(it) }
        return client ?: error("Nessuna macchina selezionata")
    }

    fun close() {
        stopAll()
        client?.close()
        client = null
    }

    // --- comandi base ------------------------------------------------------------

    private fun send(data: ByteArray) {
        ensure().send(data)
    }

    private fun readAll() {
        val c = client ?: return
        for ((k, a) in posAddr) c.read(a)?.let { synchronized(pos) { pos[k] = it } }
        readStatus()
    }

    private fun readStatus() {
        client?.read(0x0400)?.let { status = it }
        lastStatus = now()
    }

    private fun jobState(): String = status?.let { st ->
        when {
            st and 2L != 0L -> "paused"
            st and 1L != 0L -> "running"
            else -> "idle"
        }
    } ?: "unknown"

    private fun job(action: String) {
        when (action) {
            "pause" -> send(bytes(0xD8, 0x02))
            "resume" -> send(bytes(0xD8, 0x03))
            "stop" -> {
                send(bytes(0xD8, 0x01))
                stopAll()
            }
            // D8 00 arma la macchina ma non avvia nulla senza un file selezionato in memoria
            "start" -> error("INIZIO non ancora attivo: manca il comando di avvio del file in memoria")
            else -> error("Azione sconosciuta: $action")
        }
        readStatus()
    }

    /** File salvati nella memoria del controller. */
    private fun files(): JSONArray {
        val c = ensure()
        val n = c.read(0x0405) ?: error("Impossibile leggere la memoria della macchina")
        // il file 0 è l'ultimo lavoro inviato (es. con lo Start di RDWorks), non compare nell'elenco
        val out = JSONArray().put(JSONObject().put("n", 0).put("name", "Ultimo lavoro inviato").put("last", true))
        for (i in 1..n.toInt().coerceAtMost(255)) {
            val r = c.send(bytes(0xE8, 0x01, i shr 8, i and 0xFF), true) ?: continue
            if (r.size < 5 || r[0] != 0xE8.toByte()) continue
            val name = String(r, 4, r.size - 4, Charsets.ISO_8859_1).substringBefore('\u0000')
            out.put(JSONObject().put("n", i).put("name", name).put("last", false))
        }
        return out
    }

    private fun startFile(n: Int) {
        require(n in 0..255)
        if (status?.let { it and 1L != 0L } == true) error("La macchina sta già lavorando")
        followStop()
        release()
        send(bytes(0xE8, 0x03, n shr 8, n and 0xFF))
        readStatus()
    }

    /** Porta la testa a X 0, Y 0 in linea retta. */
    private fun home(mmS: Double) {
        followStop()
        release()
        readAll()
        val x = synchronized(pos) { pos["x"] } ?: error("Posizione non disponibile")
        val y = synchronized(pos) { pos["y"] } ?: error("Posizione non disponibile")
        if (status?.let { it and 1L != 0L } == true) error("La macchina sta lavorando")
        setSpeed("x", mmS)
        send(bytes(0xD9, 0x10, 0x02) + Codec.enc35(-x) + Codec.enc35(-y))
        // continua a leggere la posizione per tutta la durata stimata del movimento
        lastActive = now() + maxOf(x, y) / 1000.0 / speed
    }

    private fun setSpeed(axis: String, mmS: Double) {
        val v = mmS.coerceIn(0.5, maxSpeed.getValue(axis))
        send(bytes(0xC9, 0x02) + Codec.enc35((v * 1000).toLong()))
        speed = v
    }

    private fun press(axis: String, sign: Int, mmS: Double) {
        followStop()
        release()
        setSpeed(axis, mmS)
        val t = now()
        held = axis to sign
        beat = t
        lastActive = t
        send(bytes(0xD8, 0x20 + jog.getValue(axis to sign)))
    }

    private fun release() {
        val h = held ?: return
        held = null
        // UDP può perdere pacchetti: il rilascio si manda più volte.
        repeat(3) { runCatching { send(bytes(0xD8, 0x30 + jog.getValue(h))) } }
        lastActive = now()
    }

    private fun step(axis: String, sign: Int, stepUm: Long, mmS: Double) {
        followStop()
        release()
        setSpeed(axis, mmS)
        val delta = sign * stepUm.coerceIn(100, 10_000)
        send(bytes(0xD9, axisCode.getValue(axis), 0x02) + Codec.enc35(delta))
        lastActive = now()
    }

    /** Ferma ogni movimento a tasto (anche quelli di un'eventuale sessione precedente). */
    fun stopAll() {
        followStop()
        release()
        client?.let { c -> for (n in 0..5) repeat(2) { runCatching { c.send(bytes(0xD8, 0x30 + n)) } } }
    }

    // --- inseguimento del puntatore ------------------------------------------------

    private fun followKey(axis: String, sign: Int?) {
        val cur = followKeys[axis]
        if (cur == sign) return
        if (cur != null) repeat(2) { send(bytes(0xD8, 0x30 + jog.getValue(axis to cur))) }
        if (sign != null) send(bytes(0xD8, 0x20 + jog.getValue(axis to sign)))
        followKeys[axis] = sign
    }

    private fun followStop() {
        followTarget = null
        dragging = false
        for (a in listOf("x", "y")) runCatching { followKey(a, null) }
    }

    private fun followTo(x: Long, y: Long, mmS: Double) {
        val m = machine ?: error("Nessuna macchina selezionata")
        if (followTarget == null) release()
        if (followTarget == null || abs(speed - mmS) > 0.05) setSpeed("x", mmS)
        followTarget = mapOf("x" to x.coerceIn(0, m.w), "y" to y.coerceIn(0, m.h))
        dragging = true
        beat = now()
        lastActive = beat
    }

    /** Distanze (µm) di ripartenza e rilascio, scalate sulla velocità attuale. */
    private fun thresholds(): Pair<Double, Double> {
        val v = speed
        val stop = (v * latencyS + v * v / (2 * accMmS2) + 0.1) * 1000
        return stop + 200 to stop
    }

    private fun followStep(t: Double) {
        if (dragging && t - beat > watchdogS + 0.2) return followStop()
        lastActive = t
        readAll()
        val target = followTarget ?: return
        val (startUm, stopUm) = thresholds()
        var settled = true
        for (a in listOf("x", "y")) {
            val p = synchronized(pos) { pos[a] } ?: continue
            val err = (target.getValue(a) - p).toDouble()
            val key = followKeys[a]
            if (key != null && key * err > stopUm) {
                settled = false
            } else if (key == null && abs(err) > startUm) {
                followKey(a, if (err > 0) 1 else -1)
                settled = false
            } else {
                followKey(a, null)
            }
        }
        if (!dragging && (settled || t - endedAt > followEndS)) followStop()
    }

    private fun loop() {
        while (true) {
            Thread.sleep(40)
            try {
                if (client == null) {
                    // riconnessione automatica alla macchina salvata (anche all'avvio dell'app)
                    if (!portBusy && machines.current() != null && now() - lastConnectTry > 3) {
                        lastConnectTry = now()
                        runCatching { connect(machines.current()) }
                    }
                    continue
                }
                val t = now()
                if (followTarget != null) {
                    followStep(t)
                    continue
                }
                if (held != null && t - beat > watchdogS) release()
                // durante un lavoro (anche in pausa) la posizione si legge di continuo
                val working = status?.let { it and 3L != 0L } == true
                if (held != null || working || t - lastActive < activeS) readAll()
                else if (t - lastStatus > 1.0) readStatus()
            } catch (_: Exception) {
            }
        }
    }

    // --- ricerca macchine ------------------------------------------------------------

    private fun startScan() {
        if (scanning) return
        scanning = true
        portBusy = true
        scanFound = emptyList()
        scanMsg = "Avvio ricerca…"
        thread(isDaemon = true, name = "laser-scan") {
            try {
                connect(null) // la porta 40200 serve libera per la ricerca
                scanFound = Discovery.scan(ctx) { scanMsg = it }
                scanMsg = if (scanFound.isEmpty()) "Nessuna macchina trovata" else "Trovate: ${scanFound.size}"
            } catch (e: Exception) {
                scanMsg = "Errore: ${e.message}"
            } finally {
                scanning = false
                portBusy = false
                runCatching { machines.current()?.let { connect(it) } }
            }
        }
    }

    private fun machinesJson() = JSONObject()
        .put("list", JSONArray(machines.list().map { it.json() }))
        .put("selected", machines.selected)

    // --- API per la pagina ---------------------------------------------------------------

    fun handle(path: String, d: JSONObject): JSONObject {
        val ok = JSONObject().put("ok", true)
        fun axis() = d.getString("axis").also { require(it in posAddr) }
        fun sign() = d.getInt("sign").also { require(it == 1 || it == -1) }
        return when (path) {
            "/api/pos" -> {
                val m = machine ?: machines.current()
                JSONObject().apply {
                    synchronized(pos) { for (k in posAddr.keys) put(k, pos[k] ?: JSONObject.NULL) }
                    put("w", m?.w ?: JSONObject.NULL)
                    put("h", m?.h ?: JSONObject.NULL)
                    put("machine", m?.json() ?: JSONObject.NULL)
                    put("job", jobState())
                }
            }
            "/api/press" -> ok.also { press(axis(), sign(), d.optDouble("speed", 50.0)) }
            "/api/step" -> ok.also { step(axis(), sign(), d.optLong("step", 1000), d.optDouble("speed", 50.0)) }
            "/api/beat" -> ok.also { beat = now() }
            "/api/release" -> ok.also { release() }
            "/api/follow" -> ok.also { followTo(d.getLong("x"), d.getLong("y"), d.optDouble("speed", 50.0)) }
            "/api/follow_end" -> ok.also { dragging = false; endedAt = now() }
            "/api/stop" -> ok.also { stopAll() }
            // INIZIO/PAUSA/STOP del lavoro: si attivano dopo aver verificato i comandi registrando RDWorks
            "/api/job" -> ok.also { job(d.getString("action")) }
            "/api/home" -> ok.also { home(d.optDouble("speed", 50.0)) }
            "/api/files" -> JSONObject().put("files", files())
            "/api/start_file" -> ok.also { startFile(d.getInt("n")) }
            "/api/machines" -> machinesJson()
            "/api/scan" -> ok.also { startScan() }
            "/api/scan_status" -> JSONObject().put("running", scanning).put("msg", scanMsg)
                .put("found", JSONArray(scanFound.map { it.json() }))
            "/api/probe" -> {
                if (portBusy) error("Ricerca in corso")
                portBusy = true
                connect(null)
                try {
                    Discovery.probe(d.getString("ip").trim())?.json()?.let { JSONObject().put("machine", it) }
                        ?: error("Nessun controller Ruida a questo indirizzo")
                } finally {
                    portBusy = false
                    runCatching { machines.current()?.let { connect(it) } }
                }
            }
            "/api/save" -> {
                machines.save(Machine.of(d.getJSONObject("machine")))
                machinesJson()
            }
            "/api/select" -> {
                val ip = d.getString("ip")
                machines.selected = ip
                connect(machines.current())
                machinesJson()
            }
            "/api/delete" -> {
                val ip = d.getString("ip")
                if (machine?.ip == ip) connect(null)
                machines.delete(ip)
                machinesJson()
            }
            else -> error("Comando sconosciuto: $path")
        }
    }

    fun safeStop() = runCatching { stopAll() }
}
