import 'dart:async';

import 'package:sqflite/sqflite.dart';
import 'package:uuid/uuid.dart';

import '../../core/utils/geo.dart';
import '../local/app_database.dart';
import '../local/local_change_bus.dart';
import '../local/local_row_mappers.dart';
import '../local/sync_queue_dao.dart';
import '../local/sync_queue_op.dart';
import '../models/speed_camera.dart';

/// Local-first repository: every read comes from the shared SQLite store
/// (`speed_cameras` table, see `app_database.dart`) so the map/navigation
/// screens work identically online, offline, or on a cold start with no
/// network at all. Every write lands in SQLite immediately (instant UI
/// feedback) and enqueues a `sync_queue` row; [SyncService] is the only
/// thing that talks to Firestore for these entities.
class SpeedCameraRepository {
  SpeedCameraRepository({Future<Database>? database})
      : _databaseFuture = database ?? sharedDatabase();

  final Future<Database> _databaseFuture;

  /// Cameras within [radiusMeters] of ([lat], [lng]), regardless of
  /// `status`/`source` — callers filter further as needed (e.g. hide
  /// `pending` on the driving path).
  ///
  /// Matches the geohash cell containing ([lat], [lng]) plus its 8
  /// neighbors ([geohashNeighborPrefixes]) and filters by Haversine
  /// distance client-side, same two-step approach the old Firestore-backed
  /// version used (a point within the radius can sit in a neighboring
  /// cell rather than the center one).
  Stream<List<SpeedCamera>> watchNear(double lat, double lng, double radiusMeters) {
    final prefixes = geohashNeighborPrefixes(lat, lng, radiusMeters);

    Future<List<SpeedCamera>> query() async {
      final db = await _databaseFuture;
      final likeClauses =
          List.filled(prefixes.length, '${SpeedCamerasTable.geohash} LIKE ?').join(' OR ');
      final rows = await db.query(
        SpeedCamerasTable.name,
        where: '${SpeedCamerasTable.deleted} = 0 AND ($likeClauses)',
        whereArgs: [for (final prefix in prefixes) '$prefix%'],
      );
      return rows
          .map(speedCameraFromLocalRow)
          .where((camera) => haversineDistanceMeters(lat, lng, camera.lat, camera.lng) <= radiusMeters)
          .toList();
    }

    return _watch(query);
  }

  /// Single camera by id, regardless of geohash — used where a caller
  /// already knows the id (e.g. re-reading after a write) rather than
  /// searching by location.
  Future<SpeedCamera?> getById(String id) async {
    final db = await _databaseFuture;
    final rows = await db.query(
      SpeedCamerasTable.name,
      where: '${SpeedCamerasTable.id} = ? AND ${SpeedCamerasTable.deleted} = 0',
      whereArgs: [id],
    );
    if (rows.isEmpty) return null;
    return speedCameraFromLocalRow(rows.single);
  }

  /// All pending `source:'user'` cameras created by [uid] — shown in the
  /// review banner and sheet so the user can promote them to `approved`.
  Stream<List<SpeedCamera>> watchPendingOwn(String uid) {
    Future<List<SpeedCamera>> query() async {
      final db = await _databaseFuture;
      final rows = await db.query(
        SpeedCamerasTable.name,
        where: '${SpeedCamerasTable.deleted} = 0 AND ${SpeedCamerasTable.createdBy} = ? '
            'AND ${SpeedCamerasTable.status} = ? AND ${SpeedCamerasTable.source} = ?',
        whereArgs: [uid, SpeedCameraStatus.pending.name, SpeedCameraSource.user.name],
      );
      return rows.map(speedCameraFromLocalRow).toList();
    }

    return _watch(query);
  }

  Future<String> addCamera(SpeedCamera camera) async {
    final now = DateTime.now();
    final id = const Uuid().v4();
    final toSave = SpeedCamera(
      id: id,
      lat: camera.lat,
      lng: camera.lng,
      limit: camera.limit,
      direction: camera.direction,
      status: camera.status,
      source: camera.source,
      geohash: camera.geohash,
      createdBy: camera.createdBy,
      createdAt: camera.createdAt ?? now,
      updatedAt: now,
      osmNodeId: camera.osmNodeId,
      osmNodeVersion: camera.osmNodeVersion,
      directionReversed: camera.directionReversed,
    );
    await _upsertLocal(toSave, dirty: true);
    return id;
  }

