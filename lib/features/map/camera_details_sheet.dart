import 'package:flutter/material.dart';

import '../../data/models/speed_camera.dart';

/// Read-only bottom sheet shown when tapping a camera the current user
/// doesn't own — plus, for OpenStreetMap-sourced cameras, the
/// ignore/unignore action (features.md 2.1 "Ignora Velox di OSM").
class CameraDetailsSheet extends StatelessWidget {
  const CameraDetailsSheet({
    super.key,
    required this.camera,
    required this.isIgnored,
    this.onIgnore,
    this.onUnignore,
  });

  final SpeedCamera camera;
  final bool isIgnored;
  final VoidCallback? onIgnore;
  final VoidCallback? onUnignore;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            camera.limit != null ? 'Autovelox ${camera.limit} km/h' : 'Autovelox (limite non disponibile)',
            style: Theme.of(context).textTheme.titleLarge,
          ),
          const SizedBox(height: 8),
          Text(
            'Sorgente: ${camera.source == SpeedCameraSource.openstreetmap ? 'OpenStreetMap' : 'Utente'}',
          ),
          Text(
            'Stato: ${camera.status == SpeedCameraStatus.approved ? 'Approvato' : 'In verifica'}',
          ),
          if (camera.direction != null) Text('Direzione: ${camera.direction}°'),
          if (onIgnore != null || onUnignore != null) ...[
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: isIgnored
                  ? OutlinedButton.icon(
                      onPressed: onUnignore,
                      icon: const Icon(Icons.visibility),
                      label: const Text('Togli dagli ignorati'),
                    )
                  : OutlinedButton.icon(
                      onPressed: onIgnore,
                      icon: const Icon(Icons.visibility_off),
                      label: const Text('Ignora questo velox'),
                    ),
            ),
          ],
        ],
      ),
    );
  }
}
