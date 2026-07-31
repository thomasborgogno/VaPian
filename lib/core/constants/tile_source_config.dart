/// Map tile sourcing for the OpenStreetMap-based map (replacing Google
/// Maps) — see PLAN.md for the two-tier strategy this backs:
///
/// - **Online default**: CARTO's free "Voyager" raster basemap (OSM data,
///   different host/policy than the OSM Foundation's own tile server —
///   see https://github.com/CartoDB/basemap-styles) — used whenever a
///   region hasn't been downloaded for offline use, or the point being
///   viewed falls outside every downloaded region. To switch style, change
///   [onlineRasterUrlTemplate]/[attribution] below to another provider —
///   e.g. CARTO's own `light_all`/`dark_all` for Positron/Dark Matter, or
///   `https://tile.openstreetmap.org/{z}/{x}/{y}.png` (no `{s}`/subdomains)
///   for the plain OSM Foundation style.
/// - **Offline, per region**: self-hosted vector tiles (Planetiler-built
///   `.mbtiles`, one per Italian region, published to Firebase Storage —
///   see `tools/offline_tiles/README.md`) downloaded by the user in
///   Settings and read locally via `vector_map_tiles_mbtiles`. Wired up in
///   `OfflineMapsSection`/`OfflineTileService`.
class TileSourceConfig {
  const TileSourceConfig._();

  /// CARTO Voyager raster tiles — free for light interactive use, no API
  /// key. `{s}` load-balances across CARTO's own a-d subdomains (unlike the
  /// OSM Foundation's tile server, CARTO's own usage guidance still
  /// recommends this).
  static const String onlineRasterUrlTemplate =
      'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png';

  static const List<String> onlineRasterSubdomains = ['a', 'b', 'c', 'd'];

  /// Required attribution for CARTO basemaps built from OSM data.
  static const String attribution = '© OpenStreetMap contributors © CARTO';

  /// Sent as part of the tile request `User-Agent` — the policy asks for
  /// an identifiable one, not the Dart/Flutter default.
  static const String userAgentPackageName = 'com.bortho.autovelox';

  /// Firebase Storage object path for a downloaded region's vector tiles —
  /// `{region}` is the Italian region name (see `italianRegions`), matching
  /// what the Planetiler pipeline is expected to publish.
  static String mbtilesStoragePath(String region) => 'offline_tiles/$region.mbtiles';
}
