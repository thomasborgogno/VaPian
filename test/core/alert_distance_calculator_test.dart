import 'package:autovelox_app/core/utils/alert_distance_calculator.dart';
import 'package:autovelox_app/data/models/user_settings.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('alertDistanceMeters', () {
    test('fixed mode returns the configured distance verbatim', () {
      const settings = UserSettings(
        alertDistanceMode: AlertDistanceMode.fixed,
        alertDistanceMeters: 400,
      );
      final distance = alertDistanceMeters(settings: settings, speedKmh: 130, limitKmh: 50);
      expect(distance, 400);
    });

    test('dynamic mode grows with speed and clamps to the 100m floor at low speed', () {
      const settings = UserSettings(alertDistanceMode: AlertDistanceMode.dynamicDistance);
      final distance = alertDistanceMeters(settings: settings, speedKmh: 10, limitKmh: null);
      expect(distance, 100);
    });

    test('dynamic mode applies the over-limit factor when speeding', () {
      // Chosen so both branches land clear of the 100m floor, isolating the
      // 1.3x factor from the clamp.
      const settings = UserSettings(alertDistanceMode: AlertDistanceMode.dynamicDistance);
      final underLimit = alertDistanceMeters(settings: settings, speedKmh: 130, limitKmh: 200);
      final overLimit = alertDistanceMeters(settings: settings, speedKmh: 130, limitKmh: 50);
      expect(overLimit, greaterThan(underLimit));
      expect(overLimit, closeTo(underLimit * 1.3, 0.01));
    });

    test('dynamic mode clamps to the 1500m ceiling at very high speed', () {
      const settings = UserSettings(alertDistanceMode: AlertDistanceMode.dynamicDistance);
      final distance = alertDistanceMeters(settings: settings, speedKmh: 500, limitKmh: null);
      expect(distance, 1500);
    });
  });

  group('estimateSpeedLimitKmh', () {
    test('rounds up to the next standard Italian limit', () {
      expect(estimateSpeedLimitKmh(42), 50);
      expect(estimateSpeedLimitKmh(50), 50);
      expect(estimateSpeedLimitKmh(51), 70);
    });

    test('falls back to the highest standard limit above all of them', () {
      expect(estimateSpeedLimitKmh(200), 130);
    });
  });
}
