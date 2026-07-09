# Architettura e Strategia di Sviluppo — Autovelox App

> Versione condivisa nel repo (portabile tra macchine). Per la specifica funzionale originale vedi [`features.md`](features.md); per il contesto rapido su ogni nuova sessione/macchina vedi [`CLAUDE.md`](CLAUDE.md).

## Contesto

Il progetto è agli albori (solo template Flutter di default + le dipendenze Firebase/location già aggiunte in una sessione precedente). L'utente ha scritto `features.md` con la specifica funzionale completa: mappa crowdsourced di autovelox, sync con OpenStreetMap/Overpass, pubblicazione changeset OSM via OAuth, e un'estensione Android Auto con avvisi audio/TTS in tempo reale durante la guida.

Decisioni chiave prese durante la progettazione:

1. **Nessun database locale dedicato.** Cloud Firestore offre persistenza offline nativa (cache locale automatica + coda di scritture offline) sia su Dart sia su Kotlin. Aggiungere sqflite/Isar sarebbe uno strato ridondante. Le uniche cose salvate puramente sul device (via `SharedPreferences`/`DataStore`) sono flag UI effimeri (es. dismiss di un banner), mai dati di dominio.
2. **Architettura "native-first" per il percorso di guida.** La richiesta iniziale prevedeva "logica di business in Dart, Kotlin solo per la visualizzazione via Method Channel". Verificato che i template della Car App Library (`NavigationTemplate`, `ActionStrip`, ecc.) sono renderizzati dal processo host di Android Auto e non possono essere popolati da widget Flutter; un `FlutterEngine` headless in background per eseguire lì la logica di prossimità sarebbe fragile (cold start, Doze, kill di processo) su un percorso "safety-critical". **Decisione confermata dall'utente**: tutta la logica di guida in tempo reale (query geospaziale, calcolo prossimità/direzione, filtro tolleranza, TTS, audio focus, schermate auto) vive in **Kotlin nativo**. Flutter possiede l'intera app sul telefono (mappa, inserimento/cancellazione, impostazioni, sync OSM, OAuth, risoluzione conflitti, revisione banner). Le due parti non comunicano quasi mai via Method Channel: **condividono le stesse collezioni Firestore**, ognuna con i propri listener nativi.
3. **Autenticazione: Google oppure ospite.** `AuthService` (`lib/data/services/auth_service.dart`) espone sia `signInWithGoogle()` che `signInAsGuest()` (Firebase Anonymous Auth). Se un utente ospite accede poi con Google, l'account viene *collegato* (`linkWithCredential`), non sostituito, così i dati già creati con l'uid anonimo restano suoi.

L'obiettivo di questo piano è fissare la struttura di cartelle, lo schema dati, i componenti nativi/Dart necessari e una roadmap a fasi, così da poter procedere all'implementazione in modo incrementale e verificabile — anche cambiando macchina di sviluppo.

---

## 1. Stack e librerie

**Dart (telefono):**
- State management: **Riverpod** (`flutter_riverpod`) — si integra bene con gli `Stream` di Firestore (`StreamProvider`) e disaccoppia UI da repository, facilitando i test.
- `firebase_core`, `cloud_firestore`, `firebase_auth`, `google_sign_in`, `location`, `google_maps_flutter` (già presenti).
- Da aggiungere in Fase 3: `oauth2` + `http` (OAuth OSM + REST changeset), `flutter_secure_storage` (token OSM cifrato, come richiesto in `features.md` §4).

**Kotlin (auto):**
- `androidx.car.app:app:1.4.0` (già aggiunto).
- Firebase Android SDK nativo (BoM) per Firestore — indipendente da `cloud_firestore`, stessa cache offline, stesse collezioni.
- `FusedLocationProviderClient` (Google Play Services location) per GPS/heading in background.
- `TextToSpeech` (Android SDK) + `CarContext`/`CarAudioManager` per audio focus e ducking.

Nessun `flutter_background_service`, `workmanager` o `FlutterEngine` headless: non servono con l'architettura native-first.

---

## 2. Struttura cartelle

### Dart — `lib/` (feature-first)
```
lib/
  main.dart                      # init Firebase + runApp
  app.dart                       # MaterialApp, routing (login <-> mappa), ProviderScope
  core/
    constants/auth_config.dart    # googleServerClientId (Web client ID Firebase)
    utils/geo.dart                 # haversine, bearing, geohash encode (STESSO algoritmo del lato Kotlin)
  data/
    models/                       # SpeedCamera, UserSettings, IgnoredCamera, SyncMetadata
    repositories/                 # speed_camera_repository, settings_repository, blacklist_repository
    services/                     # auth_service, firestore_paths
    providers.dart                # provider Riverpod condivisi (auth, repository, settings)
  features/
    auth/                         # LoginScreen (Google / ospite)
    map/                          # Home Page: mappa, marker colorati, FAB inserimento, viewport provider
    settings/                     # slider distanza, toggle tolleranza, sync OSM (Fase 2-3)
    osm_auth/                     # login OAuth OSM (Fase 3)
    conflict_resolution/          # pannello dedup OSM vs user, Haversine ≤ 25m (Fase 3-4)
```

