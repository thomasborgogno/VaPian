import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

/// Shared drawing for the speed-camera glyph: a colored circle and, when a
/// direction is known, a translucent radar cone behind it. Used both to
/// bake a (north-facing) [BitmapDescriptor] for saved cameras — rotated
/// cheaply via `Marker.rotation`, no need to regenerate a bitmap per
/// direction — and, as a live [CustomPainter], for the draggable draft
/// overlay where the rotation changes continuously while the user adjusts
/// the handle.
///
/// The radar cone faces due south (i.e. directly opposite the direction of
/// travel), so it fans out towards the oncoming vehicle the camera is
/// watching for. With no known direction there's no travel bearing to be
/// relative to, so no cone is drawn.
class SpeedCameraMarkerIcons {
  SpeedCameraMarkerIcons._();

  static const double circleRadius = 9;
  static const double radarRadius = 40;
  static const double radarSweepDegrees = 36;
  static const double _renderScale = 3;

  static final double _radarHalfSweepRad = radarSweepDegrees / 2 * pi / 180;
  static final double _radarLateralExtent =
      radarRadius * sin(_radarHalfSweepRad);
  static final double _halfWidth = max(circleRadius, _radarLateralExtent);

  static final double width = _halfWidth * 2;
  static final double height = circleRadius + radarRadius;

  /// The circle's center — not the bitmap's geometric center, since the
  /// radar cone extends south of it — expressed as a fraction of [height]
  /// for `Marker.anchor` / positioning the live overlay widget.
  static final Offset anchor = Offset(0.5, circleRadius / height);

  static final Map<(Color, bool), BitmapDescriptor> _cameraCache = {};

  static Future<BitmapDescriptor> forColor(
    Color color, {
    bool hasDirection = true,
  }) async {
    final key = (color, hasDirection);
    return _cameraCache[key] ??= await _renderCamera(
      color,
      hasDirection: hasDirection,
    );
  }

  /// Draws the glyph centered at [center] on an already-scaled [canvas].
  /// [rotationDegrees] rotates the whole glyph (radar cone + circle) around
  /// [center]. When [hasDirection] is false, no cone is drawn.
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
      // Cone bisector at 90° (canvas angles grow clockwise from the positive
      // x-axis) points due south — opposite the direction of travel, i.e.
      // towards the oncoming vehicle.
      final startAngle = pi / 2 - _radarHalfSweepRad;
      final sweepAngle = _radarHalfSweepRad * 2;
      final radarPath = Path()
        ..moveTo(center.dx, center.dy)
        ..arcTo(
          Rect.fromCircle(center: center, radius: radarRadius),
          startAngle,
          sweepAngle,
          false,
        )
        ..close();
      canvas.drawPath(radarPath, Paint()..color = color.withAlpha(90));
    }

    canvas.drawCircle(center, circleRadius, Paint()..color = color);
    canvas.drawCircle(
      center,
      circleRadius,
      Paint()
        ..color = Colors.white
        ..style = PaintingStyle.stroke
        ..strokeWidth = circleRadius * 0.16,
    );

    canvas.restore();
  }

  static Future<BitmapDescriptor> _renderCamera(
    Color color, {
    required bool hasDirection,
  }) async {
    final rawWidth = width * _renderScale;
    final rawHeight = height * _renderScale;
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder, Rect.fromLTWH(0, 0, rawWidth, rawHeight))
      ..scale(_renderScale);

    paintGlyph(
      canvas,
      Offset(width / 2, anchor.dy * height),
      color,
      hasDirection: hasDirection,
    );

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
