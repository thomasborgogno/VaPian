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
    required this.onMarkerPanUpdate,
    required this.onHandlePanUpdate,
  });

  final CameraDraft draft;
  final Offset markerPosition;
  final GestureDragUpdateCallback onMarkerPanUpdate;
  final GestureDragUpdateCallback onHandlePanUpdate;

  /// Fixed on-screen distance (logical px) between the draft marker and its
  /// direction handle — independent of zoom, so it always reads clearly.
  /// Also used by `MapScreen` to turn a handle drag into an angle change.
  static const double handleDistancePx = 70;
  static const double handleDotRadius = 14;

  @override
  Widget build(BuildContext context) {
    final theta = draft.direction * pi / 180;
    final handlePosition =
        markerPosition + Offset(handleDistancePx * sin(theta), -handleDistancePx * cos(theta));

    return Stack(
      children: [
        Positioned(
          left: markerPosition.dx - SpeedCameraMarkerIcons.width / 2,
          top: markerPosition.dy - SpeedCameraMarkerIcons.anchor.dy * SpeedCameraMarkerIcons.height,
          child: GestureDetector(
            onPanUpdate: onMarkerPanUpdate,
            child: SizedBox(
              width: SpeedCameraMarkerIcons.width,
              height: SpeedCameraMarkerIcons.height,
              child: CustomPaint(
                painter: DraftMarkerPainter(color: speedCameraOrange, rotationDegrees: draft.direction),
              ),
            ),
          ),
        ),
        Positioned(
          left: handlePosition.dx - handleDotRadius,
          top: handlePosition.dy - handleDotRadius,
          child: GestureDetector(
            onPanUpdate: onHandlePanUpdate,
            child: Container(
              width: handleDotRadius * 2,
              height: handleDotRadius * 2,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white,
                border: Border.all(color: Colors.deepPurple, width: 3),
              ),
              child: Center(
                child: Container(
                  width: handleDotRadius,
                  height: handleDotRadius,
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.deepPurple),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}
