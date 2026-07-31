# Pipeline tile vettoriali offline (self-hosted, Planetiler)

Genera un file `.mbtiles` per ciascuna delle 5 macro-zone italiane ufficiali
di Geofabrik e lo pubblica come asset di una GitHub Release, da dove l'app
lo scarica con una richiesta HTTP diretta, senza autenticazione — il repo è
pubblico, quindi non serve alcun account/servizio di hosting esterno (vedi
[`TileSourceConfig.mbtilesDownloadUrl`](../../lib/core/constants/tile_source_config.dart)):
`https://github.com/thomasborgogno/VaPian/releases/download/offline-tiles-latest/{zona}.mbtiles`,
dove `{zona}` è uno dei 5 nomi in
[`italianMacroZoneNames`](../../lib/core/constants/italian_macro_zones.dart)
(`Nord-Ovest`, `Nord-Est`, `Centro`, `Sud`, `Isole`).

**Perché macro-zone e non le 20 regioni amministrative**: le tile
vettoriali sono già molto più leggere del raster (l'intera Italia in
OpenMapTiles è dell'ordine di 1-2 GB, non decine), quindi non serve la
granularità delle 20 regioni per tenere i file piccoli. Le 5 macro-zone di
Geofabrik (`nord-ovest`, `nord-est`, `centro`, `sud`, `isole`) hanno il
vantaggio di avere già un estratto OSM pronto e ritagliato — niente
bounding-box custom da mantenere né tool di ritaglio (`osmium`) nella
pipeline. La sincronizzazione dati OSM/Overpass (`OsmSyncSection`) resta
invece alla granularità delle 20 regioni, che è un problema diverso (dati
freschi da Overpass, non tile mappa).

**Perché GitHub Release e non un servizio cloud dedicato**: nessun account
esterno da creare, nessun secret da configurare, nessun costo — il
`GITHUB_TOKEN` che ogni workflow riceve automaticamente basta a pubblicare
release sul proprio repo. L'unico requisito è che il repo resti pubblico
(lo è: gli asset di release di un repo privato richiederebbero
autenticazione anche in lettura, e l'app non può spedire un token
GitHub incorporato agli utenti finali).

## Pipeline automatizzata (GitHub Actions)

[`.github/workflows/offline_tiles.yml`](../../.github/workflows/offline_tiles.yml)
esegue l'intera pipeline (download estratti Geofabrik per zona → Planetiler
→ pubblicazione su una GitHub Release) sia manualmente (tab *Actions* →
*Rebuild offline map tiles* → *Run workflow*) sia in automatico il primo di
ogni mese. Non richiede alcun setup manuale oltre ai permessi che il
workflow stesso dichiara (`permissions: contents: write`) — nessun secret
da creare.

La release usa un tag fisso (`offline-tiles-latest`): ogni esecuzione
sostituisce gli asset della release esistente invece di crearne una nuova,
così l'URL che l'app usa resta sempre lo stesso.

Finché la pipeline non è mai stata eseguita con successo, il download di
una zona da Impostazioni → "Mappe offline" fallisce con un 404 —
comportamento noto e già gestito dall'isolamento dei fallimenti per zona
in `OfflineMapsSection`.

## Perché self-hosted

Il download di massa dal tile server pubblico di OpenStreetMap
(`tile.openstreetmap.org`, usato come sorgente online di default,
[`TileSourceConfig`](../../lib/core/constants/tile_source_config.dart))
viola la [Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/)
di OSM, che vieta il bulk download per uso offline. Le tile *vettoriali*
per l'uso offline vanno quindi generate da un estratto OSM e ospitate da
noi, non scaricate in blocco dal server pubblico.

## Cosa fa la pipeline, passo per passo

Lo stesso script che gira in CI ([`build_zones.sh`](build_zones.sh)) è
eseguibile anche in locale (Linux/macOS con Java 21+ e `jq` installati) —
utile per debug o per generare le tile senza aspettare CI:

```bash
sudo apt-get install -y jq   # o brew install jq su macOS

curl -L -O https://github.com/onthegomap/planetiler/releases/latest/download/planetiler.jar

./build_zones.sh zones.json planetiler.jar out/
```

Per ciascuna delle 5 zone in [`zones.json`](zones.json) (stessi nomi/slug
di [`italianMacroZones`](../../lib/core/constants/italian_macro_zones.dart)
— **tenere i due file sincronizzati a mano**, nessun codegen li collega):

1. **Download** dell'estratto già ritagliato da Geofabrik:
   `https://download.geofabrik.de/europe/italy/{slug}-latest.osm.pbf`.
2. **Planetiler** genera l'`.mbtiles` con lo schema OpenMapTiles di default
   (strade, edifici, POI, confini amministrativi) da quell'estratto.

Poi, in CI, [`softprops/action-gh-release`](https://github.com/softprops/action-gh-release)
pubblica tutti i file generati come asset della release
`offline-tiles-latest` (creandola se non esiste, sostituendone gli asset se
esiste già).

### Requisiti macchina (per l'esecuzione locale — il runner GitHub Actions li soddisfa già)

- **Java 21+**.
- **~5-10 GB di spazio disco libero** per zona (l'estratto di una
  macro-zona è alcune centinaia di MB; il processing Planetiler ne
  richiede di più ma resta ben lontano dalla scala "intero pianeta").
- **4+ GB di RAM** consigliati.

## Verifica

Dopo che la pipeline ha pubblicato almeno una zona, in Impostazioni →
"Mappe offline" selezionarla e premere "Scarica per offline": il download
deve completare senza errori e, disattivando la rete, la mappa di quella
zona deve restare visibile — renderizzata dai tile vettoriali locali via
`OfflineVectorTileLayer` (`vector_map_tiles`/`vector_map_tiles_mbtiles`),
con lo stile "OSM Bright" vendorizzato in
`assets/map_styles/osm_bright_style.json` (vedi
`lib/features/map/offline_map_theme.dart`). Fuori da una zona scaricata,
resta il fallback raster online (`TileSourceConfig`).

Nota: questa integrazione **non è stata verificata end-to-end** in nessuna
sessione — sia la pipeline CI sia il rendering vettoriale lato app
richiedono un vero file `.mbtiles`, che non esiste ancora. Il codice
dell'app compila e non introduce regressioni quando nessuna zona è
scaricata (fallback raster); il workflow CI non è mai stato eseguito. La
prima esecuzione di entrambi va controllata a mano.
