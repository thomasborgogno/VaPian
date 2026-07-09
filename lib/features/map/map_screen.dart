import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:location/location.dart' as loc;

import '../../core/utils/geo.dart';
import '../../data/models/speed_camera.dart';
import '../../data/providers.dart';
import 'map_viewport.dart';
import 'marker_style.dart';

const List<int> _speedLimitPresets = [30, 50, 70, 90, 110, 130];

class MapScreen extends ConsumerStatefulWidget {
  const MapScreen({super.key});

  @override
  ConsumerState<MapScreen> createState() => _MapScreenState();
}

class _MapScreenState extends ConsumerState<MapScreen> {
  GoogleMapController? _controller;
  LatLng _initialPosition = LatLng(
    MapViewport.initial.latitude,
    MapViewport.initial.longitude,
  );

  @override
  void initState() {
    super.initState();
    _resolveInitialPosition();
  }

  Future<void> _resolveInitialPosition() async {
    final position = await _currentDevicePosition();
    if (position == null || !mounted) return;
    setState(() => _initialPosition = position);
    _controller?.animateCamera(CameraUpdate.newLatLng(position));
  }

  Future<LatLng?> _currentDevicePosition() async {
    final location = loc.Location();
    try {
      if (!await location.serviceEnabled()) {
        if (!await location.requestService()) return null;
      }
      var permission = await location.hasPermission();
      if (permission == loc.PermissionStatus.denied) {
        permission = await location.requestPermission();
      }
      if (permission != loc.PermissionStatus.granted &&
          permission != loc.PermissionStatus.grantedLimited) {
        return null;
      }
      final data = await location.getLocation();
      if (data.latitude == null || data.longitude == null) return null;
      return LatLng(data.latitude!, data.longitude!);
    } catch (_) {
      return null;
    }
  }

  Future<void> _refreshViewport() async {
    final controller = _controller;
    if (controller == null) return;
    final bounds = await controller.getVisibleRegion();
    final centerLat = (bounds.northeast.latitude + bounds.southwest.latitude) / 2;
    final centerLng = (bounds.northeast.longitude + bounds.southwest.longitude) / 2;
    final radiusMeters = haversineDistanceMeters(
      centerLat,
      centerLng,
      bounds.northeast.latitude,
      bounds.northeast.longitude,
    );
    ref.read(mapViewportProvider.notifier).update(
      MapViewport(latitude: centerLat, longitude: centerLng, radiusMeters: radiusMeters),
    );
  }

  Future<void> _placeCamera(LatLng position) async {
    final uid = ref.read(authStateProvider).value?.uid;
    if (uid == null) return;
    final result = await showModalBottomSheet<_CameraDraft>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _AddCameraSheet(position: position),
    );
    if (result == null) return;

    final geohash = encodeGeohash(position.latitude, position.longitude, precision: 9);
    await ref.read(speedCameraRepositoryProvider).addCamera(
      SpeedCamera(
        id: '',
        lat: position.latitude,
        lng: position.longitude,
        limit: result.limit,
        direction: result.direction,
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.user,
        geohash: geohash,
        createdBy: uid,
      ),
    );
  }

  Future<void> _showCameraDetails(SpeedCamera camera) async {
    final uid = ref.read(authStateProvider).value?.uid;
    final isOwner = uid != null && camera.createdBy == uid;

    await showModalBottomSheet<void>(
      context: context,
      builder: (context) => _CameraDetailsSheet(
        camera: camera,
        canDelete: isOwner,
        onDelete: () async {
          await ref.read(speedCameraRepositoryProvider).deleteCamera(camera.id);
          if (context.mounted) Navigator.of(context).pop();
        },
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final camerasAsync = ref.watch(nearbyCamerasProvider);

    final markers = camerasAsync.maybeWhen(
      data: (cameras) => cameras
          .map(
            (camera) => Marker(
              markerId: MarkerId(camera.id),
              position: LatLng(camera.lat, camera.lng),
              icon: BitmapDescriptor.defaultMarkerWithHue(markerHueFor(camera)),
              rotation: camera.direction?.toDouble() ?? 0,
              onTap: () => _showCameraDetails(camera),
            ),
          )
          .toSet(),
      orElse: () => <Marker>{},
    );

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
      body: GoogleMap(
        initialCameraPosition: CameraPosition(target: _initialPosition, zoom: 14),
        markers: markers,
        myLocationEnabled: true,
        myLocationButtonEnabled: true,
        onMapCreated: (controller) {
          _controller = controller;
          _refreshViewport();
        },
        onCameraIdle: _refreshViewport,
        onLongPress: _placeCamera,
      ),
      floatingActionButton: FloatingActionButton(
        onPressed: () async {
          final center = await _controller?.getVisibleRegion();
          if (center == null) return;
          final target = LatLng(
            (center.northeast.latitude + center.southwest.latitude) / 2,
            (center.northeast.longitude + center.southwest.longitude) / 2,
          );
          _placeCamera(target);
        },
        tooltip: 'Segnala autovelox',
        child: const Icon(Icons.add),
      ),
    );
  }
}

