import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;

import '../../core/utils/geo.dart';
import '../models/speed_camera.dart';

class OverpassException implements Exception {
  OverpassException(this.message);

  final String message;

  @override
  String toString() => message;
}

/// Internal signal: this endpoint failed in a way worth retrying elsewhere.
class _RetryableOverpassFailure implements Exception {
  _RetryableOverpassFailure(this.reason);

  final String reason;
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

  /// Public Overpass instances, tried in order: when one is overloaded
  /// (504/429 "server is probably too busy"), unreachable or times out, the
  /// next is tried instead of failing the whole sync. Every instance serves
  /// the same global OSM data, so the result is equivalent.
  static const List<String> endpoints = [
    'https://overpass-api.de/api/interpreter',
    'https://overpass.private.coffee/api/interpreter',
    'https://overpass.kumi.systems/api/interpreter',
  ];

  /// Overpass's public instance returns 406 for generic HTTP-library
  /// User-Agents (including Dart's own default) — it expects a descriptive
  /// one identifying the app, per Overpass API usage policy.
  static const String _userAgent = 'AutoveloxApp/1.0';

  Future<List<SpeedCamera>> fetchSpeedCameras(String regionName) async {
    // Matched on `name:it`, not the plain `name` tag: OSM's `name` on two of
    // the 20 regions is the official bilingual form ("Trentino-Alto
    // Adige/Südtirol", "Valle d'Aosta / Vallée d'Aoste"), which never equals
    // the plain Italian name in `italianRegions` and silently returns zero
    // results. `name:it` is the plain Italian name for all 20.
    final query = '''
[out:json][timeout:60];
area["name:it"="$regionName"]["admin_level"="4"]["boundary"="administrative"]->.searchArea;
(
  node["highway"="speed_camera"](area.searchArea);
  node["enforcement"="maxspeed"](area.searchArea);
);
out body;
''';

    final failures = <String>[];
    for (final endpoint in endpoints) {
      try {
        return await _fetchFrom(endpoint, query);
      } on _RetryableOverpassFailure catch (failure) {
        failures.add('${Uri.parse(endpoint).host}: ${failure.reason}');
      }
    }
    throw OverpassException(
      'Nessun server Overpass ha risposto per $regionName (${failures.join('; ')}). '
      'Riprova tra qualche minuto.',
    );
  }

  /// Runs [query] against one [endpoint]. Throws [_RetryableOverpassFailure]
  /// for anything another instance might not suffer from (overload, timeout,
  /// network error, non-JSON/`runtime error` body) and [OverpassException]
  /// for a failure that would repeat everywhere (e.g. 400 = bad query).
  Future<List<SpeedCamera>> _fetchFrom(String endpoint, String query) async {
    final http.Response response;
    try {
      response = await _client
          .post(
            Uri.parse(endpoint),
            headers: {'User-Agent': _userAgent},
            body: {'data': query},
          )
          // The query itself asks Overpass for [timeout:60]; this client-side
          // bound is only there so a hung connection (no response at all,
          // e.g. flaky mobile data) fails over to the next endpoint instead
          // of leaving the sync button spinning forever.
          .timeout(const Duration(seconds: 90));
    } on TimeoutException {
      throw _RetryableOverpassFailure('nessuna risposta dopo 90 secondi');
    } on http.ClientException catch (e) {
      throw _RetryableOverpassFailure('errore di rete (${e.message})');
    } on SocketException catch (e) {
      throw _RetryableOverpassFailure('errore di rete (${e.message})');
    }

    if (response.statusCode == 400) {
      throw OverpassException('Overpass ha rifiutato la query (400)');
    }
    if (response.statusCode != 200) {
      // 504/429 = busy or rate-limited: another instance may well answer.
      throw _RetryableOverpassFailure('HTTP ${response.statusCode}');
    }

    final Map<String, dynamic> decoded;
    try {
      decoded = jsonDecode(response.body) as Map<String, dynamic>;
    } on FormatException {
      // An overloaded instance can answer 200 with an HTML error page.
      throw _RetryableOverpassFailure('risposta non valida');
    }
    final remark = decoded['remark'] as String?;
    if (remark != null && remark.contains('runtime error')) {
      throw _RetryableOverpassFailure(remark);
    }
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
