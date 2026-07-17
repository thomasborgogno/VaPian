import 'package:cloud_firestore/cloud_firestore.dart';

import '../models/ignored_camera.dart';
import '../models/queued_ignore.dart';
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

  CollectionReference<Map<String, dynamic>> _queuedIgnoresCollection(String uid) =>
      _firestore
          .collection(FirestorePaths.users)
          .doc(uid)
          .collection(FirestorePaths.queuedIgnores);

  /// Queues an "Ignora" tapped while driving (Android Auto, or the phone's
  /// own navigation mode) for later phone-side confirmation, instead of
  /// blacklisting it immediately (features.md 3.3.1) — surfaces in
  /// [watchQueuedIgnores]/`PendingReviewSheet` until [confirmQueuedIgnore] or
  /// [dismissQueuedIgnore] resolves it.
  Future<void> queueIgnore(String uid, String cameraId) {
    return _queuedIgnoresCollection(uid)
        .doc(cameraId)
        .set(QueuedIgnore(cameraId: cameraId, queuedAt: DateTime.now()).toMap());
  }

  /// Cameras tapped "Ignora" from Android Auto, awaiting phone-side
  /// confirmation (features.md 3.3.1).
  Stream<List<QueuedIgnore>> watchQueuedIgnores(String uid) {
    return _queuedIgnoresCollection(uid)
        .snapshots()
        .map((snapshot) => snapshot.docs.map(QueuedIgnore.fromFirestore).toList());
  }

  /// User confirmed the queued ignore on the phone: promote it to a
  /// permanent blacklist entry and drop it from the queue.
  Future<void> confirmQueuedIgnore(String uid, String cameraId) async {
    await ignoreCamera(uid, cameraId);
    await _queuedIgnoresCollection(uid).doc(cameraId).delete();
  }

  /// User dismissed the queued ignore without blacklisting it.
  Future<void> dismissQueuedIgnore(String uid, String cameraId) {
    return _queuedIgnoresCollection(uid).doc(cameraId).delete();
  }
}
