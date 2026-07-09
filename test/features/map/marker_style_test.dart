import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/features/map/marker_style.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

void main() {
  group('markerHueFor', () {
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
      expect(markerHueFor(camera), BitmapDescriptor.hueGreen);
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
      expect(markerHueFor(camera), BitmapDescriptor.hueBlue);
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
      expect(markerHueFor(camera), BitmapDescriptor.hueOrange);
    });
  });
}
