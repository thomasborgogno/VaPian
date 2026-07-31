import 'package:flutter/material.dart' hide Theme;
import 'package:mbtiles/mbtiles.dart';
import 'package:vector_map_tiles/vector_map_tiles.dart';
import 'package:vector_map_tiles_mbtiles/vector_map_tiles_mbtiles.dart';
import 'package:vector_tile_renderer/vector_tile_renderer.dart' show Theme;

import '../../data/services/offline_tile_service.dart';
import 'offline_map_theme.dart';

/// Renders a downloaded region's self-hosted vector tiles
/// (`OfflineTileService`/`tools/offline_tiles/README.md`) with the OSM
/// Bright theme ([OfflineMapTheme]) — the offline counterpart to the online
/// raster `TileLayer` (`TileSourceConfig`). Callers pick between the two
/// based on whether the current viewport falls inside a region the user has
/// actually downloaded (see `MapScreen._refreshViewport`/
/// `italian_region_bounds.dart`'s `regionContaining`).
class OfflineVectorTileLayer extends StatelessWidget {
  const OfflineVectorTileLayer({super.key, required this.region});

  final String region;

  @override
  Widget build(BuildContext context) {
    return FutureBuilder<(Theme, MbTilesVectorTileProvider)>(
      future: _load(region),
      builder: (context, snapshot) {
        final data = snapshot.data;
        if (data == null) return const SizedBox.shrink();
        final (theme, provider) = data;
        return VectorTileLayer(
          theme: theme,
          tileProviders: TileProviders({'openmaptiles': provider}),
        );
      },
    );
  }

  static Future<(Theme, MbTilesVectorTileProvider)> _load(String region) async {
    final theme = await OfflineMapTheme.load();
    final provider = await _OfflineVectorTileProviders.forRegion(region);
    return (theme, provider);
  }
}

/// Keeps one open `MbTiles`/`MbTilesVectorTileProvider` per region for the
/// app's lifetime instead of reopening the SQLite file on every rebuild —
/// panning in and out of a downloaded region is expected to happen
/// repeatedly during a single map session.
class _OfflineVectorTileProviders {
  _OfflineVectorTileProviders._();

  static final Map<String, MbTilesVectorTileProvider> _providers = {};

  static Future<MbTilesVectorTileProvider> forRegion(String region) async {
    final cached = _providers[region];
    if (cached != null) return cached;
    final file = await OfflineTileService().localFileFor(region);
    final provider = MbTilesVectorTileProvider(mbtiles: MbTiles(mbtilesPath: file.path));
    _providers[region] = provider;
    return provider;
  }
}
