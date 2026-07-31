import 'maptiler_config.dart';

/// Map tile sourcing for the OpenStreetMap-based map (replacing Google
/// Maps) — see PLAN.md for the two-tier strategy this backs:
///
/// - **Online default**: MapTiler's hosted "Bright" raster basemap (the
///   published equivalent of the classic "OSM Bright" style) when
///   [MaptilerConfig.isConfigured], falling back to CARTO's free,
///   keyless "Voyager" basemap otherwise — so the map still works out of
///   the box on a machine that hasn't set up a MapTiler key yet. Used
///   whenever a region hasn't been downloaded for offline use, or the
///   point being viewed falls outside every downloaded region.
/// - **Offline, per region**: self-hosted vector tiles (Planetiler-built
///   `.mbtiles`, one per Italian region, published to Firebase Storage —
///   see `tools/offline_tiles/README.md`) downloaded by the user in
///   Settings and read locally via `vector_map_tiles_mbtiles`. Wired up in
///   `OfflineMapsSection`/`OfflineTileService`.
class TileSourceConfig {
  const TileSourceConfig._();

  /// MapTiler's "Bright" (`bright-v2`) raster tiles — needs
  /// [MaptilerConfig.apiKey]; see `CLAUDE.md`/`README.md` for how to set
  /// one up on a new machine.
  static const String _maptilerBrightUrlTemplate =
      'https://api.maptiler.com/maps/bright-v2/256/{z}/{x}/{y}.png?key=${MaptilerConfig.apiKey}';

  /// CARTO Voyager raster tiles — free for light interactive use, no API
  /// key. `{s}` load-balances across CARTO's own a-d subdomains (unlike the
  /// OSM Foundation's tile server, CARTO's own usage guidance still
  /// recommends this).
  static const String _cartoVoyagerUrlTemplate =
      'https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png';

  static String get onlineRasterUrlTemplate =>
      MaptilerConfig.isConfigured ? _maptilerBrightUrlTemplate : _cartoVoyagerUrlTemplate;

  /// Only meaningful for the CARTO fallback — MapTiler's endpoint has no
  /// `{s}` placeholder, so `TileLayer` simply ignores this list when it's
  /// the active source.
  static const List<String> onlineRasterSubdomains = ['a', 'b', 'c', 'd'];

  static String get attribution => MaptilerConfig.isConfigured
      ? '© MapTiler © OpenStreetMap contributors'
      : '© OpenStreetMap contributors © CARTO';

  /// Sent as part of the tile request `User-Agent` — the policy asks for
  /// an identifiable one, not the Dart/Flutter default.
  static const String userAgentPackageName = 'com.bortho.autovelox';

  /// Firebase Storage object path for a downloaded region's vector tiles —
  /// `{region}` is the Italian region name (see `italianRegions`), matching
  /// what the Planetiler pipeline is expected to publish.
  static String mbtilesStoragePath(String region) => 'offline_tiles/$region.mbtiles';
}
