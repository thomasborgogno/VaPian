import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/features/map/marker_style.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('markerColorFor', () {
    test('OpenStreetMap cameras are green regardless of status', () {
      const camera = SpeedCamera(
        id: 'cam1',
        lat: 0,
        lng: 0,
        limit: 90,
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.openstreetmap,
        geohash: 'x',
      );
      expect(markerColorFor(camera), speedCameraGreen);
    });

    test('approved user reports are blue', () {
      const camera = SpeedCamera(
        id: 'cam1',
        lat: 0,
        lng: 0,
        limit: 90,
        status: SpeedCameraStatus.approved,
        source: SpeedCameraSource.user,
        geohash: 'x',
      );
      expect(markerColorFor(camera), speedCameraBlue);
    });

    test('pending user reports are orange', () {
      const camera = SpeedCamera(
        id: 'cam1',
        lat: 0,
        lng: 0,
        limit: 90,
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.user,
        geohash: 'x',
      );
      expect(markerColorFor(camera), speedCameraOrange);
    });
  });
}
