import 'package:autovelox_app/core/utils/proximity_calculator.dart';
import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:flutter_test/flutter_test.dart';

SpeedCamera _camera({required double lat, required double lng, int? direction}) {
  return SpeedCamera(
    id: 'x',
    lat: lat,
    lng: lng,
    status: SpeedCameraStatus.approved,
    source: SpeedCameraSource.openstreetmap,
    geohash: 'x',
    direction: direction,
  );
}

void main() {
  group('angularDifferenceDeg', () {
    test('is zero for identical bearings', () {
      expect(angularDifferenceDeg(10, 10), 0);
    });

    test('takes the shortest way around the circle', () {
      expect(angularDifferenceDeg(350, 10), closeTo(20, 0.001));
      expect(angularDifferenceDeg(10, 350), closeTo(20, 0.001));
    });

    test('caps at 180 for opposite bearings', () {
      expect(angularDifferenceDeg(0, 180), closeTo(180, 0.001));
    });
  });

  group('isAheadInTravelDirection', () {
    // Driver at the equator heading due north (0°); camera due north of them.
    final camera = _camera(lat: 1.0, lng: 0.0);

    test('true when camera is within the ahead tolerance, no direction tag', () {
      expect(
        isAheadInTravelDirection(userLat: 0, userLng: 0, headingDeg: 0, camera: camera),
        isTrue,
      );
    });

    test('false when camera is behind the driver', () {
      expect(
        isAheadInTravelDirection(userLat: 0, userLng: 0, headingDeg: 180, camera: camera),
        isFalse,
      );
    });

    test('false when camera is outside the 45-degree ahead cone', () {
      expect(
        isAheadInTravelDirection(userLat: 0, userLng: 0, headingDeg: 90, camera: camera),
        isFalse,
      );
    });

    test('true when camera direction tag roughly matches driver heading (same carriageway)', () {
      final sameWay = _camera(lat: 1.0, lng: 0.0, direction: 0);
      expect(
        isAheadInTravelDirection(userLat: 0, userLng: 0, headingDeg: 0, camera: sameWay),
        isTrue,
      );
    });

    test('false when camera direction tag is opposite the driver heading (other carriageway)', () {
      final oncoming = _camera(lat: 1.0, lng: 0.0, direction: 180);
      expect(
        isAheadInTravelDirection(userLat: 0, userLng: 0, headingDeg: 0, camera: oncoming),
        isFalse,
      );
    });
  });
}
