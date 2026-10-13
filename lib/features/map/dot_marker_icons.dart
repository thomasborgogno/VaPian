import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

/// Small colored dot shown instead of the full camera glyph once zoomed out
/// past `MapScreen`'s dot threshold — at that scale the full icon (with its
/// direction pointer) is too small to read anyway, and rendering hundreds
/// of full-size bitmaps is what caused the lag this replaces.
class DotMarkerIcons {
  DotMarkerIcons._();

  static const double radius = 6;
  static const double _renderScale = 3;

  static final Map<Color, BitmapDescriptor> _cache = {};

  static Future<BitmapDescriptor> forColor(Color color) async {
    return _cache[color] ??= await _render(color);
  }

  static Future<BitmapDescriptor> _render(Color color) async {
    final size = radius * 2;
    final rawSize = size * _renderScale;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Rect.fromLTWH(0, 0, rawSize, rawSize))
      ..scale(_renderScale);
    final center = Offset(size / 2, size / 2);

    canvas.drawCircle(center, radius, Paint()..color = color);
    canvas.drawCircle(
      center,
      radius,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = 1.5,
    );

    final picture = recorder.endRecording();
    final image = await picture.toImage(rawSize.round(), rawSize.round());
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    return BitmapDescriptor.bytes(bytes!.buffer.asUint8List(), width: size, height: size);
  }
}
