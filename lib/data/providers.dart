import 'package:cloud_firestore/cloud_firestore.dart';
import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'local/app_database.dart';
import 'local/local_change_bus.dart';
import 'local/sync_queue_dao.dart';
import 'models/queued_ignore.dart';
import 'models/speed_camera.dart';
import 'models/sync_metadata.dart';
import 'models/user_settings.dart';
import 'repositories/blacklist_repository.dart';
import 'repositories/navigation_prefs_repository.dart';
import 'repositories/settings_repository.dart';
import 'repositories/speed_camera_repository.dart';
import 'services/auth_service.dart';
import 'services/offline_tile_service.dart';
import 'services/sync_service.dart';

final firestoreProvider = Provider<FirebaseFirestore>((ref) {
  return FirebaseFirestore.instance;
});

final authServiceProvider = Provider<AuthService>((ref) => AuthService());

/// Drains locally-queued writes to Firestore and pulls remote changes back
/// into the local store — see `SyncService`. Started the first time
/// anything reads this provider (`App` watches it once at the root) and
/// kept alive for the app's lifetime.
final offlineTileServiceProvider = Provider<OfflineTileService>((ref) => OfflineTileService());

final syncServiceProvider = Provider<SyncService>((ref) {
  final service = SyncService();
  service.start();
  ref.onDispose(service.dispose);
  return service;
});

final authStateProvider = StreamProvider<User?>((ref) {
  return ref.watch(authServiceProvider).authStateChanges;
});

final speedCameraRepositoryProvider = Provider<SpeedCameraRepository>((ref) {
  return SpeedCameraRepository();
});

final settingsRepositoryProvider = Provider<SettingsRepository>((ref) {
  return SettingsRepository(firestore: ref.watch(firestoreProvider));
});

final blacklistRepositoryProvider = Provider<BlacklistRepository>((ref) {
  return BlacklistRepository();
});

final navigationPrefsRepositoryProvider = Provider<NavigationPrefsRepository>((ref) {
  return NavigationPrefsRepository();
});

final userSettingsProvider = StreamProvider<UserSettings>((ref) {
  final user = ref.watch(authStateProvider).value;
  if (user == null) return Stream.value(const UserSettings());
  return ref.watch(settingsRepositoryProvider).watchSettings(user.uid);
});

final syncMetadataProvider = StreamProvider<SyncMetadata>((ref) {
  return ref.watch(settingsRepositoryProvider).watchGlobalSyncMetadata();
});

/// Number of local writes (new/edited/deleted cameras, ignore/unignore,
/// queued Android Auto ignores) not yet confirmed pushed to Firestore —
/// surfaced as a small "in attesa di sincronizzazione" indicator so a user
/// who edited something offline knows it hasn't reached the server yet.
/// Re-queries on every [LocalChangeBus] tick, same pattern as the
/// local-first repositories' own `watch*` streams.
final pendingSyncCountProvider = StreamProvider<int>((ref) async* {
  final dao = SyncQueueDao(await sharedDatabase());
  Future<int> query() => dao.pendingCount();
  yield await query();
  yield* LocalChangeBus.instance.onChange.asyncMap((_) => query());
});

/// Regions the user selected for offline use ([UserSettings.offlineMapRegions])
/// that actually have their `.mbtiles` downloaded locally — the subset
/// `OfflineVectorTileLayer`/`MapScreen` treat as "render from local vector
/// tiles instead of the online raster fallback". Selecting a region only
/// queues it; `OfflineMapsSection` invalidates this provider once the
/// download actually completes.
final downloadedRegionsProvider = FutureProvider<Set<String>>((ref) async {
  final regions = ref.watch(userSettingsProvider).value?.offlineMapRegions ?? const [];
  final tileService = ref.watch(offlineTileServiceProvider);
  final downloaded = <String>{};
  for (final region in regions) {
    if (await tileService.isDownloaded(region)) downloaded.add(region);
  }
  return downloaded;
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

/// Cameras tapped "Ignora" from Android Auto, awaiting phone-side
/// confirmation before becoming a permanent blacklist entry (features.md
/// 3.3.1). Returns an empty list when not signed in.
final queuedIgnoresProvider = StreamProvider<List<QueuedIgnore>>((ref) {
  final uid = ref.watch(authStateProvider).value?.uid;
  if (uid == null) return Stream.value(const []);
  return ref.watch(blacklistRepositoryProvider).watchQueuedIgnores(uid);
});
