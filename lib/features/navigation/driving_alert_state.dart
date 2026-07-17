import '../../data/models/speed_camera.dart';

/// Snapshot of the phone-side navigation mode's proximity engine — mirrors
/// the native Android Auto `DrivingAlertState`, plus [headingDeg] so the
/// navigation UI can orient the map/virtual car.
class DrivingAlertState {
  const DrivingAlertState({
    this.camera,
    this.distanceMeters,
    this.speedKmh = 0.0,
    this.headingDeg = 0.0,
    this.withinRange = false,
    this.overLimit = false,
  });

  final SpeedCamera? camera;
  final double? distanceMeters;
  final double speedKmh;
  final double headingDeg;

  /// True once [distanceMeters] is at or under the current alert distance
  /// (see `alertDistanceMeters`) — this is what the UI/TTS treat as "active
  /// alert", as opposed to [camera] merely being the nearest ahead-matching
  /// candidate regardless of distance.
  final bool withinRange;
  final bool overLimit;

  DrivingAlertState copyWith({
    SpeedCamera? camera,
    bool clearCamera = false,
    double? distanceMeters,
    bool clearDistance = false,
    double? speedKmh,
    double? headingDeg,
    bool? withinRange,
    bool? overLimit,
  }) {
    return DrivingAlertState(
      camera: clearCamera ? null : (camera ?? this.camera),
      distanceMeters: clearDistance ? null : (distanceMeters ?? this.distanceMeters),
      speedKmh: speedKmh ?? this.speedKmh,
      headingDeg: headingDeg ?? this.headingDeg,
      withinRange: withinRange ?? this.withinRange,
      overLimit: overLimit ?? this.overLimit,
    );
  }
}
