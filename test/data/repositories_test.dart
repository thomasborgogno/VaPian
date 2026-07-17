import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/data/models/user_settings.dart';
import 'package:autovelox_app/data/repositories/blacklist_repository.dart';
import 'package:autovelox_app/data/repositories/settings_repository.dart';
import 'package:autovelox_app/data/repositories/speed_camera_repository.dart';
import 'package:fake_cloud_firestore/fake_cloud_firestore.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('SpeedCameraRepository', () {
    late FakeFirebaseFirestore firestore;
    late SpeedCameraRepository repository;

    setUp(() {
      firestore = FakeFirebaseFirestore();
      repository = SpeedCameraRepository(firestore: firestore);
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

    test('deleteCamera removes the document', () async {
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

      final doc = await firestore.collection('speed_cameras').doc(id).get();
      expect(doc.exists, isFalse);
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

      final doc = await firestore.collection('speed_cameras').doc(id).get();
      expect(doc.data()!['status'], 'approved');
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

      final snapshot = await firestore.collection('speed_cameras').get();
      expect(snapshot.docs, hasLength(1));
      expect(snapshot.docs.single.data()['limit'], 70);
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

      final snapshot = await firestore.collection('speed_cameras').doc('osm_9').get();
      expect(snapshot.data()!['directionReversed'], isTrue);
      expect(snapshot.data()!['direction'], 90);
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
    test('ignoreCamera then watchIgnoredCameras includes it', () async {
      final repository = BlacklistRepository(firestore: FakeFirebaseFirestore());

      await repository.ignoreCamera('uid1', 'cam1');
      final ignored = await repository.watchIgnoredCameras('uid1').first;

      expect(ignored.map((c) => c.cameraId), contains('cam1'));
    });

    test('unignoreCamera removes it again', () async {
      final repository = BlacklistRepository(firestore: FakeFirebaseFirestore());

      await repository.ignoreCamera('uid1', 'cam1');
      await repository.unignoreCamera('uid1', 'cam1');
      final ignored = await repository.watchIgnoredCameras('uid1').first;

      expect(ignored, isEmpty);
    });

    test('queueIgnore then watchQueuedIgnores includes it', () async {
      final repository = BlacklistRepository(firestore: FakeFirebaseFirestore());

      await repository.queueIgnore('uid1', 'cam1');
      final queued = await repository.watchQueuedIgnores('uid1').first;

      expect(queued.map((q) => q.cameraId), contains('cam1'));
    });

    test('confirmQueuedIgnore blacklists it and clears the queue', () async {
      final repository = BlacklistRepository(firestore: FakeFirebaseFirestore());

      await repository.queueIgnore('uid1', 'cam1');
      await repository.confirmQueuedIgnore('uid1', 'cam1');

      final ignored = await repository.watchIgnoredCameras('uid1').first;
      final queued = await repository.watchQueuedIgnores('uid1').first;
      expect(ignored.map((c) => c.cameraId), contains('cam1'));
      expect(queued, isEmpty);
    });

    test('dismissQueuedIgnore clears the queue without blacklisting', () async {
      final repository = BlacklistRepository(firestore: FakeFirebaseFirestore());

      await repository.queueIgnore('uid1', 'cam1');
      await repository.dismissQueuedIgnore('uid1', 'cam1');

      final ignored = await repository.watchIgnoredCameras('uid1').first;
      final queued = await repository.watchQueuedIgnores('uid1').first;
      expect(ignored, isEmpty);
      expect(queued, isEmpty);
    });
  });
}
