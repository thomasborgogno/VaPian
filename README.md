# VaPian!

App Android nativa (Kotlin + Jetpack Compose) che avvisa degli **autovelox** in tempo reale, con dati OpenStreetMap
e segnalazioni degli utenti. Un solo APK, due ruoli:

| | **Telefono** | **Autoradio / tablet in auto (Dashboard)** |
|---|---|---|
| Rete | online (OSM/Overpass, Firebase, Google Maps) | **sempre offline** |
| Cosa fa | mappa, modifica e segnalazione autovelox, impostazioni, scarica i dati per regione, player multimediale | radar, velocità e limite, avvisi, pulsante "Segnala", player, volume |
| Dati | scarica da OSM e sincronizza con Firestore | li riceve dal telefono via **Bluetooth Classic (RFCOMM)** |

Il ruolo si sceglie da solo: schermo largo in orizzontale (≥ 840 dp) o modalità auto → Dashboard, altrimenti Telefono.
L'autoradio non ha rete: ogni 14 giorni un banner ricorda di aggiornare i dati dal telefono, che è l'unico nodo
connesso a internet. Le segnalazioni fatte dall'autoradio passano dal telefono prima di arrivare a Firestore.

Requisiti: Android 8.0+ (`minSdk 26`), Bluetooth Classic per il collegamento telefono ↔ autoradio.

Documentazione: [ARCHITECTURE.md](ARCHITECTURE.md) (moduli e decisioni) · [PROTOCOL.md](PROTOCOL.md) (protocollo del link Bluetooth).

## Provare una release (MVP)

1. Dalla pagina **Releases** del repository scarica `VaPian-vX.Y.Z.apk` (e controlla l'hash con il file `.sha256`).
2. Installa lo **stesso APK** sul telefono e sull'autoradio (abilita "origini sconosciute"; sull'autoradio anche con `adb install`).
3. **Associa il telefono all'autoradio** dalle impostazioni Bluetooth dell'autoradio.
4. Sul **telefono**: concedi posizione, notifiche e Bluetooth; in *Impostazioni → Dati autovelox* scarica la regione
   che ti serve; in *Autoradio* lascia attivo "Sempre pronto in background" e, per il player, concedi l'accesso alle notifiche.
5. Sull'**autoradio**: scorri la dashboard da destra a sinistra → *Impostazioni → Scegli telefono*. Quando il link è attivo
   sparisce l'icona "telefono scollegato" e arrivano dati e player.
6. In auto: la *Diagnostica* (nelle impostazioni della Dashboard) raccoglie GPS e segnali del veicolo per capire cosa
   espone l'autoradio.

## Compilare

Serve JDK 17+ e Android SDK (compileSdk 37). Tre file locali, **mai nel repository**:

- `local.properties`: `sdk.dir=...` e `MAPS_API_KEY=<chiave Google Maps>`
- `app/google-services.json`: configurazione Firebase del progetto
- `keystore.properties` (solo per la release firmata): `storeFile`, `storePassword`, `keyAlias`, `keyPassword`

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug      # verifica completa
./gradlew :app:assembleRelease -PvapianVersion=1.0.0                    # APK release firmato
```

La versione dell'APK viene da `-PvapianVersion` (`1.2.3` → `versionCode 10203`); senza, è `0.0.0-dev`.
Se manca il keystore l'APK release esce **non firmato** (non installabile): è voluto, mai firmato in debug di nascosto.

La build debug include un ricevitore `DebugFixReceiver` per simulare fix GPS via `adb`; non esiste nella release.

## Pubblicare una release

La pipeline [`release.yml`](.github/workflows/release.yml) parte da un tag:

```bash
git tag v1.0.0
git push origin v1.0.0
```

Esegue i test unitari, costruisce l'APK firmato con la versione del tag, ne verifica firma e versione e crea la
GitHub Release con l'APK e l'hash SHA-256 (note generate dai commit). Un tag con suffisso (`v1.0.0-rc1`) diventa pre-release.
[`ci.yml`](.github/workflows/ci.yml) esegue test, build debug e lint a ogni push su `master` e su ogni pull request.

### Segreti da configurare (Settings → Secrets and variables → Actions)

| Segreto | Contenuto |
|---|---|
| `GOOGLE_SERVICES_JSON` | `base64 -w0 app/google-services.json` |
| `MAPS_API_KEY` | chiave Google Maps |
| `KEYSTORE_BASE64` | `base64 -w0 vapian-release.p12` |
| `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` | credenziali del keystore |

Genera il keystore una sola volta e **conservalo con cura**: serve la stessa chiave per ogni aggiornamento futuro.

```bash
keytool -genkeypair -keystore vapian-release.p12 -storetype PKCS12 -alias vapian -keyalg RSA -keysize 4096 -validity 10000
```

> **Attenzione, repository pubblico.** L'APK contiene la chiave Maps (è nel manifest) e viene pubblicato: limita la
> chiave nella console Google Cloud a *app Android* con package `com.bortho.autovelox` e **SHA-1 del keystore di release**
> (`keytool -list -v -keystore vapian-release.p12`). Lo stesso SHA-1 va registrato su Firebase se usi l'accesso Google.

## Stato

Implementati: guida e avvisi, link Bluetooth telefono ↔ autoradio con dataset per regione, player sull'autoradio
(copertina, avanzamento con seek), mappa con marker, filtri e modifica, impostazioni, Dashboard con radar.
Da fare: login Google e collegamento dall'ospite, modalità navigazione sul telefono, OAuth/changeset OSM,
Android Auto, scelta delle regioni dall'autoradio. Dettagli in [ARCHITECTURE.md](ARCHITECTURE.md).
