import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/speed_camera.dart';
import '../../data/providers.dart';

class MapViewport {
  const MapViewport({
    required this.latitude,
    required this.longitude,
    required this.radiusMeters,
  });

  // Rome city center, used until the device location / first camera move
  // resolves a real one.
  static const MapViewport initial = MapViewport(
    latitude: 41.9028,
    longitude: 12.4964,
    radiusMeters: 5000,
  );

  final double latitude;
  final double longitude;
  final double radiusMeters;
}

class MapViewportNotifier extends Notifier<MapViewport> {
  @override
  MapViewport build() => MapViewport.initial;

  void update(MapViewport viewport) => state = viewport;
}

final mapViewportProvider = NotifierProvider<MapViewportNotifier, MapViewport>(
  MapViewportNotifier.new,
);

/// How many times larger than the visible radius to query — cameras this far
/// outside the screen are preloaded so that panning never reveals a blank area.
const double viewportPrefetchMultiplier = 3.0;

final nearbyCamerasProvider = StreamProvider.autoDispose<List<SpeedCamera>>((ref) {
  final viewport = ref.watch(mapViewportProvider);
  return ref
      .watch(speedCameraRepositoryProvider)
      .watchNear(
        viewport.latitude,
        viewport.longitude,
        viewport.radiusMeters * viewportPrefetchMultiplier,
      );
});
