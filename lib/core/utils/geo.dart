import 'dart:math';

const String _geohashBase32 = '0123456789bcdefghjkmnpqrstuvwxyz';

double _degToRad(double deg) => deg * pi / 180.0;

double _radToDeg(double rad) => rad * 180.0 / pi;

const double _earthRadiusMeters = 6371000.0;

double haversineDistanceMeters(
  double lat1,
  double lng1,
  double lat2,
  double lng2,
) {
  final dLat = _degToRad(lat2 - lat1);
  final dLng = _degToRad(lng2 - lng1);
  final a = sin(dLat / 2) * sin(dLat / 2) +
      cos(_degToRad(lat1)) *
          cos(_degToRad(lat2)) *
          sin(dLng / 2) *
          sin(dLng / 2);
  final c = 2 * atan2(sqrt(a), sqrt(1 - a));
  return _earthRadiusMeters * c;
}

double bearingDegrees(double lat1, double lng1, double lat2, double lng2) {
  final phi1 = _degToRad(lat1);
  final phi2 = _degToRad(lat2);
  final deltaLambda = _degToRad(lng2 - lng1);
  final y = sin(deltaLambda) * cos(phi2);
  final x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda);
  final theta = atan2(y, x);
  return (_radToDeg(theta) + 360) % 360;
}

/// Point reached by travelling [distanceMeters] from ([latitude], [longitude])
/// along [bearingDeg] (0 = north, 90 = east). Used to place the direction
/// "handle" a fixed distance from a speed camera marker.
({double lat, double lng}) destinationPoint(
  double latitude,
  double longitude,
  double bearingDeg,
  double distanceMeters,
) {
  final bearingRad = _degToRad(bearingDeg);
  final latRad = _degToRad(latitude);
  final lngRad = _degToRad(longitude);
  final angularDistance = distanceMeters / _earthRadiusMeters;

  final destLatRad = asin(
    sin(latRad) * cos(angularDistance) +
        cos(latRad) * sin(angularDistance) * cos(bearingRad),
  );
  final destLngRad = lngRad +
      atan2(
        sin(bearingRad) * sin(angularDistance) * cos(latRad),
        cos(angularDistance) - sin(latRad) * sin(destLatRad),
      );

  return (lat: _radToDeg(destLatRad), lng: _radToDeg(destLngRad));
}

/// Standard geohash base32 encoding (bit-interleaved lat/lng), same
/// algorithm ported 1:1 to Kotlin's `GeoHashUtil` so both clients index
/// `speed_cameras.geohash` identically.
String encodeGeohash(double latitude, double longitude, {int precision = 9}) {
  var latMin = -90.0, latMax = 90.0;
  var lngMin = -180.0, lngMax = 180.0;
  final buffer = StringBuffer();
  var bit = 0, ch = 0;
  var evenBit = true;
  while (buffer.length < precision) {
    if (evenBit) {
      final mid = (lngMin + lngMax) / 2;
      if (longitude >= mid) {
        ch |= (1 << (4 - bit));
        lngMin = mid;
      } else {
        lngMax = mid;
      }
    } else {
      final mid = (latMin + latMax) / 2;
      if (latitude >= mid) {
        ch |= (1 << (4 - bit));
        latMin = mid;
      } else {
        latMax = mid;
      }
    }
    evenBit = !evenBit;
    if (bit < 4) {
      bit++;
    } else {
      buffer.write(_geohashBase32[ch]);
      bit = 0;
      ch = 0;
    }
  }
  return buffer.toString();
}

/// Approximate geohash cell width (in meters) for each precision length,
/// used to pick the shortest hash prefix whose cell still covers the
/// requested search radius.
const List<double> _geohashCellWidthMeters = [
  5009400, // 1
  1252300, // 2
  156500, // 3
  39100, // 4
  4890, // 5
  1220, // 6
  152.9, // 7
  38.2, // 8
  4.77, // 9
];

int geohashPrecisionForRadiusMeters(double radiusMeters) {
  for (var i = _geohashCellWidthMeters.length - 1; i >= 0; i--) {
    if (_geohashCellWidthMeters[i] >= radiusMeters) {
      return i + 1;
    }
  }
  return 1;
}

class GeohashQueryBounds {
  const GeohashQueryBounds(this.start, this.end);

  final String start;
  final String end;
}

/// Single-cell prefix range for a Firestore `geohash` field query.
///
/// This is a simple approximation (one geohash cell, not the full 9-cell
/// neighbor coverage of GeoFire-style libraries): points near a cell edge
/// may be missed. Good enough for the foundational repository layer; the
/// map feature (Phase 1) upgrades to `geoflutterfire_plus` for accurate
/// "cameras in view" queries.
GeohashQueryBounds geohashQueryBounds(
  double latitude,
  double longitude,
  double radiusMeters,
) {
  final precision = geohashPrecisionForRadiusMeters(radiusMeters);
  final hash = encodeGeohash(latitude, longitude, precision: precision);
  return GeohashQueryBounds(hash, '$hash');
}
