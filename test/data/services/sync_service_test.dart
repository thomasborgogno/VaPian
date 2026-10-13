import 'package:autovelox_app/data/local/app_database.dart';
import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/data/repositories/blacklist_repository.dart';
import 'package:autovelox_app/data/repositories/speed_camera_repository.dart';
import 'package:autovelox_app/data/services/sync_service.dart';
import 'package:fake_cloud_firestore/fake_cloud_firestore.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:sqflite/sqflite.dart';

Future<Database> _testDb() async => (await AppDatabase.openInMemoryForTest()).db;

void main() {
  group('SyncService.drainQueue', () {
    test('pushes a locally-added camera to Firestore', () async {
      final firestore = FakeFirebaseFirestore();
      final dbFuture = _testDb();
      final repository = SpeedCameraRepository(database: dbFuture);
      final syncService = SyncService(firestore: firestore, database: dbFuture);

      final id = await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9,
          lng: 12.5,
          limit: 50,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
          createdBy: 'uid1',
        ),
      );

      await syncService.drainQueue();

      final doc = await firestore.collection('speed_cameras').doc(id).get();
      expect(doc.exists, isTrue);
      expect(doc.data()!['limit'], 50);
    });

    test('pushes a camera deletion to Firestore', () async {
      final firestore = FakeFirebaseFirestore();
      final dbFuture = _testDb();
      final repository = SpeedCameraRepository(database: dbFuture);
      final syncService = SyncService(firestore: firestore, database: dbFuture);

      final id = await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9,
          lng: 12.5,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
        ),
      );
      await syncService.drainQueue();
      await repository.deleteCamera(id);
      await syncService.drainQueue();

      final doc = await firestore.collection('speed_cameras').doc(id).get();
      expect(doc.exists, isFalse);
    });

    test('coalesced edits only push the final state once', () async {
      final firestore = FakeFirebaseFirestore();
      final dbFuture = _testDb();
      final repository = SpeedCameraRepository(database: dbFuture);
      final syncService = SyncService(firestore: firestore, database: dbFuture);

      final id = await repository.addCamera(
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
      await repository.updateStatus(id, SpeedCameraStatus.approved);

      await syncService.drainQueue();

      final doc = await firestore.collection('speed_cameras').doc(id).get();
      expect(doc.data()!['status'], 'approved');
    });

    test('pushes an ignored camera to the per-user Firestore subcollection', () async {
      final firestore = FakeFirebaseFirestore();
      final dbFuture = _testDb();
      final repository = BlacklistRepository(database: dbFuture);
      final syncService = SyncService(firestore: firestore, database: dbFuture);

      await repository.ignoreCamera('uid1', 'cam1');
      await syncService.drainQueue();

      final doc =
          await firestore.collection('users').doc('uid1').collection('ignored_cameras').doc('cam1').get();
      expect(doc.exists, isTrue);
    });
  });

  group('SyncService.start', () {
    test('drains automatically after a local write, with no connectivity change', () async {
      // connectivity_plus sets up a platform EventChannel as soon as
      // start() listens to it — needs the test binding initialized, unlike
      // the other groups here which never touch connectivity_plus.
      TestWidgetsFlutterBinding.ensureInitialized();
      final firestore = FakeFirebaseFirestore();
      final dbFuture = _testDb();
      final repository = SpeedCameraRepository(database: dbFuture);
      final syncService = SyncService(firestore: firestore, database: dbFuture);
      syncService.start();
      addTearDown(syncService.dispose);

      final id = await repository.addCamera(
        const SpeedCamera(
          id: '',
          lat: 41.9,
          lng: 12.5,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: 'sr2yk1z',
        ),
      );

      // addCamera's own LocalChangeBus tick (fired synchronously by the
      // repository write) is what should trigger the drain here — no
      // explicit drainQueue()/connectivity event, matching a delete/edit
      // made while the app was already online.
      await Future<void>.delayed(const Duration(milliseconds: 20));

      final doc = await firestore.collection('speed_cameras').doc(id).get();
      expect(doc.exists, isTrue);
    });
  });

  group('SyncService.pullNear', () {
    test('merges a remote camera into the local store without touching dirty rows', () async {
      final firestore = FakeFirebaseFirestore();
      final dbFuture = _testDb();
      final repository = SpeedCameraRepository(database: dbFuture);
      final syncService = SyncService(firestore: firestore, database: dbFuture);

      await firestore.collection('speed_cameras').doc('remote1').set(
        const SpeedCamera(
          id: 'remote1',
          lat: 41.9028,
          lng: 12.4964,
          limit: 90,
          status: SpeedCameraStatus.approved,
          source: SpeedCameraSource.openstreetmap,
          geohash: 'sr2yk1z',
        ).toMap(),
      );

      await syncService.pullNear(41.9028, 12.4964, 5000);

      final cameras = await repository.watchNear(41.9028, 12.4964, 5000).first;
      expect(cameras.map((c) => c.id), contains('remote1'));
    });

    test('does not overwrite a local row with unsynced edits', () async {
      final firestore = FakeFirebaseFirestore();
      final dbFuture = _testDb();
      final repository = SpeedCameraRepository(database: dbFuture);
      final syncService = SyncService(firestore: firestore, database: dbFuture);

      const camera = SpeedCamera(
        id: 'shared1',
        lat: 41.9028,
        lng: 12.4964,
        limit: 50,
        status: SpeedCameraStatus.approved,
        source: SpeedCameraSource.openstreetmap,
        geohash: 'sr2yk1z',
      );
      await repository.upsertOsmCamera(camera); // dirty locally, not yet drained
      await firestore.collection('speed_cameras').doc('shared1').set(camera.copyWith(limit: 130).toMap());

      await syncService.pullNear(41.9028, 12.4964, 5000);

      final local = await repository.getById('shared1');
      expect(local!.limit, 50);
    });
  });
}
