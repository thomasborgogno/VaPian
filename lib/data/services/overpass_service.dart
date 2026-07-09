import 'dart:convert';

import 'package:http/http.dart' as http;

import '../../core/utils/geo.dart';
import '../models/speed_camera.dart';

class OverpassException implements Exception {
  OverpassException(this.message);

  final String message;

  @override
  String toString() => message;
}

const Map<String, int> _compassDirections = {
  'N': 0,
  'NNE': 23,
  'NE': 45,
  'ENE': 68,
  'E': 90,
  'ESE': 113,
  'SE': 135,
  'SSE': 158,
  'S': 180,
  'SSW': 203,
  'SW': 225,
  'WSW': 248,
  'W': 270,
  'WNW': 293,
  'NW': 315,
  'NNW': 338,
};

/// OSM `maxspeed` is normally a plain km/h number for Italian roads (e.g.
/// `"50"`); this also tolerates a trailing unit like `"50 mph"` by reading
/// just the leading digits. Returns null if missing/unparseable — the
/// camera is still saved, just without a known limit.
int? parseMaxSpeedKmh(String? raw) {
  if (raw == null) return null;
  final match = RegExp(r'\d+').firstMatch(raw);
  if (match == null) return null;
  return int.tryParse(match.group(0)!);
}

/// OSM `direction` is either a numeric bearing or a compass abbreviation
/// (`N`, `NE`, ...).
int? parseDirectionDegrees(String? raw) {
  if (raw == null) return null;
  final trimmed = raw.trim();
  final numeric = int.tryParse(trimmed);
  if (numeric != null) return ((numeric % 360) + 360) % 360;
  return _compassDirections[trimmed.toUpperCase()];
}

/// Downloads `highway=speed_camera` / `enforcement=maxspeed` nodes from the
/// Overpass API, scoped to one Italian region (features.md 2.2) — querying
/// the whole country at once risks Overpass request timeouts.
class OverpassService {
  OverpassService({http.Client? client}) : _client = client ?? http.Client();

  final http.Client _client;

  static const String _endpoint = 'https://overpass-api.de/api/interpreter';

  /// Overpass's public instance returns 406 for generic HTTP-library
  /// User-Agents (including Dart's own default) — it expects a descriptive
  /// one identifying the app, per Overpass API usage policy.
  static const String _userAgent = 'AutoveloxApp/1.0';

  Future<List<SpeedCamera>> fetchSpeedCameras(String regionName) async {
    final query = '''
[out:json][timeout:60];
area["name"="$regionName"]["admin_level"="4"]["boundary"="administrative"]->.searchArea;
(
  node["highway"="speed_camera"](area.searchArea);
  node["enforcement"="maxspeed"](area.searchArea);
);
out body;
''';

    final response = await _client.post(
      Uri.parse(_endpoint),
      headers: {'User-Agent': _userAgent},
      body: {'data': query},
    );
    if (response.statusCode != 200) {
      throw OverpassException('Overpass ha risposto ${response.statusCode}');
    }

    final decoded = jsonDecode(response.body) as Map<String, dynamic>;
    final elements = decoded['elements'] as List<dynamic>? ?? const [];

    final cameras = <SpeedCamera>[];
    for (final element in elements) {
      final node = element as Map<String, dynamic>;
      final tags = (node['tags'] as Map<String, dynamic>?) ?? const {};
      // No fallback if maxspeed is missing/unparseable: the camera is still
      // saved (features.md doesn't require a known limit to plot a node),
      // just without a limit rather than a made-up one.
      final limit = parseMaxSpeedKmh(tags['maxspeed'] as String?);

      final lat = (node['lat'] as num).toDouble();
      final lng = (node['lon'] as num).toDouble();
      cameras.add(
        SpeedCamera(
          id: 'osm_${node['id']}',
          lat: lat,
          lng: lng,
          limit: limit,
          direction: parseDirectionDegrees(tags['direction'] as String?),
          status: SpeedCameraStatus.approved,
          source: SpeedCameraSource.openstreetmap,
          geohash: encodeGeohash(lat, lng, precision: 9),
        ),
      );
    }
    return cameras;
  }
}
