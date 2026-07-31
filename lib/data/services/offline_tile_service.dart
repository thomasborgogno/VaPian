import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../core/constants/tile_source_config.dart';

/// Downloads/deletes a macro-zone's self-hosted vector-tile `.mbtiles` file
/// (Planetiler pipeline → GitHub Release asset, see
/// `tools/offline_tiles/README.md`) into local app storage, so
/// `vector_map_tiles_mbtiles` can read it offline. Plain HTTP GET against a
/// public URL — no auth, no external hosting account, since the release is
/// published on this (public) repo.
///
/// **Requires the pipeline to have published at least once** — until then,
/// [downloadZone] fails with a 404 [http.ClientException]/non-200 status.
class OfflineTileService {
  OfflineTileService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  Future<Directory> _tilesDirectory() async {
    final supportDir = await getApplicationSupportDirectory();
    final tilesDir = Directory(p.join(supportDir.path, 'offline_tiles'));
    if (!await tilesDir.exists()) {
      await tilesDir.create(recursive: true);
    }
    return tilesDir;
  }

  Future<File> localFileFor(String zone) async {
    final dir = await _tilesDirectory();
    return File(p.join(dir.path, '$zone.mbtiles'));
  }

  Future<bool> isDownloaded(String zone) async => (await localFileFor(zone)).exists();

  /// Downloads [zone]'s `.mbtiles`, emitting progress in `[0, 1]` as it
  /// goes — the last value emitted is always `1.0` on success. Writes
  /// straight to the final file path; a failure partway through leaves a
  /// truncated file, so callers should treat any thrown error as "not
  /// downloaded" and let the user retry rather than trusting a partial file.
  Stream<double> downloadZone(String zone) async* {
    final file = await localFileFor(zone);
    final request = http.Request('GET', Uri.parse(TileSourceConfig.mbtilesDownloadUrl(zone)));
    final response = await _client.send(request);
    if (response.statusCode != 200) {
      throw http.ClientException('HTTP ${response.statusCode} downloading $zone.mbtiles');
    }

    final totalBytes = response.contentLength ?? 0;
    var receivedBytes = 0;
    final sink = file.openWrite();
    try {
      await for (final chunk in response.stream) {
        sink.add(chunk);
        receivedBytes += chunk.length;
        if (totalBytes > 0) yield receivedBytes / totalBytes;
      }
    } finally {
      await sink.close();
    }
    yield 1.0;
  }

  Future<void> deleteZone(String zone) async {
    final file = await localFileFor(zone);
    if (await file.exists()) {
      await file.delete();
    }
  }
}
