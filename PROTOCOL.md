# VaPian! — Protocollo Bluetooth telefono ↔ autoradio (v2, bozza)

> Contesto e decisioni: [`ARCHITECTURE.md`](ARCHITECTURE.md).
>
> **Cambio rispetto alla v1.** L'indagine sulla head unit target ha mostrato che gira
> su **Android 8.1 (SDK 27)** e che **non ha il Bluetooth Low Energy** (`android.hardware.bluetooth_le` assente,
> `mMaxScanFilters=0`). La v1 (GATT, advertising, L2CAP CoC) non è realizzabile. La v2 usa **Bluetooth Classic RFCOMM**
> (serial port), disponibile su entrambi i dispositivi. Numeri in **little-endian**.

## 1. Trasporto

- **RFCOMM** su un socket con UUID proprietario `7a1c0001-5f3e-4b1a-9c1d-5a0b0c0d0e01` (provvisorio, da congelare).
- **Telefono = server**: `listenUsingRfcommWithServiceRecord("VaPian", UUID)` mentre l'app è attiva o il servizio di
  collegamento è in esecuzione. **Autoradio = client**: `createRfcommSocketToServiceRecord(UUID)`, riprova con backoff
  1 s → 30 s.
- **Associazione**: i due dispositivi sono già associati per l'audio (A2DP/AVRCP/HFP). L'utente sceglie una volta il
  telefono tra i dispositivi associati dell'autoradio; l'indirizzo resta **solo in locale** sull'autoradio e non
  viaggia mai nei messaggi.
- La connessione è cifrata dal link Bluetooth associato (`createRfcommSocket…` sicuro, non `Insecure`).
- Lo stack Bluetooth dell'autoradio è **soggetto a blocchi frequenti**: ogni funzione deve tollerare cadute del
  link in qualsiasi momento (riconnessione automatica, coda `outbox`, trasferimenti ripetibili da capo).
- Il canale è uno stream affidabile e ordinato: niente MTU, niente frammentazione, niente finestre di ack. La
  robustezza sta nel framing e nelle conferme di livello applicativo.

## 2. Framing

```
u16 magic     0x5650 ("VP")
u8  type      tipo messaggio (§4–§8)
u8  flags     riservato, 0
u32 length    lunghezza payload (max 262144)
...payload
u32 crc32     CRC32 di header + payload
```

- Il ricevente scarta byte finché non trova `magic`, poi valida `length` e `crc32`; un frame non valido viene scartato
  e contato (3 in 10 s → chiusura del socket).
- Payload: **CBOR** (`kotlinx-serialization-cbor`) per i messaggi di controllo; **binario a record fissi** per i
  dataset (§6.1).
- Un frame alla volta, in ordine; non c'è multiplexing. Un trasferimento lungo (dataset, cover) occupa il canale e
  i messaggi urgenti (`REPORT`, `PING`) attendono la fine del frame in corso: i chunk sono piccoli (≤ 16 KiB) per
  restare reattivi.

## 3. Handshake e versione

1. L'autoradio apre il socket e invia `HELLO {proto: 2, appVersion, deviceId, role: "radio", caps: [dataset, report, media]}`.
2. Il telefono risponde `HELLO_ACK {proto, deviceId, role: "phone", now}`; `proto` = il minore dei due.
   Se incompatibile → `ERROR{PROTO_UNSUPPORTED}` e chiusura.
3. `deviceId` = UUID casuale generato al primo avvio (`UserSession.localKey`), mai derivato da identificativi hardware.
4. **Heartbeat** `PING`/`PONG` ogni 15 s; 3 mancati → link caduto, il client riconnette.

## 4. Segnalazioni (autoradio → telefono)

`REPORT_CAMERA` (CBOR):

```
{ id: <uuid16>,           // idempotenza: generato sull'autoradio al tap
  lat, lng, speedKmh, bearingDeg,
  limitKmh,               // stima come oggi (arrotondamento a scaglione standard)
  ts,                     // epoch ms al tap (UTC)
  deviceId }
```

`REPORT_IGNORE {cameraId, ts}` per "Ignora velox" (solo OSM).

- L'autoradio scrive la segnalazione in locale (Room, `dirty=1`, coda `outbox`) **prima** di inviarla: funziona anche
  senza telefono.
- Il telefono la converte in una riga `speed_cameras` (`pending`, `user`, `createdBy` = proprio uid, `id` = `<uuid>`)
  più una riga in `sync_queue`, **idempotente sull'`id`**: un duplicato viene riconfermato senza reinserirlo.
- Conferma: `ACK {id}` solo **dopo** la scrittura in Room. L'autoradio toglie la riga dall'outbox all'ack.
- Alla riconnessione l'autoradio rinvia tutto l'outbox non confermato, in ordine.
- Validazione sul telefono: coordinate valide, `ts` non nel futuro oltre 5 min, rate-limit 1 segnalazione/2 s per
  `deviceId`.
