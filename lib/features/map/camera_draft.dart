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
    this.existingOsmNodeId,
    this.existingOsmNodeVersion,
    this.canPublishToOsm = false,
    this.canEditOsm = false,
    this.osmEditNodeId,
    this.readOnly = false,
    this.hasKnownDirection = false,
    this.directionReversed = false,
    this.originalLimit,
    this.originalDirection,
  });

  final LatLng position;
  final double direction;
  final int limit;
  final String? existingId;
  final SpeedCameraSource? existingSource;
  final SpeedCameraStatus? existingStatus;

  /// The camera's `limit`/`direction` as they were when editing started —
  /// immutable for the life of the draft, used to tell whether the data
  /// OpenStreetMap cares about has actually changed (see
  /// `DraftSheet.onSaveOsmEdit`'s enabled state), as opposed to the
  /// currently-edited [limit]/[direction] above.
  final int? originalLimit;
  final int? originalDirection;

  /// True when editing a camera neither owned by this user nor editable via
  /// an OpenStreetMap login — only the local `directionReversed` toggle (see
  /// below) is interactive; position/handle dragging and the limit/save/
  /// delete/approve/publish actions are all disabled. Always false for a
  /// brand-new draft (there's nothing to lack permission over yet).
  final bool readOnly;

  /// True when the camera being edited has a known `direction` — this is
  /// what actually gates whether the `directionReversed` toggle appears at
  /// all, regardless of [readOnly].
  final bool hasKnownDirection;

  /// Local-only flag being edited (see [SpeedCamera.directionReversed]) —
  /// only persisted once a save action in the sheet actually fires, same as
  /// [limit]/[direction].
  final bool directionReversed;

  /// Non-null when this report was already published as a real node on
  /// OpenStreetMap — [existingOsmNodeVersion] is needed to delete it later.
  final int? existingOsmNodeId;
  final int? existingOsmNodeVersion;

  /// True only when editing an existing `source: 'user'`
  /// report and the user has an OpenStreetMap account linked
  /// (features.md 4) — computed once when the draft starts.
  final bool canPublishToOsm;

  /// True when editing a camera that already has a live OpenStreetMap node
  /// (an Overpass-imported camera, or a user report already published) and
  /// the user has an OpenStreetMap account linked — lets them update
  /// `maxspeed`/`direction` directly on that node. See [SpeedCamera.osmId].
  final bool canEditOsm;

  /// The OSM node id to update when [canEditOsm] is true.
  final int? osmEditNodeId;

  bool get isNew => existingId == null;

  CameraDraft copyWith({LatLng? position, double? direction, bool? directionReversed}) {
    return CameraDraft(
      position: position ?? this.position,
      direction: direction ?? this.direction,
      limit: limit,
      existingId: existingId,
      existingSource: existingSource,
      existingStatus: existingStatus,
      existingOsmNodeId: existingOsmNodeId,
      existingOsmNodeVersion: existingOsmNodeVersion,
      canPublishToOsm: canPublishToOsm,
      canEditOsm: canEditOsm,
      osmEditNodeId: osmEditNodeId,
      readOnly: readOnly,
      hasKnownDirection: hasKnownDirection,
      directionReversed: directionReversed ?? this.directionReversed,
      originalLimit: originalLimit,
      originalDirection: originalDirection,
    );
  }
}
