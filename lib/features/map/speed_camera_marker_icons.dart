import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

/// Shared drawing for the speed-camera glyph: a colored circular badge with
/// the camera pictogram (`assets/speed_camera_icon.svg`) inside, and, when a
/// direction is known, an arrow pointing towards the direction of travel the
/// camera monitors.
///
/// The badge and the arrow are rendered as **two independent markers** on
/// purpose: the badge must always stay upright on screen (billboard)
/// regardless of the map's bearing, while the arrow must rotate to reflect
/// the true monitored bearing. `MapScreen` places two `flutter_map`
/// `Marker`s at the same position — [SpeedCameraIconMarker] (`rotate: true`,
/// so it's counter-rotated against the map's own rotation and stays
/// upright) and, only when a direction is known, [SpeedCameraArrowMarker]
/// (`rotate: false`, so it turns together with the map — the ground-plane
/// behavior needed for its rotation degrees to mean a true compass bearing).
/// The live draft overlay, which paints directly on a canvas it controls,
/// uses [paintCombined] instead to get the same effect (upright badge,
/// rotating arrow) in one widget.
class SpeedCameraMarkerIcons {
  SpeedCameraMarkerIcons._();

  static const String _svgAsset = 'assets/speed_camera_icon.svg';

  static const double circleRadius = 11;
  static const double arrowLength = 30; // circle edge to shaft tip, excludes the arrowhead below.
  static const double arrowHeadWidth = 11;
  static const double arrowHeadLength = 11;
  // The arrowhead's apex extends slightly past the shaft tip — reserved here
  // so it isn't clipped by the canvas bounds.
  static const double _arrowHeadApex = arrowHeadLength * 0.6;

  /// White border (matching the badge's own ring) plus a very thin grey
  /// hairline just outside it — applied identically to the circle and the
  /// arrow so the two read as one consistent shape, even though they're
  /// drawn/rotated independently (see class doc).
  static const double _whiteBorderWidth = circleRadius * 0.16;
  static const double _grayBorderWidth = 0.6;
  static const double _borderMargin = _whiteBorderWidth + _grayBorderWidth;
  static const Color _grayBorderColor = Color(0xFF9E9E9E);

  /// Dimensions of the standalone icon-only marker (billboard) — a square
  /// centered on the camera's position, sized to fit the circle plus its
  /// white/grey border.
  static const double iconSize = (circleRadius + _borderMargin) * 2;

  /// Dimensions of the standalone arrow-only marker (rotating) — anchored
  /// at its bottom-center, which sits exactly at the camera's position, so
  /// the arrow appears to extend outward from the badge's edge. Widened/
  /// heightened over the bare arrow geometry to leave room for its own
  /// border without clipping.
  static const double arrowWidth = arrowHeadWidth + _borderMargin * 2;
  static const double arrowHeight =
      circleRadius + arrowLength + _arrowHeadApex + _borderMargin;

  /// Combined bounding box for the live draft overlay ([paintCombined]),
  /// which draws both pieces on one canvas it positions itself — the widest
  /// element is the badge, the tallest span is badge-center to arrow-tip.
  static const double width = iconSize;
  static const double height = (circleRadius + _borderMargin) + (arrowLength + _arrowHeadApex + _borderMargin);
  static const Offset anchor = Offset(0.5, (arrowLength + _arrowHeadApex + _borderMargin) / height);

  static ui.Picture? _iconPicture;
  static Size? _iconPictureSize;
  static Future<void>? _iconLoadFuture;

  /// Kicks off (idempotently) loading the SVG pictogram, so it's already
  /// cached by the time [paintIcon]/[paintCombined] need to paint
  /// synchronously (e.g. from `CustomPainter.paint`). Safe to call eagerly
  /// and repeatedly.
  static Future<void> ensureIconLoaded() {
    return _iconLoadFuture ??= () async {
      final info = await vg.loadPicture(const SvgAssetLoader(_svgAsset), null);
      _iconPicture = info.picture;
      _iconPictureSize = info.size;
    }();
  }

  /// Draws the badge (circle + camera pictogram) centered at [center],
  /// never rotated — always upright regardless of the map's bearing or the
  /// camera's direction. Degrades gracefully to a plain circle if the SVG
  /// hasn't finished loading yet (only possible on a very early frame;
  /// [ensureIconLoaded] should already have been kicked off well before).
  static void paintIcon(Canvas canvas, Offset center, Color color) {
    canvas.drawCircle(
      center,
      circleRadius + _borderMargin,
      Paint()..color = _grayBorderColor,
    );
    canvas.drawCircle(
      center,
      circleRadius + _whiteBorderWidth,
      Paint()..color = Colors.white,
    );
    canvas.drawCircle(center, circleRadius, Paint()..color = color);

    final picture = _iconPicture;
    final svgSize = _iconPictureSize;
    if (picture == null || svgSize == null) return;

    // The pictogram is inset from the circle's edge so the colored ring
    // stays visible all around it.
    final glyphDiameter = circleRadius * 2 * 0.62;
    final scale = glyphDiameter / max(svgSize.width, svgSize.height);
    canvas.save();
    canvas.translate(
      center.dx - (svgSize.width * scale) / 2,
      center.dy - (svgSize.height * scale) / 2,
    );
    canvas.scale(scale);
    canvas.drawPicture(picture);
    canvas.restore();
  }

