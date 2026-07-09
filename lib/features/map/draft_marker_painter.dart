import 'package:flutter/material.dart';

import 'speed_camera_marker_icons.dart';

/// Live-rotating version of [SpeedCameraMarkerIcons] for the draggable
/// draft overlay (a real Marker's rotation is native/cheap, but a plain
/// Flutter widget has to repaint the glyph itself as the handle turns it).
class DraftMarkerPainter extends CustomPainter {
  const DraftMarkerPainter({required this.color, required this.rotationDegrees});

  final Color color;
  final double rotationDegrees;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, SpeedCameraMarkerIcons.anchor.dy * size.height);
    SpeedCameraMarkerIcons.paintGlyph(canvas, center, color, rotationDegrees: rotationDegrees);
  }

  @override
  bool shouldRepaint(covariant DraftMarkerPainter oldDelegate) {
    return oldDelegate.color != color || oldDelegate.rotationDegrees != rotationDegrees;
  }
}
