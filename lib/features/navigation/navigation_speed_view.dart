import 'dart:math';

import 'package:flutter/material.dart';

import 'navigation_alert_controller.dart';

/// Navigation-mode "animazione di velocità" view (variant 3): no map — a
/// virtual car with radial motion lines whose speed/intensity scales with
/// the current speed, plus the numeric speedometer and camera-proximity
/// text when an alert is active.
class NavigationSpeedView extends StatefulWidget {
  const NavigationSpeedView({super.key, required this.controller});

  final NavigationAlertController controller;

  @override
  State<NavigationSpeedView> createState() => _NavigationSpeedViewState();
}

class _NavigationSpeedViewState extends State<NavigationSpeedView>
    with SingleTickerProviderStateMixin {
  late final AnimationController _ticker;

  @override
  void initState() {
    super.initState();
    _ticker = AnimationController(vsync: this, duration: const Duration(seconds: 2))..repeat();
    widget.controller.addListener(_onControllerChanged);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    _ticker.dispose();
    super.dispose();
  }

  void _onControllerChanged() => setState(() {});

  @override
  Widget build(BuildContext context) {
    final state = widget.controller.state;
    final speedKmh = state.speedKmh;

    return Container(
      color: Colors.black,
      child: Stack(
        alignment: Alignment.center,
        children: [
          AnimatedBuilder(
            animation: _ticker,
            builder: (context, _) => CustomPaint(
              size: Size.infinite,
              painter: _SpeedLinesPainter(progress: _ticker.value, speedKmh: speedKmh),
            ),
          ),
          Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.directions_car, color: Colors.white, size: 72),
              const SizedBox(height: 8),
              Text(
                speedKmh.round().toString(),
                style: const TextStyle(
                  color: Colors.white,
                  fontSize: 64,
                  fontWeight: FontWeight.bold,
                ),
              ),
              const Text('km/h', style: TextStyle(color: Colors.white70, fontSize: 18)),
              if (state.camera != null && state.withinRange) ...[
                const SizedBox(height: 24),
                Text(
                  state.camera!.limit != null
                      ? 'Autovelox: limite ${state.camera!.limit} km/h'
                      : 'Autovelox in avvicinamento',
                  style: const TextStyle(color: Colors.amber, fontSize: 20),
                ),
                if (state.distanceMeters != null)
                  Text(
                    'Tra ${state.distanceMeters!.round()} m',
                    style: const TextStyle(color: Colors.amber, fontSize: 16),
                  ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

/// Radial motion lines: count and outward speed scale with [speedKmh], each
/// looping outward as [progress] cycles 0→1 (driven by a repeating
/// `AnimationController`).
class _SpeedLinesPainter extends CustomPainter {
  _SpeedLinesPainter({required this.progress, required this.speedKmh});

  final double progress;
  final double speedKmh;

  static const int _maxLines = 24;
  static const double _maxSpeedForFullEffect = 150;

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final maxRadius = size.shortestSide * 0.7;
    final intensity = (speedKmh / _maxSpeedForFullEffect).clamp(0.0, 1.0);
    if (intensity <= 0) return;

    final lineCount = (4 + intensity * (_maxLines - 4)).round();
    final paint = Paint()
      ..color = Colors.white.withAlpha((80 * intensity).round())
      ..strokeWidth = 2
      ..strokeCap = StrokeCap.round;

    for (var i = 0; i < lineCount; i++) {
      final angle = (2 * pi / lineCount) * i;
      final phase = (progress + i / lineCount) % 1.0;
      final innerRadius = maxRadius * 0.15 + phase * maxRadius * 0.85;
      final outerRadius = innerRadius + maxRadius * 0.12 * intensity;
      final direction = Offset(cos(angle), sin(angle));
      canvas.drawLine(center + direction * innerRadius, center + direction * outerRadius, paint);
    }
  }

  @override
  bool shouldRepaint(covariant _SpeedLinesPainter oldDelegate) {
    return oldDelegate.progress != progress || oldDelegate.speedKmh != speedKmh;
  }
}
