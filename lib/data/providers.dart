import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'models/speed_camera.dart';
import 'models/sync_metadata.dart';
import 'models/user_settings.dart';
import 'repositories/blacklist_repository.dart';
import 'repositories/settings_repository.dart';
import 'repositories/speed_camera_repository.dart';
import 'services/auth_service.dart';

final firestoreProvider = Provider<FirebaseFirestore>((ref) {
  return FirebaseFirestore.instance;
});

final authServiceProvider = Provider<AuthService>((ref) => AuthService());

final authStateProvider = StreamProvider<User?>((ref) {
  return ref.watch(authServiceProvider).authStateChanges;
});

final speedCameraRepositoryProvider = Provider<SpeedCameraRepository>((ref) {
  return SpeedCameraRepository(firestore: ref.watch(firestoreProvider));
});

final settingsRepositoryProvider = Provider<SettingsRepository>((ref) {
  return SettingsRepository(firestore: ref.watch(firestoreProvider));
});

final blacklistRepositoryProvider = Provider<BlacklistRepository>((ref) {
  return BlacklistRepository(firestore: ref.watch(firestoreProvider));
});

final userSettingsProvider = StreamProvider<UserSettings>((ref) {
  final user = ref.watch(authStateProvider).value;
  if (user == null) return Stream.value(const UserSettings());
  return ref.watch(settingsRepositoryProvider).watchSettings(user.uid);
});

final syncMetadataProvider = StreamProvider<SyncMetadata>((ref) {
  return ref.watch(settingsRepositoryProvider).watchGlobalSyncMetadata();
});

/// IDs of cameras the current user has chosen to hide (features.md 2.1
/// "Ignora Velox di OSM").  Returns an empty set when not signed in.
final ignoredCameraIdsProvider = StreamProvider<Set<String>>((ref) {
  final uid = ref.watch(authStateProvider).value?.uid;
  if (uid == null) return Stream.value(const {});
  return ref
      .watch(blacklistRepositoryProvider)
      .watchIgnoredCameras(uid)
      .map((list) => list.map((e) => e.cameraId).toSet());
});

/// Pending user-sourced cameras owned by the current user — used by the
/// review banner and sheet (features.md 2.1 "Banner di Conferma Android
/// Auto").  Returns an empty list when not signed in.
final pendingOwnCamerasProvider = StreamProvider<List<SpeedCamera>>((ref) {
  final uid = ref.watch(authStateProvider).value?.uid;
  if (uid == null) return Stream.value(const []);
  return ref.watch(speedCameraRepositoryProvider).watchPendingOwn(uid);
});
