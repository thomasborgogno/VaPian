import 'package:cloud_firestore/cloud_firestore.dart';

import '../models/ignored_camera.dart';
import '../services/firestore_paths.dart';

/// Per-user camera blacklist ("Ignora Velox di OSM", features.md 2.1),
/// stored as a Firestore subcollection instead of a local table — it still
/// gets offline caching for free from the Firestore SDK on both the phone
/// and the Android Auto native side.
class BlacklistRepository {
  BlacklistRepository({FirebaseFirestore? firestore})
      : _firestore = firestore ?? FirebaseFirestore.instance;

  final FirebaseFirestore _firestore;

  CollectionReference<Map<String, dynamic>> _collection(String uid) =>
      _firestore
          .collection(FirestorePaths.users)
          .doc(uid)
          .collection(FirestorePaths.ignoredCameras);

  Stream<List<IgnoredCamera>> watchIgnoredCameras(String uid) {
    return _collection(uid)
        .snapshots()
        .map((snapshot) => snapshot.docs.map(IgnoredCamera.fromFirestore).toList());
  }

  Future<void> ignoreCamera(String uid, String cameraId) {
    return _collection(uid)
        .doc(cameraId)
        .set(IgnoredCamera(cameraId: cameraId, ignoredAt: DateTime.now()).toMap());
  }

  Future<void> unignoreCamera(String uid, String cameraId) {
    return _collection(uid).doc(cameraId).delete();
  }
}
