import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';
import 'package:location/location.dart' as loc;

import '../../core/utils/geo.dart';
import '../../core/widgets/user_avatar.dart';
import '../../data/models/speed_camera.dart';
import '../../data/models/user_settings.dart';
import '../../data/providers.dart';
import '../../data/services/osm_auth_service.dart';
import '../../data/services/osm_edit_service.dart';
import '../settings/settings_screen.dart';
import 'camera_details_sheet.dart';
import 'camera_draft.dart';
import 'camera_filter.dart';
import 'dot_marker_icons.dart';
import 'draft_overlay.dart';
import 'draft_sheet.dart';
import 'filter_sheet.dart';
import 'map_style_config.dart';
import 'map_style_toggle.dart';
import 'map_viewport.dart';
import 'marker_style.dart';
import 'pending_cameras_banner.dart';
import 'pending_review_sheet.dart';
import 'speed_camera_marker_icons.dart';
import 'speed_limit_presets.dart';
import 'sync_reminder_banner.dart';

/// Below this zoom, cameras render as a small colored dot instead of the
/// full camera glyph — with clustering disabled, this is what keeps a
/// zoomed-out, camera-dense view legible and cheap to draw.
const double _dotZoomThreshold = 10;

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

class _MapScreenState extends ConsumerState<MapScreen> {
  final _scaffoldKey = GlobalKey<ScaffoldState>();
  GoogleMapController? _controller;
  LatLng _initialPosition = LatLng(
    MapViewport.initial.latitude,
    MapViewport.initial.longitude,
  );
  CameraDraft? _draft;
  Offset? _draftScreenPosition;
  PersistentBottomSheetController? _sheetController;
  double _currentZoom = 14;
  bool _syncReminderDismissed = false;

  List<SpeedCamera> _allCameras = [];
  Set<Marker> _markers = {};
  bool _showDots = false;
  CameraFilter _filter = const CameraFilter();

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
  /// camera list changes, whenever a draft edit starts/ends, whenever the
  /// zoom crosses the dot threshold, and whenever the filter changes.
  Future<void> _rebuildMarkers() async {
    final ignoredIds = ref.read(ignoredCameraIdsProvider).value ?? {};
    final draft = _draft;
    final showIgnoredOnly = _filter.showIgnoredOnly;

    final cameras = _allCameras
        .where((c) => showIgnoredOnly == ignoredIds.contains(c.id))
        .where((c) => showIgnoredOnly || _filter.matchesLimit(c))
        .where((c) => draft == null || c.id != draft.existingId)
        .toList();

    final markers = await Future.wait(
      cameras.map((c) => _buildMarkerForCamera(c, grayedOut: showIgnoredOnly)),
    );
    if (!mounted) return;
    setState(() => _markers = markers.toSet());
  }

  Future<Marker> _buildMarkerForCamera(
    SpeedCamera camera, {
    bool grayedOut = false,
  }) async {
    final color = grayedOut ? speedCameraGray : markerColorFor(camera);
    if (_showDots) {
      return Marker(
        markerId: MarkerId(camera.id),
        position: LatLng(camera.lat, camera.lng),
        icon: await DotMarkerIcons.forColor(color),
      );
    }
    return Marker(
      markerId: MarkerId(camera.id),
      position: LatLng(camera.lat, camera.lng),
      icon: await SpeedCameraMarkerIcons.forColor(
        color,
        hasDirection: camera.direction != null,
      ),
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
    final centerLat =
        (bounds.northeast.latitude + bounds.southwest.latitude) / 2;
    final centerLng =
        (bounds.northeast.longitude + bounds.southwest.longitude) / 2;
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
        last.latitude,
        last.longitude,
        centerLat,
        centerLng,
      );
      // Prefetched radius = last.radiusMeters * 3; visible radius = radiusMeters.
      // Remaining buffer = prefetched – moved – visible.  Re-query when < 0.
      final remainingBuffer =
          last.radiusMeters * (viewportPrefetchMultiplier - 1) - movedMeters;
      if (remainingBuffer >= radiusMeters) return;
    }

