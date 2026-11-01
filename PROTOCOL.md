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

### 5.1 Record camera (binario, lunghezza variabile, ~32 byte)

L'id è una stringa (gli id OSM sono `osm_<nodo>`, le segnalazioni nuove sono UUID, ma i dati esistenti su Firestore
hanno anche id da 20 caratteri):

```
u8  idLen | id (UTF-8, idLen byte, 1..255)
i32 lat_e7      latitudine * 1e7
i32 lng_e7      longitudine * 1e7
u8  limitKmh    0 = sconosciuto
u16 direction   0..359, 0xFFFF = assente
u8  flags       bit0 approved | bit1 openstreetmap | bit2 directionReversed
u32 updatedAt   epoch secondi
```

Un id `osm_12345678901` dà un record di 32 byte. Il geohash non viaggia: il ricevente lo ricalcola.
Si includono le `approved`; le `pending` altrui non servono (non generano avvisi).

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
7. `lastDatasetReceivedAt` si aggiorna solo a sync completo (alimenta il promemoria a 14 giorni). Se una regione arriva corrotta (lunghezza o CRC32 sbagliati), viene scartata, i dati precedenti restano e il sync non risulta completo.
8. Le segnalazioni dei **singoli utenti** viaggiano come pseudo-regione `_segnalazioni` (tutte le `approved` non OSM).

Le righe locali dell'autoradio con `dirty=1` (segnalazioni non ancora confermate) **non** vengono toccate dallo swap.

Dimensioni: 10.000 record = 320 KB. RFCOMM regge in pratica 50–150 KB/s: **pochi secondi**.

## 6. Media (implementato)

Il player è quello del **telefono**, mostrato e controllato dall'autoradio sul canale RFCOMM (indipendente dallo stack
AVRCP della ROM, che resta un'alternativa da valutare). Il telefono lo spinge solo se la radio ha annunciato `media`
nelle `caps` dell'`HELLO`.

- `MEDIA_STATE` (telefono → radio, CBOR): `{state: playing|paused|stopped, title, artist, album, durationMs, positionMs, ts, app, coverId}`.
  Inviato a ogni cambiamento e circa ogni secondo mentre suona. La radio **estrapola** la posizione dall'istante in
  cui riceve il messaggio, con il proprio orologio: i due orologi non devono coincidere.
- `COVER_REQ {coverId}` (radio → telefono): la radio chiede i byte una sola volta per `coverId`.
- `COVER {coverId, jpeg}` (telefono → radio): JPEG al massimo 128×128, qualità ~70 (heap della radio ~144 MB).
- `MEDIA_CMD {cmd, positionMs?}` (radio → telefono): `play | pause | toggle | next | prev | seek`. `seek` porta il brano a `positionMs` (la barra di avanzamento della Dashboard è un cursore); l'app musicale deve supportare il salto.
- Il telefono legge le sessioni media di qualunque app con `MediaSessionManager`; Android lo consente solo con un
  `NotificationListenerService` abilitato dall'utente ("accesso alle notifiche", concesso una volta, solo per leggere il player).
  Senza, il telefono invia `stopped`. Da Android 11 serve `<queries>` per leggere il nome delle app musicali.

## 7. Errori e sicurezza

- `ERROR {code, detail}`: `PROTO_UNSUPPORTED`, `BAD_PAYLOAD`, `RATE_LIMITED`, `BUSY`, `INTERNAL`.
- Ogni payload è validato in lunghezza e intervallo prima dell'uso; i messaggi malformati si scartano e si contano.
- Nessun dato fuori dai campi documentati; nessun identificativo hardware (IMEI, MAC) nei messaggi.
- Compatibilità: i cambi compatibili aggiungono campi CBOR opzionali; i cambi incompatibili alzano `proto`.

## 8. Da verificare sull'autoradio

UUID definitivi; velocità reale di RFCOMM verso un telefono vero; quanto spesso si blocca lo stack e come ripartono i
socket dopo un blocco; metadati AVRCP con il telefono che suona; se `A2dpMediaBrowserService` è accessibile all'app.
