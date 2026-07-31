# Pipeline tile vettoriali offline (self-hosted, Planetiler)

Genera un file `.mbtiles` per regione italiana e lo pubblica su Firebase
Storage nel path che l'app si aspetta
([`TileSourceConfig.mbtilesStoragePath`](../../lib/core/constants/tile_source_config.dart)):
`offline_tiles/{regione}.mbtiles`, dove `{regione}` è uno dei 20 nomi in
[`italianRegions`](../../lib/core/constants/italian_regions.dart) (es.
`offline_tiles/Lombardia.mbtiles`).

**Questo è un passo operativo, non automatizzato**: va eseguito manualmente
(o in una pipeline CI dedicata, non presente in questo repo) ogni volta che
si vuole aggiornare/pubblicare le tile offline. Finché non viene eseguito
almeno una volta, il download di una regione da Impostazioni → "Mappe
offline" fallisce con un 404 — comportamento noto e già gestito
dall'isolamento dei fallimenti per regione in `OfflineMapsSection`.

## Perché self-hosted

Il download di massa dal tile server pubblico di OpenStreetMap
(`tile.openstreetmap.org`, usato come sorgente online di default,
[`TileSourceConfig`](../../lib/core/constants/tile_source_config.dart))
viola la [Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/)
di OSM, che vieta il bulk download per uso offline. Le tile *vettoriali*
per l'uso offline vanno quindi generate da un estratto OSM e ospitate da
noi, non scaricate in blocco dal server pubblico.

## Prerequisiti

- **Java 21+** (richiesto da Planetiler).
- **~50-100 GB di spazio disco libero** per l'estratto Italia + gli
  intermedi di Planetiler (l'estratto compresso è ~2 GB, ma il processing
  richiede molto più spazio temporaneo).
- **8+ GB di RAM** consigliati per la macchina che esegue Planetiler.
- Un progetto Firebase con **Firebase Storage abilitato** (Console →
  Storage → "Inizia") — non ancora abilitato di default su
  `bortho-autovelox` al momento in cui questo documento è stato scritto.
- `gsutil` o l'interfaccia web di Firebase Storage per l'upload.

## 1. Scaricare l'estratto OSM Italia

```bash
curl -O https://download.geofabrik.de/europe/italy-latest.osm.pbf
```

Aggiornato periodicamente da Geofabrik — rieseguire questo step per
rigenerare le tile con dati più recenti.

## 2. Generare gli `.mbtiles` con Planetiler

```bash
curl -L -O https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar
java -Xmx8g -jar planetiler.jar --download --area=italy \
  --output=italy.mbtiles
```

Questo produce **un solo `.mbtiles`** per l'intero paese, con lo schema
OpenMapTiles di default (strade, edifici, POI, confini amministrativi —
sufficiente per uno stile stradale leggibile).

## 3. Ritagliare per regione

Planetiler non ha un flag "ritaglia per confine amministrativo" pronto
all'uso: il modo più semplice è passare un bounding box per regione (vedi
[`italianRegionBounds`](../../lib/core/constants/italian_region_bounds.dart)
per i valori già usati per il priming degli autovelox — sono
approssimativi, non i confini amministrativi esatti, ma sufficienti per
questo scopo) invece dell'intera Italia:

```bash
java -Xmx8g -jar planetiler.jar --download \
  --bounds="<minLng>,<minLat>,<maxLng>,<maxLat>" \
  --output="Lombardia.mbtiles"
```

Ripetere per le 20 regioni (script di comodo, non incluso qui — da scrivere
quando si esegue davvero questa pipeline, iterando su
`italianRegionBounds`).

## 4. Pubblicare su Firebase Storage

```bash
gsutil cp Lombardia.mbtiles gs://<bucket>/offline_tiles/Lombardia.mbtiles
```

oppure via Console → Storage → upload manuale nel path `offline_tiles/`.

Le regole di Storage devono permettere la lettura autenticata (coerente con
le regole già usate per Firestore in `firestore.rules`) — non ancora
scritte in questo repo, da aggiungere insieme all'abilitazione di Storage.

## 5. Verifica

Dopo l'upload di almeno una regione, in Impostazioni → "Mappe offline"
selezionarla e premere "Scarica per offline": il download deve completare
senza errori e, disattivando la rete, la mappa di quella regione deve
restare visibile — renderizzata dai tile vettoriali locali via
`OfflineVectorTileLayer` (`vector_map_tiles`/`vector_map_tiles_mbtiles`),
con lo stile "OSM Bright" vendorizzato in
`assets/map_styles/osm_bright_style.json` (vedi
`lib/features/map/offline_map_theme.dart`). Fuori da una regione scaricata,
resta il fallback raster online (`TileSourceConfig`).

Nota: questa integrazione **non è stata verificata end-to-end** in nessuna
sessione — richiede un vero file `.mbtiles` generato con questa pipeline,
che non esiste ancora. Il codice compila e non introduce regressioni
quando nessuna regione è scaricata (fallback raster), ma il rendering
vettoriale vero e proprio va controllato a mano la prima volta che un
file reale viene scaricato su un device.
