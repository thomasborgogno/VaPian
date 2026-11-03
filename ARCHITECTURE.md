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

Kotlin, Jetpack Compose (Material 3), Hilt, Room, Coroutines/Flow, WorkManager (solo telefono), `maps-compose` (solo telefono), Firebase Auth + Firestore (solo telefono), Media3 `MediaController` (autoradio), Bluetooth Classic RFCOMM (`BluetoothServerSocket` sul telefono, `BluetoothSocket` sull'autoradio): il BLE non è disponibile sull'autoradio.

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

La Dashboard è un pager a due pagine: **principale** (velocità, radar, segnala, player; in alto a destra compare solo l'icona "telefono scollegato" quando il link non c'è) e **impostazioni** (swipe da destra a sinistra: scelta/cambio telefono, età dei dati, Diagnostica). Banner e barre restano su entrambe. In basso due barre sottili: **luminosità** della finestra (metà sinistra, ricordata; si applica solo dopo che la si muove, così non disattiva la luminosità automatica) e **volume** (metà destra). Le **impostazioni della dashboard** includono anche distanza di preavviso fissa/dinamica con cursore, tolleranza intelligente (inviate al telefono, `SETTINGS_UPDATE`) e quanto abbassare la musica durante gli avvisi.
**Player**: senza musica mostra i pulsanti delle app scelte nelle impostazioni del telefono (di default YouTube Music e Spotify) per avviarle sul telefono; in alto a sinistra mostra icona e nome dell'app attiva, con un menu a tendina per cambiare app (il vecchio player viene fermato). Il pulsante microfono nella striscia sinistra (compare con un telefono scelto) apre l'assistente **del telefono** (Gemini) come il tasto voce di un kit auto: la radio, che è un client vivavoce (HFP), invia al telefono la richiesta standard di riconoscimento vocale (`BluetoothHeadsetClient.startVoiceRecognition`, API nascosta raggiunta per riflessione: sull'Android 8.1 della radio è consentito, dalle versioni successive no). Il resto lo fa il sistema del telefono, anche a schermo spento e bloccato, con audio su microfono e altoparlanti della radio. Nessun codice né permesso speciale sul telefono; richiede che la radio sia collegata al telefono anche come vivavoce. Opzione "Sfondo con la cover" nelle impostazioni della dashboard: la copertina sfocata (ridotta a ~16 px e ingrandita con filtro, valido anche senza il blur nativo di Android 12) e scurita dietro la card; le cover arrivano a 300 px dal telefono. Titolo e "Artista · Album" su una riga ciascuno; la barra di avanzamento è disegnata a onda animata, in stile Material 3 Expressive (piatta in pausa), ed è anche il cursore per spostarsi nel brano. **Avvisi e musica**: la voce dell'avviso non prende il focus audio (che farebbe fermare il player, anche via Bluetooth) ma abbassa il volume musica della percentuale impostata e lo ripristina a fine frase (solo se nessuno l'ha cambiato nel frattempo); la voce esce sul canale sveglia, così l'attenuazione non la abbassa.

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
   scelta del telefono, **player** (la barra di avanzamento è anche un cursore: rilasciandolo il telefono salta a quel punto) (cover, titolo, artista, progresso, prev/play/next) via canale RFCOMM, provato
   tra Pixel e tablet; la scheda si adatta all'altezza disponibile. ⬜ AVRCP come alternativa.
6. **Segnali dell'auto** ✅ `VehicleSignals` (porte, marcia, velocità dal CAN) in sola lettura. ⬜ Significato dei codici da test in auto.
7. **UI telefono** ✅ due schede (Mappa, Impostazioni) con **barra di navigazione flottante** (a pillola, sopra il contenuto; si nasconde mentre si
   modifica un autovelox). **Mappa** (Google Maps) con la **stessa grafica di marker della versione Flutter**: badge circolare
   col pittogramma della telecamera (blu = OSM, rosso = segnalazione approvata, arancione = in attesa o bozza, grigio =
   ignorato), freccia a parte ruotata sulla direzione monitorata (due marker per camera: badge sempre dritto, freccia
   piatta), pallini sotto zoom 10. Bozza trascinabile: si sposta il marker e si ruota la **maniglia viola** a 70 dp; pannello con
   limiti, "senso di marcia contrario", elimina, approva, ignora/ripristina. Elenco delle segnalazioni da confermare. In alto a destra: pulsante **Filtri** (come in Flutter: limiti di velocità, "senza limite noto", "solo ignorati" in grigio, "Cancella filtro", segnalino quando attivo) accanto a quello della posizione, la bussola sotto; tutti ridisegnati con i colori del tema (quelli nativi di Google Maps sono sempre chiari). Il filtro è stato di sessione, non salvato.
   **Impostazioni**: avvisi (attivazione, preavviso fisso/dinamico, tolleranza), autoradio (collegamento, pronto in background, accesso al player) e dati autovelox per regione (download da OSM): quelle già scaricate in cima, tutte le altre in un accordion. Colori: **Material You** dal telefono su Android 12+, altrimenti un teal (la Dashboard è sempre scura, fondo nero).
   Il radar della Dashboard usa gli stessi badge e colori.
   **Account** ✅ ospite (anonimo Firebase, silenzioso) o **Google** via Credential Manager: l'accesso *collega* la credenziale all'ospite, quindi uid e
   segnalazioni restano (se l'account Google era già usato altrove si passa a quello). **Guida** ✅ terza scheda, a tutto schermo e con lo schermo sempre acceso: tre viste
   **Guida, aggiornata:** la vista "radar" è la card del radar della Dashboard (orario e data, velocità, radar con le onde; con un autovelox in arrivo la velocità cresce, compaiono limite e distanza e lo sfondo vira ad ambra/rosso). Si segnala toccando la metà bassa del radar, con "Annulla" tondo; non compare nulla del player né delle barre. Un telefono in orizzontale (largo ma basso, < 480 dp) resta il telefono e non diventa Dashboard: lì orario e velocità stanno sulla stessa riga.
   (velocità + radar, mappa scura, mappa) con scheda di avviso e Segnala/Ignora; gli avvisi vocali vengono dallo stesso `GuidanceController` che gira anche a schermo spento.
   **OpenStreetMap** ✅ account proprio (OAuth 2 + PKCE, client pubblico, token cifrato con chiave Keystore; `osm/`): pubblica un proprio autovelox approvato come nodo
   `highway=speed_camera` e, se già pubblicato, lo elimina anche da OSM; ogni scrittura chiede conferma. Gli endpoint sono in `BuildConfig` e si possono puntare alla
   sandbox OSM da `local.properties` (`OSM_BASE_URL`, `OSM_API_URL`, `OSM_CLIENT_ID`). ⬜ Modifica di limite/direzione di un nodo OSM esistente.
9. **Hardening** 🟨 Test strumentati su Room/repository ✅ (6, `src/androidTest`, lanciati a mano con `am instrument` per non disinstallare l'app). ⬜ Test del sync Firestore, avvio a freddo in modalità aereo, batteria, emulatore API 26 landscape 1280×720.

## 12. Vincoli della head unit target e cosa resta da verificare

Progettare per: SDK 27, solo 32 bit, CPU debole, heap app ~144 MB, schermo 1280×720 a 160 dpi (= 1280×720 dp,
landscape fisso), nessun accelerometro, **nessun BLE**, stack Bluetooth instabile, GPS integrato, volume regolabile da
software. Niente animazioni pesanti, bitmap piccole, radar in `Canvas`. I dati misurati sull'hardware di sviluppo
non sono versionati.

Da verificare sul campo: metadati AVRCP con il telefono che suona; velocità reale di RFCOMM; comportamento dei socket dopo
un blocco dello stack Bluetooth; significato dei codici del ponte CAN (marcia, tasti).
