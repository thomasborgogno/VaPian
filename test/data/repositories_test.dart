import 'package:autovelox_app/core/utils/geo.dart';
import 'package:autovelox_app/data/local/app_database.dart';
import 'package:autovelox_app/data/local/sync_queue_dao.dart';
import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/data/models/user_settings.dart';
import 'package:autovelox_app/data/repositories/blacklist_repository.dart';
import 'package:autovelox_app/data/repositories/settings_repository.dart';
import 'package:autovelox_app/data/repositories/speed_camera_repository.dart';
import 'package:fake_cloud_firestore/fake_cloud_firestore.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite/sqflite.dart';

/// Local-first repositories read/write an isolated in-memory SQLite
/// database per test — see `test/flutter_test_config.dart` for the FFI
/// factory setup this relies on.
Future<Database> _testDb() async => (await AppDatabase.openInMemoryForTest()).db;

void main() {
  group('SpeedCameraRepository', () {
    late SpeedCameraRepository repository;

    setUp(() async {
      repository = SpeedCameraRepository(database: _testDb());
    });

    test('addCamera then watchNear returns the camera within radius', () async {
      await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9028,
          lng: 12.4964,
          limit: 90,
          status: SpeedCameraStatus.approved,
          source: SpeedCameraSource.openstreetmap,
          geohash: 'sr2yk1z',
        ),
      );

      final cameras = await repository.watchNear(41.9028, 12.4964, 5000).first;

      expect(cameras, hasLength(1));
      expect(cameras.first.limit, 90);
    });

    test('watchNear excludes cameras outside the radius', () async {
      await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 45.4642,
          lng: 9.1900,
          limit: 130,
          status: SpeedCameraStatus.approved,
          source: SpeedCameraSource.openstreetmap,
          geohash: 'u0nd9x2y',
        ),
      );

      final cameras = await repository.watchNear(41.9028, 12.4964, 5000).first;

      expect(cameras, isEmpty);
    });

    test(
        'watchNear finds a camera whose stored geohash falls in a '
        "neighboring cell, not the query center's", () async {
      const lat = 41.9028;
      const lng = 12.4964;
      const radiusMeters = 5000.0;
      final precision = geohashPrecisionForRadiusMeters(radiusMeters);
      final centerPrefix = encodeGeohash(lat, lng, precision: precision);
      final neighborPrefix = geohashNeighborPrefixes(lat, lng, radiusMeters)
          .firstWhere((prefix) => prefix != centerPrefix);

      // The camera sits right at the query center (distance 0, always within
      // radius) but its geohash belongs to a neighboring cell -- exactly the
      // case a single-cell query silently drops, since it never asks for
      // that prefix at all.
      await repository.addCamera(
        SpeedCamera(
          id: '',
          lat: lat,
          lng: lng,
          status: SpeedCameraStatus.approved,
          source: SpeedCameraSource.openstreetmap,
          geohash: neighborPrefix.padRight(9, '0'),
        ),
      );

      final cameras = await repository.watchNear(lat, lng, radiusMeters).first;

      expect(cameras, hasLength(1));
    });

    test('deleteCamera hides it from watchNear', () async {
      final id = await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9028,
          lng: 12.4964,
          limit: 50,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
          createdBy: 'uid1',
        ),
      );

      await repository.deleteCamera(id);

      final cameras = await repository.watchNear(41.9028, 12.4964, 5000).first;
      expect(cameras, isEmpty);
    });

    test('updateStatus promotes pending to approved', () async {
      final id = await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9028,
          lng: 12.4964,
          limit: 50,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
          createdBy: 'uid1',
        ),
      );

      await repository.updateStatus(id, SpeedCameraStatus.approved);

      final cameras = await repository.watchPendingOwn('uid1').first;
      expect(cameras, isEmpty);
    });

    test('watchPendingOwn returns only pending user cameras for the given uid', () async {
      await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9028,
          lng: 12.4964,
          limit: 50,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
          createdBy: 'uid1',
        ),
      );
      await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9028,
          lng: 12.4964,
          limit: 90,
          status: SpeedCameraStatus.approved,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
          createdBy: 'uid1',
        ),
      );
      await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9028,
          lng: 12.4964,
          limit: 70,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
          createdBy: 'uid2',
        ),
      );

      final cameras = await repository.watchPendingOwn('uid1').first;

      expect(cameras, hasLength(1));
      expect(cameras.first.limit, 50);
    });

    test('upsertOsmCamera re-syncing the same node updates it in place', () async {
      const first = SpeedCamera(
        id: 'osm_123',
        lat: 45.4642,
        lng: 9.1900,
        limit: 50,
        status: SpeedCameraStatus.approved,
        source: SpeedCameraSource.openstreetmap,
        geohash: 'u0nd9x2y',
      );
      await repository.upsertOsmCamera(first);
      await repository.upsertOsmCamera(first.copyWith(limit: 70));

      final camera = await repository.getById('osm_123');
      expect(camera, isNotNull);
      expect(camera!.limit, 70);
    });

    test('updateDirectionReversed flips only that field, for any source', () async {
      const camera = SpeedCamera(
        id: 'osm_9',
        lat: 45.0,
        lng: 9.0,
        direction: 90,
        status: SpeedCameraStatus.approved,
        source: SpeedCameraSource.openstreetmap,
        geohash: 'x',
      );
      await repository.upsertOsmCamera(camera);

      await repository.updateDirectionReversed('osm_9', true);

      final updated = await repository.getById('osm_9');
      expect(updated, isNotNull);
      expect(updated!.directionReversed, isTrue);
      expect(updated.direction, 90);
    });

    test('addCamera then updateStatus coalesce into a single pending sync op', () async {
      final dbFuture = _testDb();
      final localRepository = SpeedCameraRepository(database: dbFuture);

      final id = await localRepository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9,
          lng: 12.5,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
          createdBy: 'uid1',
        ),
      );
      await localRepository.updateStatus(id, SpeedCameraStatus.approved);

      final pending = await SyncQueueDao(await dbFuture).pending();
      expect(pending, hasLength(1));
      expect(pending.single.targetId, id);
    });
  });

  group('SettingsRepository', () {
    test('updateSettings then watchSettings reflects the change', () async {
      final firestore = FakeFirebaseFirestore();
      final repository = SettingsRepository(firestore: firestore);

      await repository.updateSettings(
        'uid1',
        const UserSettings(alertDistanceMeters: 500, toleranceFilterEnabled: false),
      );

      final settings = await repository.watchSettings('uid1').first;

      expect(settings.alertDistanceMeters, 500);
      expect(settings.toleranceFilterEnabled, isFalse);
    });

    test('watchSettings returns defaults when no doc exists', () async {
      final repository = SettingsRepository(firestore: FakeFirebaseFirestore());

      final settings = await repository.watchSettings('missing-uid').first;

      expect(settings.alertDistanceMeters, 300);
    });

    test('recordSync updates the global sync metadata', () async {
      final firestore = FakeFirebaseFirestore();
      final repository = SettingsRepository(firestore: firestore);

      await repository.recordSync(camerasSynced: 42);

      final metadata = await repository.watchGlobalSyncMetadata().first;
      expect(metadata.camerasSynced, 42);
      expect(metadata.isStale(), isFalse);
    });
  });

  group('BlacklistRepository', () {
    late BlacklistRepository repository;

    setUp(() async {
      repository = BlacklistRepository(database: _testDb());
    });

    test('ignoreCamera then watchIgnoredCameras includes it', () async {
      await repository.ignoreCamera('uid1', 'cam1');
      final ignored = await repository.watchIgnoredCameras('uid1').first;

      expect(ignored.map((c) => c.cameraId), contains('cam1'));
    });

    test('unignoreCamera removes it again', () async {
      await repository.ignoreCamera('uid1', 'cam1');
      await repository.unignoreCamera('uid1', 'cam1');
      final ignored = await repository.watchIgnoredCameras('uid1').first;

      expect(ignored, isEmpty);
    });

    test('queueIgnore then watchQueuedIgnores includes it', () async {
      await repository.queueIgnore('uid1', 'cam1');
      final queued = await repository.watchQueuedIgnores('uid1').first;

      expect(queued.map((q) => q.cameraId), contains('cam1'));
    });

    test('confirmQueuedIgnore blacklists it and clears the queue', () async {
      await repository.queueIgnore('uid1', 'cam1');
      await repository.confirmQueuedIgnore('uid1', 'cam1');

      final ignored = await repository.watchIgnoredCameras('uid1').first;
      final queued = await repository.watchQueuedIgnores('uid1').first;
      expect(ignored.map((c) => c.cameraId), contains('cam1'));
      expect(queued, isEmpty);
    });

    test('dismissQueuedIgnore clears the queue without blacklisting', () async {
      await repository.queueIgnore('uid1', 'cam1');
      await repository.dismissQueuedIgnore('uid1', 'cam1');

      final ignored = await repository.watchIgnoredCameras('uid1').first;
      final queued = await repository.watchQueuedIgnores('uid1').first;
      expect(ignored, isEmpty);
      expect(queued, isEmpty);
    });
  });
}
