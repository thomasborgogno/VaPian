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

    test('falls back to the next endpoint when the first answers 504', () async {
      final hosts = <String>[];
      final client = MockClient((request) async {
        hosts.add(request.url.host);
        if (hosts.length == 1) return http.Response('busy', 504);
        return http.Response(
          jsonEncode({
            'elements': [
              {'type': 'node', 'id': 1, 'lat': 46.5, 'lon': 11.3, 'tags': {'maxspeed': '50'}},
            ],
          }),
          200,
        );
      });

      final cameras = await OverpassService(client: client).fetchSpeedCameras('Trentino-Alto Adige');

      expect(cameras, hasLength(1));
      expect(hosts, hasLength(2));
      expect(hosts[0], isNot(hosts[1]));
    });

    test('falls back when an endpoint answers 200 with a non-JSON body', () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        if (calls == 1) return http.Response('<html>busy</html>', 200);
        return http.Response(jsonEncode({'elements': []}), 200);
      });

      final cameras = await OverpassService(client: client).fetchSpeedCameras('Lazio');

      expect(cameras, isEmpty);
      expect(calls, 2);
    });

    test('does not retry on 400 (the query itself is bad)', () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        return http.Response('bad query', 400);
      });

      await expectLater(
        OverpassService(client: client).fetchSpeedCameras('Lazio'),
        throwsA(isA<OverpassException>()),
      );
      expect(calls, 1);
    });

    test('throws once every endpoint has failed', () async {
      var calls = 0;
      final client = MockClient((request) async {
        calls++;
        return http.Response('busy', 504);
      });

      await expectLater(
        OverpassService(client: client).fetchSpeedCameras('Lazio'),
        throwsA(isA<OverpassException>()),
      );
      expect(calls, OverpassService.endpoints.length);
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