class _CameraDraft {
  const _CameraDraft({required this.limit, required this.direction});

  final int limit;
  final int direction;
}

class _AddCameraSheet extends StatefulWidget {
  const _AddCameraSheet({required this.position});

  final LatLng position;

  @override
  State<_AddCameraSheet> createState() => _AddCameraSheetState();
}

class _AddCameraSheetState extends State<_AddCameraSheet> {
  int _limit = 90;
  double _direction = 0;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Nuovo autovelox', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 16),
          const Text('Limite di velocità'),
          Wrap(
            spacing: 8,
            children: _speedLimitPresets
                .map(
                  (preset) => ChoiceChip(
                    label: Text('$preset'),
                    selected: _limit == preset,
                    onSelected: (_) => setState(() => _limit = preset),
                  ),
                )
                .toList(),
          ),
          const SizedBox(height: 16),
          Text('Direzione: ${_direction.round()}°'),
          Row(
            children: [
              Transform.rotate(
                angle: _direction * 3.1415926535 / 180,
                child: const Icon(Icons.navigation),
              ),
              Expanded(
                child: Slider(
                  value: _direction,
                  min: 0,
                  max: 359,
                  divisions: 359,
                  label: '${_direction.round()}°',
                  onChanged: (value) => setState(() => _direction = value),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              Expanded(
                child: OutlinedButton(
                  onPressed: () => Navigator.of(context).pop(),
                  child: const Text('Annulla'),
                ),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: FilledButton(
                  onPressed: () => Navigator.of(context).pop(
                    _CameraDraft(limit: _limit, direction: _direction.round()),
                  ),
                  child: const Text('Salva'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _CameraDetailsSheet extends StatelessWidget {
  const _CameraDetailsSheet({
    required this.camera,
    required this.canDelete,
    required this.onDelete,
  });

  final SpeedCamera camera;
  final bool canDelete;
  final VoidCallback onDelete;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Autovelox ${camera.limit} km/h', style: Theme.of(context).textTheme.titleLarge),
          const SizedBox(height: 8),
          Text('Sorgente: ${camera.source == SpeedCameraSource.openstreetmap ? 'OpenStreetMap' : 'Utente'}'),
          Text('Stato: ${camera.status == SpeedCameraStatus.approved ? 'Approvato' : 'In verifica'}'),
          if (camera.direction != null) Text('Direzione: ${camera.direction}°'),
          if (canDelete) ...[
            const SizedBox(height: 16),
            FilledButton.tonalIcon(
              onPressed: () => _confirmDelete(context),
              icon: const Icon(Icons.delete_outline),
              label: const Text('Elimina'),
            ),
          ],
        ],
      ),
    );
  }

  Future<void> _confirmDelete(BuildContext context) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Elimina autovelox'),
        content: const Text('Vuoi eliminare definitivamente questa segnalazione?'),
        actions: [
          TextButton(onPressed: () => Navigator.of(context).pop(false), child: const Text('Annulla')),
          TextButton(onPressed: () => Navigator.of(context).pop(true), child: const Text('Elimina')),
        ],
      ),
    );
    if (confirmed == true) onDelete();
  }
}
