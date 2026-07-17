import 'package:autovelox_app/data/models/speed_camera.dart';
import 'package:autovelox_app/data/services/osm_auth_service.dart';
import 'package:autovelox_app/data/services/osm_edit_service.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';

class _FakeOsmAuthService extends OsmAuthService {
  _FakeOsmAuthService(this._token, {String? username = 'testuser'})
      : _username = username;

  final String? _token;
  final String? _username;

  @override
  Future<String?> readAccessToken() async => _token;

  @override
  Future<String?> fetchUsername() async => _username;
}

void main() {
  group('buildChangesetXml', () {
    test('includes the created_by and comment tags', () {
      final xml = buildChangesetXml(createdBy: 'testuser', comment: 'test comment');
      expect(xml, contains('k="created_by" v="testuser"'));
      expect(xml, contains('k="comment" v="test comment"'));
    });
  });

  group('buildNodeXml', () {
    test('includes lat/lng, the changeset id, and known tags', () {
      const camera = SpeedCamera(
        id: '',
        lat: 45.4642,
        lng: 9.1900,
        limit: 50,
        direction: 180,
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.user,
        geohash: 'x',
      );
      final xml = buildNodeXml(42, camera);
      expect(xml, contains('changeset="42"'));
      expect(xml, contains('lat="45.4642"'));
      expect(xml, contains('lon="9.19"'));
      expect(xml, contains('k="maxspeed" v="50"'));
      expect(xml, contains('k="direction" v="180"'));
    });

    test('omits maxspeed/direction tags when unknown', () {
      const camera = SpeedCamera(
        id: '',
        lat: 45.4642,
        lng: 9.1900,
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.user,
        geohash: 'x',
      );
      final xml = buildNodeXml(1, camera);
      expect(xml, isNot(contains('maxspeed')));
      expect(xml, isNot(contains('k="direction"')));
    });
  });

  group('OsmEditService.publishCamera', () {
    const camera = SpeedCamera(
      id: '',
      lat: 45.0,
      lng: 9.0,
      limit: 50,
      status: SpeedCameraStatus.pending,
      source: SpeedCameraSource.user,
      geohash: 'x',
    );

    test('creates a changeset, creates the node, then closes the changeset', () async {
      final calls = <String>[];
      final client = MockClient((request) async {
        calls.add('${request.method} ${request.url.path}');
        if (request.url.path.endsWith('/changeset/create')) {
          return http.Response('101', 200);
        }
        if (request.url.path.endsWith('/node/create')) {
          return http.Response('202', 200);
        }
        return http.Response('', 200);
      });

      final service = OsmEditService(client: client, authService: _FakeOsmAuthService('token'));
      final nodeId = await service.publishCamera(camera);

      expect(nodeId, 202);
      expect(calls, [
        'PUT /api/0.6/changeset/create',
        'PUT /api/0.6/node/create',
        'PUT /api/0.6/changeset/101/close',
      ]);
    });

    test('still closes the changeset if node creation fails', () async {
      final calls = <String>[];
      final client = MockClient((request) async {
        calls.add('${request.method} ${request.url.path}');
        if (request.url.path.endsWith('/changeset/create')) {
          return http.Response('101', 200);
        }
        if (request.url.path.endsWith('/node/create')) {
          return http.Response('server error', 500);
        }
        return http.Response('', 200);
      });

      final service = OsmEditService(client: client, authService: _FakeOsmAuthService('token'));

      await expectLater(service.publishCamera(camera), throwsA(isA<OsmEditException>()));
      expect(calls, contains('PUT /api/0.6/changeset/101/close'));
    });

    test('throws without a linked OpenStreetMap account', () {
      final service = OsmEditService(
        client: MockClient((request) async => http.Response('', 200)),
        authService: _FakeOsmAuthService(null),
      );

      expect(service.publishCamera(camera), throwsA(isA<OsmEditException>()));
    });
  });

  group('buildUpdateNodeXml', () {
    test('includes id, version, changeset, lat/lon, and all given tags', () {
      final xml = buildUpdateNodeXml(42, 99, 3, 45.4642, 9.19, {
        'highway': 'speed_camera',
        'maxspeed': '70',
        'direction': '90',
      });
      expect(xml, contains('id="99"'));
      expect(xml, contains('version="3"'));
      expect(xml, contains('changeset="42"'));
      expect(xml, contains('lat="45.4642"'));
      expect(xml, contains('lon="9.19"'));
      expect(xml, contains('k="highway" v="speed_camera"'));
      expect(xml, contains('k="maxspeed" v="70"'));
      expect(xml, contains('k="direction" v="90"'));
    });

    test('escapes special XML characters in tag values', () {
      final xml = buildUpdateNodeXml(1, 1, 1, 0, 0, {'note': 'a & b <c> "d"'});
      expect(xml, contains('v="a &amp; b &lt;c&gt; &quot;d&quot;"'));
    });
  });

  group('OsmEditService.updateNode', () {
    test('reads the current node, updates only the given tags, then closes the changeset', () async {
      final calls = <String>[];
      String? putBody;
      final client = MockClient((request) async {
        calls.add('${request.method} ${request.url.path}');
        if (request.url.path.endsWith('/node/99.json')) {
          return http.Response(
            '{"elements":[{"type":"node","id":99,"lat":45.0,"lon":9.0,'
            '"version":3,"tags":{"highway":"speed_camera","note":"kept"}}]}',
            200,
          );
        }
        if (request.url.path.endsWith('/changeset/create')) {
          return http.Response('101', 200);
        }
        if (request.url.path == '/api/0.6/node/99' && request.method == 'PUT') {
          putBody = request.body;
          return http.Response('4', 200);
        }
        return http.Response('', 200);
      });

      final service = OsmEditService(client: client, authService: _FakeOsmAuthService('token'));
      await service.updateNode(99, limit: 70, direction: 180);

      expect(calls, [
        'GET /api/0.6/node/99.json',
        'PUT /api/0.6/changeset/create',
        'PUT /api/0.6/node/99',
        'PUT /api/0.6/changeset/101/close',
      ]);
      expect(putBody, contains('version="3"'));
      expect(putBody, contains('k="note" v="kept"'));
      expect(putBody, contains('k="highway" v="speed_camera"'));
      expect(putBody, contains('k="maxspeed" v="70"'));
      expect(putBody, contains('k="direction" v="180"'));
    });

    test('removes a tag when its value is null', () async {
      String? putBody;
      final client = MockClient((request) async {
        if (request.url.path.endsWith('/node/5.json')) {
          return http.Response(
            '{"elements":[{"id":5,"lat":0,"lon":0,"version":1,'
            '"tags":{"maxspeed":"50","direction":"90"}}]}',
            200,
          );
        }
        if (request.url.path.endsWith('/changeset/create')) {
          return http.Response('1', 200);
        }
        if (request.url.path == '/api/0.6/node/5' && request.method == 'PUT') {
          putBody = request.body;
          return http.Response('2', 200);
        }
        return http.Response('', 200);
      });

      final service = OsmEditService(client: client, authService: _FakeOsmAuthService('token'));
      await service.updateNode(5, limit: 50, direction: null);

      expect(putBody, isNot(contains('direction')));
      expect(putBody, contains('k="maxspeed" v="50"'));
    });

    test('throws without a linked OpenStreetMap account', () {
      final service = OsmEditService(
        client: MockClient((request) async => http.Response('', 200)),
        authService: _FakeOsmAuthService(null),
      );

      expect(service.updateNode(1, limit: 50), throwsA(isA<OsmEditException>()));
    });
  });
}
