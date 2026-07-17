import 'dart:math';

import 'package:flutter/material.dart';

import 'camera_draft.dart';
import 'draft_marker_painter.dart';
import 'marker_style.dart';
import 'speed_camera_marker_icons.dart';

/// Draggable overlay shown while placing/editing a [CameraDraft]: the camera
/// glyph itself (drag to move) plus a small handle offset from it (drag to
/// rotate, tangentially, around the glyph) — both positioned in screen space
/// by `MapScreen` since they track the draft's projected lat/lng.
class DraftOverlay extends StatelessWidget {
  const DraftOverlay({
    super.key,
    required this.draft,
    required this.markerPosition,
    required this.bearing,
    required this.onMarkerPanUpdate,
    required this.onHandlePanUpdate,
  });

  final CameraDraft draft;
  final Offset markerPosition;

  /// Current map camera bearing (degrees clockwise from north) — needed to
  /// convert `draft.direction`, a compass bearing, into a screen-space angle
  /// now that the map can be rotated with two fingers.
  final double bearing;
  final GestureDragUpdateCallback onMarkerPanUpdate;
  final GestureDragUpdateCallback onHandlePanUpdate;

  /// Fixed on-screen distance (logical px) between the draft marker and its
  /// direction handle — independent of zoom, so it always reads clearly.
  /// Also used by `MapScreen` to turn a handle drag into an angle change.
  static const double handleDistancePx = 70;
  static const double handleDotInnerRadius = 14;
  static const double handleDotOuterRadius = 24;

  @override
  Widget build(BuildContext context) {
    // Screen-space heading of the handle: opposite the direction of travel
    // (so it sits on the same side as the radar cone, see
    // SpeedCameraMarkerIcons), then corrected for the map's own rotation —
    // draft.direction is a compass bearing, but screen "up" only means
    // "north" when bearing is 0.
    final screenDirection = draft.direction - bearing;
    final theta = screenDirection * pi / 180 + pi;
    final handlePosition =
        markerPosition +
        Offset(handleDistancePx * sin(theta), -handleDistancePx * cos(theta));

    return Stack(
      children: [
        Positioned(
          left: markerPosition.dx - SpeedCameraMarkerIcons.width / 2,
          top:
              markerPosition.dy -
              SpeedCameraMarkerIcons.anchor.dy * SpeedCameraMarkerIcons.height,
          child: GestureDetector(
            onPanUpdate: onMarkerPanUpdate,
            child: SizedBox(
              width: SpeedCameraMarkerIcons.width,
              height: SpeedCameraMarkerIcons.height,
              child: CustomPaint(
                painter: DraftMarkerPainter(
                  color: speedCameraOrange,
                  rotationDegrees: screenDirection,
                ),
              ),
            ),
          ),
        ),
        Positioned(
          left: handlePosition.dx - handleDotInnerRadius,
          top: handlePosition.dy - handleDotInnerRadius,
          child: GestureDetector(
            onPanUpdate: onHandlePanUpdate,
            child: Container(
              width: handleDotOuterRadius,
              height: handleDotOuterRadius,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white,
                border: Border.all(color: Colors.deepPurple, width: 2),
              ),
              child: Center(
                child: Container(
                  width: handleDotInnerRadius,
                  height: handleDotInnerRadius,
                  decoration: const BoxDecoration(
                    shape: BoxShape.circle,
                    color: Colors.deepPurple,
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
