import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:location/location.dart' as loc;

import '../../core/utils/geo.dart';
import '../../core/widgets/user_avatar.dart';
import '../../data/models/speed_camera.dart';
import '../../data/providers.dart';
import '../../data/services/osm_auth_service.dart';
import '../../data/services/osm_edit_service.dart';
import '../settings/settings_screen.dart';
import 'dot_marker_icons.dart';
import 'draft_marker_painter.dart';
import 'map_style_config.dart';
import 'map_viewport.dart';
import 'marker_style.dart';
import 'speed_camera_marker_icons.dart';

const List<int> _speedLimitPresets = [30, 50, 70, 90, 110, 130];

/// Below this zoom, cameras render as a small colored dot instead of the
/// full camera glyph — with clustering disabled, this is what keeps a
/// zoomed-out, camera-dense view legible and cheap to draw.
const double _dotZoomThreshold = 10;

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
    this.existingSource,
    this.existingStatus,
    this.canPublishToOsm = false,
  });

  final LatLng position;
  final double direction;
  final int limit;
  final String? existingId;
  final SpeedCameraSource? existingSource;
  final SpeedCameraStatus? existingStatus;

  /// True only when editing an existing `source: 'user'`, `status: 'pending'`
  /// report and the user has an OpenStreetMap account linked
  /// (features.md 4) — computed once in `_startDraft`.
  final bool canPublishToOsm;

  bool get isNew => existingId == null;

  _CameraDraft copyWith({LatLng? position, double? direction}) {
    return _CameraDraft(
      position: position ?? this.position,
      direction: direction ?? this.direction,
      limit: limit,
      existingId: existingId,
      existingSource: existingSource,
      existingStatus: existingStatus,
      canPublishToOsm: canPublishToOsm,
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
  _CameraDraft? _draft;
  Offset? _draftScreenPosition;
  PersistentBottomSheetController? _sheetController;
  double _currentZoom = 14;
  bool _syncReminderDismissed = false;

  List<SpeedCamera> _allCameras = [];
  Set<Marker> _markers = {};
  bool _showDots = false;

  /// Last center for which a Firestore query was issued — used to suppress
  /// re-queries when the user barely moves the map.
  MapViewport? _lastQueriedViewport;

  @override
  void initState() {
    super.initState();
    _resolveInitialPosition();
  }

  /// Rebuilds every camera marker directly (no clustering) — dots
  /// below [_dotZoomThreshold], full glyphs above it. Called whenever the
  /// camera list changes, whenever a draft edit starts/ends, and whenever
  /// the zoom crosses the dot threshold.
  Future<void> _rebuildMarkers() async {
    final draft = _draft;
    final cameras = draft == null
        ? _allCameras
        : _allCameras.where((c) => c.id != draft.existingId).toList();

    final markers = await Future.wait(cameras.map(_buildMarkerForCamera));
    if (!mounted) return;
    setState(() => _markers = markers.toSet());
  }

  Future<Marker> _buildMarkerForCamera(SpeedCamera camera) async {
    final color = markerColorFor(camera);
    if (_showDots) {
      return Marker(
        markerId: MarkerId(camera.id),
        position: LatLng(camera.lat, camera.lng),
        icon: await DotMarkerIcons.forColor(color),
        onTap: () => _onMarkerTap(camera),
      );
    }
    return Marker(
      markerId: MarkerId(camera.id),
      position: LatLng(camera.lat, camera.lng),
      icon: await SpeedCameraMarkerIcons.forColor(color, hasDirection: camera.direction != null),
      anchor: SpeedCameraMarkerIcons.anchor,
      rotation: camera.direction?.toDouble() ?? 0,
      onTap: () => _onMarkerTap(camera),
    );
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

    // Skip the re-query if the new center is still well inside the already-
    // prefetched area (prefetch = 3× the visible radius).  We only issue a new
    // query when the user has moved far enough that the prefetch buffer shrinks
    // to less than one visible-radius worth of margin.
    final last = _lastQueriedViewport;
    if (last != null) {
      final movedMeters = haversineDistanceMeters(
        last.latitude, last.longitude, centerLat, centerLng,
      );
      // Prefetched radius = last.radiusMeters * 3; visible radius = radiusMeters.
      // Remaining buffer = prefetched – moved – visible.  Re-query when < 0.
      final remainingBuffer = last.radiusMeters * (viewportPrefetchMultiplier - 1) - movedMeters;
      if (remainingBuffer >= radiusMeters) return;
    }

    final newViewport = MapViewport(latitude: centerLat, longitude: centerLng, radiusMeters: radiusMeters);
    _lastQueriedViewport = newViewport;
    ref.read(mapViewportProvider.notifier).update(newViewport);
  }

  void _onCameraMove(CameraPosition position) {
    _currentZoom = position.zoom;
    final shouldShowDots = _currentZoom < _dotZoomThreshold;
    if (shouldShowDots != _showDots) {
      _showDots = shouldShowDots;
      _rebuildMarkers();
    }
    if (_draft != null) {
      _syncDraftScreenPosition();
    }
  }

  void _onCameraIdle() {
    _refreshViewport();
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

    final isPendingUserReport =
        existing != null &&
        existing.source == SpeedCameraSource.user &&
        existing.status == SpeedCameraStatus.pending;
    final osmLinked = isPendingUserReport ? await OsmAuthService().isLinked() : false;

    final screenCoordinate = await controller.getScreenCoordinate(position);
    if (!mounted) return;
    final ratio = MediaQuery.of(context).devicePixelRatio;

    setState(() {
      _draft = _CameraDraft(
        position: position,
        direction: (existing?.direction ?? 0).toDouble(),
        limit: existing?.limit ?? _speedLimitPresets[1],
        existingId: existing?.id,
        existingSource: existing?.source,
        existingStatus: existing?.status,
        canPublishToOsm: isPendingUserReport && osmLinked,
      );
      _draftScreenPosition = Offset(screenCoordinate.x / ratio, screenCoordinate.y / ratio);
    });
    _rebuildMarkers();

    _sheetController = _scaffoldKey.currentState!.showBottomSheet(
      (context) => _DraftSheet(
        initialLimit: _draft!.limit,
        onSave: _saveDraft,
        onCancel: _closeDraft,
        onDelete: _draft!.isNew ? null : _deleteDraft,
        onPublishToOsm: _draft!.canPublishToOsm ? _publishToOsm : null,
      ),
    );
    _sheetController!.closed.then((_) {
      if (mounted) {
        setState(() {
          _draft = null;
          _draftScreenPosition = null;
        });
        _rebuildMarkers();
      }
    });
  }

  void _closeDraft() {
    _sheetController?.close();
  }

  /// Saves the current draft (new report or edit) and returns the
  /// resulting document id — shared by "Salva" and "Pubblica su
  /// OpenStreetMap" (which saves first, then publishes what was saved).
  Future<String?> _persistDraft(int limit) async {
    final draft = _draft;
    final uid = ref.read(authStateProvider).value?.uid;
    if (draft == null || uid == null) return null;

    final repository = ref.read(speedCameraRepositoryProvider);
    final geohash = encodeGeohash(draft.position.latitude, draft.position.longitude, precision: 9);
    final direction = draft.direction.round();

    if (draft.isNew) {
      return repository.addCamera(
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
    }
    await repository.updateCamera(
      draft.existingId!,
      lat: draft.position.latitude,
      lng: draft.position.longitude,
      limit: limit,
      direction: direction,
      geohash: geohash,
    );
    return draft.existingId!;
  }

  Future<void> _saveDraft(int limit) async {
    await _persistDraft(limit);
    _closeDraft();
  }

  /// features.md 4: promotes a pending user report to `approved` by first
  /// saving it, then publishing it as a new node on production OpenStreetMap
  /// via a changeset.
  Future<void> _publishToOsm(int limit) async {
    final draft = _draft;
    if (draft == null) return;
    try {
      final id = await _persistDraft(limit);
      if (id == null) return;
      final camera = SpeedCamera(
        id: id,
        lat: draft.position.latitude,
        lng: draft.position.longitude,
        limit: limit,
        direction: draft.direction.round(),
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.user,
        geohash: '',
      );
      await OsmEditService().publishCamera(camera);
      await ref.read(speedCameraRepositoryProvider).updateStatus(id, SpeedCameraStatus.approved);
      _closeDraft();
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text('Pubblicazione OSM non riuscita: $e')));
      }
    }
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
    ref.listen(nearbyCamerasProvider, (previous, next) {
      final cameras = next.value;
      if (cameras == null) return;
      _allCameras = cameras;
      _rebuildMarkers();
    });
    final markers = _markers;
    final draft = _draft;
    final syncMetadata = ref.watch(syncMetadataProvider).value;
    final showSyncReminder =
        !_syncReminderDismissed && draft == null && (syncMetadata?.isStale() ?? false);

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
            mapType: MapStyleConfig.mapType,
            style: MapStyleConfig.styleJson,
            zoomControlsEnabled: MapStyleConfig.zoomControlsEnabled,
            zoomGesturesEnabled: MapStyleConfig.zoomGesturesEnabled,
            compassEnabled: MapStyleConfig.compassEnabled,
            mapToolbarEnabled: MapStyleConfig.mapToolbarEnabled,
            buildingsEnabled: MapStyleConfig.buildingsEnabled,
            indoorViewEnabled: MapStyleConfig.indoorViewEnabled,
            trafficEnabled: MapStyleConfig.trafficEnabled,
            liteModeEnabled: MapStyleConfig.liteModeEnabled,
            myLocationEnabled: MapStyleConfig.myLocationEnabled,
            myLocationButtonEnabled: MapStyleConfig.myLocationButtonEnabled,
            rotateGesturesEnabled: MapStyleConfig.rotateGesturesEnabled,
            tiltGesturesEnabled: MapStyleConfig.tiltGesturesEnabled,
            scrollGesturesEnabled: draft == null,
            onMapCreated: (controller) {
              _controller = controller;
              _refreshViewport();
            },
            onCameraMove: _onCameraMove,
            onCameraIdle: _onCameraIdle,
            onLongPress: (position) => _startDraft(position: position),
          ),
          if (draft != null) _buildDraftOverlay(),
          if (showSyncReminder)
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: _SyncReminderBanner(
                onSync: () {
                  setState(() => _syncReminderDismissed = true);
                  Navigator.of(context).push(
                    MaterialPageRoute(builder: (context) => const SettingsScreen()),
                  );
                },
                onDismiss: () => setState(() => _syncReminderDismissed = true),
              ),
            ),
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

