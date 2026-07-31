/// Approximate lat/lng bounding box for an Italian region — good enough to
/// decide "does this camera belong to the region the user downloaded for
/// offline use", not for anything requiring exact administrative borders
/// (a handful of cameras right on a regional border may be included or
/// excluded either way).
class RegionBounds {
  const RegionBounds(this.minLat, this.maxLat, this.minLng, this.maxLng);

  final double minLat;
  final double maxLat;
  final double minLng;
  final double maxLng;

  bool contains(double lat, double lng) =>
      lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
}

/// Bounding boxes for the 20 regions in [italianRegions], keyed by the same
/// names — used to scope both the offline map tile download and the
/// speed-camera local-store priming (`SyncService.primeRegion`) to "what's
/// roughly inside this region".
const Map<String, RegionBounds> italianRegionBounds = {
  'Abruzzo': RegionBounds(41.6, 42.9, 13.0, 14.8),
  'Basilicata': RegionBounds(39.9, 41.1, 15.3, 16.9),
  'Calabria': RegionBounds(37.9, 40.2, 15.6, 17.3),
  'Campania': RegionBounds(39.9, 41.5, 13.7, 15.8),
  'Emilia-Romagna': RegionBounds(43.7, 45.1, 9.1, 12.8),
  'Friuli-Venezia Giulia': RegionBounds(45.5, 46.7, 12.3, 13.9),
  'Lazio': RegionBounds(40.8, 42.9, 11.4, 14.0),
  'Liguria': RegionBounds(43.7, 44.7, 7.4, 10.1),
  'Lombardia': RegionBounds(44.7, 46.7, 8.5, 11.5),
  'Marche': RegionBounds(42.7, 44.0, 12.2, 13.9),
  'Molise': RegionBounds(41.4, 42.1, 14.1, 15.2),
  'Piemonte': RegionBounds(44.0, 46.5, 6.6, 9.2),
  'Puglia': RegionBounds(39.7, 42.2, 14.9, 18.6),
  'Sardegna': RegionBounds(38.8, 41.3, 8.1, 9.9),
  'Sicilia': RegionBounds(36.6, 38.8, 11.9, 15.7),
  'Toscana': RegionBounds(42.2, 44.5, 9.6, 12.4),
  'Trentino-Alto Adige': RegionBounds(45.6, 47.1, 10.4, 12.5),
  'Umbria': RegionBounds(42.3, 43.7, 11.9, 13.3),
  "Valle d'Aosta": RegionBounds(45.5, 46.0, 6.8, 7.9),
  'Veneto': RegionBounds(44.7, 46.7, 10.6, 13.2),
};

/// The region whose bounding box contains ([lat], [lng]), if any — used to
/// decide whether the map should render from a downloaded region's local
/// vector tiles instead of the online raster fallback (`OfflineVectorTileLayer`).
/// A point near a regional border can fall inside more than one box; the
/// first match (map iteration order, same as [italianRegionBounds]'s
/// declaration order) wins — acceptable given these boxes are already only
/// approximate.
String? regionContaining(double lat, double lng) {
  for (final entry in italianRegionBounds.entries) {
    if (entry.value.contains(lat, lng)) return entry.key;
  }
  return null;
}
