import 'package:flutter/material.dart';

import 'speed_camera_marker_icons.dart';

/// Live-rotating version of [SpeedCameraMarkerIcons] for the draggable
/// draft overlay (a real Marker's rotation is native/cheap, but a plain
/// Flutter widget has to repaint the glyph itself as the handle turns it).
/// Only the arrow rotates with [rotationDegrees] — the badge (circle +
/// camera pictogram) always stays upright, same as the two real `Marker`s
/// used elsewhere for a saved camera.
class DraftMarkerPainter extends CustomPainter {
  const DraftMarkerPainter({
    required this.color,
    required this.rotationDegrees,
    this.hasDirection = true,
  });

  final Color color;
  final double rotationDegrees;
  final bool hasDirection;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, SpeedCameraMarkerIcons.anchor.dy * size.height);
    SpeedCameraMarkerIcons.paintCombined(
      canvas,
      center,
      color,
      rotationDegrees: rotationDegrees,
      hasDirection: hasDirection,
    );
  }

  @override
  bool shouldRepaint(covariant DraftMarkerPainter oldDelegate) {
    return oldDelegate.color != color ||
        oldDelegate.rotationDegrees != rotationDegrees ||
        oldDelegate.hasDirection != hasDirection;
  }
}
