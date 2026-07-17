import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

/// Shared drawing for the speed-camera glyph: a colored circle and, when a
/// direction is known, an arrow from its center pointing towards the
/// direction of travel the camera monitors. Used both to bake a
/// (north-facing) [BitmapDescriptor] for saved cameras — rotated cheaply via
/// `Marker.rotation`, no need to regenerate a bitmap per direction — and, as
/// a live [CustomPainter], for the draggable draft overlay where the
/// rotation changes continuously while the user adjusts the handle.
///
/// Callers pass `rotationDegrees` = the monitored travel direction (see
/// `SpeedCamera.monitoredTravelDirectionDeg`) — this glyph doesn't know
/// about OSM's raw `direction` tag or the local `directionReversed` flag,
/// it just draws whatever bearing it's given. With no known direction
/// there's no bearing to draw, so no arrow is drawn.
class SpeedCameraMarkerIcons {
  SpeedCameraMarkerIcons._();

  static const double circleRadius = 9;
  static const double arrowLength =
      34; // circle edge to shaft tip, excludes the arrowhead below.
  static const double arrowHeadWidth = 11;
  static const double arrowHeadLength = 11;
  // The arrowhead's apex extends slightly past the shaft tip — reserved here
  // so it isn't clipped by the canvas bounds.
  static const double _arrowHeadApex = arrowHeadLength * 0.6;
  static const double _renderScale = 3;

  static const double width = circleRadius * 2;
  static const double height = circleRadius + arrowLength + _arrowHeadApex;

  /// The circle's center — not the bitmap's geometric center, since the
  /// arrow extends above it (towards the top of the unrotated glyph) —
  /// expressed as a fraction of [height] for `Marker.anchor` / positioning
  /// the live overlay widget.
  static const Offset anchor = Offset(
    0.5,
    (arrowLength + _arrowHeadApex) / height,
  );

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
  /// [rotationDegrees] rotates the whole glyph (arrow + circle) around
  /// [center] — 0 draws the arrow pointing straight up, so passing a
  /// compass bearing here points it at that bearing on a north-up map.
  /// When [hasDirection] is false, no arrow is drawn.
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
      final tip = Offset(center.dx, center.dy - arrowLength);
      final shaftStart = Offset(center.dx, center.dy - circleRadius);
      canvas.drawLine(
        shaftStart,
        tip,
        Paint()
          ..color = color
          ..strokeWidth = 4.5
          ..strokeCap = StrokeCap.round,
      );

      final headPath = Path()
        ..moveTo(tip.dx, tip.dy - arrowHeadLength * 0.6)
        ..lineTo(tip.dx - arrowHeadWidth / 2, tip.dy + arrowHeadLength * 0.4)
        ..lineTo(tip.dx + arrowHeadWidth / 2, tip.dy + arrowHeadLength * 0.4)
        ..close();
      canvas.drawPath(headPath, Paint()..color = color);
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
