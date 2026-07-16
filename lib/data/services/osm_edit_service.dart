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
/// `createdBy` is the signed-in OpenStreetMap username, not the app name —
/// see [OsmAuthService.fetchUsername].
String buildChangesetXml({
  required String createdBy,
  String comment = 'Aggiunta autovelox segnalato dagli utenti',
}) {
  return '<osm><changeset>'
      '<tag k="created_by" v="$createdBy"/>'
      '<tag k="comment" v="$comment"/>'
      '</changeset></osm>';
}

/// Builds the OSM node-creation XML payload for [camera] under
/// [changesetId] — `maxspeed`/`direction` tags are omitted when unknown
/// rather than sending a made-up value.
String buildNodeXml(int changesetId, SpeedCamera camera) {
  final tags = StringBuffer()..write('<tag k="highway" v="speed_camera"/>');
  if (camera.limit != null) {
    tags.write('<tag k="maxspeed" v="${camera.limit}"/>');
  }
  if (camera.direction != null) {
    tags.write('<tag k="direction" v="${camera.direction}"/>');
  }

  return '<osm><node changeset="$changesetId" lat="${camera.lat}" lon="${camera.lng}">'
      '$tags'
      '</node></osm>';
}

/// Builds the OSM node-deletion XML payload. The API requires the node's
/// current version to detect edit conflicts; despite the delete operation
/// not changing them, production OpenStreetMap's XML parser also rejects a
/// `<node>` element without `lat`/`lon` (undocumented on the wiki), so they
/// must be included too.
String buildDeleteNodeXml(
  int changesetId,
  int nodeId,
  int version,
  double lat,
  double lon,
) {
  return '<osm><node id="$nodeId" version="$version" changeset="$changesetId" '
      'lat="$lat" lon="$lon"/></osm>';
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

  Future<int> _createChangeset(
    Map<String, String> headers, {
    String comment = 'Aggiunta autovelox segnalato dagli utenti',
  }) async {
    final username = await _authService.fetchUsername();
    final response = await _client.put(
      Uri.parse('${OsmConfig.apiBaseUrl}/api/0.6/changeset/create'),
      headers: headers,
      body: buildChangesetXml(
        createdBy: username ?? 'VaPian! - Autovelox App',
        comment: comment,
      ),
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

  /// Deletes a node this app previously published (features.md 4) from
  /// **production** OpenStreetMap. `lat`/`lng` are the node's last known
  /// coordinates — required by the delete XML payload (see
  /// [buildDeleteNodeXml]).
  Future<void> deleteNode(
    int nodeId, {
    required double lat,
    required double lng,
    int version = 1,
  }) async {
    final token = await _authService.readAccessToken();
    if (token == null) {
      throw OsmEditException('Accedi con OpenStreetMap prima di eliminare.');
    }
    final headers = {
      'Authorization': 'Bearer $token',
      'Content-Type': 'text/xml',
    };

    final changesetId = await _createChangeset(
      headers,
      comment: 'Rimozione autovelox segnalato dagli utenti',
    );
    try {
      await _deleteNode(headers, changesetId, nodeId, version, lat, lng);
      await _closeChangeset(headers, changesetId);
    } catch (_) {
      await _closeChangeset(headers, changesetId);
      rethrow;
    }
  }

  Future<void> _deleteNode(
    Map<String, String> headers,
    int changesetId,
    int nodeId,
    int version,
    double lat,
    double lng,
  ) async {
    final response = await _client.delete(
      Uri.parse('${OsmConfig.apiBaseUrl}/api/0.6/node/$nodeId'),
      headers: headers,
      body: buildDeleteNodeXml(changesetId, nodeId, version, lat, lng),
    );
    if (response.statusCode != 200) {
      throw OsmEditException(
        'Eliminazione nodo OSM non riuscita (${response.statusCode}): ${response.body}',
      );
    }
  }
}
