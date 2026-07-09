import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:location/location.dart' as loc;

import '../../core/utils/geo.dart';
import '../../core/widgets/user_avatar.dart';
import '../../data/models/speed_camera.dart';
import '../../data/providers.dart';
import '../settings/settings_screen.dart';
import 'draft_marker_painter.dart';
import 'map_viewport.dart';
import 'marker_style.dart';
import 'speed_camera_marker_icons.dart';

const List<int> _speedLimitPresets = [30, 50, 70, 90, 110, 130];

/// Fixed on-screen distance (logical px) between a draft marker and its
/// direction handle — independent of zoom, so it always reads clearly.
const double _handleDistancePx = 70;
const double _handleDotRadius = 14;

/// Web Mercator ground resolution at zoom 0, tile size 256 — used to convert
/// a drag delta in screen pixels into a lat/lng delta without any async
/// round-trip to the map controller.
const double _groundResolutionAtZoom0 = 156543.03392;
const double _metersPerDegreeLatitude = 111320.0;

class MapScreen extends ConsumerStatefulWidget {
  const MapScreen({super.key});

  @override
  ConsumerState<MapScreen> createState() => _MapScreenState();
}

/// A speed camera being placed or edited directly on the map: position and
/// direction come from dragging the marker/handle overlay, only the speed
/// limit is picked from the bottom sheet.
class _CameraDraft {
  const _CameraDraft({
    required this.position,
    required this.direction,
    required this.limit,
    this.existingId,
  });

  final LatLng position;
  final double direction;
  final int limit;
  final String? existingId;

  bool get isNew => existingId == null;

  _CameraDraft copyWith({LatLng? position, double? direction}) {
    return _CameraDraft(
      position: position ?? this.position,
      direction: direction ?? this.direction,
      limit: limit,
      existingId: existingId,
    );
  }
}

