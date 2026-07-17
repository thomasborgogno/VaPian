import '../../data/models/speed_camera.dart';
import 'geo.dart';

/// 1:1 Dart port of the Android Auto native
/// `carapp/util/ProximityCalculator.kt`, used by the phone-side navigation
/// mode so both clients decide "is this camera ahead of me" identically.
const double _aheadToleranceDeg = 45.0;
const double _cameraDirectionToleranceDeg = 70.0;

/// Shortest angular distance between two compass bearings, in [0, 180].
double angularDifferenceDeg(double a, double b) {
  final diff = (a - b) % 360;
  final folded = diff < 0 ? diff + 360 : diff;
  return folded > 180 ? 360 - folded : folded;
}

/// True when [camera] is roughly ahead of a driver at ([userLat], [userLng])
/// travelling on bearing [headingDeg] — the bearing from the driver to the
/// camera must be within [_aheadToleranceDeg], and, if the camera has a known
/// `direction` tag, the driver's heading must also roughly match it (within
/// [_cameraDirectionToleranceDeg]) so only same-direction cameras alert.
bool isAheadInTravelDirection({
  required double userLat,
  required double userLng,
  required double headingDeg,
  required SpeedCamera camera,
}) {
  final bearingToCamera = bearingDegrees(userLat, userLng, camera.lat, camera.lng);
  if (angularDifferenceDeg(headingDeg, bearingToCamera) > _aheadToleranceDeg) {
    return false;
  }
  final cameraDirection = camera.direction;
  if (cameraDirection == null) return true;
  return angularDifferenceDeg(headingDeg, cameraDirection.toDouble()) <=
      _cameraDirectionToleranceDeg;
}
