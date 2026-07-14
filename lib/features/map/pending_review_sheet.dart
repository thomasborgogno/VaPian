import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/speed_camera.dart';
import '../../data/providers.dart';

/// Bottom sheet listing all pending own cameras so the user can approve,
/// edit, or delete them (features.md 2.1 "Banner di Conferma Android Auto").
class PendingReviewSheet extends ConsumerWidget {
  const PendingReviewSheet({super.key, required this.onEdit});

  final void Function(SpeedCamera) onEdit;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final cameras = ref.watch(pendingOwnCamerasProvider).value ?? [];
    final uid = ref.watch(authStateProvider).value?.uid;

    return DraggableScrollableSheet(
      initialChildSize: 0.5,
      maxChildSize: 0.9,
      minChildSize: 0.3,
      expand: false,
      builder: (_, scrollController) => SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
              child: cameras.isEmpty
                  ? Text(
                      'Tutte le segnalazioni revisionate.',
                      style: Theme.of(context).textTheme.bodyMedium,
                    )
                  : Text(
                      'Segnalazioni in attesa (${cameras.length})',
                      style: Theme.of(context).textTheme.titleMedium,
                    ),
            ),
            Expanded(
              child: cameras.isEmpty
                  ? const SizedBox.shrink()
                  : ListView.builder(
                      controller: scrollController,
                      itemCount: cameras.length,
                      itemBuilder: (_, i) {
                        final camera = cameras[i];
                        return ListTile(
                          leading: const Icon(Icons.speed),
                          title: Text(
                            camera.limit != null ? '${camera.limit} km/h' : 'Limite sconosciuto',
                          ),
                          subtitle: Text(
                            '${camera.lat.toStringAsFixed(5)}, ${camera.lng.toStringAsFixed(5)}',
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              IconButton(
                                icon: const Icon(Icons.check_circle_outline),
                                tooltip: 'Approva',
                                onPressed: uid == null
                                    ? null
                                    : () => ref
                                        .read(speedCameraRepositoryProvider)
                                        .updateStatus(camera.id, SpeedCameraStatus.approved),
                              ),
                              IconButton(
                                icon: const Icon(Icons.edit_outlined),
                                tooltip: 'Modifica sulla mappa',
                                onPressed: () => onEdit(camera),
                              ),
                              IconButton(
                                icon: const Icon(Icons.delete_outline),
                                tooltip: 'Elimina',
                                onPressed: uid == null
                                    ? null
                                    : () => ref
                                        .read(speedCameraRepositoryProvider)
                                        .deleteCamera(camera.id),
                              ),
                            ],
                          ),
                        );
                      },
                    ),
            ),
            Padding(
              padding: const EdgeInsets.all(16),
              child: SizedBox(
                width: double.infinity,
                child: OutlinedButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Chiudi'),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
