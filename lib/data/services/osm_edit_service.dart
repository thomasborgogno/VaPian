import 'package:http/http.dart' as http;

import '../../core/constants/osm_config.dart';
import '../models/speed_camera.dart';
import 'osm_auth_service.dart';

class OsmEditException implements Exception {
  OsmEditException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Builds the OSM changeset XML payload (features.md 4 tag list).
String buildChangesetXml({
  String comment = 'Aggiunta autovelox segnalato dagli utenti',
}) {
  return '<osm><changeset>'
      '<tag k="created_by" v="VaPian! - Autovelox App"/>'
      '<tag k="comment" v="$comment"/>'
      '</changeset></osm>';
}

/// Builds the OSM node-creation XML payload for [camera] under
/// [changesetId] — `maxspeed`/`direction` tags are omitted when unknown
/// rather than sending a made-up value.
String buildNodeXml(int changesetId, SpeedCamera camera) {
  final tags = StringBuffer()..write('<tag k="highway" v="speed_camera"/>');
  if (camera.limit != null)
    tags.write('<tag k="maxspeed" v="${camera.limit}"/>');
  if (camera.direction != null)
    tags.write('<tag k="direction" v="${camera.direction}"/>');

  return '<osm><node changeset="$changesetId" lat="${camera.lat}" lon="${camera.lng}">'
      '$tags'
      '</node></osm>';
}

/// Publishes a user-reported camera as a new node on **production**
/// OpenStreetMap (features.md 4) via a changeset — requires an access
/// token from [OsmAuthService].
class OsmEditService {
  OsmEditService({http.Client? client, OsmAuthService? authService})
    : _client = client ?? http.Client(),
      _authService = authService ?? OsmAuthService();

  final http.Client _client;
  final OsmAuthService _authService;

  Future<int> publishCamera(SpeedCamera camera) async {
    final token = await _authService.readAccessToken();
    if (token == null) {
      throw OsmEditException('Accedi con OpenStreetMap prima di pubblicare.');
    }
    final headers = {
      'Authorization': 'Bearer $token',
      'Content-Type': 'text/xml',
    };

    final changesetId = await _createChangeset(headers);
    try {
      final nodeId = await _createNode(headers, changesetId, camera);
      await _closeChangeset(headers, changesetId);
      return nodeId;
    } catch (_) {
      await _closeChangeset(headers, changesetId);
      rethrow;
    }
  }

  Future<int> _createChangeset(Map<String, String> headers) async {
    final response = await _client.put(
      Uri.parse('${OsmConfig.apiBaseUrl}/api/0.6/changeset/create'),
      headers: headers,
      body: buildChangesetXml(),
    );
    if (response.statusCode != 200) {
      throw OsmEditException(
        'Creazione changeset OSM non riuscita (${response.statusCode}).',
      );
    }
    return int.parse(response.body.trim());
  }

  Future<int> _createNode(
    Map<String, String> headers,
    int changesetId,
    SpeedCamera camera,
  ) async {
    final response = await _client.put(
      Uri.parse('${OsmConfig.apiBaseUrl}/api/0.6/node/create'),
      headers: headers,
      body: buildNodeXml(changesetId, camera),
    );
    if (response.statusCode != 200) {
      throw OsmEditException(
        'Creazione nodo OSM non riuscita (${response.statusCode}).',
      );
    }
    return int.parse(response.body.trim());
  }

  Future<void> _closeChangeset(Map<String, String> headers, int changesetId) {
    return _client.put(
      Uri.parse('${OsmConfig.apiBaseUrl}/api/0.6/changeset/$changesetId/close'),
      headers: headers,
    );
  }
}
