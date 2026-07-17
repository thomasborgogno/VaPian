import 'package:cloud_firestore/cloud_firestore.dart';

import '../../core/utils/geo.dart';
import '../models/speed_camera.dart';
import '../services/firestore_paths.dart';

class SpeedCameraRepository {
  SpeedCameraRepository({FirebaseFirestore? firestore})
      : _firestore = firestore ?? FirebaseFirestore.instance;

  final FirebaseFirestore _firestore;

  CollectionReference<Map<String, dynamic>> get _collection =>
      _firestore.collection(FirestorePaths.speedCameras);

  /// Cameras within [radiusMeters] of ([lat], [lng]), regardless of
  /// `status`/`source` — callers filter further as needed (e.g. hide
  /// `pending` on the driving path).
  Stream<List<SpeedCamera>> watchNear(
    double lat,
    double lng,
    double radiusMeters,
  ) {
    final bounds = geohashQueryBounds(lat, lng, radiusMeters);
    return _collection
        .where('geohash', isGreaterThanOrEqualTo: bounds.start)
        .where('geohash', isLessThanOrEqualTo: bounds.end)
        .snapshots()
        .map((snapshot) => snapshot.docs
            .map(SpeedCamera.fromFirestore)
            .where((camera) =>
                haversineDistanceMeters(lat, lng, camera.lat, camera.lng) <=
                radiusMeters)
            .toList());
  }

  Future<String> addCamera(SpeedCamera camera) async {
    final now = DateTime.now();
    final data = camera
        .copyWith(createdAt: camera.createdAt ?? now, updatedAt: now)
        .toMap();
    final doc = await _collection.add(data);
    return doc.id;
  }

  Future<void> deleteCamera(String id) => _collection.doc(id).delete();

  Future<void> updateStatus(String id, SpeedCameraStatus status) {
    return _collection.doc(id).update({
      'status': status.name,
      'updatedAt': Timestamp.fromDate(DateTime.now()),
    });
  }

  /// Records that this report was just published as a real node on
  /// production OpenStreetMap (features.md 4) — also promotes it to
  /// `approved`, since a published report has by definition been reviewed.
  Future<void> markPublishedToOsm(String id, int nodeId, {int version = 1}) {
    return _collection.doc(id).update({
      'osmNodeId': nodeId,
      'osmNodeVersion': version,
      'status': SpeedCameraStatus.approved.name,
      'updatedAt': Timestamp.fromDate(DateTime.now()),
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
    return _collection.doc(id).update({
      'lat': ?lat,
      'lng': ?lng,
      'limit': ?limit,
      'direction': ?direction,
      'geohash': ?geohash,
      'directionReversed': ?directionReversed,
      'updatedAt': Timestamp.fromDate(DateTime.now()),
    });
  }

  /// Saves the local-only "camera mounted facing the other way" flag
  /// (features.md — never published to OSM, see [SpeedCamera.directionReversed])
  /// on its own — used by the read-only tier of the edit sheet, where this
  /// toggle is the only thing the current user is allowed to save.
  Future<void> updateDirectionReversed(String id, bool reversed) {
    return _collection.doc(id).update({
      'directionReversed': reversed,
      'updatedAt': Timestamp.fromDate(DateTime.now()),
    });
  }

  /// All pending `source:'user'` cameras created by [uid] — shown in the
  /// review banner and sheet so the user can promote them to `approved`.
  /// Requires a composite index: createdBy + status + source (Firestore will
  /// prompt to create it on first real-device run if missing).
  Stream<List<SpeedCamera>> watchPendingOwn(String uid) {
    return _collection
        .where('createdBy', isEqualTo: uid)
        .where('status', isEqualTo: SpeedCameraStatus.pending.name)
        .where('source', isEqualTo: SpeedCameraSource.user.name)
        .snapshots()
        .map((s) => s.docs.map(SpeedCamera.fromFirestore).toList());
  }

  /// Inserts or refreshes an OSM-sourced camera, keyed by its OSM node id
  /// (`camera.id`) so re-syncing the same node updates it in place instead
  /// of duplicating it (features.md 5.2, step 1). `createdAt` is left
  /// untouched by design: an OSM node's real creation date lives in OSM's
  /// own history, not ours.
  Future<void> upsertOsmCamera(SpeedCamera camera) {
    final data = camera.toMap()..['updatedAt'] = Timestamp.fromDate(DateTime.now());
    return _collection.doc(camera.id).set(data, SetOptions(merge: true));
  }
}