- Annullamento entro 5 s: avviene **prima** dell'invio, sull'autoradio.

## 5. Dataset (telefono → autoradio)

Unità di trasferimento: la **regione**. Una fotografia per regione è più semplice e robusta di un delta, e a queste
dimensioni basta.

### 5.1 Record camera (binario, 32 byte)

```
 0  u8[16] id          UUID (segnalazioni utente) oppure 8 byte a 0 + u64 osmNodeId (id "osm_<n>")
16  i32    lat_e7      latitudine * 1e7
20  i32    lng_e7      longitudine * 1e7
24  u8     limitKmh    0 = sconosciuto
25  u16    direction   0..359, 0xFFFF = assente
27  u8     flags       bit0 status(0=pending,1=approved) | bit1 source(0=user,1=openstreetmap)
                       | bit2 directionReversed | bit3 deleted (tombstone)
28  u32    updatedAt   epoch secondi
```

Il geohash non viaggia: l'autoradio lo ricalcola. Includi le `approved` e le `pending` proprie; non le `pending` altrui.

### 5.2 Flusso

1. `DATASET_MANIFEST_REQ {have: {regione: version}}` (autoradio).
2. `DATASET_MANIFEST {regions: [{name, version, count, bytes, crc32, downloadedAt}], blacklistVersion, settingsVersion}`.
   `version` = istante del download OSM della regione sul telefono, in secondi: cambia solo con un nuovo download.
3. L'autoradio chiede ciò che ha `version` diversa: `DATASET_FETCH {name}` (l'utente può restringere la scelta).
4. Il telefono invia `DATASET_BEGIN {name, count, bytes, crc32}`, poi `DATASET_CHUNK {name, offset, data}` (≤ 16 KiB), poi
   `DATASET_END {name}`.
5. L'autoradio verifica il CRC32 totale, scrive in `dataset_staging` e **in un'unica transazione** sostituisce le righe
   pulite della regione e aggiorna `dataset_meta`. Se il link cade o il CRC non torna: scarta e ripete da capo la regione.
6. Poi `BLACKLIST_SNAPSHOT {cameraIds[]}` e `SETTINGS_SNAPSHOT {alertDistanceMode, alertDistanceMeters, toleranceFilterEnabled}`.
7. `lastDatasetReceivedAt` si aggiorna solo a trasferimento riuscito (alimenta il promemoria a 14 giorni).

Le righe locali dell'autoradio con `dirty=1` (segnalazioni non ancora confermate) **non** vengono toccate dallo swap.

Dimensioni: 10.000 record = 320 KB. RFCOMM regge in pratica 50–150 KB/s: **pochi secondi**.

## 6. Media (opzionale)

Percorso primario da decidere dopo i test sul campo; sono previsti entrambi dietro un'interfaccia `MediaSource`:

- **AVRCP nativo**: l'autoradio ha A2DP sink e AVRCP controller (multi-player disattivo, volume assoluto no). Il servizio
  `A2dpMediaBrowserService` risulta registrato; i metadati andranno verificati con il telefono che suona. Su questo
  stack, fragile, può dare dati incompleti.
- **Canale RFCOMM proprietario** (più controllabile, serve l'app sul telefono): `MEDIA_STATE` (CBOR `{state, title, artist,
  album, durationMs, positionMs, ts, app, coverId}`, posizione ogni 1 s solo in `playing`), `MEDIA_CMD {cmd: play|pause|next|prev|seek, positionMs?}`,
  `COVER_REQ {coverId}` → `COVER {coverId, jpeg}` (JPEG ≤ 128×128, qualità ~70: la memoria heap delle app è limitata a 144 MB).
- Sul telefono l'app sorgente si legge con `MediaSessionManager` + `NotificationListenerService` (permesso concesso dall'utente).

La posizione **non** viaggia più dal telefono: l'autoradio ha un GPS integrato valido (fix con velocità e direzione).

## 7. Errori e sicurezza

- `ERROR {code, detail}`: `PROTO_UNSUPPORTED`, `BAD_PAYLOAD`, `RATE_LIMITED`, `BUSY`, `INTERNAL`.
- Ogni payload è validato in lunghezza e intervallo prima dell'uso; i messaggi malformati si scartano e si contano.
- Nessun dato fuori dai campi documentati; nessun identificativo hardware (IMEI, MAC) nei messaggi.
- Compatibilità: i cambi compatibili aggiungono campi CBOR opzionali; i cambi incompatibili alzano `proto`.

## 8. Da verificare sull'autoradio

UUID definitivi; velocità reale di RFCOMM verso un telefono vero; quanto spesso si blocca lo stack e come ripartono i
socket dopo un blocco; metadati AVRCP con il telefono che suona; se `A2dpMediaBrowserService` è accessibile all'app.
