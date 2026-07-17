import 'package:autovelox_app/core/utils/tolerance_filter.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('shouldPlayAudio', () {
    test('always plays when the filter is disabled', () {
      expect(
        shouldPlayAudio(speedKmh: 10, limitKmh: 50, toleranceFilterEnabled: false),
        isTrue,
      );
    });

    test('always plays when the limit is unknown', () {
      expect(
        shouldPlayAudio(speedKmh: 10, limitKmh: null, toleranceFilterEnabled: true),
        isTrue,
      );
    });

    test('mutes at or under 90% of the limit', () {
      expect(shouldPlayAudio(speedKmh: 45, limitKmh: 50, toleranceFilterEnabled: true), isFalse);
    });

    test('plays once above 90% of the limit', () {
      expect(shouldPlayAudio(speedKmh: 46, limitKmh: 50, toleranceFilterEnabled: true), isTrue);
    });
  });
}
