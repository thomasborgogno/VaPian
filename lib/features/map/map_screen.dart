import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/speed_camera.dart';
import '../../data/providers.dart';

/// Placeholder home screen for Fase 0: proves the Firestore wiring end to
/// end (auth -> repository -> stream) before the real map UI lands in Fase 1.
class MapScreen extends ConsumerWidget {
  const MapScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final user = ref.watch(authStateProvider).value;
    final camerasAsync = ref.watch(_nearbyCamerasProvider);

    return Scaffold(
      appBar: AppBar(
        title: const Text('Autovelox App'),
        actions: [
          IconButton(
            icon: const Icon(Icons.logout),
            onPressed: () => ref.read(authServiceProvider).signOut(),
          ),
        ],
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(user?.isAnonymous == true ? 'Accesso come ospite' : 'Accesso con Google'),
            const SizedBox(height: 8),
            Text('uid: ${user?.uid ?? '-'}'),
            const SizedBox(height: 24),
            camerasAsync.when(
              data: (cameras) => Text(
                '${cameras.length} autovelox nel raggio di test (Roma centro, 20km)',
              ),
              loading: () => const CircularProgressIndicator(),
              error: (err, _) => Text('Errore Firestore: $err'),
            ),
          ],
        ),
      ),
    );
  }
}

final _nearbyCamerasProvider = StreamProvider.autoDispose<List<SpeedCamera>>((ref) {
  return ref.watch(speedCameraRepositoryProvider).watchNear(41.9028, 12.4964, 20000);
});
