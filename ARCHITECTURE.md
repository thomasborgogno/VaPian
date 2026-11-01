# VaPian! — Architettura (Kotlin nativo)

> Stato: bozza di lavoro. Sorgente di verità funzionale: il repo Flutter originale.
> (`features.md`, `PLAN.md`, `CLAUDE.md`). Protocollo Bluetooth: vedi [`PROTOCOL.md`](PROTOCOL.md).

## 1. Principio guida: due ruoli, una sola APK

La stessa APK gira in due **ruoli**, scelti al lancio (override manuale nelle impostazioni):

| | **Telefono** (Pixel 8, Android 17) | **Autoradio / Dashboard** (head unit Android economica: **Android 8.1 / SDK 27**, 32 bit, senza BLE) |
|---|---|---|
| Rete | **Unico nodo connesso a internet** | **Mai connessa**: nessuna chiamata di rete, nessun Firebase, nessuna Maps |
| Account | Firebase (Google / ospite) | Nessuno. Ha solo un `deviceId` locale |
| Dati | Scarica da OSM/Overpass le **sole regioni scelte dall'utente**, sync Firestore | Riceve il dataset dal telefono via Bluetooth |
| UI | Mappa Google, segnalazione, navigazione, impostazioni | Dashboard: radar, player, volume, pulsante "Segnala" |
| Link Bluetooth | **RFCOMM server** | **RFCOMM client** (l'autoradio **non ha BLE**) |

Conseguenze (decisione di prodotto):
- L'autoradio è **offline per costruzione**, non "offline quando manca la rete". Questo elimina l'incertezza su GMS/Maps/Firebase sulla ROM della head unit e rende il fallback radar la UI principale, non un ripiego.
- Tutto ciò che l'autoradio produce (segnalazioni, "ignora") viaggia al telefono via Bluetooth (RFCOMM); è il telefono a scriverlo su Firestore con il **proprio uid**. Le regole Firestore (`createdBy == request.auth.uid`) restano invariate.
- Il ruolo `Dashboard` non inizializza Firebase né Maps (init lazy, solo ruolo telefono): avvio a freddo più rapido e nessun crash per GMS vecchio.

## 2. Aggiornamento dati dell'autoradio

Flusso:
1. Sul **telefono** l'utente sceglie le regioni (le 20 di `italian_regions`) e scarica da Overpass (come `OverpassService`: query per regione, 3 endpoint di fallback, UA descrittivo). I dati finiscono in Room, il sync verso Firestore resta com'è.
2. All'avvio e a connessione Bluetooth stabilita, l'autoradio dice al telefono cosa ha (`DatasetManifest`: per regione `version`).
3. Il telefono confronta e invia **solo le regioni cambiate** (snapshot per regione, vedi PROTOCOL §5). L'autoradio li applica in una transazione su una tabella di staging, poi swap: un trasferimento interrotto non corrompe mai il dataset in uso.
4. Insieme al dataset viaggiano **blacklist (ignored) e impostazioni di avviso** dell'utente, perché l'autoradio non ha account.

**Promemoria ogni 14 giorni** (valore unico: `features.md` diceva 15, `PLAN.md` 7):
- Autoradio: se `lastDatasetReceivedAt` > 14 giorni → banner non bloccante in Dashboard: *"Dati autovelox vecchi di N giorni. Aggiornali dal telefono."* Mai modale, mai durante la guida con velocità > 0 oltre al banner.
- Telefono: se `lastOsmDownloadAt` (per regione) > 14 giorni → banner nella Home, come lo Smart Reminder attuale. Un aggiornamento completo richiede quindi due passi (scarica da OSM, poi invia all'autoradio): il telefono mostra "Invia all'autoradio" quando c'è una regione più nuova di quella sull'autoradio.
- Il dataset **non scade**: oltre i 14 giorni gli avvisi continuano a funzionare con i dati che ci sono.

Dimensioni: un record compatto pesa 32 byte (PROTOCOL §5.1). Anche 10.000 punti sono ~320 KB: pochi secondi su RFCOMM.

## 3. Stack

Kotlin, Jetpack Compose (Material 3), Hilt, Room, Coroutines/Flow, WorkManager (solo telefono), `maps-compose` (solo telefono), Firebase Auth + Firestore (solo telefono), `androidx.car.app` (Android Auto, solo telefono), Media3 `MediaController` (autoradio), Bluetooth Classic RFCOMM (`BluetoothServerSocket` sul telefono, `BluetoothSocket` sull'autoradio): il BLE non è disponibile sull'autoradio.

`minSdk 26` (l'autoradio è SDK 27), `targetSdk/compileSdk 37`. Niente librerie native: l'autoradio è solo armeabi-v7a (32 bit, Cortex-A7). Il ruolo si sceglie a runtime, non con flavor, così resta una APK sola; il codice rete/Firebase vive in un modulo/package caricato solo dal ruolo telefono.

## 4. Moduli (package `com.bortho.autovelox`)

```
core/        geo (geohash, haversine, bearing), AlertDistance, ToleranceFilter, Proximity   <- puri, testati JVM
data/        Room (entity/dao/db), repository, SyncQueue
sync/        FirestoreSync (WorkManager), OverpassClient, OsmAuth/OsmEdit      [solo telefono]
link/        protocollo (framing, messaggi), RfcommServer (telefono), RfcommClient (autoradio), DatasetTransfer, MediaBridge
vehicle/     VehicleSignals: lettura dei segnali CAN (autoradio), vedi §7.1
guidance/    CameraProximityService (foreground), TTS, audio focus
ui/phone/    mappa, segnalazione, navigazione, impostazioni
ui/dashboard/radar, player, volume, pulsante Segnala, banner aggiornamento
car/         Android Auto (CarAppService, schermate)                          [solo telefono]
```

`core/` e `guidance/` sono **condivisi** dai due ruoli: l'autoradio fa gli stessi avvisi di prossimità/TTS leggendo Room, esattamente come oggi il servizio nativo.

## 5. Database

Nuovo database Room `vapian.db` (**nessuna compatibilità con l'app Flutter**: né file, né schema, né migrazione). Tabelle: `speed_cameras`, `ignored_cameras`, `queued_ignores`, `sync_queue`, `dataset_meta`. Lo schema **Firestore** resta invece quello esistente (collezioni e campi invariati), così regole e dati online continuano a funzionare. Gli schemi Room sono esportati in `app/schemas/`.

La colonna `uid` di blacklist e ignori in coda è una **chiave locale per installazione** (`UserSession.localKey`), non l'uid Firebase: l'app funziona offline dal primo avvio senza account. Il `SyncEngine` la traduce nell'uid Firebase solo nei percorsi Firestore e in `createdBy`.

Regole di scrittura (correggono i difetti del codice Flutter/Kotlin attuale):
- Un solo modello `SpeedCamera` completo: `directionReversed`, `createdBy`, `createdAt`, `osmNodeId`/`osmNodeVersion` come **`Long`** (gli id dei nodi OSM superano 2³¹).
- Il merge da remoto/Bluetooth **non sovrascrive le righe `dirty`** e non azzera i campi che non conosce (`@Upsert` mirato, mai `REPLACE`).
- La direzione monitorata usa `directionReversed` anche nel calcolo di prossimità.
- Id camera: `osm_<nodeId>` per OSM, UUID per le segnalazioni utente; sull'autoradio l'UUID è generato localmente e fa da chiave di idempotenza.

## 6. Offline-first

- Avvio a freddo: nessuna schermata attende rete/auth. Telefono: sessione in cache → entra; altrimenti ospite locale (uid locale, login rimandato; il service di guida non dipende da `FirebaseAuth`).
- Single source of truth = Room. Rete = sola sincronizzazione (WorkManager + callback connettività) e download regioni.
- Mappa Google solo sul telefono; senza rete mostra il radar vettoriale (stesso composable della Dashboard).

## 7. Dashboard (ruolo autoradio, anche tablet landscape)

Layout come da piano: ~2/3 radar + ~1/3 player, barra volume in basso sempre visibile, pulsante "Segnala" grande in basso-centro dell'area radar.
- **Radar**: posizione al centro, camere entro raggio, prossima camera in evidenza con distanza/limite, tachimetro in alto. `Canvas` Compose, nessun tile.
- **Segnala**: un tap, target ≥ 72 dp, feedback aptico/sonoro/TTS, barra "Annulla" 5 s, poi salvataggio locale + invio al telefono (PROTOCOL §5).
- **Rilevamento**: larghezza ≥ 840 dp + landscape, oppure `UI_MODE_TYPE_CAR`, oppure override manuale. Il ruolo Dashboard disattiva rete e Firebase.
- Tema scuro, font grandi, nessun testo piccolo.
- **Posizione**: l'autoradio ha un **GPS integrato** valido (fix con velocità e direzione; da fermo la direzione non è affidabile e va ignorata sotto ~3 km/h): nessuna condivisione dal telefono.

### 7.1 Dati dell'auto (ponte CAN)

Le head unit Android aftermarket non hanno le API `android.car`: porte, marcia, tasti, radar di parcheggio e velocità
dal CAN passano da un servizio del produttore, raggiungibile in AIDL. L'interfaccia, ricostruita in sola lettura, è in
`app/src/main/aidl/com/carocean/**` (+ i parcelable in `app/src/main/java/com/carocean/**`); la schermata
**Diagnostica → "Ascolta veicolo"** si registra ai callback e li elenca con l'ora, per scoprire il significato dei
codici numerici (marcia, tasti). `VehicleSignals` sarà l'interfaccia di produzione; la Dashboard mostrerà porte,
retromarcia e velocità solo se il segnale c'è. **Solo lettura**: nessun comando verso il veicolo, i `set*` del servizio
non sono dichiarati.

## 8. Player

Primario: `MediaController`/`MediaBrowser` sulla sessione AVRCP; comandi con `TransportControls`. Alternativa/complemento: canale RFCOMM proprietario (PROTOCOL §6) per metadati e cover. L'autoradio ha A2DP sink e AVRCP controller (volume assoluto no, multi-player disattivo). La scelta dipende dalla prova sul campo (Fase 7).

## 9. Permessi e servizi

- API 26–30: `BLUETOOTH`, `BLUETOOTH_ADMIN` (`maxSdkVersion=30`); RFCOMM verso un dispositivo già associato non richiede la posizione. API 31+: `BLUETOOTH_CONNECT` (e `BLUETOOTH_SCAN` solo se serve scoprire dispositivi).
- Posizione: `ACCESS_FINE_LOCATION` per la guida; su Android 10+ `ACCESS_BACKGROUND_LOCATION` separato.
- Su Android 8.1 (autoradio) niente tipi di foreground service; sul telefono (Android 14+) tipi dichiarati: `location`, `connectedDevice`. Il manifest li dichiara con `tools:targetApi`, il codice sceglie per versione.
- Il layer Bluetooth sta dietro un'interfaccia `LinkTransport`, con RFCOMM come unica implementazione.

## 10. Segreti e configurazione (invariati)

`applicationId com.bortho.autovelox`, progetto Firebase `bortho-autovelox`, stesso `google-services.json`, `MAPS_API_KEY` da `local.properties` via `manifestPlaceholders`, stesso keystore e SHA-1, `googleServerClientId` come in `auth_config.dart`. OSM punta alla **produzione**: nessun changeset di test. Nessun segreto nel repo; `release.yml` da portare con gli stessi secret (build Gradle invece di `flutter build`).

## 11. Fasi e stato

1. **Fondamenta** ✅ Gradle, Compose, Hilt, Room, Firebase; build, test e lint verdi.
2. **Core** ✅ `core/` puro con test JVM (geo, prossimità, `GuidanceEngine`, radar, ruolo), Room + repository,
   Overpass per regione (provato contro il server reale), `SyncEngine` Firestore, `UserSession` (ospite locale),
   `SyncWorker` (WorkManager). ⬜ Login Google e collegamento dall'ospite.
3. **Servizio di guida** ✅ `GuidanceController` + `GuidanceService` (GPS, voce italiana, audio focus, segnala/annulla/ignora).
   Le direzioni GPS da fermo sono rumore: il motore tiene l'ultima direzione affidabile.
4. **Link Bluetooth** ✅ Framing + CRC, messaggi CBOR, dataset per regione, sessioni telefono/autoradio con test in memoria
   (outbox con ack, idempotenza, rate limit, dataset corrotto scartato); trasporto RFCOMM con riconnessione e watchdog.
   ⬜ Prova su due dispositivi reali.
5. **Dashboard** ✅ Radar in Canvas, velocità, limite, avviso, segnala con annulla a 5 s, ignora, barra del volume, banner a 14 giorni,
   scelta del telefono, **player** (cover, titolo, artista, progresso, prev/play/next) via canale RFCOMM, provato
   tra Pixel e tablet; la scheda si adatta all'altezza disponibile. ⬜ AVRCP come alternativa.
6. **Segnali dell'auto** ✅ `VehicleSignals` (porte, marcia, velocità dal CAN) in sola lettura. ⬜ Significato dei codici da test in auto.
7. **UI telefono** ⬜ Per ora: avvisi on/off, download regioni da OSM, stato del link. Mancano mappa Google, segnalazione con
   direzione, impostazioni, navigazione, OSM OAuth/changeset.
8. **Android Auto** ⬜.
9. **Hardening** ⬜ Test strumentati su Room e sync, avvio a freddo in modalità aereo, batteria, emulatore API 26 landscape 1280×720.

## 12. Vincoli della head unit target e cosa resta da verificare

Progettare per: SDK 27, solo 32 bit, CPU debole, heap app ~144 MB, schermo 1280×720 a 160 dpi (= 1280×720 dp,
landscape fisso), nessun accelerometro, **nessun BLE**, stack Bluetooth instabile, GPS integrato, volume regolabile da
software. Niente animazioni pesanti, bitmap piccole, radar in `Canvas`. I dati misurati sull'hardware di sviluppo
non sono versionati.

Da verificare sul campo: metadati AVRCP con il telefono che suona; velocità reale di RFCOMM; comportamento dei socket dopo
un blocco dello stack Bluetooth; significato dei codici del ponte CAN (marcia, tasti).
