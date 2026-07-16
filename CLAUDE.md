# VaPian! - Autovelox App

App Flutter (Android) per la segnalazione crowdsourced di autovelox, con estensione Android Auto nativa (Kotlin, `androidx.car.app`).

- Specifica funzionale completa: [`features.md`](features.md)
- Architettura, decisioni e roadmap a fasi (stato aggiornato ad ogni fase): [`PLAN.md`](PLAN.md)

## Decisioni architetturali da non rimettere in discussione senza motivo

- **Nessun database locale dedicato** (no sqflite/Isar). Cloud Firestore fa da cache offline sia su Dart che su Kotlin.
- **Native-first per Android Auto**: tutta la logica di guida in tempo reale (GPS, prossimità, TTS, audio focus, schermate auto) vive in Kotlin nativo, non in Dart/Method Channel. Flutter possiede solo l'app telefono. Le due parti comunicano scrivendo/leggendo le stesse collezioni Firestore, non via Method Channel. Motivazione completa in `PLAN.md`.
- **Auth**: Google Sign-In o accesso come ospite (Firebase Anonymous Auth). Un ospite che poi accede con Google viene collegato (`linkWithCredential`), non sostituito.
- Package Android: `com.bortho.autovelox`. Progetto Firebase: `bortho-autovelox`.
- **⚠️ OpenStreetMap (`lib/core/constants/osm_config.dart`) punta alla produzione**, non a una sandbox: login OAuth2 e pubblicazione changeset (`OsmEditService`) scrivono dati reali su `www.openstreetmap.org`/`api.openstreetmap.org`. Non redirigere "per comodità" verso `master.apis.dev.openstreetmap.org` senza chiederlo esplicitamente.

## Setup richiesto su ogni nuova macchina

Questi file sono **intenzionalmente non versionati** (percorsi o credenziali specifiche della macchina) — vanno ricreati quando si clona il repo altrove:

1. **`android/local.properties`** — non esiste nel repo. Crearlo con:
   ```properties
   sdk.dir=<percorso Android SDK>
   flutter.sdk=<percorso Flutter SDK>
   MAPS_API_KEY=<API key Maps SDK for Android>
   ```
   La `MAPS_API_KEY` si ottiene da Google Cloud Console (progetto `bortho-autovelox`) → *API e servizi* → abilita **Maps SDK for Android** → crea/recupera una API key.

2. **Google Sign-In — impronta SHA-1 per macchina**: ogni macchina ha un proprio keystore di debug (`~/.android/debug.keystore`), quindi una SHA-1 diversa. Su ogni nuova macchina:
   ```bash
   keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android -keypass android
   ```
   e registrare la riga `SHA1` in Firebase Console → Project settings → app Android `com.bortho.autovelox` → *Aggiungi impronta digitale*. La stessa impronta va incollata anche nella whitelist della `MAPS_API_KEY` (Google Cloud Console → Credenziali → restrizioni). Senza questo, login Google e/o mappa falliscono solo su quella macchina.

   Il `googleServerClientId` (Web client ID) invece **è** versionato in [`lib/core/constants/auth_config.dart`](lib/core/constants/auth_config.dart) — non va rigenerato.

3. **`google-services.json` / `lib/firebase_options.dart`**: già versionati, non serve rifare `flutterfire configure` su una nuova macchina.

4. **`.claude/settings.local.json`**: permessi locali della sessione Claude Code, non versionato di proposito (per-macchina/per-utente).

## Comandi utili

- `flutter analyze` — deve restare pulito ad ogni fase.
- `flutter test` — repository e modelli sono testati con `fake_cloud_firestore` (nessun emulatore Firebase necessario).
- `flutter build apk --debug` — verifica end-to-end che Gradle/manifest compilino.
