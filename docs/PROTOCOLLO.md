# Protocollo Ruida (UDP) e funzionamento dell'app

Note ricavate analizzando il traffico di RDWorks verso un controller **Ruida RDC6334G**
(laser Wedge 1600×1000). Tutto è stato verificato su quella macchina; su altri controller Ruida
potrebbe differire.

## Rete

- Il controller si comanda via **UDP**: si scrive sulla porta **50200** del laser e le risposte
  tornano sulla porta **40200** del computer.
- Il controller risponde all'ultimo computer che gli scrive: due programmi insieme
  (RDWorks, uno script, questa app) si rubano le risposte. Usarne uno alla volta.

## Formato dei pacchetti

Ogni pacchetto UDP = 2 byte di checksum (somma dei byte, big-endian) + byte "rimescolati" con una
chiave **magic** (`0x11` sulla macchina testata; l'app prova anche `0x88` e `0x38`).
I numeri sono a 35 bit in 5 byte da 7 bit, in µm o µm/s. Ogni comando riceve un ACK `CC`.

### Lettura

| Comando | Significato |
|---|---|
| `DA 00 04 21` / `04 31` / `04 41` | posizione X / Y / Z |
| `DA 00 00 26` / `00 36` | larghezza piano X / Y |
| `DA 00 00 28` | accelerazione jog X |
| `DA 00 04 00` | stato: `…600` a riposo, `…401` in lavoro, `…403` in pausa (bit 0 lavoro, bit 1 pausa) |
| `DA 00 04 05` | numero di file in memoria |
| `E8 01 00 nn` | nome del file nn |

### Movimento (laser spento)

| Comando | Significato |
|---|---|
| `C9 02 <v>` | velocità di jog (vale per la sessione, non viene salvata) |
| `D8 2n` / `D8 3n` | tasto jog premuto / rilasciato. n: 0 = X+, 1 = X−, 2 = Y−, 3 = Y+, 4/5 = Z (**direzione Z non verificata**) |
| `D9 <asse> 02 <delta>` | spostamento relativo (asse 0 = X, 1 = Y, 2 = Z). **Non si interrompe** una volta partito |
| `D9 10 02 <dx> <dy>` | spostamento relativo X+Y in linea retta |

### Lavoro

| Comando | Significato |
|---|---|
| `E8 03 00 00` | **avvia l'ultimo lavoro inviato** |
| `E8 03 00 nn` | avvia il file nn della memoria |
| `D8 02` / `D8 03` | pausa / continua |
| `D8 01` | stop del lavoro |

Cose che **non** funzionano: `D8 00` da solo (arma la macchina ma non parte niente), i tasti
pannello simulati `A5 50/51`, e `D8 01` non ferma uno spostamento `D9`.

## Struttura dell'app

- Kotlin + WebView. L'interfaccia è `app/src/main/assets/index.html`, che chiama l'app con
  `VM.call("/api/...", json)` (ponte in `Bridge.kt`).
- `Ruida.kt`: protocollo. `Laser.kt`: logica (jog, passi, inseguimento del punto, HOME, lavoro,
  stato, watchdog). `Machines.kt`: macchine salvate e ricerca in rete.
- **Ricerca in rete**: interroga tutta la /24 del Wi-Fi a cui è collegato il dispositivo e
  riconosce i controller Ruida leggendo le dimensioni del piano. Nessun indirizzo IP scritto nel codice.
- **Sicurezza**: se la pagina smette di mandare il "battito" (0,3 s) i tasti jog vengono rilasciati;
  uscendo dall'app ogni movimento si ferma. Non ci sono conferme sui pulsanti: **INIZIO accende
  il laser al primo tocco**.

## Come ricavare i comandi

Si registra il traffico di RDWorks (su Windows, con `pktmon`), si usa RDWorks e poi si decodifica
la cattura:

```
pktmon filter add rdw -i <IP-del-laser> -t UDP
pktmon start --capture --pkt-size 0 --file-name C:\temp\rdw.etl
# ... usare RDWorks ...
pktmon stop
pktmon etl2pcap C:\temp\rdw.etl --out C:\temp\rdw.pcapng
pktmon filter remove
```

## Da fare

- Verificare la direzione di Z▲ / Z▼ (`D8 24` / `D8 25`).
- Confermare zoom e trascinamento a due dita sul touch.
