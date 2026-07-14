import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../data/models/speed_camera.dart';

/// A speed camera being placed or edited directly on the map: position and
/// direction come from dragging the marker/handle overlay, only the speed
/// limit is picked from the bottom sheet.
class CameraDraft {
  const CameraDraft({
    required this.position,
    required this.direction,
    required this.limit,
    this.existingId,
    this.existingSource,
    this.existingStatus,
    this.canPublishToOsm = false,
  });

  final LatLng position;
  final double direction;
  final int limit;
  final String? existingId;
  final SpeedCameraSource? existingSource;
  final SpeedCameraStatus? existingStatus;

  /// True only when editing an existing `source: 'user'`, `status: 'pending'`
  /// report and the user has an OpenStreetMap account linked
  /// (features.md 4) — computed once when the draft starts.
  final bool canPublishToOsm;

  bool get isNew => existingId == null;

  CameraDraft copyWith({LatLng? position, double? direction}) {
    return CameraDraft(
      position: position ?? this.position,
      direction: direction ?? this.direction,
      limit: limit,
      existingId: existingId,
      existingSource: existingSource,
      existingStatus: existingStatus,
      canPublishToOsm: canPublishToOsm,
    );
  }
}
