import 'dart:convert';

import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/data/services/overpass_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

void main() {
  group('parseMaxSpeedKmh', () {
    test('parses a plain number', () {
      expect(parseMaxSpeedKmh('50'), 50);
    });

    test('extracts leading digits when a unit is appended', () {
      expect(parseMaxSpeedKmh('50 mph'), 50);
    });

    test('returns null for null or unparseable input', () {
      expect(parseMaxSpeedKmh(null), isNull);
      expect(parseMaxSpeedKmh('unknown'), isNull);
    });
  });

  group('parseDirectionDegrees', () {
    test('parses a numeric bearing', () {
      expect(parseDirectionDegrees('180'), 180);
    });

    test('parses compass abbreviations case-insensitively', () {
      expect(parseDirectionDegrees('se'), 135);
      expect(parseDirectionDegrees('N'), 0);
    });

    test('normalizes negative/over-360 bearings', () {
      expect(parseDirectionDegrees('-10'), 350);
      expect(parseDirectionDegrees('370'), 10);
    });

    test('returns null for null or unrecognized input', () {
      expect(parseDirectionDegrees(null), isNull);
      expect(parseDirectionDegrees('sideways'), isNull);
    });
  });

  group('OverpassService.fetchSpeedCameras', () {
    test('sends a non-generic User-Agent (Overpass 406s the Dart default)', () async {
      String? sentUserAgent;
      final client = MockClient((request) async {
        sentUserAgent = request.headers['user-agent'];
        return http.Response(jsonEncode({'elements': []}), 200);
      });

      await OverpassService(client: client).fetchSpeedCameras('Lombardia');

      expect(sentUserAgent, isNotNull);
      expect(sentUserAgent, isNot(contains('Dart')));
    });

    test('parses a node with a known maxspeed', () async {
      final client = MockClient((request) async {
        return http.Response(
          jsonEncode({
            'elements': [
              {
                'type': 'node',
                'id': 123,
                'lat': 45.4642,
                'lon': 9.1900,
                'tags': {'highway': 'speed_camera', 'maxspeed': '50', 'direction': '180'},
              },
            ],
          }),
          200,
        );
      });

      final cameras = await OverpassService(client: client).fetchSpeedCameras('Lombardia');

      expect(cameras, hasLength(1));
      final camera = cameras.single;
      expect(camera.id, 'osm_123');
      expect(camera.limit, 50);
      expect(camera.direction, 180);
      expect(camera.source, SpeedCameraSource.openstreetmap);
      expect(camera.status, SpeedCameraStatus.approved);
      expect(camera.geohash, isNotEmpty);
    });

    test('still saves a node with no maxspeed tag, just without a limit', () async {
      final client = MockClient((request) async {
        return http.Response(
          jsonEncode({
            'elements': [
              {
                'type': 'node',
                'id': 456,
                'lat': 41.9028,
                'lon': 12.4964,
                'tags': {'highway': 'speed_camera'},
              },
            ],
          }),
          200,
        );
      });

      final cameras = await OverpassService(client: client).fetchSpeedCameras('Lazio');

      expect(cameras, hasLength(1));
      expect(cameras.single.limit, isNull);
    });

    test('throws OverpassException on a non-200 response', () async {
      final client = MockClient((request) async => http.Response('error', 500));

      expect(
        () => OverpassService(client: client).fetchSpeedCameras('Lombardia'),
        throwsA(isA<OverpassException>()),
      );
    });
  });
}
