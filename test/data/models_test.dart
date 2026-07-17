import 'package:autovelox_app/data/models/ignored_camera.dart';
import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/data/models/sync_metadata.dart';
import 'package:autovelox_app/data/models/user_settings.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('SpeedCamera', () {
    test('round-trips through toMap/fromMap', () {
      final camera = SpeedCamera(
        id: 'cam1',
        lat: 41.9028,
        lng: 12.4964,
        limit: 90,
        direction: 180,
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.user,
        geohash: 'sr2yk',
        createdBy: 'uid123',
      );

      final restored = SpeedCamera.fromMap('cam1', camera.toMap());

      expect(restored.id, camera.id);
      expect(restored.lat, camera.lat);
      expect(restored.lng, camera.lng);
      expect(restored.limit, camera.limit);
      expect(restored.direction, camera.direction);
      expect(restored.status, SpeedCameraStatus.pending);
      expect(restored.source, SpeedCameraSource.user);
      expect(restored.geohash, camera.geohash);
      expect(restored.createdBy, camera.createdBy);
    });

    test('copyWith overrides only the given fields', () {
      const camera = SpeedCamera(
        id: 'cam1',
        lat: 41.9028,
        lng: 12.4964,
        limit: 90,
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.openstreetmap,
        geohash: 'sr2yk',
      );

      final approved = camera.copyWith(status: SpeedCameraStatus.approved);

      expect(approved.status, SpeedCameraStatus.approved);
      expect(approved.limit, camera.limit);
      expect(approved.source, camera.source);
    });

    test('round-trips with a null limit (OSM node without maxspeed)', () {
      const camera = SpeedCamera(
        id: 'osm_1',
        lat: 41.9028,
        lng: 12.4964,
        status: SpeedCameraStatus.approved,
        source: SpeedCameraSource.openstreetmap,
        geohash: 'sr2yk',
      );

      final map = camera.toMap();
      expect(map.containsKey('limit'), isFalse);

      final restored = SpeedCamera.fromMap('osm_1', map);
      expect(restored.limit, isNull);
    });
  });

  group('UserSettings', () {
    test('defaults match features.md 2.2', () {
      const settings = UserSettings();
      expect(settings.alertDistanceMode, AlertDistanceMode.fixed);
      expect(settings.alertDistanceMeters, 300);
      expect(settings.toleranceFilterEnabled, isTrue);
    });

    test('round-trips the dynamic mode through its string value', () {
      const settings = UserSettings(
        alertDistanceMode: AlertDistanceMode.dynamicDistance,
        alertDistanceMeters: 500,
        toleranceFilterEnabled: false,
      );

      final map = settings.toMap();
      expect(map['alertDistanceMode'], 'dynamic');

      final restored = UserSettings.fromMap(map);
      expect(restored.alertDistanceMode, AlertDistanceMode.dynamicDistance);
      expect(restored.alertDistanceMeters, 500);
      expect(restored.toleranceFilterEnabled, isFalse);
    });

    test('round-trips the selected OSM sync regions', () {
      const settings = UserSettings(osmSyncRegions: ['Lombardia', 'Lazio']);

      final restored = UserSettings.fromMap(settings.toMap());

      expect(restored.osmSyncRegions, ['Lombardia', 'Lazio']);
    });

    test('defaults to no OSM sync regions selected', () {
      const settings = UserSettings();
      expect(settings.osmSyncRegions, isEmpty);
    });
  });

  group('IgnoredCamera', () {
    test('round-trips through toMap/fromMap', () {
      final ignored = IgnoredCamera(
        cameraId: 'cam1',
        ignoredAt: DateTime(2026, 1, 1),
      );
      final restored = IgnoredCamera.fromMap('cam1', ignored.toMap());
      expect(restored.cameraId, 'cam1');
      expect(restored.ignoredAt, isNotNull);
    });
  });

  group('SyncMetadata', () {
    test('is stale when never synced', () {
      const metadata = SyncMetadata();
      expect(metadata.isStale(), isTrue);
    });

    test('is not stale right after syncing', () {
      final metadata = SyncMetadata(lastSyncAt: DateTime.now());
      expect(metadata.isStale(), isFalse);
    });

    test('is stale after the 15-day smart reminder window', () {
      final metadata = SyncMetadata(
        lastSyncAt: DateTime.now().subtract(const Duration(days: 16)),
      );
      expect(metadata.isStale(), isTrue);
    });
  });
}
