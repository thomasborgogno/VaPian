import 'app_database.dart';
import '../models/ignored_camera.dart';
import '../models/queued_ignore.dart';
import '../models/speed_camera.dart';

/// Converts between [SpeedCamera] and a `speed_cameras` SQLite row.
///
/// Firestore's `SpeedCamera.toMap()`/`fromMap()` are shaped for Firestore
/// (`Timestamp`, no `region`/`dirty`/`deleted` bookkeeping columns), so the
/// local store gets its own pair instead of reusing those.
extension SpeedCameraLocalRow on SpeedCamera {
  Map<String, Object?> toLocalRow({String? region, bool dirty = false, bool deleted = false}) {
    return {
      SpeedCamerasTable.id: id,
      SpeedCamerasTable.lat: lat,
      SpeedCamerasTable.lng: lng,
      SpeedCamerasTable.speedLimit: limit,
      SpeedCamerasTable.direction: direction,
      SpeedCamerasTable.status: status.name,
      SpeedCamerasTable.source: source.name,
      SpeedCamerasTable.geohash: geohash,
      SpeedCamerasTable.region: region,
      SpeedCamerasTable.createdBy: createdBy,
      SpeedCamerasTable.createdAt: createdAt?.millisecondsSinceEpoch,
      SpeedCamerasTable.updatedAt: updatedAt?.millisecondsSinceEpoch,
      SpeedCamerasTable.osmNodeId: osmNodeId,
      SpeedCamerasTable.osmNodeVersion: osmNodeVersion,
      SpeedCamerasTable.directionReversed: directionReversed ? 1 : 0,
      SpeedCamerasTable.dirty: dirty ? 1 : 0,
      SpeedCamerasTable.deleted: deleted ? 1 : 0,
    };
  }
}

SpeedCamera speedCameraFromLocalRow(Map<String, Object?> row) {
  return SpeedCamera(
    id: row[SpeedCamerasTable.id] as String,
    lat: row[SpeedCamerasTable.lat] as double,
    lng: row[SpeedCamerasTable.lng] as double,
    limit: row[SpeedCamerasTable.speedLimit] as int?,
    direction: row[SpeedCamerasTable.direction] as int?,
    status: SpeedCameraStatus.fromValue(row[SpeedCamerasTable.status] as String),
    source: SpeedCameraSource.fromValue(row[SpeedCamerasTable.source] as String),
    geohash: row[SpeedCamerasTable.geohash] as String,
    createdBy: row[SpeedCamerasTable.createdBy] as String?,
    createdAt: _epochOrNull(row[SpeedCamerasTable.createdAt]),
    updatedAt: _epochOrNull(row[SpeedCamerasTable.updatedAt]),
    osmNodeId: row[SpeedCamerasTable.osmNodeId] as int?,
    osmNodeVersion: row[SpeedCamerasTable.osmNodeVersion] as int?,
    directionReversed: (row[SpeedCamerasTable.directionReversed] as int? ?? 0) != 0,
  );
}

extension IgnoredCameraLocalRow on IgnoredCamera {
  Map<String, Object?> toLocalRow(String uid, {bool dirty = false, bool deleted = false}) {
    return {
      IgnoredCamerasTable.uid: uid,
      IgnoredCamerasTable.cameraId: cameraId,
      IgnoredCamerasTable.ignoredAt:
          (ignoredAt ?? DateTime.now()).millisecondsSinceEpoch,
      IgnoredCamerasTable.dirty: dirty ? 1 : 0,
      IgnoredCamerasTable.deleted: deleted ? 1 : 0,
    };
  }
}

IgnoredCamera ignoredCameraFromLocalRow(Map<String, Object?> row) {
  return IgnoredCamera(
    cameraId: row[IgnoredCamerasTable.cameraId] as String,
    ignoredAt: _epochOrNull(row[IgnoredCamerasTable.ignoredAt]),
  );
}

extension QueuedIgnoreLocalRow on QueuedIgnore {
  Map<String, Object?> toLocalRow(String uid, {bool dirty = false, bool deleted = false}) {
    return {
      QueuedIgnoresTable.uid: uid,
      QueuedIgnoresTable.cameraId: cameraId,
      QueuedIgnoresTable.queuedAt: (queuedAt ?? DateTime.now()).millisecondsSinceEpoch,
      QueuedIgnoresTable.dirty: dirty ? 1 : 0,
      QueuedIgnoresTable.deleted: deleted ? 1 : 0,
    };
  }
}

QueuedIgnore queuedIgnoreFromLocalRow(Map<String, Object?> row) {
  return QueuedIgnore(
    cameraId: row[QueuedIgnoresTable.cameraId] as String,
    queuedAt: _epochOrNull(row[QueuedIgnoresTable.queuedAt]),
  );
}

DateTime? _epochOrNull(Object? value) =>
    value == null ? null : DateTime.fromMillisecondsSinceEpoch(value as int);
