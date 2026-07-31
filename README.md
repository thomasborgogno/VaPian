# VaPian! — Autovelox App

App Flutter (Android) per la segnalazione crowdsourced di autovelox, con estensione **Android Auto** nativa (Kotlin, `androidx.car.app`).

L'app integra i dati geografici di **OpenStreetMap** (via query Overpass), un database centralizzato su **Cloud Firestore** e permette agli utenti abilitati di ripubblicare le segnalazioni verificate su OSM. Durante la guida, un modulo nativo Android Auto (oltre a una modalità di navigazione lato telefono) traccia la posizione GPS e avvisa con TTS/segnale acustico l'avvicinamento a un autovelox nel proprio senso di marcia.

## Funzionalità principali

- **Mappa interattiva** (OpenStreetMap, via `flutter_map`) con marker colorati per fonte/stato (OSM verificato, segnalazione utente approvata/in attesa).
- **Inserimento/eliminazione** di autovelox tramite pressione prolungata o pulsante flottante, con direzione e limite di velocità.
- **Blacklist personale** per nascondere autovelox OSM non più presenti nella realtà, senza modificare il dato condiviso.
- **Filtro mappa** per limite di velocità o per soli elementi ignorati.
- **Sincronizzazione OpenStreetMap** manuale (Overpass Turbo) con promemoria se i dati locali sono vecchi.
- **Distanza di preavviso** configurabile, fissa o dinamica in base allo scostamento velocità/limite, con filtro tolleranza per ridurre falsi allarmi.
- **Android Auto**: interfaccia di guida ad alto contrasto, avvisi visivi/audio (TTS) in tempo reale, pulsanti rapidi "Segnala Velox" / "Ignora Velox".
- **Modalità di navigazione lato telefono**: le stesse funzionalità di prossimità/avviso della guida Android Auto, ma direttamente sull'app telefono (utile su tablet montati in auto).

La specifica funzionale completa è in [`features.md`](features.md); le decisioni architetturali e la roadmap a fasi sono in [`PLAN.md`](PLAN.md).

## Architettura — decisioni chiave

- **Database locale SQLite condiviso** (`sqflite`/`SQLiteOpenHelper`, stesso file `autovelox_shared.db`): ogni lettura passa da lì, le scritture ci vanno subito e si sincronizzano con Firestore quando torna la rete — vedi `PLAN.md` § Fase OSM/offline.
- **OpenStreetMap** (`flutter_map`) per il rendering mappa, con tile vettoriali self-hosted scaricabili per regione per l'uso offline.
- **Native-first per Android Auto**: tutta la logica di guida in tempo reale (GPS, prossimità, TTS, audio focus, schermate auto) vive in Kotlin nativo, non in Dart/Method Channel. Le due parti comunicano scrivendo/leggendo le stesse collezioni Firestore, non via bridge.
- **Autenticazione**: Google Sign-In o accesso come ospite (Firebase Anonymous Auth); un ospite che poi accede con Google viene collegato (`linkWithCredential`), non sostituito.
- **⚠️ OpenStreetMap punta alla produzione**, non a una sandbox: login OAuth2 e pubblicazione changeset scrivono dati reali su `www.openstreetmap.org`.

## Stack

- **Dart/Flutter**: Riverpod (state management), `cloud_firestore` + `firebase_auth` + `google_sign_in`, `flutter_map` (mappa OpenStreetMap), `sqflite` (store locale offline), `location`, `flutter_secure_storage` (token OSM), `flutter_tts`, `wakelock_plus`.
- **Kotlin (Android Auto)**: `androidx.car.app`, Firebase Android SDK nativo, `FusedLocationProviderClient`, `TextToSpeech`.

## Setup di sviluppo

Package Android: `com.bortho.autovelox`. Progetto Firebase: `bortho-autovelox`.

Alcuni file sono **intenzionalmente non versionati** (percorsi/credenziali specifiche della macchina) e vanno ricreati su ogni nuovo ambiente:

1. **`android/local.properties`** (da creare, non esiste nel repo):
   ```properties
   sdk.dir=<percorso Android SDK>
   flutter.sdk=<percorso Flutter SDK>
   ```

2. **Google Sign-In — impronta SHA-1 per macchina**: ogni macchina ha un proprio keystore di debug, quindi una SHA-1 diversa.
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
   Registra la riga `SHA1` in Firebase Console → Project settings → app Android `com.bortho.autovelox`.

3. **`google-services.json` / `lib/firebase_options.dart`**: già versionati (i client identifier Firebase non sono segreti: l'accesso è protetto dalle Firestore Security Rules in [`firestore.rules`](firestore.rules) e dalle restrizioni per package/SHA-1 sulla API key), non serve rifare `flutterfire configure`.

4. **`maptiler.json`** (opzionale, da creare, non esiste nel repo): API key gratuita per lo stile mappa "OSM Bright" — senza, l'app usa CARTO Voyager come fallback (nessuna chiave richiesta, mappa comunque funzionante). Per abilitarlo: crea una chiave su https://cloud.maptiler.com/account/keys/, salvala in `maptiler.json`:
   ```json
   {"MAPTILER_API_KEY": "<la-tua-chiave>"}
   ```
   e lancia con `--dart-define-from-file=maptiler.json` (vedi sotto).

## Comandi utili

```bash
flutter analyze          # deve restare pulito ad ogni fase
flutter test              # repository e modelli testati con fake_cloud_firestore, nessun emulatore necessario
flutter build apk --debug # verifica end-to-end che Gradle/manifest compilino
flutter run --dart-define-from-file=maptiler.json # per usare lo stile OSM Bright invece del fallback CARTO
```