  Future<void> deleteCamera(String id) async {
    final db = await _databaseFuture;
    await db.update(
      SpeedCamerasTable.name,
      {SpeedCamerasTable.deleted: 1, SpeedCamerasTable.dirty: 1},
      where: '${SpeedCamerasTable.id} = ?',
      whereArgs: [id],
    );
    await SyncQueueDao(db).enqueueDelete(SyncOpType.upsertCamera, SyncOpType.deleteCamera, id);
    LocalChangeBus.instance.notify();
  }

  Future<void> updateStatus(String id, SpeedCameraStatus status) {
    return _updateFields(id, {SpeedCamerasTable.status: status.name});
  }

  /// Records that this report was just published as a real node on
  /// production OpenStreetMap (features.md 4) — also promotes it to
  /// `approved`, since a published report has by definition been reviewed.
  Future<void> markPublishedToOsm(String id, int nodeId, {int version = 1}) {
    return _updateFields(id, {
      SpeedCamerasTable.osmNodeId: nodeId,
      SpeedCamerasTable.osmNodeVersion: version,
      SpeedCamerasTable.status: SpeedCameraStatus.approved.name,
    });
  }

  /// Corrects an existing report's position/limit/direction (e.g. after
  /// dragging the marker or its direction handle on the map), and/or the
  /// local-only `directionReversed` flag (never published to OSM — see
  /// [SpeedCamera.directionReversed]).
  Future<void> updateCamera(
    String id, {
    double? lat,
    double? lng,
    int? limit,
    int? direction,
    String? geohash,
    bool? directionReversed,
  }) {
    return _updateFields(id, {
      SpeedCamerasTable.lat: ?lat,
      SpeedCamerasTable.lng: ?lng,
      SpeedCamerasTable.speedLimit: ?limit,
      SpeedCamerasTable.direction: ?direction,
      SpeedCamerasTable.geohash: ?geohash,
      if (directionReversed != null) SpeedCamerasTable.directionReversed: directionReversed ? 1 : 0,
    });
  }

  /// Saves the local-only "camera mounted facing the other way" flag
  /// (features.md — never published to OSM, see [SpeedCamera.directionReversed]).
  Future<void> updateDirectionReversed(String id, bool reversed) {
    return _updateFields(id, {SpeedCamerasTable.directionReversed: reversed ? 1 : 0});
  }

  /// Inserts or refreshes an OSM-sourced camera, keyed by its OSM node id
  /// (`camera.id`) so re-syncing the same node updates it in place instead
  /// of duplicating it (features.md 5.2, step 1). `createdAt` is left
  /// untouched by design: an OSM node's real creation date lives in OSM's
  /// own history, not ours.
  Future<void> upsertOsmCamera(SpeedCamera camera, {String? region}) {
    return _upsertLocal(camera, dirty: true, region: region);
  }

  Future<void> _updateFields(String id, Map<String, Object?> fields) async {
    if (fields.isEmpty) return;
    final db = await _databaseFuture;
    await db.update(
      SpeedCamerasTable.name,
      {...fields, SpeedCamerasTable.updatedAt: DateTime.now().millisecondsSinceEpoch, SpeedCamerasTable.dirty: 1},
      where: '${SpeedCamerasTable.id} = ?',
      whereArgs: [id],
    );
    await SyncQueueDao(db).enqueueUpsert(SyncOpType.upsertCamera, SyncOpType.deleteCamera, id);
    LocalChangeBus.instance.notify();
  }

  Future<void> _upsertLocal(SpeedCamera camera, {required bool dirty, String? region}) async {
    final db = await _databaseFuture;
    await db.insert(
      SpeedCamerasTable.name,
      camera.toLocalRow(dirty: dirty, region: region),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
    if (dirty) {
      await SyncQueueDao(db).enqueueUpsert(SyncOpType.upsertCamera, SyncOpType.deleteCamera, camera.id);
    }
    LocalChangeBus.instance.notify();
  }

  Stream<List<SpeedCamera>> _watch(Future<List<SpeedCamera>> Function() query) {
    late StreamController<List<SpeedCamera>> controller;
    StreamSubscription<void>? busSubscription;

    Future<void> emit() async {
      final result = await query();
      if (!controller.isClosed) controller.add(result);
    }

    controller = StreamController<List<SpeedCamera>>.broadcast(
      onListen: () {
        emit();
        busSubscription = LocalChangeBus.instance.onChange.listen((_) => emit());
      },
      onCancel: () => busSubscription?.cancel(),
    );
    return controller.stream;
  }
}
