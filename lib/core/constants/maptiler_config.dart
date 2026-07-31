/// MapTiler API key, for the "OSM Bright" raster basemap
/// ([TileSourceConfig]) — free tier, ~100k tile requests/month, no billing
/// required. Get one at https://cloud.maptiler.com/account/keys/ and pass
/// it at build/run time via `--dart-define=MAPTILER_API_KEY=<key>` (or
/// `--dart-define-from-file=maptiler.json`, gitignored, containing
/// `{"MAPTILER_API_KEY": "<key>"}`) — never commit the key itself.
///
/// Deliberately **not** machine-local like `android/local.properties`
/// (MAPS_API_KEY's old home): this key is consumed by Dart code directly
/// (the tile URL), so it has to reach Dart via `--dart-define`, not
/// Gradle's `manifestPlaceholders`.
class MaptilerConfig {
  const MaptilerConfig._();

  static const String apiKey = String.fromEnvironment('MAPTILER_API_KEY');

  static bool get isConfigured => apiKey.isNotEmpty;
}
