import 'package:flutter/material.dart';

/// Small colored dot shown instead of the full camera glyph once zoomed out
/// past `MapScreen`'s dot threshold — at that scale the full icon (with its
/// direction pointer) is too small to read anyway, and rendering hundreds of
/// full glyphs is what caused the lag this replaces.
///
/// A `flutter_map` `Marker.child` is a plain widget (unlike the old
/// `GoogleMap` bitmap descriptors), so this paints directly instead of
/// baking a bitmap — one `CustomPaint` per dot marker.
class DotMarker extends StatelessWidget {
  const DotMarker({super.key, required this.color});

  final Color color;

  static const double radius = 6;
  static const double diameter = radius * 2;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: const Size(diameter, diameter),
      painter: _DotPainter(color),
    );
  }
}

class _DotPainter extends CustomPainter {
  const _DotPainter(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    canvas.drawCircle(center, DotMarker.radius, Paint()..color = color);
    canvas.drawCircle(
      center,
      DotMarker.radius,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );
  }

  @override
  bool shouldRepaint(_DotPainter oldDelegate) => oldDelegate.color != color;
}
