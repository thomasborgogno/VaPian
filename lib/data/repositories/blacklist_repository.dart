import 'dart:async';

import 'package:sqflite/sqflite.dart';

import '../local/app_database.dart';
import '../local/local_change_bus.dart';
import '../local/local_row_mappers.dart';
import '../local/sync_queue_dao.dart';
import '../local/sync_queue_op.dart';
import '../models/ignored_camera.dart';
import '../models/queued_ignore.dart';

/// Local-first per-user camera blacklist ("Ignora Velox di OSM",
/// features.md 2.1) and the Android-Auto "queued ignore" review queue
/// (features.md 3.3.1) — both mirrored in SQLite (`ignored_cameras`,
/// `queued_ignores`, see `app_database.dart`) so they read/write instantly
/// offline; [SyncService] pushes changes to Firestore when online.
class BlacklistRepository {
  BlacklistRepository({Future<Database>? database}) : _databaseFuture = database ?? sharedDatabase();

  final Future<Database> _databaseFuture;

  Stream<List<IgnoredCamera>> watchIgnoredCameras(String uid) {
    Future<List<IgnoredCamera>> query() async {
      final db = await _databaseFuture;
      final rows = await db.query(
        IgnoredCamerasTable.name,
        where: '${IgnoredCamerasTable.uid} = ? AND ${IgnoredCamerasTable.deleted} = 0',
        whereArgs: [uid],
      );
      return rows.map(ignoredCameraFromLocalRow).toList();
    }

    return _watch(query);
  }

  Future<void> ignoreCamera(String uid, String cameraId) async {
    final db = await _databaseFuture;
    final camera = IgnoredCamera(cameraId: cameraId, ignoredAt: DateTime.now());
    await db.insert(
      IgnoredCamerasTable.name,
      camera.toLocalRow(uid, dirty: true),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
    await SyncQueueDao(db).enqueueUpsert(
      SyncOpType.upsertIgnoredCamera,
      SyncOpType.deleteIgnoredCamera,
      _targetId(uid, cameraId),
      payload: {'uid': uid, 'cameraId': cameraId},
    );
    LocalChangeBus.instance.notify();
  }

  Future<void> unignoreCamera(String uid, String cameraId) async {
    final db = await _databaseFuture;
    await db.update(
      IgnoredCamerasTable.name,
      {IgnoredCamerasTable.deleted: 1, IgnoredCamerasTable.dirty: 1},
      where: '${IgnoredCamerasTable.uid} = ? AND ${IgnoredCamerasTable.cameraId} = ?',
      whereArgs: [uid, cameraId],
    );
    await SyncQueueDao(db).enqueueDelete(
      SyncOpType.upsertIgnoredCamera,
      SyncOpType.deleteIgnoredCamera,
      _targetId(uid, cameraId),
      payload: {'uid': uid, 'cameraId': cameraId},
    );
    LocalChangeBus.instance.notify();
  }

  /// Queues an "Ignora" tapped while driving (Android Auto, or the phone's
  /// own navigation mode) for later phone-side confirmation, instead of
  /// blacklisting it immediately (features.md 3.3.1) — surfaces in
  /// [watchQueuedIgnores]/`PendingReviewSheet` until [confirmQueuedIgnore] or
  /// [dismissQueuedIgnore] resolves it.
  Future<void> queueIgnore(String uid, String cameraId) async {
    final db = await _databaseFuture;
    final queued = QueuedIgnore(cameraId: cameraId, queuedAt: DateTime.now());
    await db.insert(
      QueuedIgnoresTable.name,
      queued.toLocalRow(uid, dirty: true),
      conflictAlgorithm: ConflictAlgorithm.replace,
    );
    await SyncQueueDao(db).enqueue(
      SyncOpType.queueIgnore,
      _targetId(uid, cameraId),
      payload: {'uid': uid, 'cameraId': cameraId},
    );
    LocalChangeBus.instance.notify();
  }

  /// Cameras tapped "Ignora" from Android Auto, awaiting phone-side
  /// confirmation (features.md 3.3.1).
  Stream<List<QueuedIgnore>> watchQueuedIgnores(String uid) {
    Future<List<QueuedIgnore>> query() async {
      final db = await _databaseFuture;
      final rows = await db.query(
        QueuedIgnoresTable.name,
        where: '${QueuedIgnoresTable.uid} = ? AND ${QueuedIgnoresTable.deleted} = 0',
        whereArgs: [uid],
      );
      return rows.map(queuedIgnoreFromLocalRow).toList();
    }

    return _watch(query);
  }

  /// User confirmed the queued ignore on the phone: promote it to a
  /// permanent blacklist entry and drop it from the queue.
  Future<void> confirmQueuedIgnore(String uid, String cameraId) async {
    await ignoreCamera(uid, cameraId);
    await _removeQueuedIgnore(uid, cameraId);
  }

  /// User dismissed the queued ignore without blacklisting it.
  Future<void> dismissQueuedIgnore(String uid, String cameraId) => _removeQueuedIgnore(uid, cameraId);

  Future<void> _removeQueuedIgnore(String uid, String cameraId) async {
    final db = await _databaseFuture;
    await db.delete(
      QueuedIgnoresTable.name,
      where: '${QueuedIgnoresTable.uid} = ? AND ${QueuedIgnoresTable.cameraId} = ?',
      whereArgs: [uid, cameraId],
    );
    // Also removes the queue doc on Firestore, in case it was already
    // pushed there — otherwise a future SyncService pull would resurrect it
    // locally after the user already resolved it on the phone.
    await SyncQueueDao(db).enqueue(
      SyncOpType.dismissQueuedIgnore,
      _targetId(uid, cameraId),
      payload: {'uid': uid, 'cameraId': cameraId},
    );
    LocalChangeBus.instance.notify();
  }

  String _targetId(String uid, String cameraId) => '$uid/$cameraId';

  Stream<List<T>> _watch<T>(Future<List<T>> Function() query) {
    late StreamController<List<T>> controller;
    StreamSubscription<void>? busSubscription;

    Future<void> emit() async {
      final result = await query();
      if (!controller.isClosed) controller.add(result);
    }

    controller = StreamController<List<T>>.broadcast(
      onListen: () {
        emit();
        busSubscription = LocalChangeBus.instance.onChange.listen((_) => emit());
      },
      onCancel: () => busSubscription?.cancel(),
    );
    return controller.stream;
  }
}
