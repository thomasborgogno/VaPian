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
}
