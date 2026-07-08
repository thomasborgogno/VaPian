import 'package:cloud_firestore/cloud_firestore.dart';

import '../models/sync_metadata.dart';
import '../models/user_settings.dart';
import '../services/firestore_paths.dart';

class SettingsRepository {
  SettingsRepository({FirebaseFirestore? firestore})
      : _firestore = firestore ?? FirebaseFirestore.instance;

  final FirebaseFirestore _firestore;

  DocumentReference<Map<String, dynamic>> _userDoc(String uid) =>
      _firestore.collection(FirestorePaths.users).doc(uid);

  Stream<UserSettings> watchSettings(String uid) {
    return _userDoc(uid).snapshots().map((doc) {
      final settingsMap = doc.data()?['settings'];
      if (settingsMap == null) return const UserSettings();
      return UserSettings.fromMap(Map<String, dynamic>.from(settingsMap as Map));
    });
  }

  Future<void> updateSettings(String uid, UserSettings settings) {
    return _userDoc(uid).set(
      {'settings': settings.toMap()},
      SetOptions(merge: true),
    );
  }

  DocumentReference<Map<String, dynamic>> get _globalSyncDoc => _firestore
      .collection(FirestorePaths.syncMetadata)
      .doc(FirestorePaths.globalSyncDoc);

  Stream<SyncMetadata> watchGlobalSyncMetadata() {
    return _globalSyncDoc.snapshots().map((doc) {
      final data = doc.data();
      return data == null ? const SyncMetadata() : SyncMetadata.fromMap(data);
    });
  }

  Future<void> recordSync({required int camerasSynced}) {
    return _globalSyncDoc.set(
      SyncMetadata(lastSyncAt: DateTime.now(), camerasSynced: camerasSynced).toMap(),
    );
  }
}
