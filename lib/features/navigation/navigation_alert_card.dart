import 'package:flutter/material.dart';

import '../../core/utils/device_form_factor.dart';
import '../../data/models/speed_camera.dart';
import 'driving_alert_state.dart';
import 'navigation_theme.dart';

/// Overlay shared by all 3 navigation-mode views: the proximity alert card
/// (when a camera is within range) plus the action button row — "Ignora
/// Velox" (only for an active OpenStreetMap-sourced camera), "Segnala
/// Velox" (always), cycle-view, and exit.
class NavigationAlertCard extends StatelessWidget {
  const NavigationAlertCard({
    super.key,
    required this.state,
    required this.onReport,
    required this.onIgnore,
    required this.onCycleView,
    required this.onExit,
    required this.colors,
  });

  final DrivingAlertState state;
  final VoidCallback onReport;
  final VoidCallback onIgnore;
  final VoidCallback onCycleView;
  final VoidCallback onExit;
  final NavigationButtonColors colors;

  @override
  Widget build(BuildContext context) {
    final camera = state.camera;
    final canIgnore = camera != null && camera.source == SpeedCameraSource.openstreetmap;
    // Bigger, easier-to-hit targets on tablets — the expected in-car mount,
    // glanced at rather than looked at closely while driving.
    final isTablet = isTabletFormFactor(context);
    final buttonPadding = isTablet
        ? const EdgeInsets.symmetric(horizontal: 32, vertical: 24)
        : const EdgeInsets.symmetric(horizontal: 20, vertical: 14);
    final buttonTextStyle = TextStyle(fontSize: isTablet ? 20 : 14);
    final buttonIconSize = isTablet ? 32.0 : 24.0;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Row(
              children: [
                _RoundIconButton(
                  icon: Icons.arrow_back,
                  tooltip: 'Esci',
                  onPressed: onExit,
                  colors: colors,
                ),
                const Spacer(),
                _RoundIconButton(
                  icon: Icons.swap_horiz,
                  tooltip: 'Cambia vista',
                  onPressed: onCycleView,
                  colors: colors,
                ),
              ],
            ),
            if (camera != null && state.withinRange) ...[
              const SizedBox(height: 12),
              _AlertCard(camera: camera, distanceMeters: state.distanceMeters, overLimit: state.overLimit),
            ],
            const Spacer(),
            Align(
              alignment: Alignment.bottomLeft,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: colors.filledBackground,
                      foregroundColor: colors.filledForeground,
                      padding: buttonPadding,
                      textStyle: buttonTextStyle,
                    ),
                    onPressed: onReport,
                    icon: Icon(Icons.add_a_photo_outlined, size: buttonIconSize),
                    label: const Text('Segnala Velox'),
                  ),
                  if (canIgnore) ...[
                    const SizedBox(height: 12),
                    OutlinedButton.icon(
                      style: OutlinedButton.styleFrom(
                        foregroundColor: colors.outlinedForeground,
                        side: BorderSide(color: colors.outlinedBorder),
                        padding: buttonPadding,
                        textStyle: buttonTextStyle,
                      ),
                      onPressed: onIgnore,
                      icon: Icon(Icons.visibility_off_outlined, size: buttonIconSize),
                      label: const Text('Ignora Velox'),
                    ),
                  ],
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _AlertCard extends StatelessWidget {
  const _AlertCard({required this.camera, required this.distanceMeters, required this.overLimit});

  final SpeedCamera camera;
  final double? distanceMeters;
  final bool overLimit;

  @override
  Widget build(BuildContext context) {
    final color = overLimit ? Colors.red : Colors.amber;
    final distance = distanceMeters ?? 0;
    final distanceText =
        distance >= 1000 ? '${(distance / 1000).round()} km' : '${distance.round()} m';
    return Card(
      color: color.withAlpha(230),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.speed, color: Colors.black, size: 36),
            const SizedBox(width: 12),
            Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(
                  camera.limit != null ? '${camera.limit} km/h' : 'Limite sconosciuto',
                  style: const TextStyle(
                    color: Colors.black,
                    fontWeight: FontWeight.bold,
                    fontSize: 20,
                  ),
                ),
                Text('Tra $distanceText', style: const TextStyle(color: Colors.black87)),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _RoundIconButton extends StatelessWidget {
  const _RoundIconButton({
    required this.icon,
    required this.tooltip,
    required this.onPressed,
    required this.colors,
  });

  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;
  final NavigationButtonColors colors;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: colors.roundIconBackground.withAlpha(230),
      shape: const CircleBorder(),
      child: IconButton(
        icon: Icon(icon, color: colors.roundIconForeground),
        tooltip: tooltip,
        onPressed: onPressed,
      ),
    );
  }
}
