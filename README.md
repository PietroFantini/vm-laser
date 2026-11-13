# VM Laser

App Android per comandare un laser con controller **Ruida** (testata su un Wedge Laser 1600×1000 con
**RDC6334G**) direttamente da un tablet, via Wi-Fi. Nata per sostituire il pannello originale con
lo schermo guasto.

> ⚠️ **Usala a tuo rischio e pericolo.** Comanda un laser vero: nessuna garanzia, nessuna conferma sui
> pulsanti (INIZIO avvia il lavoro al primo tocco). Vedi la [licenza](LICENSE).

## Cosa fa

- **Movimento**: frecce continue o a passi (0,1 / 1 / 10 mm), asse Z, velocità regolabile, HOME (X0 Y0).
- **Piano di lavoro**: trascini il punto rosso dove vuoi spostare la testa; zoom e spostamento
  con due dita (la velocità si divide per lo zoom); "Inverti X" per guardarlo dal davanti.
- **Lavoro**: INIZIO (ultimo lavoro inviato oppure file scelto dalla memoria della macchina),
  PAUSA/CONTINUA, STOP, stato in tempo reale e percorso della testa durante il lavoro.
- **Ricerca in rete**: trova da sola i controller Ruida sulla rete Wi-Fi del tablet, oppure aggiungi
  la macchina per indirizzo IP. Puoi salvarne più di una.
- **Sicurezza di base**: se l'interfaccia smette di rispondere i movimenti si fermano; uscendo
  dall'app ogni movimento viene interrotto. All'avvio compare un'avvertenza da confermare.

## Cosa serve

- Un laser con controller **Ruida** raggiungibile in rete (UDP, porte 50200 / 40200).
- Un dispositivo Android (minSdk 26) sulla **stessa rete Wi-Fi** del laser.
- Il controller risponde all'ultimo computer che gli scrive: usa **un solo programma alla volta**
  (RDWorks, questa app, script vari).

## Compilare

```
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Serve Android Studio / SDK Android (compileSdk 36) e JDK 17. Per la build `release` firmata crea
la tua chiave e un file `keystore/keystore.properties` con `storeFile`, `storePassword`, `keyAlias`,
`keyPassword`: la cartella `keystore/` è ignorata da git e non va mai pubblicata.

## Struttura

| File | Cosa contiene |
|---|---|
| `app/src/main/assets/index.html` | interfaccia (HTML/JS dentro una WebView) |
| `Ruida.kt` | protocollo UDP del controller |
| `Laser.kt` | logica: jog, HOME, lavoro, stato, watchdog |
| `Machines.kt` | macchine salvate e ricerca in rete |
| `Bridge.kt` | ponte tra la pagina e il codice Kotlin |

I dettagli del protocollo, ricavati analizzando il traffico di RDWorks, sono in
[docs/PROTOCOLLO.md](docs/PROTOCOLLO.md).

## Limiti noti

- Testata solo su una macchina; altri controller Ruida potrebbero rispondere in modo diverso.
- La direzione dell'asse Z (Z▲ / Z▼) non è stata verificata.
- La macchina testata ha un problema noto ai tasti HOME e INIZIO (a volte vibra al rientro di HOME
  e INIZIO non sempre parte): è un difetto della macchina presente anche prima.

## Licenza

[MIT](LICENSE): fanne quello che vuoi, a tuo rischio e pericolo.
