import '../../data/models/user_settings.dart';

/// 1:1 Dart port of the Android Auto native
/// `carapp/util/AlertDistanceCalculator.kt`.
const double _reactionTimeSeconds = 1.5;
const double _decelerationMs2 = 7.0;
const double _overLimitFactor = 1.3;
const double _minMeters = 100.0;
const double _maxMeters = 1500.0;

const List<int> _standardLimitsKmh = [30, 50, 70, 90, 110, 130];

/// Distance (meters) at which to start alerting, given the user's
/// [UserSettings.alertDistanceMode] — a fixed value, or one dynamically
/// derived from reaction time + braking distance at the current speed
/// (features.md 2.2).
double alertDistanceMeters({
  required UserSettings settings,
  required double speedKmh,
  required int? limitKmh,
}) {
  if (settings.alertDistanceMode == AlertDistanceMode.fixed) {
    return settings.alertDistanceMeters.toDouble();
  }

  final speedMs = speedKmh / 3.6;
  final reactionDistance = speedMs * _reactionTimeSeconds;
  final brakingDistance = (speedMs * speedMs) / (2 * _decelerationMs2);
  final overLimitFactor =
      (limitKmh != null && speedKmh > limitKmh) ? _overLimitFactor : 1.0;
  final distance = (reactionDistance + brakingDistance) * overLimitFactor;
  return distance.clamp(_minMeters, _maxMeters);
}

/// Fallback speed limit (km/h) for a quick "Segnala Velox" report when the
/// real limit isn't known: the smallest standard Italian limit that is at
/// least the current speed, or the highest one if speed exceeds them all.
int estimateSpeedLimitKmh(double speedKmh) {
  final rounded = speedKmh.round();
  for (final limit in _standardLimitsKmh) {
    if (limit >= rounded) return limit;
  }
  return _standardLimitsKmh.last;
}