### Kotlin — `android/app/src/main/kotlin/com/bortho/autovelox/`
```
MainActivity.kt                  # esistente, host Flutter per l'app telefono
carapp/                          # Fase 5-6
  AutoveloxApplication.kt        # Application custom: FirebaseApp.initializeApp() eager (serve anche se Android Auto avvia il processo senza MainActivity)
  AutoveloxCarAppService.kt      # CarAppService, dichiarato in AndroidManifest con intent-filter androidx.car.app.CarAppService
  AutoveloxSession.kt            # Session -> onCreateScreen
  screens/MainCarScreen.kt       # NavigationTemplate/GridTemplate + ActionStrip coi due pulsanti grandi
  CameraProximityService.kt      # Foreground Service (type="location"): loop GPS, query geohash Firestore, ProximityCalculator, ToleranceFilter, TTS, audio focus
  data/CameraRepository.kt       # wrapper nativo su FirebaseFirestore (stesse collezioni del lato Dart)
  data/SettingsRepository.kt
  data/BlacklistRepository.kt
  util/GeoHashUtil.kt            # stesso algoritmo geohash di core/utils/geo.dart — porting 1:1, documentato
  util/ProximityCalculator.kt    # distanza + bearing match col senso di marcia
  util/ToleranceFilter.kt        # state machine isteresi (soglia 10% + override in accelerazione)
res/xml/automotive_app_desc.xml  # dichiarazione app Android Auto (categoria, min car api level) — Fase 5
```

---

## 3. Schema dati Firestore (evoluzione di `features.md` §5.1)

**`speed_cameras/{id}`** — come da spec, con aggiunte:
- `geohash: String` — per query di prossimità (bounding box), calcolato alla scrittura sia da Dart (sync OSM/inserimento utente) sia da Kotlin (report rapido da auto).
- `createdBy: String?` (uid), `createdAt`, `updatedAt` — per audit e per limitare le eliminazioni ai soli record propri.

**`users/{uid}`**
- `settings`: `alertDistanceMode` (`fixed`|`dynamic`), `alertDistanceMeters` (100–1000), `toleranceFilterEnabled` (bool, default true).
- `osmLinked: bool`, `lastOsmSyncAt: Timestamp` (per lo Smart Reminder dei 7 giorni).
- Sottocollezione **`ignored_cameras/{cameraId}`** — sostituisce la "tabella locale" della spec: blacklist per-utente su Firestore, con cache offline automatica su entrambi i lati.

**`sync_metadata/global`** — `lastSyncAt`, `camerasSynced`: marcatore globale per lo Smart Reminder OSM.

**Security rules** (`firestore.rules`, già scritte e versionate): scrittura/cancellazione di `speed_cameras` con `source:'user'` solo se `createdBy == request.auth.uid`. `source:'openstreetmap'` è scrivibile da qualunque utente autenticato per l'MVP — **hardening previsto in Fase 7**: spostare l'ingestione dietro una Cloud Function per evitare che un client malevolo spacci record falsi come "verificati OSM".

---

## 4. Roadmap a fasi

| Fase | Contenuto | Stato |
|---|---|---|
| **0 — Fondamenta** | Riverpod, struttura cartelle Dart, modelli dati, repository Firestore, regole di sicurezza base, `geo.dart` (haversine + geohash), auth Google/ospite | ✅ Completata |
| **1 — Mappa & CRUD (telefono)** | `google_maps_flutter`, marker colorati per stato/sorgente, inserimento (long-press/FAB) con limite da preset + direzione, cancellazione propri marker, query "camere in vista" via geohash | ✅ Completata |
| **2 — Impostazioni** | Slider distanza (fissa/dinamica), toggle filtro tolleranza, persistiti su `users/{uid}.settings` | ⬜ Prossima |
| **3 — Integrazione OSM (telefono)** | Sync manuale Overpass → Firestore, Smart Reminder 7gg, login OAuth2 OSM, pubblicazione changeset | ⬜ |
| **4 — Blacklist & revisione** | Sottocollezione `ignored_cameras`, banner di conferma su avvio app per elementi `pending` catturati da Android Auto, promozione a `approved` | ⬜ |
| **5 — Fondamenta Android Auto** | `AutoveloxCarAppService`/`Session`/`Screen` scheletro, `automotive_app_desc.xml`, manifest, prima schermata lista camere vicine, test su Desktop Head Unit (DHU) | ⬜ |
| **6 — Logica di guida nativa** | `CameraProximityService`: GPS+heading, query geohash, `ProximityCalculator`, `ToleranceFilter`, card di allarme, TTS + audio focus via `CarContext`, i due pulsanti azione (Ignora/Segnala) → scritture Firestore | ⬜ |
| **7 — Hardening** | Audit security rules, eventuale Cloud Function per l'ingestione OSM, permessi location in background (dialog Android 12+), test batteria, revisione linee guida Google per app di guida/distrazione, test su head unit reale | ⬜ |
| **8 — Rilascio** | Crashlytics/Analytics (opzionali), icone, privacy policy (obbligatoria: posizione + dati crowdsourced), listing Play Store | ⬜ |

Ogni fase è pensata per essere verificabile isolatamente (l'MVP telefono, Fasi 0–4, funziona ed è testabile senza toccare Android Auto).

---

## 5. Setup richiesto su ogni nuova macchina

Alcuni file sono intenzionalmente **non versionati** (contengono percorsi o chiavi legate alla singola macchina/keystore) — vedi [`CLAUDE.md`](CLAUDE.md) per l'elenco completo e le istruzioni passo-passo.

## Verifica

- Dopo ogni fase: `flutter analyze` pulito + `flutter test` (repository/modelli testati con `fake_cloud_firestore`, nessun emulatore reale necessario).
- Dopo la Fase 1: `flutter build apk --debug` per confermare che Gradle/manifest compilino.
- Dopo la Fase 5–6: test con Android Auto Desktop Head Unit (DHU) per verificare rendering schermate, pulsanti azione, TTS e ducking audio.
