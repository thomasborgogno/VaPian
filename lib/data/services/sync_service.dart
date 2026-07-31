import 'dart:async';
import 'dart:developer' as developer;

import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:sqflite/sqflite.dart';

import '../../core/utils/geo.dart';
import '../local/app_database.dart';
import '../local/local_change_bus.dart';
import '../local/local_row_mappers.dart';
import '../local/sync_queue_dao.dart';
import '../local/sync_queue_op.dart';
import '../models/speed_camera.dart';
import 'firestore_paths.dart';

/// The only thing in the app that talks to Firestore for speed cameras and
/// the per-user blacklist/queue: local-first repositories only ever touch
/// SQLite (see `SpeedCameraRepository`/`BlacklistRepository`) and record
/// what changed in `sync_queue`; this service replays that queue against
/// Firestore once connectivity returns, and pulls remote changes back into
/// the local store on demand (see [pullNear], [primeRegion]).
class SyncService {
  SyncService({FirebaseFirestore? firestore, Future<Database>? database})
      : _firestore = firestore ?? FirebaseFirestore.instance,
        _databaseFuture = database ?? sharedDatabase();

  final FirebaseFirestore _firestore;
  final Future<Database> _databaseFuture;
  StreamSubscription<List<ConnectivityResult>>? _connectivitySubscription;
  bool _draining = false;

  CollectionReference<Map<String, dynamic>> get _camerasCollection =>
      _firestore.collection(FirestorePaths.speedCameras);

  /// Starts watching connectivity and drains the queue on every transition
  /// to "online" (plus once immediately, in case it's already online).
  void start() {
    _connectivitySubscription = Connectivity().onConnectivityChanged.listen((results) {
      if (!results.contains(ConnectivityResult.none)) {
        drainQueue();
      }
    });
    drainQueue();
  }

  void dispose() {
    _connectivitySubscription?.cancel();
  }

  /// Replays every pending `sync_queue` row against Firestore, oldest
  /// first. A failed op is left in the queue (with its attempt count
  /// bumped) so it's retried on the next drain instead of being lost —
  /// e.g. this app came back online but this specific write still 404s.
  /// Re-entrant calls (e.g. a connectivity tick firing while a previous
  /// drain is still running) are ignored rather than queued.
  Future<void> drainQueue() async {
    if (_draining) return;
    _draining = true;
    try {
      final db = await _databaseFuture;
      final dao = SyncQueueDao(db);
      for (final op in await dao.pending()) {
        try {
          await _replay(db, op);
          await dao.remove(op.rowId);
        } catch (error, stackTrace) {
          await dao.markAttempted(op.rowId);
          developer.log(
            'sync_queue replay failed for ${op.type.name}/${op.targetId}',
            error: error,
            stackTrace: stackTrace,
            name: 'SyncService',
          );
        }
      }
    } finally {
      _draining = false;
    }
  }

  Future<void> _replay(Database db, SyncQueueOp op) async {
    switch (op.type) {
      case SyncOpType.upsertCamera:
        final rows = await db.query(
          SpeedCamerasTable.name,
          where: '${SpeedCamerasTable.id} = ?',
          whereArgs: [op.targetId],
        );
        if (rows.isEmpty) return;
        final camera = speedCameraFromLocalRow(rows.single);
        await _camerasCollection.doc(camera.id).set(camera.toMap(), SetOptions(merge: true));
      case SyncOpType.deleteCamera:
        await _camerasCollection.doc(op.targetId).delete();
      case SyncOpType.upsertIgnoredCamera:
        final uid = op.payload['uid'] as String;
        final cameraId = op.payload['cameraId'] as String;
        final rows = await db.query(
          IgnoredCamerasTable.name,
          where: '${IgnoredCamerasTable.uid} = ? AND ${IgnoredCamerasTable.cameraId} = ?',
          whereArgs: [uid, cameraId],
        );
        if (rows.isEmpty) return;
        final ignored = ignoredCameraFromLocalRow(rows.single);
        await _ignoredCollection(uid).doc(cameraId).set(ignored.toMap());
      case SyncOpType.deleteIgnoredCamera:
        final uid = op.payload['uid'] as String;
        final cameraId = op.payload['cameraId'] as String;
        await _ignoredCollection(uid).doc(cameraId).delete();
      case SyncOpType.queueIgnore:
        final uid = op.payload['uid'] as String;
        final cameraId = op.payload['cameraId'] as String;
        final rows = await db.query(
          QueuedIgnoresTable.name,
          where: '${QueuedIgnoresTable.uid} = ? AND ${QueuedIgnoresTable.cameraId} = ?',
          whereArgs: [uid, cameraId],
        );
        if (rows.isEmpty) return;
        final queued = queuedIgnoreFromLocalRow(rows.single);
        await _queuedIgnoresCollection(uid).doc(cameraId).set(queued.toMap());
      case SyncOpType.dismissQueuedIgnore:
        final uid = op.payload['uid'] as String;
        final cameraId = op.payload['cameraId'] as String;
        await _queuedIgnoresCollection(uid).doc(cameraId).delete();
    }
  }

  CollectionReference<Map<String, dynamic>> _ignoredCollection(String uid) => _firestore
      .collection(FirestorePaths.users)
      .doc(uid)
      .collection(FirestorePaths.ignoredCameras);

  CollectionReference<Map<String, dynamic>> _queuedIgnoresCollection(String uid) => _firestore
      .collection(FirestorePaths.users)
      .doc(uid)
      .collection(FirestorePaths.queuedIgnores);

  /// One-shot refresh of the local store with whatever is currently on
  /// Firestore around ([lat], [lng]) — the remote-pull half of keeping the
  /// map's visible area fresh when online (the local-first repositories
  /// only ever read what's already cached locally). Safe to call
  /// repeatedly (e.g. on viewport change or reconnect); local rows with
  /// unsynced edits ([SpeedCamerasTable.dirty] = 1) are left alone so an
  /// in-flight offline edit is never clobbered by a stale remote read.
  Future<void> pullNear(double lat, double lng, double radiusMeters, {String? region}) {
    return _pullByPrefixes(geohashNeighborPrefixes(lat, lng, radiusMeters), region: region);
  }

  Future<void> _pullByPrefixes(List<String> prefixes, {String? region}) async {
    final db = await _databaseFuture;
    for (final prefix in prefixes) {
      final snapshot = await _camerasCollection
          .where('geohash', isGreaterThanOrEqualTo: prefix)
          .where('geohash', isLessThanOrEqualTo: '$prefix')
          .get();
      final batch = db.batch();
      for (final doc in snapshot.docs) {
        final existing = await db.query(
          SpeedCamerasTable.name,
          where: '${SpeedCamerasTable.id} = ? AND ${SpeedCamerasTable.dirty} = 1',
          whereArgs: [doc.id],
        );
        if (existing.isNotEmpty) continue;
        final camera = SpeedCamera.fromFirestore(doc);
        batch.insert(
          SpeedCamerasTable.name,
          camera.toLocalRow(region: region),
          conflictAlgorithm: ConflictAlgorithm.replace,
        );
      }
      await batch.commit(noResult: true);
    }
    LocalChangeBus.instance.notify();
  }
}
