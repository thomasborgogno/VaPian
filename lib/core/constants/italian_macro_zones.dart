/// Approximate lat/lng bounding box for an area — good enough to decide
/// "does this camera/tile belong to the area the user downloaded for
/// offline use", not for anything requiring exact administrative borders.
class RegionBounds {
  const RegionBounds(this.minLat, this.maxLat, this.minLng, this.maxLng);

  final double minLat;
  final double maxLat;
  final double minLng;
  final double maxLng;

  bool contains(double lat, double lng) =>
      lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
}

/// One of Geofabrik's 5 official Italian macro-zones
/// (https://download.geofabrik.de/europe/italy.html) — used for offline map
/// tiles/camera priming instead of the 20 administrative regions
/// (`italianRegions`, still used by `OsmSyncSection`'s Overpass sync):
/// downloading/generating tiles at this coarser granularity means far fewer,
/// larger files instead of 20 small ones, and [geofabrikSlug] lets the
/// tile-generation pipeline (`tools/offline_tiles/`) use Geofabrik's
/// already-clipped extracts directly — no bounding-box clipping step needed.
class ItalianMacroZone {
  const ItalianMacroZone(this.name, this.geofabrikSlug, this.bounds);

  /// Display name, also used as the Firebase Storage/local file key (see
  /// `TileSourceConfig.mbtilesStoragePath`) — keep in sync by hand with
  /// `tools/offline_tiles/zones.json`.
  final String name;

  /// Geofabrik's own slug for this zone's extract, e.g.
  /// `https://download.geofabrik.de/europe/italy/{slug}-latest.osm.pbf`.
  final String geofabrikSlug;

  final RegionBounds bounds;
}

/// The 5 zones, envelope bounding boxes derived from the regions each
/// contains (some overlap at the seams is expected — same approximation
/// trade-off as the old per-region boxes).
const List<ItalianMacroZone> italianMacroZones = [
  ItalianMacroZone(
    'Nord-Ovest',
    'nord-ovest',
    RegionBounds(43.7, 46.7, 6.6, 11.5), // Valle d'Aosta, Piemonte, Liguria, Lombardia
  ),
  ItalianMacroZone(
    'Nord-Est',
    'nord-est',
    RegionBounds(43.7, 47.1, 9.1, 13.9), // Trentino-Alto Adige, Veneto, Friuli-Venezia Giulia, Emilia-Romagna
  ),
  ItalianMacroZone(
    'Centro',
    'centro',
    RegionBounds(40.8, 44.5, 9.6, 14.0), // Toscana, Umbria, Marche, Lazio
  ),
  ItalianMacroZone(
    'Sud',
    'sud',
    RegionBounds(37.9, 42.9, 13.0, 18.6), // Abruzzo, Molise, Campania, Puglia, Basilicata, Calabria
  ),
  ItalianMacroZone(
    'Isole',
    'isole',
    RegionBounds(36.6, 41.3, 8.1, 15.7), // Sicilia, Sardegna
  ),
];

const List<String> italianMacroZoneNames = [
  'Nord-Ovest',
  'Nord-Est',
  'Centro',
  'Sud',
  'Isole',
];

/// The zone whose bounding box contains ([lat], [lng]), if any — used to
/// decide whether the map should render from a downloaded zone's local
/// vector tiles instead of the online raster fallback (`OfflineVectorTileLayer`).
/// A point near a seam can fall inside more than one box; the first match
/// (declaration order above) wins.
ItalianMacroZone? zoneContaining(double lat, double lng) {
  for (final zone in italianMacroZones) {
    if (zone.bounds.contains(lat, lng)) return zone;
  }
  return null;
}
