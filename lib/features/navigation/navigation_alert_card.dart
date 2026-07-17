import 'package:flutter/material.dart';

import '../../data/models/speed_camera.dart';
import 'driving_alert_state.dart';

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
  });

  final DrivingAlertState state;
  final VoidCallback onReport;
  final VoidCallback onIgnore;
  final VoidCallback onCycleView;
  final VoidCallback onExit;

  @override
  Widget build(BuildContext context) {
    final camera = state.camera;
    final canIgnore = camera != null && camera.source == SpeedCameraSource.openstreetmap;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          children: [
            Row(
              children: [
                _RoundIconButton(icon: Icons.arrow_back, tooltip: 'Esci', onPressed: onExit),
                const Spacer(),
                _RoundIconButton(
                  icon: Icons.swap_horiz,
                  tooltip: 'Cambia vista',
                  onPressed: onCycleView,
                ),
              ],
            ),
            if (camera != null && state.withinRange) ...[
              const SizedBox(height: 12),
              _AlertCard(camera: camera, distanceMeters: state.distanceMeters, overLimit: state.overLimit),
            ],
            const Spacer(),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed: onReport,
                    icon: const Icon(Icons.add_a_photo_outlined),
                    label: const Text('Segnala Velox'),
                  ),
                ),
                if (canIgnore) ...[
                  const SizedBox(width: 12),
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: onIgnore,
                      icon: const Icon(Icons.visibility_off_outlined),
                      label: const Text('Ignora Velox'),
                    ),
                  ),
                ],
              ],
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
  const _RoundIconButton({required this.icon, required this.tooltip, required this.onPressed});

  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Theme.of(context).colorScheme.surface.withAlpha(230),
      shape: const CircleBorder(),
      child: IconButton(icon: Icon(icon), tooltip: tooltip, onPressed: onPressed),
    );
  }
}
