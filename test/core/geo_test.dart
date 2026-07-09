import 'package:autovelox_app/core/utils/geo.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('haversineDistanceMeters', () {
    test('is zero for identical points', () {
      expect(haversineDistanceMeters(41.9028, 12.4964, 41.9028, 12.4964), 0);
    });

    test('matches known Rome-Milan distance within 1%', () {
      // Rome (41.9028, 12.4964) -> Milan (45.4642, 9.1900), ~477 km.
      final meters = haversineDistanceMeters(41.9028, 12.4964, 45.4642, 9.1900);
      expect(meters, closeTo(477000, 4770));
    });
  });

  group('bearingDegrees', () {
    test('due north is ~0 degrees', () {
      final bearing = bearingDegrees(45, 9, 46, 9);
      expect(bearing, closeTo(0, 1));
    });

    test('due east is ~90 degrees', () {
      final bearing = bearingDegrees(45, 9, 45, 10);
      expect(bearing, closeTo(90, 1));
    });
  });

  group('encodeGeohash', () {
    test('matches the canonical ezs42 reference value', () {
      expect(encodeGeohash(42.6, -5.6, precision: 5), 'ezs42');
    });

    test('is deterministic', () {
      final a = encodeGeohash(41.9028, 12.4964, precision: 9);
      final b = encodeGeohash(41.9028, 12.4964, precision: 9);
      expect(a, b);
    });

    test('nearby points share a longer prefix than far apart points', () {
      final rome = encodeGeohash(41.9028, 12.4964, precision: 9);
      final romeNearby = encodeGeohash(41.9030, 12.4966, precision: 9);
      final milan = encodeGeohash(45.4642, 9.1900, precision: 9);

      int commonPrefixLength(String a, String b) {
        var i = 0;
        while (i < a.length && i < b.length && a[i] == b[i]) {
          i++;
        }
        return i;
      }

      expect(
        commonPrefixLength(rome, romeNearby),
        greaterThan(commonPrefixLength(rome, milan)),
      );
    });
  });

  group('geohashPrecisionForRadiusMeters', () {
    test('shrinks as the search radius grows', () {
      final smallRadiusPrecision = geohashPrecisionForRadiusMeters(50);
      final largeRadiusPrecision = geohashPrecisionForRadiusMeters(50000);
      expect(smallRadiusPrecision, greaterThan(largeRadiusPrecision));
    });
  });

  group('destinationPoint', () {
    test('round-trips distance and bearing back to the origin', () {
      const originLat = 41.9028;
      const originLng = 12.4964;
      const bearing = 200.0;
      const distance = 15.0;

      final destination = destinationPoint(originLat, originLng, bearing, distance);

      expect(
        haversineDistanceMeters(originLat, originLng, destination.lat, destination.lng),
        closeTo(distance, 0.5),
      );
      expect(
        bearingDegrees(originLat, originLng, destination.lat, destination.lng),
        closeTo(bearing, 0.5),
      );
    });

    test('due north increases latitude and keeps longitude', () {
      final destination = destinationPoint(41.9028, 12.4964, 0, 100);
      expect(destination.lat, greaterThan(41.9028));
      expect(destination.lng, closeTo(12.4964, 0.0001));
    });
  });

  group('geohashQueryBounds', () {
    test('start is a prefix of end and sorts before it', () {
      final bounds = geohashQueryBounds(41.9028, 12.4964, 5000);
      expect(bounds.end.startsWith(bounds.start), isTrue);
      expect(bounds.start.compareTo(bounds.end), lessThan(0));
    });
  });
}
