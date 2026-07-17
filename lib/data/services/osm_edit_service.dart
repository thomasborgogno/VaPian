import 'dart:convert';

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

/// Builds the OSM node-update XML payload — used to change `maxspeed`/
/// `direction` on an existing node while preserving every other tag it
/// already had (the API replaces a node's tags wholesale, it doesn't merge
/// them, so the full current tag set must be sent back).
String buildUpdateNodeXml(
  int changesetId,
  int nodeId,
  int version,
  double lat,
  double lon,
  Map<String, String> tags,
) {
  final tagsXml = StringBuffer();
  tags.forEach((key, value) {
    tagsXml.write('<tag k="${_escapeXmlAttr(key)}" v="${_escapeXmlAttr(value)}"/>');
  });
  return '<osm><node id="$nodeId" version="$version" changeset="$changesetId" '
      'lat="$lat" lon="$lon">$tagsXml</node></osm>';
}

String _escapeXmlAttr(String value) => value
    .replaceAll('&', '&amp;')
    .replaceAll('"', '&quot;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;');

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

class _OsmNode {
  _OsmNode({
    required this.version,
    required this.lat,
    required this.lon,
    required this.tags,
  });

  final int version;
  final double lat;
  final double lon;
  final Map<String, String> tags;
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

  /// Updates `maxspeed`/`direction` on an existing node — either an
  /// Overpass-imported OSM camera or one this app previously published — on
  /// **production** OpenStreetMap. All other tags on the node are read back
  /// and re-sent untouched (see [buildUpdateNodeXml]). Passing `null` for
  /// either value removes that tag rather than leaving it unchanged, so
  /// callers should pass the currently-known value when they don't intend
  /// to clear it.
  Future<void> updateNode(
    int nodeId, {
    int? limit,
    int? direction,
  }) async {
    final token = await _authService.readAccessToken();
    if (token == null) {
      throw OsmEditException('Accedi con OpenStreetMap prima di modificare.');
    }
    final headers = {
      'Authorization': 'Bearer $token',
      'Content-Type': 'text/xml',
    };

    final node = await _fetchNode(nodeId);
    final tags = Map<String, String>.from(node.tags);
    if (limit != null) {
      tags['maxspeed'] = '$limit';
    } else {
      tags.remove('maxspeed');
    }
    if (direction != null) {
      tags['direction'] = '$direction';
    } else {
      tags.remove('direction');
    }

    final changesetId = await _createChangeset(
      headers,
      comment: 'Aggiornamento dati autovelox',
    );
    try {
      await _updateNode(headers, changesetId, nodeId, node.version, node.lat, node.lon, tags);
      await _closeChangeset(headers, changesetId);
    } catch (_) {
      await _closeChangeset(headers, changesetId);
      rethrow;
    }
  }

  Future<_OsmNode> _fetchNode(int nodeId) async {
    final response = await _client.get(
      Uri.parse('${OsmConfig.apiBaseUrl}/api/0.6/node/$nodeId.json'),
    );
    if (response.statusCode != 200) {
      throw OsmEditException(
        'Lettura nodo OSM non riuscita (${response.statusCode}).',
      );
    }
    final decoded = jsonDecode(response.body) as Map<String, dynamic>;
    final elements = decoded['elements'] as List<dynamic>? ?? const [];
    if (elements.isEmpty) {
      throw OsmEditException('Nodo OSM $nodeId non trovato.');
    }
    final element = elements.first as Map<String, dynamic>;
    final tags = (element['tags'] as Map<String, dynamic>?) ?? const {};
    return _OsmNode(
      version: element['version'] as int,
      lat: (element['lat'] as num).toDouble(),
      lon: (element['lon'] as num).toDouble(),
      tags: tags.map((key, value) => MapEntry(key, value.toString())),
    );
  }

  Future<void> _updateNode(
    Map<String, String> headers,
    int changesetId,
    int nodeId,
    int version,
    double lat,
    double lon,
    Map<String, String> tags,
  ) async {
    final response = await _client.put(
      Uri.parse('${OsmConfig.apiBaseUrl}/api/0.6/node/$nodeId'),
      headers: headers,
      body: buildUpdateNodeXml(changesetId, nodeId, version, lat, lon, tags),
    );
    if (response.statusCode != 200) {
      throw OsmEditException(
        'Aggiornamento nodo OSM non riuscito (${response.statusCode}): ${response.body}',
      );
    }
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
