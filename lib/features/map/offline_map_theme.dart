import 'dart:convert';

import 'package:flutter/services.dart';
import 'package:vector_tile_renderer/vector_tile_renderer.dart';

/// The "OSM Bright" render theme (colors/layer rules only — not the tile
/// data), vendored from
/// https://github.com/openmaptiles/osm-bright-gl-style (BSD-3-Clause, see
/// `assets/map_styles/LICENSE.md`) as `assets/map_styles/osm_bright_style.json`.
///
/// That style's own `sources`/`glyphs`/`sprite` fields point at MapTiler's
/// hosted OpenMapTiles data (API-key-gated) — [ThemeReader.read] only reads
/// the `layers` array, so none of that is ever used: the actual tile data
/// comes from [OfflineVectorTileLayer]'s own self-hosted `.mbtiles`
/// (Planetiler already builds the same OpenMapTiles schema this style
/// expects — see `tools/offline_tiles/README.md`), keyed under the same
/// `openmaptiles` source id this style's layers reference.
class OfflineMapTheme {
  OfflineMapTheme._();

  static const String _assetPath = 'assets/map_styles/osm_bright_style.json';

  static Theme? _cached;
  static Future<Theme>? _loading;

  static Future<Theme> load() {
    final cached = _cached;
    if (cached != null) return Future.value(cached);
    return _loading ??= _load();
  }

  static Future<Theme> _load() async {
    final text = await rootBundle.loadString(_assetPath);
    final json = jsonDecode(text) as Map<String, dynamic>;
    final theme = ThemeReader().read(json);
    _cached = theme;
    return theme;
  }
}