  /// Draws just the direction arrow, rotated [rotationDegrees] around
  /// [origin] (the badge's center) — 0 draws it pointing straight up, so
  /// passing a compass bearing here (corrected for map bearing as needed)
  /// points it at that bearing. Bordered the same way as the badge (white
  /// ring + thin grey hairline) so the two read as one shape.
  static void paintArrow(Canvas canvas, Offset origin, Color color, double rotationDegrees) {
    canvas.save();
    canvas.translate(origin.dx, origin.dy);
    canvas.rotate(rotationDegrees * pi / 180);
    canvas.translate(-origin.dx, -origin.dy);

    final tip = Offset(origin.dx, origin.dy - arrowLength);
    final shaftStart = Offset(origin.dx, origin.dy - circleRadius);
    const shaftCoreWidth = 4.5;
    _paintBorderedLine(canvas, shaftStart, tip, color, shaftCoreWidth);

    final headPath = Path()
      ..moveTo(tip.dx, tip.dy - arrowHeadLength * 0.6)
      ..lineTo(tip.dx - arrowHeadWidth / 2, tip.dy + arrowHeadLength * 0.4)
      ..lineTo(tip.dx + arrowHeadWidth / 2, tip.dy + arrowHeadLength * 0.4)
      ..close();
    _paintBorderedPath(canvas, headPath, color);

    canvas.restore();
  }

  /// Draws a stroked line with the same white-ring + grey-hairline border
  /// treatment as the badge — layered widest-to-narrowest so only a thin
  /// ring of each underlying color peeks out from behind the next.
  static void _paintBorderedLine(
    Canvas canvas,
    Offset a,
    Offset b,
    Color color,
    double coreWidth,
  ) {
    canvas.drawLine(
      a,
      b,
      Paint()
        ..color = _grayBorderColor
        ..strokeWidth = coreWidth + 2 * _borderMargin
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawLine(
      a,
      b,
      Paint()
        ..color = Colors.white
        ..strokeWidth = coreWidth + 2 * _whiteBorderWidth
        ..strokeCap = StrokeCap.round,
    );
    canvas.drawLine(
      a,
      b,
      Paint()
        ..color = color
        ..strokeWidth = coreWidth
        ..strokeCap = StrokeCap.round,
    );
  }

  /// Draws a filled path with the same border treatment, approximated by
  /// stroking the path's own outline at increasing widths before the final
  /// fill — the outward half of each stroke is what ends up visible.
  static void _paintBorderedPath(Canvas canvas, Path path, Color color) {
    canvas.drawPath(
      path,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeJoin = StrokeJoin.round
        ..strokeCap = StrokeCap.round
        ..strokeWidth = 2 * _borderMargin
        ..color = _grayBorderColor,
    );
    canvas.drawPath(
      path,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeJoin = StrokeJoin.round
        ..strokeCap = StrokeCap.round
        ..strokeWidth = 2 * _whiteBorderWidth
        ..color = Colors.white,
    );
    canvas.drawPath(path, Paint()..style = PaintingStyle.fill..color = color);
  }

  /// Draws both pieces on one canvas — used by the live draft overlay,
  /// which (unlike two separate map markers) fully controls its own canvas
  /// and can rotate the arrow independently of the always-upright badge in
  /// a single paint call. [rotationDegrees] only ever affects the arrow.
  static void paintCombined(
    Canvas canvas,
    Offset center,
    Color color, {
    double rotationDegrees = 0,
    bool hasDirection = true,
  }) {
    if (hasDirection) {
      paintArrow(canvas, center, color, rotationDegrees);
    }
    paintIcon(canvas, center, color);
  }
}

/// `flutter_map` `Marker.child` for the always-upright badge — pair with
/// `Marker(rotate: true, ...)` so it's counter-rotated against the map's own
/// rotation. See [SpeedCameraMarkerIcons] for why this and
/// [SpeedCameraArrowMarker] are two separate markers.
class SpeedCameraIconMarker extends StatelessWidget {
  const SpeedCameraIconMarker({super.key, required this.color});

  final Color color;

  static const double size = SpeedCameraMarkerIcons.iconSize;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(size: const Size(size, size), painter: _IconPainter(color));
  }
}

class _IconPainter extends CustomPainter {
  const _IconPainter(this.color);

  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    SpeedCameraMarkerIcons.paintIcon(canvas, Offset(size.width / 2, size.height / 2), color);
  }

  @override
  bool shouldRepaint(_IconPainter oldDelegate) => oldDelegate.color != color;
}

/// `flutter_map` `Marker.child` for the rotating direction arrow — pair with
/// `Marker(rotate: false, ...)` (the default) so it turns together with the
/// map, the ground-plane behavior [rotationDegrees] (a true compass
/// bearing) needs. Anchored at the bottom-center via
/// `Marker(alignment: Alignment.topCenter, ...)`, matching
/// [SpeedCameraMarkerIcons.arrowHeight]'s bottom-anchored geometry.
class SpeedCameraArrowMarker extends StatelessWidget {
  const SpeedCameraArrowMarker({super.key, required this.color, required this.rotationDegrees});

  final Color color;
  final double rotationDegrees;

  static const double width = SpeedCameraMarkerIcons.arrowWidth;
  static const double height = SpeedCameraMarkerIcons.arrowHeight;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: const Size(width, height),
      painter: _ArrowPainter(color, rotationDegrees),
    );
  }
}

class _ArrowPainter extends CustomPainter {
  const _ArrowPainter(this.color, this.rotationDegrees);

  final Color color;
  final double rotationDegrees;

  @override
  void paint(Canvas canvas, Size size) {
    // The camera's actual position sits at the bottom-center of this widget
    // (see the marker's `Alignment.topCenter` in MapScreen) — draw the arrow
    // pointing straight up from there before rotation is applied.
    SpeedCameraMarkerIcons.paintArrow(canvas, Offset(size.width / 2, size.height), color, rotationDegrees);
  }

  @override
  bool shouldRepaint(_ArrowPainter oldDelegate) =>
      oldDelegate.color != color || oldDelegate.rotationDegrees != rotationDegrees;
}
