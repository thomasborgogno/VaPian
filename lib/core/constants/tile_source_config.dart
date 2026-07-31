/// Map tile sourcing for the OpenStreetMap-based map (replacing Google
/// Maps) — see PLAN.md for the two-tier strategy this backs:
///
/// - **Online default**: the standard public OSM raster tile server,
///   respecting the OSM Tile Usage Policy (normal interactive load, proper
///   attribution/user agent below) — used whenever a region hasn't been
///   downloaded for offline use, or the point being viewed falls outside
///   every downloaded region.
/// - **Offline, per region**: self-hosted vector tiles (Planetiler-built
///   `.mbtiles`, one per Italian region, published to Firebase Storage —
///   see `tools/offline_tiles/README.md`) downloaded by the user in
///   Settings and read locally via `vector_map_tiles_mbtiles`. Wired up in
///   `OfflineMapsSection`/`OfflineTileService`.
class TileSourceConfig {
  const TileSourceConfig._();

  /// Standard OSM raster tile endpoint — no `{s}` subdomains: OSM Operations
  /// now asks clients not to use them (see
  /// https://github.com/openstreetmap/operations/issues/737).
  static const String onlineRasterUrlTemplate = 'https://tile.openstreetmap.org/{z}/{x}/{y}.png';

  /// Required by the OSM Tile Usage Policy for any app consuming the
  /// public tile server.
  static const String attribution = '© OpenStreetMap contributors';

  /// Sent as part of the tile request `User-Agent` — the policy asks for
  /// an identifiable one, not the Dart/Flutter default.
  static const String userAgentPackageName = 'com.bortho.autovelox';

  /// Firebase Storage object path for a downloaded region's vector tiles —
  /// `{region}` is the Italian region name (see `italianRegions`), matching
  /// what the Planetiler pipeline is expected to publish.
  static String mbtilesStoragePath(String region) => 'offline_tiles/$region.mbtiles';
}