    final newViewport = MapViewport(
      latitude: centerLat,
      longitude: centerLng,
      radiusMeters: radiusMeters,
    );
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
    final screenCoordinate = await controller.getScreenCoordinate(
      draft.position,
    );
    if (!mounted || _draft == null) return;
    final ratio = MediaQuery.of(context).devicePixelRatio;
    setState(() {
      _draftScreenPosition = Offset(
        screenCoordinate.x / ratio,
        screenCoordinate.y / ratio,
      );
    });
  }

  Future<void> _startDraft({
    required LatLng position,
    SpeedCamera? existing,
  }) async {
    if (_draft != null) return;
    final controller = _controller;
    if (controller == null) return;

    final isUserReportNotPending =
        existing != null &&
        existing.source == SpeedCameraSource.user &&
        existing.status != SpeedCameraStatus.pending;
    final isOsmSourced =
        existing != null && existing.source == SpeedCameraSource.openstreetmap;
    final osmEditNodeId = isOsmSourced ? existing.osmId : null;
    final osmLinked = (isUserReportNotPending || osmEditNodeId != null)
        ? await OsmAuthService().isLinked()
        : false;

    final screenCoordinate = await controller.getScreenCoordinate(position);
    if (!mounted) return;
    final ratio = MediaQuery.of(context).devicePixelRatio;

    setState(() {
      _draft = CameraDraft(
        position: position,
        direction: (existing?.direction ?? 0).toDouble(),
        limit: existing?.limit ?? speedLimitPresets[1],
        existingId: existing?.id,
        existingSource: existing?.source,
        existingStatus: existing?.status,
        existingOsmNodeId: existing?.osmNodeId,
        existingOsmNodeVersion: existing?.osmNodeVersion,
        canPublishToOsm:
            isUserReportNotPending && osmLinked && existing.osmNodeId == null,
        canEditOsm: osmEditNodeId != null && osmLinked,
        osmEditNodeId: osmEditNodeId,
      );
      _draftScreenPosition = Offset(
        screenCoordinate.x / ratio,
        screenCoordinate.y / ratio,
      );
    });
    _rebuildMarkers();

    final isPending = existing?.status == SpeedCameraStatus.pending;
    _sheetController = _scaffoldKey.currentState!.showBottomSheet(
      (context) => DraftSheet(
        initialLimit: _draft!.limit,
        onSave: _saveDraft,
        onCancel: _closeDraft,
        onDelete: (_draft!.isNew || isOsmSourced) ? null : _deleteDraft,
        onApprove: isPending ? _approveDraft : null,
        onPublishToOsm: _draft!.canPublishToOsm ? _publishToOsm : null,
        onSaveOsmEdit: _draft!.canEditOsm ? _saveOsmEdit : null,
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
    final geohash = encodeGeohash(
      draft.position.latitude,
      draft.position.longitude,
      precision: 9,
    );
    final direction = draft.direction.round();

    if (draft.isNew) {
      return repository.addCamera(
        SpeedCamera(
          id: '',
          lat: draft.position.latitude,
          lng: draft.position.longitude,
          limit: limit,
          direction: direction,
          status: SpeedCameraStatus.approved,
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

  Future<void> _approveDraft(int limit) async {
    final id = await _persistDraft(limit);
    if (id == null) return;
    await ref
        .read(speedCameraRepositoryProvider)
        .updateStatus(id, SpeedCameraStatus.approved);
    _closeDraft();
  }

  /// features.md 4: after an explicit confirmation (publishing writes real,
  /// public data), promotes a user report to `approved` by first saving it,
  /// then publishing it as a new node on production OpenStreetMap via a
  /// changeset.
  Future<void> _publishToOsm(int limit) async {
    final draft = _draft;
    if (draft == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Pubblica su OpenStreetMap'),
        content: const Text(
          'Stai per pubblicare questo autovelox su OpenStreetMap, una mappa '
          'pubblica consultata da migliaia di persone. Prima di procedere, '
          'assicurati che la posizione e la direzione indicate sulla mappa '
          'siano corrette e verificate: una volta pubblicato, il punto sarà '
          'visibile a tutti gli utenti di OpenStreetMap.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Annulla'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Pubblica'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

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
      final nodeId = await OsmEditService().publishCamera(camera);
      await ref
          .read(speedCameraRepositoryProvider)
          .markPublishedToOsm(id, nodeId);
      _closeDraft();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('Autovelox pubblicato su OpenStreetMap.'),
          ),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Pubblicazione OSM non riuscita: $e')),
        );
      }
    }
  }

  /// Updates `maxspeed`/`direction` on a camera's existing live
  /// OpenStreetMap node (features.md 4) — after an explicit confirmation,
  /// since this writes real, public data. Also refreshes the local Firestore
  /// cache so the map reflects the change without waiting for the next sync.
  Future<void> _saveOsmEdit(int limit) async {
    final draft = _draft;
    final nodeId = draft?.osmEditNodeId;
    if (draft == null || nodeId == null) return;

    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Modifica dati su OpenStreetMap'),
        content: const Text(
          'Stai per modificare un nodo pubblico su OpenStreetMap, consultato '
          'da migliaia di persone. Assicurati che il limite di velocità e la '
          'direzione indicati siano corretti prima di confermare.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Annulla'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Conferma'),
          ),
        ],
      ),
    );
    if (confirmed != true || !mounted) return;

    try {
      final direction = draft.direction.round();
      await OsmEditService().updateNode(nodeId, limit: limit, direction: direction);
      final existingId = draft.existingId;
      if (existingId != null) {
        await ref
            .read(speedCameraRepositoryProvider)
            .updateCamera(existingId, limit: limit, direction: direction);
      }
      _closeDraft();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Dati aggiornati su OpenStreetMap.')),
        );
      }
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('Modifica OSM non riuscita: $e')),
        );
      }
    }
  }

  Future<void> _deleteDraft() async {
    final id = _draft?.existingId;
    if (id == null) return;

    final osmNodeId = _draft?.existingOsmNodeId;
    if (osmNodeId != null) {
      try {
        await OsmEditService().deleteNode(
          osmNodeId,
          lat: _draft!.position.latitude,
          lng: _draft!.position.longitude,
          version: _draft?.existingOsmNodeVersion ?? 1,
        );
      } catch (e) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text('Eliminazione da OpenStreetMap non riuscita: $e'),
            ),
          );
        }
        return;
      }
    }

    await ref.read(speedCameraRepositoryProvider).deleteCamera(id);
    _closeDraft();
  }

  /// Converts a screen-space drag delta into a lat/lng delta using the
  /// current zoom's Web Mercator ground resolution — synchronous, so
  /// dragging never waits on the platform view.
  ({double dLat, double dLng}) _screenDeltaToLatLngDelta(
    Offset delta,
    double atLatitude,
  ) {
    final metersPerPixel =
        _groundResolutionAtZoom0 *
        cos(atLatitude * pi / 180) /
        pow(2, _currentZoom);
    final groundDx = delta.dx * metersPerPixel;
    final groundDy = delta.dy * metersPerPixel;
    final dLat = -groundDy / _metersPerDegreeLatitude;
    final dLng =
        groundDx / (_metersPerDegreeLatitude * cos(atLatitude * pi / 180));
    return (dLat: dLat, dLng: dLng);
  }

  void _onMarkerPanUpdate(DragUpdateDetails details) {
    final draft = _draft;
    final screenPosition = _draftScreenPosition;
    if (draft == null || screenPosition == null) return;
    // Editing an OpenStreetMap-sourced camera only allows correcting its
    // limit/direction (see _saveOsmEdit) — its position is OSM's, not ours
    // to move.
    if (draft.existingSource == SpeedCameraSource.openstreetmap) return;
    final delta = _screenDeltaToLatLngDelta(
      details.delta,
      draft.position.latitude,
    );
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
    // The handle sits opposite the travel direction (see DraftOverlay), so
    // its screen angle is offset by 180° from draft.direction.
    final thetaRad = draft.direction * pi / 180 + pi;
    // Tangential component of the drag, perpendicular to the current
    // handle direction, turned into an angle change around the marker.
    final tangential =
        details.delta.dx * cos(thetaRad) + details.delta.dy * sin(thetaRad);
    final deltaAngleDeg = tangential / DraftOverlay.handleDistancePx * 180 / pi;
    final newDirection = ((draft.direction + deltaAngleDeg) % 360 + 360) % 360;
    setState(() => _draft = draft.copyWith(direction: newDirection));
  }

  Future<void> _onMarkerTap(SpeedCamera camera) async {
    final uid = ref.read(authStateProvider).value?.uid;
    final isOwner = uid != null && camera.createdBy == uid;
    if (isOwner) {
      await _startDraft(position: LatLng(camera.lat, camera.lng), existing: camera);
      return;
    }
    // Cameras with a live OpenStreetMap node (Overpass-imported, or a user
    // report already published) can have their maxspeed/direction corrected
    // directly on OSM once the user is signed in (features.md 4).
    if (camera.osmId != null && await OsmAuthService().isLinked()) {
      if (!mounted) return;
      await _startDraft(position: LatLng(camera.lat, camera.lng), existing: camera);
      return;
    }
    if (mounted) await _showReadOnlyDetails(camera);
  }

  Future<void> _showReadOnlyDetails(SpeedCamera camera) {
    final uid = ref.read(authStateProvider).value?.uid;
    final ignoredIds = ref.read(ignoredCameraIdsProvider).value ?? {};
    final isIgnored = ignoredIds.contains(camera.id);
    final isOsm = camera.source == SpeedCameraSource.openstreetmap;

    VoidCallback? ignoreCallback;
    VoidCallback? unignoreCallback;
    if (isOsm && uid != null) {
      final safeUid = uid;
      ignoreCallback = () {
        Navigator.of(context).pop();
        ref.read(blacklistRepositoryProvider).ignoreCamera(safeUid, camera.id);
      };
      if (isIgnored) {
        unignoreCallback = () {
          Navigator.of(context).pop();
          ref
              .read(blacklistRepositoryProvider)
              .unignoreCamera(safeUid, camera.id);
        };
      }
    }

    return showModalBottomSheet<void>(
      context: context,
      builder: (context) => CameraDetailsSheet(
        camera: camera,
        isIgnored: isIgnored,
        onIgnore: ignoreCallback,
        onUnignore: unignoreCallback,
      ),
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

  void _showFilterSheet() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => FilterSheet(
        initialFilter: _filter,
        onFilterChanged: (filter) {
          setState(() => _filter = filter);
          _rebuildMarkers();
        },
      ),
    );
  }

  void _showPendingReview() {
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => PendingReviewSheet(
        onEdit: (camera) {
          Navigator.of(context).pop();
          _startDraft(
            position: LatLng(camera.lat, camera.lng),
            existing: camera,
          );
        },
      ),
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
    ref.listen(ignoredCameraIdsProvider, (previous, next) => _rebuildMarkers());

    final markers = _markers;
    final draft = _draft;
    final syncMetadata = ref.watch(syncMetadataProvider).value;
    final showSyncReminder =
        !_syncReminderDismissed &&
        draft == null &&
        (syncMetadata?.isStale() ?? false);
    final pendingCount =
        (ref.watch(pendingOwnCamerasProvider).value?.length ?? 0) +
        (ref.watch(queuedIgnoresProvider).value?.length ?? 0);
    final showPendingBanner = draft == null && pendingCount > 0;

    return Scaffold(
      key: _scaffoldKey,
      appBar: AppBar(
        title: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Image.asset('assets/icon/foreground.png', width: 32, height: 32),
            const SizedBox(width: 8),
            const Text('VaPian! - Maps'),
          ],
        ),
        actions: [
          IconButton(
            icon: _filter.isActive
                ? const Badge(smallSize: 8, child: Icon(Icons.filter_list))
                : const Icon(Icons.filter_list),
            tooltip: _filter.isActive ? 'Filtro attivo' : 'Filtra autovelox',
            onPressed: _showFilterSheet,
          ),
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
            initialCameraPosition: CameraPosition(
              target: _initialPosition,
              zoom: _currentZoom,
            ),
            markers: markers,
            mapType: MapStyleConfig.mapTypeFor(
              ref.watch(userSettingsProvider).value?.mapStyle ??
                  MapStyle.normal,
            ),
            style: MapStyleConfig.styleJson,
            // Sposta i controlli nativi (bottone "la mia posizione", zoom)
            // sopra il FloatingActionButton "+", che altrimenti li
            // sovrappone essendo entrambi ancorati in basso a destra.
            padding: const EdgeInsets.only(bottom: 88),
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
          if (draft != null && _draftScreenPosition != null)
            DraftOverlay(
              draft: draft,
              markerPosition: _draftScreenPosition!,
              onMarkerPanUpdate: _onMarkerPanUpdate,
              onHandlePanUpdate: _onHandlePanUpdate,
            ),
          Positioned(
            right: 60,
            top: 12,
            child: SafeArea(child: MapStyleToggle()),
          ),
          if (showSyncReminder || showPendingBanner)
            Positioned(
              top: 0,
              left: 0,
              right: 0,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (showSyncReminder)
                    SyncReminderBanner(
                      onSync: () {
                        setState(() => _syncReminderDismissed = true);
                        Navigator.of(context).push(
                          MaterialPageRoute(
                            builder: (context) => const SettingsScreen(),
                          ),
                        );
                      },
                      onDismiss: () =>
                          setState(() => _syncReminderDismissed = true),
                    ),
                  if (showPendingBanner)
                    PendingCamerasBanner(
                      count: pendingCount,
                      onReview: _showPendingReview,
                    ),
                ],
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
