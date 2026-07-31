# Pipeline tile vettoriali offline (self-hosted, Planetiler)

Genera un file `.mbtiles` per regione italiana e lo pubblica su Firebase
Storage nel path che l'app si aspetta
([`TileSourceConfig.mbtilesStoragePath`](../../lib/core/constants/tile_source_config.dart)):
`offline_tiles/{regione}.mbtiles`, dove `{regione}` è uno dei 20 nomi in
[`italianRegions`](../../lib/core/constants/italian_regions.dart) (es.
`offline_tiles/Lombardia.mbtiles`).

## Pipeline automatizzata (GitHub Actions)

[`.github/workflows/offline_tiles.yml`](../../.github/workflows/offline_tiles.yml)
esegue l'intera pipeline (estratto OSM → ritaglio per regione → Planetiler →
upload su Storage) sia manualmente (tab *Actions* → *Rebuild offline map
tiles* → *Run workflow*) sia in automatico il primo di ogni mese.

**Due cose restano da fare manualmente** — non automatizzabili da qui,
richiedono accesso alla Console Google/Firebase:

1. **Abilitare Firebase Storage** sul progetto (Console Firebase → Storage →
   "Inizia") — non ancora abilitato di default su `bortho-autovelox` al
   momento in cui questo documento è stato scritto. Configurare le regole di
   Storage per la lettura autenticata (coerente con `firestore.rules`).

2. **Creare un service account** con permesso *Storage Object Admin* sul
   bucket, scaricarne la chiave JSON, e aggiungere due secret al repository
   GitHub (Settings → Secrets and variables → Actions):
   - `FIREBASE_SERVICE_ACCOUNT_JSON` — contenuto del file JSON della chiave.
   - `FIREBASE_STORAGE_BUCKET` — nome del bucket (es.
     `bortho-autovelox.firebasestorage.app`).

Finché questi due punti non sono a posto, il workflow fallisce nello step
di autenticazione — gli step di generazione tile (Planetiler) girano
comunque, quindi è un buon modo per verificare che il resto della pipeline
funzioni anche prima di aver configurato Storage.

Finché la pipeline non è mai stata eseguita con successo, il download di
una regione da Impostazioni → "Mappe offline" fallisce con un 404 —
comportamento noto e già gestito dall'isolamento dei fallimenti per
regione in `OfflineMapsSection`.

## Perché self-hosted

Il download di massa dal tile server pubblico di OpenStreetMap
(`tile.openstreetmap.org`, usato come sorgente online di default,
[`TileSourceConfig`](../../lib/core/constants/tile_source_config.dart))
viola la [Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/)
di OSM, che vieta il bulk download per uso offline. Le tile *vettoriali*
per l'uso offline vanno quindi generate da un estratto OSM e ospitate da
noi, non scaricate in blocco dal server pubblico.

## Cosa fa la pipeline, passo per passo

Lo stesso script che gira in CI ([`build_regions.sh`](build_regions.sh)) è
eseguibile anche in locale (Linux/macOS con `osmium-tool` e Java 21+
installati) — utile per debug o per generare le tile senza aspettare CI:

```bash
sudo apt-get install -y osmium-tool jq   # o brew install osmium-tool jq su macOS

curl -L -O https://download.geofabrik.de/europe/italy-latest.osm.pbf
curl -L -O https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar

./build_regions.sh regions.json italy-latest.osm.pbf planetiler.jar out/
```

Per ciascuna delle 20 regioni in [`regions.json`](regions.json) (stessi
bounding box di
[`italianRegionBounds`](../../lib/core/constants/italian_region_bounds.dart)
— **tenere i due file sincronizzati a mano**, nessun codegen li collega):

1. **`osmium extract --bbox ...`** ritaglia la regione dall'estratto Italia
   completo (bounding box, non confine amministrativo esatto — sufficiente
   per questo scopo, coerente con l'approssimazione già accettata per il
   priming degli autovelox).
2. **Planetiler** genera l'`.mbtiles` con lo schema OpenMapTiles di default
   (strade, edifici, POI, confini amministrativi) dal ritaglio.

Poi, in CI, `gsutil -m cp out/*.mbtiles gs://<bucket>/offline_tiles/`
pubblica tutti i file generati.

### Requisiti macchina (per l'esecuzione locale — il runner GitHub Actions li soddisfa già)

- **Java 21+**.
- **~10-20 GB di spazio disco libero** (l'estratto Italia è ~1 GB
  compresso; il ritaglio per regione più il processing Planetiler
  richiedono più spazio, ma alla scala di una singola regione, non
  dell'intero paese).
- **4+ GB di RAM** consigliati.

## Verifica

Dopo che la pipeline ha pubblicato almeno una regione, in Impostazioni →
"Mappe offline" selezionarla e premere "Scarica per offline": il download
deve completare senza errori e, disattivando la rete, la mappa di quella
regione deve restare visibile — renderizzata dai tile vettoriali locali via
`OfflineVectorTileLayer` (`vector_map_tiles`/`vector_map_tiles_mbtiles`),
con lo stile "OSM Bright" vendorizzato in
`assets/map_styles/osm_bright_style.json` (vedi
`lib/features/map/offline_map_theme.dart`). Fuori da una regione scaricata,
resta il fallback raster online (`TileSourceConfig`).

Nota: questa integrazione **non è stata verificata end-to-end** in nessuna
sessione — sia la pipeline CI sia il rendering vettoriale lato app
richiedono un vero file `.mbtiles`, che non esiste ancora. Il codice
dell'app compila e non introduce regressioni quando nessuna regione è
scaricata (fallback raster); il workflow CI non è mai stato eseguito. La
prima esecuzione di entrambi va controllata a mano.