class _MapScreenState extends ConsumerState<MapScreen> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();
  GoogleMapController? _controller;
  LatLng _initialPosition = LatLng(
    MapViewport.initial.latitude,
    MapViewport.initial.longitude,
  );
  final Map<Color, BitmapDescriptor> _cameraIcons = {};
  _CameraDraft? _draft;
  Offset? _draftScreenPosition;
  PersistentBottomSheetController? _sheetController;
  double _currentZoom = 14;

  @override
  void initState() {
    super.initState();
    _resolveInitialPosition();
    _loadIcons();
  }

  Future<void> _loadIcons() async {
    final results = await Future.wait([
      SpeedCameraMarkerIcons.forColor(speedCameraGreen),
      SpeedCameraMarkerIcons.forColor(speedCameraBlue),
      SpeedCameraMarkerIcons.forColor(speedCameraOrange),
    ]);
    if (!mounted) return;
    setState(() {
      _cameraIcons[speedCameraGreen] = results[0];
      _cameraIcons[speedCameraBlue] = results[1];
      _cameraIcons[speedCameraOrange] = results[2];
    });
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

  void _onCameraMove(CameraPosition position) {
    _currentZoom = position.zoom;
    if (_draft != null) {
      _syncDraftScreenPosition();
    }
  }

  /// Re-projects the draft's lat/lng anchor to screen coordinates — needed
  /// because pinch-zoom (still enabled while editing) changes the on-screen
  /// position of a point even though its geo position hasn't moved.
  Future<void> _syncDraftScreenPosition() async {
    final controller = _controller;
    final draft = _draft;
    if (controller == null || draft == null) return;
    final screenCoordinate = await controller.getScreenCoordinate(draft.position);
    if (!mounted || _draft == null) return;
    final ratio = MediaQuery.of(context).devicePixelRatio;
    setState(() {
      _draftScreenPosition = Offset(screenCoordinate.x / ratio, screenCoordinate.y / ratio);
    });
  }

  Future<void> _startDraft({required LatLng position, SpeedCamera? existing}) async {
    if (_draft != null) return;
    final controller = _controller;
    if (controller == null) return;

    final screenCoordinate = await controller.getScreenCoordinate(position);
    if (!mounted) return;
    final ratio = MediaQuery.of(context).devicePixelRatio;

    setState(() {
      _draft = _CameraDraft(
        position: position,
        direction: (existing?.direction ?? 0).toDouble(),
        limit: existing?.limit ?? _speedLimitPresets[1],
        existingId: existing?.id,
      );
      _draftScreenPosition = Offset(screenCoordinate.x / ratio, screenCoordinate.y / ratio);
    });

    _sheetController = _scaffoldKey.currentState!.showBottomSheet(
      (context) => _DraftSheet(
        initialLimit: _draft!.limit,
        onSave: _saveDraft,
        onCancel: _closeDraft,
        onDelete: _draft!.isNew ? null : _deleteDraft,
      ),
    );
    _sheetController!.closed.then((_) {
      if (mounted) {
        setState(() {
          _draft = null;
          _draftScreenPosition = null;
        });
      }
    });
  }

  void _closeDraft() {
    _sheetController?.close();
  }

  Future<void> _saveDraft(int limit) async {
    final draft = _draft;
    final uid = ref.read(authStateProvider).value?.uid;
    if (draft == null || uid == null) return;

    final repository = ref.read(speedCameraRepositoryProvider);
    final geohash = encodeGeohash(draft.position.latitude, draft.position.longitude, precision: 9);
    final direction = draft.direction.round();

    if (draft.isNew) {
      await repository.addCamera(
        SpeedCamera(
          id: '',
          lat: draft.position.latitude,
          lng: draft.position.longitude,
          limit: limit,
          direction: direction,
          status: SpeedCameraStatus.pending,
          source: SpeedCameraSource.user,
          geohash: geohash,
          createdBy: uid,
        ),
      );
    } else {
      await repository.updateCamera(
        draft.existingId!,
        lat: draft.position.latitude,
        lng: draft.position.longitude,
        limit: limit,
        direction: direction,
        geohash: geohash,
      );
    }
    _closeDraft();
  }

  Future<void> _deleteDraft() async {
    final id = _draft?.existingId;
    if (id == null) return;
    await ref.read(speedCameraRepositoryProvider).deleteCamera(id);
    _closeDraft();
  }

  /// Converts a screen-space drag delta into a lat/lng delta using the
  /// current zoom's Web Mercator ground resolution — synchronous, so
  /// dragging never waits on the platform view.
  ({double dLat, double dLng}) _screenDeltaToLatLngDelta(Offset delta, double atLatitude) {
    final metersPerPixel =
        _groundResolutionAtZoom0 * cos(atLatitude * pi / 180) / pow(2, _currentZoom);
    final groundDx = delta.dx * metersPerPixel;
    final groundDy = delta.dy * metersPerPixel;
    final dLat = -groundDy / _metersPerDegreeLatitude;
    final dLng = groundDx / (_metersPerDegreeLatitude * cos(atLatitude * pi / 180));
    return (dLat: dLat, dLng: dLng);
  }

  void _onMarkerPanUpdate(DragUpdateDetails details) {
    final draft = _draft;
    final screenPosition = _draftScreenPosition;
    if (draft == null || screenPosition == null) return;
    final delta = _screenDeltaToLatLngDelta(details.delta, draft.position.latitude);
    setState(() {
      _draftScreenPosition = screenPosition + details.delta;
      _draft = draft.copyWith(
        position: LatLng(
          draft.position.latitude + delta.dLat,
          draft.position.longitude + delta.dLng,
        ),
      );
    });
  }

  void _onHandlePanUpdate(DragUpdateDetails details) {
    final draft = _draft;
    if (draft == null) return;
    final thetaRad = draft.direction * pi / 180;
    // Tangential component of the drag, perpendicular to the current
    // handle direction, turned into an angle change around the marker.
    final tangential = details.delta.dx * cos(thetaRad) + details.delta.dy * sin(thetaRad);
    final deltaAngleDeg = tangential / _handleDistancePx * 180 / pi;
    final newDirection = ((draft.direction + deltaAngleDeg) % 360 + 360) % 360;
    setState(() => _draft = draft.copyWith(direction: newDirection));
  }

  void _onMarkerTap(SpeedCamera camera) {
    final uid = ref.read(authStateProvider).value?.uid;
    final isOwner = uid != null && camera.createdBy == uid;
    if (isOwner) {
      _startDraft(position: LatLng(camera.lat, camera.lng), existing: camera);
    } else {
      _showReadOnlyDetails(camera);
    }
  }

  Future<void> _showReadOnlyDetails(SpeedCamera camera) {
    return showModalBottomSheet<void>(
      context: context,
      builder: (context) => _CameraDetailsSheet(camera: camera),
    );
  }

  Future<void> _onFabPressed() async {
    if (_draft != null) return;
    final controller = _controller;
    if (controller == null) return;
    final bounds = await controller.getVisibleRegion();
    final center = LatLng(
      (bounds.northeast.latitude + bounds.southwest.latitude) / 2,
      (bounds.northeast.longitude + bounds.southwest.longitude) / 2,
    );
    await _startDraft(position: center);
  }

  Set<Marker> _buildMarkers(List<SpeedCamera> cameras) {
    final draft = _draft;
    return {
      for (final camera in cameras)
        if (draft == null || draft.existingId != camera.id)
          Marker(
            markerId: MarkerId(camera.id),
            position: LatLng(camera.lat, camera.lng),
            icon: _cameraIcons[markerColorFor(camera)] ?? BitmapDescriptor.defaultMarker,
            anchor: SpeedCameraMarkerIcons.anchor,
            rotation: camera.direction?.toDouble() ?? 0,
            onTap: () => _onMarkerTap(camera),
          ),
    };
  }

  Widget _buildDraftOverlay() {
    final draft = _draft!;
    final markerPosition = _draftScreenPosition!;
    final theta = draft.direction * pi / 180;
    final handlePosition = markerPosition +
        Offset(_handleDistancePx * sin(theta), -_handleDistancePx * cos(theta));

    return Stack(
      children: [
        Positioned(
          left: markerPosition.dx - SpeedCameraMarkerIcons.width / 2,
          top: markerPosition.dy - SpeedCameraMarkerIcons.anchor.dy * SpeedCameraMarkerIcons.height,
          child: GestureDetector(
            onPanUpdate: _onMarkerPanUpdate,
            child: SizedBox(
              width: SpeedCameraMarkerIcons.width,
              height: SpeedCameraMarkerIcons.height,
              child: CustomPaint(
                painter: DraftMarkerPainter(color: speedCameraOrange, rotationDegrees: draft.direction),
              ),
            ),
          ),
        ),
        Positioned(
          left: handlePosition.dx - _handleDotRadius,
          top: handlePosition.dy - _handleDotRadius,
          child: GestureDetector(
            onPanUpdate: _onHandlePanUpdate,
            child: Container(
              width: _handleDotRadius * 2,
              height: _handleDotRadius * 2,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                color: Colors.white,
                border: Border.all(color: Colors.deepPurple, width: 3),
              ),
              child: Center(
                child: Container(
                  width: _handleDotRadius,
                  height: _handleDotRadius,
                  decoration: const BoxDecoration(shape: BoxShape.circle, color: Colors.deepPurple),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final camerasAsync = ref.watch(nearbyCamerasProvider);
    final markers = _buildMarkers(camerasAsync.value ?? const []);
    final draft = _draft;

    return Scaffold(
      key: _scaffoldKey,
      appBar: AppBar(
        title: const Text('Autovelox App'),
        actions: [
          IconButton(
            icon: UserAvatar(user: ref.watch(authStateProvider).value),
            tooltip: 'Impostazioni',
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (context) => const SettingsScreen()),
            ),
          ),
        ],
      ),
      body: Stack(
        children: [
          GoogleMap(
            initialCameraPosition: CameraPosition(target: _initialPosition, zoom: _currentZoom),
            markers: markers,
            myLocationEnabled: true,
            myLocationButtonEnabled: true,
            rotateGesturesEnabled: false,
            tiltGesturesEnabled: false,
            scrollGesturesEnabled: draft == null,
            onMapCreated: (controller) {
              _controller = controller;
              _refreshViewport();
            },
            onCameraMove: _onCameraMove,
            onCameraIdle: _refreshViewport,
            onLongPress: (position) => _startDraft(position: position),
          ),
          if (draft != null) _buildDraftOverlay(),
        ],
      ),
      floatingActionButton: draft == null
          ? FloatingActionButton(
              onPressed: _onFabPressed,
              tooltip: 'Segnala autovelox',
              child: const Icon(Icons.add),
            )
          : null,
    );
  }
}

class _DraftSheet extends StatefulWidget {
  const _DraftSheet({
    required this.initialLimit,
    required this.onSave,
    required this.onCancel,
    this.onDelete,
  });

  final int initialLimit;
  final ValueChanged<int> onSave;
  final VoidCallback onCancel;
  final VoidCallback? onDelete;

  @override
  State<_DraftSheet> createState() => _DraftSheetState();
}

class _DraftSheetState extends State<_DraftSheet> {
  late int _limit = widget.initialLimit;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: _speedLimitPresets
                    .map(
                      (preset) => Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          label: Text('$preset'),
                          selected: _limit == preset,
                          onSelected: (_) => setState(() => _limit = preset),
                        ),
                      ),
                    )
                    .toList(),
              ),
            ),
            const SizedBox(height: 16),
            Row(
              children: [
                if (widget.onDelete != null) ...[
                  IconButton(
                    onPressed: widget.onDelete,
                    icon: const Icon(Icons.delete_outline),
                    tooltip: 'Elimina',
                  ),
                  const SizedBox(width: 8),
                ],
                Expanded(
                  child: OutlinedButton(
                    onPressed: widget.onCancel,
                    child: const Text('Annulla'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: () => widget.onSave(_limit),
                    child: const Text('Salva'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _CameraDetailsSheet extends StatelessWidget {
  const _CameraDetailsSheet({required this.camera});

  final SpeedCamera camera;

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
          Text(
            'Sorgente: ${camera.source == SpeedCameraSource.openstreetmap ? 'OpenStreetMap' : 'Utente'}',
          ),
          Text(
            'Stato: ${camera.status == SpeedCameraStatus.approved ? 'Approvato' : 'In verifica'}',
          ),
          if (camera.direction != null) Text('Direzione: ${camera.direction}°'),
        ],
      ),
    );
  }
}