/// features.md 2.2 Smart Reminder: shown when the global OSM sync is more
/// than 7 days old (or has never run) — dismissing it is per-session only,
/// it comes back next time the map screen is rebuilt while still stale.
class _SyncReminderBanner extends StatelessWidget {
  const _SyncReminderBanner({required this.onSync, required this.onDismiss});

  final VoidCallback onSync;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      bottom: false,
      child: Material(
        color: Theme.of(context).colorScheme.secondaryContainer,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'I dati degli autovelox potrebbero non essere aggiornati. '
                  'Sincronizza ora con OpenStreetMap.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              TextButton(onPressed: onSync, child: const Text('Sincronizza')),
              IconButton(
                icon: const Icon(Icons.close),
                iconSize: 18,
                onPressed: onDismiss,
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _DraftSheet extends StatefulWidget {
  const _DraftSheet({
    required this.initialLimit,
    required this.onSave,
    required this.onCancel,
    this.onDelete,
    this.onPublishToOsm,
  });

  final int initialLimit;
  final ValueChanged<int> onSave;
  final VoidCallback onCancel;
  final VoidCallback? onDelete;

  /// Non-null only when editing an own pending report with OpenStreetMap
  /// linked (features.md 4) — takes the currently selected limit, like
  /// [onSave].
  final ValueChanged<int>? onPublishToOsm;

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
            const Text('Limite di velocità'),
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
            const SizedBox(height: 8),
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
            if (widget.onPublishToOsm != null) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: () => widget.onPublishToOsm!(_limit),
                  icon: const Icon(Icons.public),
                  label: const Text('Pubblica su OpenStreetMap'),
                ),
              ),
            ],
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
        ],
      ),
    );
  }
}
