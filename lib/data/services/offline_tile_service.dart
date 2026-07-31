import 'dart:io';

import 'package:firebase_storage/firebase_storage.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';

import '../../core/constants/tile_source_config.dart';

/// Downloads/deletes a region's self-hosted vector-tile `.mbtiles` file
/// (Planetiler pipeline → Firebase Storage, see
/// `tools/offline_tiles/README.md`) into local app storage, so
/// `vector_map_tiles_mbtiles` can read it offline.
///
/// **Requires the `.mbtiles` files to actually exist on Storage** — that's
/// an operational step (running the Planetiler pipeline, enabling Firebase
/// Storage on the project) outside this app's code; until then,
/// [downloadRegion] fails with a 404-shaped [FirebaseException].
class OfflineTileService {
  OfflineTileService({FirebaseStorage? storage}) : _storage = storage ?? FirebaseStorage.instance;

  final FirebaseStorage _storage;

  Future<Directory> _tilesDirectory() async {
    final supportDir = await getApplicationSupportDirectory();
    final tilesDir = Directory(p.join(supportDir.path, 'offline_tiles'));
    if (!await tilesDir.exists()) {
      await tilesDir.create(recursive: true);
    }
    return tilesDir;
  }

  Future<File> localFileFor(String region) async {
    final dir = await _tilesDirectory();
    return File(p.join(dir.path, '$region.mbtiles'));
  }

  Future<bool> isDownloaded(String region) async => (await localFileFor(region)).exists();

  /// Downloads [region]'s `.mbtiles`, emitting progress in `[0, 1]` as it
  /// goes — the last value emitted is always `1.0` on success. Writes
  /// straight to the final file path; a failure partway through leaves a
  /// truncated file, so callers should treat any thrown error as "not
  /// downloaded" and let the user retry rather than trusting a partial file.
  Stream<double> downloadRegion(String region) async* {
    final file = await localFileFor(region);
    final ref = _storage.ref(TileSourceConfig.mbtilesStoragePath(region));
    final task = ref.writeToFile(file);
    await for (final snapshot in task.snapshotEvents) {
      if (snapshot.totalBytes > 0) {
        yield snapshot.bytesTransferred / snapshot.totalBytes;
      }
    }
  }

  Future<void> deleteRegion(String region) async {
    final file = await localFileFor(region);
    if (await file.exists()) {
      await file.delete();
    }
  }
}
