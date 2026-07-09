import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

/// Shared drawing for the speed-camera glyph: a colored circle with a
/// camera icon and, when a direction is known, a triangular pointer above
/// it. Used both to bake a (north-facing) [BitmapDescriptor] for saved
/// cameras — rotated cheaply via `Marker.rotation`, no need to regenerate a
/// bitmap per direction — and, as a live [CustomPainter], for the
/// draggable draft overlay where the rotation changes continuously while
/// the user adjusts the handle.
///
/// The camera icon glyph is drawn 90° clockwise from the pointer, then
/// flipped another 180° — so its "lens" faces opposite the pointer/handle:
/// the arrow shows the direction of travel being watched, while the lens
/// faces back toward the incoming car. With no known direction there's no
/// pointer to be relative to, so the icon is drawn upright instead.
class SpeedCameraMarkerIcons {
  SpeedCameraMarkerIcons._();

  static const double circleRadius = 17;
  static const double pointerHeight = 12;
  static const double pointerHalfWidth = 6;
  static const double _renderScale = 3;

  static const double width = circleRadius * 2;
  static const double height = pointerHeight + circleRadius * 2;

  /// The circle's center — not the bitmap's geometric center, since the
  /// pointer triangle sits above it — expressed as a fraction of [height]
  /// for `Marker.anchor` / positioning the live overlay widget.
  static const Offset anchor = Offset(0.5, (pointerHeight + circleRadius) / height);

  static final Map<(Color, bool), BitmapDescriptor> _cameraCache = {};

  static Future<BitmapDescriptor> forColor(Color color, {bool hasDirection = true}) async {
    final key = (color, hasDirection);
    return _cameraCache[key] ??= await _renderCamera(color, hasDirection: hasDirection);
  }

  /// Draws the glyph centered at [center] on an already-scaled [canvas].
  /// [rotationDegrees] rotates the whole glyph (pointer + circle + icon)
  /// around [center]; the icon additionally always faces 90° further
  /// clockwise than the pointer, then 180° opposite it — the lens ends up
  /// facing back the way the pointer/handle indicate. When [hasDirection]
  /// is false, no pointer is drawn and the icon stays upright.
  static void paintGlyph(
    Canvas canvas,
    Offset center,
    Color color, {
    double rotationDegrees = 0,
    bool hasDirection = true,
  }) {
    canvas.save();
    canvas.translate(center.dx, center.dy);
    canvas.rotate(rotationDegrees * pi / 180);
    canvas.translate(-center.dx, -center.dy);

    if (hasDirection) {
      final pointerPath = Path()
        ..moveTo(center.dx, center.dy - circleRadius - pointerHeight)
        ..lineTo(center.dx - pointerHalfWidth, center.dy - circleRadius)
        ..lineTo(center.dx + pointerHalfWidth, center.dy - circleRadius)
        ..close();
      canvas.drawPath(pointerPath, Paint()..color = color);
    }

    canvas.drawCircle(center, circleRadius, Paint()..color = color);
    canvas.drawCircle(
      center,
      circleRadius,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = circleRadius * 0.09,
    );

    canvas.save();
    canvas.translate(center.dx, center.dy);
    if (hasDirection) {
      // -90° to match the pointer, +180° so the lens faces the oncoming car.
      canvas.rotate(pi / 2);
    }
    canvas.translate(-center.dx, -center.dy);
    final textPainter = TextPainter(textDirection: TextDirection.ltr)
      ..text = TextSpan(
        text: String.fromCharCode(Icons.videocam.codePoint),
        style: TextStyle(
          fontSize: circleRadius * 1.15,
          fontFamily: Icons.videocam.fontFamily,
          package: Icons.videocam.fontPackage,
          color: Colors.white,
        ),
      )
      ..layout();
    textPainter.paint(
      canvas,
      center - Offset(textPainter.width / 2, textPainter.height / 2),
    );
    canvas.restore();

    canvas.restore();
  }

  static Future<BitmapDescriptor> _renderCamera(Color color, {required bool hasDirection}) async {
    final rawWidth = width * _renderScale;
    final rawHeight = height * _renderScale;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Rect.fromLTWH(0, 0, rawWidth, rawHeight))
      ..scale(_renderScale);

    paintGlyph(canvas, Offset(width / 2, anchor.dy * height), color, hasDirection: hasDirection);

    final picture = recorder.endRecording();
    final image = await picture.toImage(rawWidth.round(), rawHeight.round());
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    return BitmapDescriptor.bytes(
      bytes!.buffer.asUint8List(),
      width: width,
      height: height,
    );
  }
}
