import 'dart:async';
import 'dart:math';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:latlong2/latlong.dart';
import 'package:location/location.dart' as loc;

import '../../core/constants/italian_macro_zones.dart';
import '../../core/constants/tile_source_config.dart';
import '../../core/utils/geo.dart';
import '../../core/widgets/user_avatar.dart';
import '../../data/models/speed_camera.dart';
import '../../data/providers.dart';
import '../../data/services/osm_auth_service.dart';
import '../../data/services/osm_edit_service.dart';
import '../navigation/navigation_screen.dart';
import '../settings/settings_screen.dart';
import 'camera_details_sheet.dart';
import 'camera_draft.dart';
import 'camera_filter.dart';
import 'current_location_marker.dart';
import 'dot_marker_icons.dart';
import 'draft_overlay.dart';
import 'draft_sheet.dart';
import 'filter_sheet.dart';
import 'map_style_config.dart';
import 'map_viewport.dart';
import 'marker_style.dart';
import 'offline_vector_tile_layer.dart';
import 'osm_attribution.dart';
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
  final MapController _controller = MapController();
  StreamSubscription<MapEvent>? _mapEventSubscription;
  LatLng _initialPosition = LatLng(
    MapViewport.initial.latitude,
    MapViewport.initial.longitude,
  );
  CameraDraft? _draft;
  Offset? _draftScreenPosition;
  PersistentBottomSheetController? _sheetController;
  double _currentZoom = 14;
  double _currentBearing = 0;
  bool _syncReminderDismissed = false;

  /// Downloaded zone whose local vector tiles cover the current viewport —
  /// null when there isn't one, meaning the online raster tiles
  /// (`TileSourceConfig`) should render instead. Updated in
  /// [_refreshViewport].
  String? _activeMbtilesZone;
  LatLng? _myLocation;
  StreamSubscription<loc.LocationData>? _locationSubscription;

  List<SpeedCamera> _allCameras = [];
  List<Marker> _markers = [];
  bool _showDots = false;
  CameraFilter _filter = const CameraFilter();

  /// Last center for which a Firestore query was issued — used to suppress
  /// re-queries when the user barely moves the map.
  MapViewport? _lastQueriedViewport;

  @override
  void initState() {
    super.initState();
    _resolveInitialPosition();
    _watchDeviceLocation();
    // Kicks off loading the camera pictogram well before any marker/draft
    // overlay needs to paint it synchronously.
    SpeedCameraMarkerIcons.ensureIconLoaded();
    _mapEventSubscription = _controller.mapEventStream.listen(_onMapEvent);
  }

  @override
  void dispose() {
    _mapEventSubscription?.cancel();
    _locationSubscription?.cancel();
    super.dispose();
  }

  /// Rebuilds every camera marker directly (no clustering) — dots below
  /// [_dotZoomThreshold], full glyphs above it. Called whenever the camera
  /// list changes, whenever a draft edit starts/ends, whenever the zoom
  /// crosses the dot threshold, and whenever the filter changes.
  void _rebuildMarkers() {
    final ignoredIds = ref.read(ignoredCameraIdsProvider).value ?? {};
    final draft = _draft;
    final showIgnoredOnly = _filter.showIgnoredOnly;

    final cameras = _allCameras
        .where((c) => showIgnoredOnly == ignoredIds.contains(c.id))
        .where((c) => showIgnoredOnly || _filter.matchesLimit(c))
        .where((c) => draft == null || c.id != draft.existingId)
        .toList();

    setState(() {
      _markers = cameras
          .expand((c) => _buildMarkersForCamera(c, grayedOut: showIgnoredOnly))
          .toList();
    });
  }

  /// One camera renders as either a single dot marker (zoomed out), or —
  /// above the dot threshold — two independent markers at the same
  /// position: an always-upright badge (`rotate: true`, counter-rotated
  /// against the map so it never turns) and, only when a direction is
  /// known, a separately-rotating arrow (`rotate: false`, so it turns with
  /// the map — the ground-plane behavior its compass-bearing rotation
  /// needs). See `SpeedCameraMarkerIcons`'s class doc.
  List<Marker> _buildMarkersForCamera(
    SpeedCamera camera, {
    bool grayedOut = false,
  }) {
    final color = grayedOut ? speedCameraGray : markerColorFor(camera);
    final position = LatLng(camera.lat, camera.lng);

    if (_showDots) {
      return [
        Marker(
          point: position,
          rotate: true,
          width: DotMarker.diameter,
          height: DotMarker.diameter,
          child: DotMarker(color: color),
        ),
      ];
    }

    final travelDirection = camera.monitoredTravelDirectionDeg;
    final markers = <Marker>[
      if (travelDirection != null)
        Marker(
          point: position,
          alignment: Alignment.topCenter,
          width: SpeedCameraArrowMarker.width,
          height: SpeedCameraArrowMarker.height,
          child: SpeedCameraArrowMarker(
            color: color,
            rotationDegrees: travelDirection.toDouble(),
          ),
        ),
      Marker(
        point: position,
        rotate: true,
        width: SpeedCameraIconMarker.size,
        height: SpeedCameraIconMarker.size,
        child: GestureDetector(
          onTap: () => _onMarkerTap(camera),
          child: SpeedCameraIconMarker(color: color),
        ),
      ),
    ];
    return markers;
  }

  Future<void> _resolveInitialPosition() async {
    final position = await _currentDevicePosition();
    if (position == null || !mounted) return;
    setState(() => _initialPosition = position);
    _controller.move(position, _currentZoom);
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

  /// Feeds the small "my location" marker (no native blue dot without
  /// Google Maps) — best-effort: permission/service failures just mean the
  /// marker never appears, same as before.
  Future<void> _watchDeviceLocation() async {
    final location = loc.Location();
    try {
      if (!await location.serviceEnabled()) return;
      var permission = await location.hasPermission();
      if (permission == loc.PermissionStatus.denied) {
        permission = await location.requestPermission();
      }
      if (permission != loc.PermissionStatus.granted &&
          permission != loc.PermissionStatus.grantedLimited) {
        return;
      }
      _locationSubscription = location.onLocationChanged.listen((data) {
        if (!mounted || data.latitude == null || data.longitude == null) return;
        setState(() => _myLocation = LatLng(data.latitude!, data.longitude!));
      });
    } catch (_) {
      // Best-effort: no marker if location isn't available.
    }
  }

  void _refreshViewport() {
    final bounds = _controller.camera.visibleBounds;
    final centerLat = (bounds.north + bounds.south) / 2;
    final centerLng = (bounds.east + bounds.west) / 2;
    final radiusMeters = haversineDistanceMeters(
      centerLat,
      centerLng,
      bounds.north,
      bounds.east,
    );

    // Swaps to the zone's local vector tiles once the map settles inside a
    // downloaded zone, back to the online raster fallback otherwise —
    // checked here (on settle) rather than every frame, so the tile source
    // doesn't flicker mid-gesture.
    final zone = zoneContaining(centerLat, centerLng);
    final downloaded = ref.read(downloadedZonesProvider).value ?? const {};
    final activeZone = (zone != null && downloaded.contains(zone.name)) ? zone.name : null;
    if (activeZone != _activeMbtilesZone) {
      setState(() => _activeMbtilesZone = activeZone);
    }

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
    ref
        .read(syncServiceProvider)
        .pullNear(
          centerLat,
          centerLng,
          radiusMeters * viewportPrefetchMultiplier,
        );
  }

  void _onPositionChanged(MapCamera camera, bool hasGesture) {
    _currentZoom = camera.zoom;
    final shouldShowDots = _currentZoom < _dotZoomThreshold;
    final dotThresholdCrossed = shouldShowDots != _showDots;
    if (dotThresholdCrossed) _showDots = shouldShowDots;
    if (_draft != null) {
      // Rebuild so the draft overlay (marker glyph + rotate handle) picks up
      // the new bearing — it draws in screen space, so a map rotation
      // changes where it needs to sit even though the draft's own
      // lat/lng/direction haven't changed.
      setState(() => _currentBearing = camera.rotation);
      _syncDraftScreenPosition();
    } else {
      _currentBearing = camera.rotation;
    }
    if (dotThresholdCrossed) _rebuildMarkers();
  }

  void _onMapEvent(MapEvent event) {
    if (event is MapEventMoveEnd || event is MapEventRotateEnd) {
      _refreshViewport();
    }
  }

  /// Re-projects the draft's lat/lng anchor to screen coordinates — needed
  /// because pinch-zoom (still enabled while editing) changes the on-screen
  /// position of a point even though its geo position hasn't moved.
  void _syncDraftScreenPosition() {
    final draft = _draft;
    if (draft == null) return;
    final screenPoint = _controller.camera.latLngToScreenPoint(draft.position);
    setState(() {
      _draftScreenPosition = Offset(screenPoint.x, screenPoint.y);
    });
  }

  /// Re-centers the map so [position] (the marker just tapped) lands in the
  /// middle of the space still visible above the now-open edit bottom
  /// sheet, instead of the exact screen center — measured via [sheetKey]
  /// once the sheet has actually been laid out, since its height depends on
  /// its content (approve/publish buttons, ignore action, etc). Reuses
  /// [_screenDeltaToLatLngDelta] (already used for drag math) to convert
  /// the needed on-screen shift into a lat/lng offset.
  void _centerAboveBottomSheet(LatLng position, GlobalKey sheetKey) {
    final sheetHeight =
        (sheetKey.currentContext?.findRenderObject() as RenderBox?)
            ?.size
            .height;
    if (sheetHeight == null || sheetHeight <= 0) return;
    final delta = _screenDeltaToLatLngDelta(
      Offset(0, sheetHeight / 2),
      position.latitude,
    );
    final target = LatLng(
      position.latitude + delta.dLat,
      position.longitude + delta.dLng,
    );
    _controller.move(target, _currentZoom);
  }

  Future<void> _startDraft({
    required LatLng position,
    SpeedCamera? existing,
  }) async {
    if (_draft != null) return;

    final uid = ref.read(authStateProvider).value?.uid;
    final isOwner =
        existing != null && uid != null && existing.createdBy == uid;

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
    final canEditOsm = osmEditNodeId != null && osmLinked;
    // Not owned by this user and not editable via OSM login: only the local
    // "verso contrario" toggle (and, for OSM cameras, ignore/unignore) stay
    // interactive — position/limit/save/delete/approve/publish are all
    // disabled. Never true for a brand-new draft.
    final readOnly = existing != null && !isOwner && !canEditOsm;

    final ignoredIds = ref.read(ignoredCameraIdsProvider).value ?? const {};
    final isIgnored = existing != null && ignoredIds.contains(existing.id);
    VoidCallback? ignoreCallback;
    VoidCallback? unignoreCallback;
    if (isOsmSourced && uid != null) {
      final cameraId = existing.id;
      ignoreCallback = () {
        ref.read(blacklistRepositoryProvider).ignoreCamera(uid, cameraId);
        _closeDraft();
      };
      if (isIgnored) {
        unignoreCallback = () {
          ref.read(blacklistRepositoryProvider).unignoreCamera(uid, cameraId);
          _closeDraft();
        };
      }
    }

    if (!mounted) return;
    final screenPoint = _controller.camera.latLngToScreenPoint(position);

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
        canEditOsm: canEditOsm,
        osmEditNodeId: osmEditNodeId,
        readOnly: readOnly,
        hasKnownDirection: existing?.direction != null,
        directionReversed: existing?.directionReversed ?? false,
        originalLimit: existing?.limit,
        originalDirection: existing?.direction,
      );
      _draftScreenPosition = Offset(screenPoint.x, screenPoint.y);
    });
    _rebuildMarkers();

    final isPending = existing?.status == SpeedCameraStatus.pending;
    final sheetKey = GlobalKey();
    _sheetController = _scaffoldKey.currentState!.showBottomSheet(
      (context) => KeyedSubtree(
        key: sheetKey,
        child: DraftSheet(
          initialLimit: _draft!.limit,
          onSave: readOnly ? null : _saveDraft,
          limitEditable: !readOnly,
          onCancel: _closeDraft,
          onDelete: (_draft!.isNew || isOsmSourced || readOnly)
              ? null
              : _deleteDraft,
          onApprove: (isPending && !readOnly) ? _approveDraft : null,
          onPublishToOsm: _draft!.canPublishToOsm ? _publishToOsm : null,
          onSaveOsmEdit: _draft!.canEditOsm ? _saveOsmEdit : null,
          originalLimit: _draft!.originalLimit,
          originalDirection: _draft!.originalDirection,
          currentDirection: _draft!.direction,
          hasKnownDirection: _draft!.hasKnownDirection,
          directionReversed: _draft!.directionReversed,
          onDirectionReversedChanged: _draft!.hasKnownDirection
              ? _onDirectionReversedPreview
              : null,
          onSaveReversedOnly: (readOnly && _draft!.hasKnownDirection)
              ? _saveReversedOnly
              : null,
          isIgnored: isIgnored,
          onIgnore: ignoreCallback,
          onUnignore: unignoreCallback,
        ),
      ),
    );
    // Tapping an existing marker should bring it into the space actually
    // left free by the sheet, not the exact screen center — measure the
    // sheet once it's laid out, then re-center the map so `position` lands
    // in the middle of the area above it.
    if (existing != null) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        _centerAboveBottomSheet(position, sheetKey);
      });
    }
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
      directionReversed: draft.directionReversed,
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

  /// Live-previews the local-only "verso contrario" flag (the draft
  /// overlay's arrow) as the user toggles it in the sheet — doesn't persist
  /// anything; that only happens when a save action actually fires (they
  /// read the latest value back from `_draft`), so "Annulla" leaves the
  /// camera untouched and the toggle can be flipped back and forth freely.
  void _onDirectionReversedPreview(bool reversed) {
    final draft = _draft;
    if (draft == null) return;
    setState(() => _draft = draft.copyWith(directionReversed: reversed));
  }

  /// Saves just the "verso contrario" flag — the only persistable action in
  /// the edit sheet's read-only tier (not owned, no OpenStreetMap login),
  /// where none of the other save actions are available.
  Future<void> _saveReversedOnly() async {
    final draft = _draft;
    final id = draft?.existingId;
    if (draft == null || id == null) return;
    await ref
        .read(speedCameraRepositoryProvider)
        .updateDirectionReversed(id, draft.directionReversed);
    _closeDraft();
  }

  /// Updates `maxspeed`/`direction` on a camera's existing live
  /// OpenStreetMap node (features.md 4) — after an explicit confirmation,
  /// since this writes real, public data. Also refreshes the local store so
  /// the map reflects the change without waiting for the next sync.
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
      await OsmEditService().updateNode(
        nodeId,
        limit: limit,
        direction: direction,
      );
      final existingId = draft.existingId;
      if (existingId != null) {
        await ref
            .read(speedCameraRepositoryProvider)
            .updateCamera(
              existingId,
              limit: limit,
              direction: direction,
              directionReversed: draft.directionReversed,
            );
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
  /// dragging never waits on the platform view. Also rotates the delta by
  /// the current camera bearing first: with two-finger map rotation
  /// enabled, screen "up" is only "north" when bearing is 0.
  ({double dLat, double dLng}) _screenDeltaToLatLngDelta(
    Offset delta,
    double atLatitude,
  ) {
    final metersPerPixel =
        _groundResolutionAtZoom0 *
        cos(atLatitude * pi / 180) /
        pow(2, _currentZoom);
    final bearingRad = _currentBearing * pi / 180;
    final eastPixels = delta.dx * cos(bearingRad) - delta.dy * sin(bearingRad);
    final southPixels = delta.dx * sin(bearingRad) + delta.dy * cos(bearingRad);
    final groundDx = eastPixels * metersPerPixel;
    final groundDy = southPixels * metersPerPixel;
    final dLat = -groundDy / _metersPerDegreeLatitude;
    final dLng =
        groundDx / (_metersPerDegreeLatitude * cos(atLatitude * pi / 180));
    return (dLat: dLat, dLng: dLng);
  }

  void _onMarkerPanUpdate(DragUpdateDetails details) {
    final draft = _draft;
    final screenPosition = _draftScreenPosition;
    if (draft == null || screenPosition == null) return;
    if (draft.readOnly) return;
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
    if (draft == null || draft.readOnly) return;
    // The handle sits concordant with draft.direction (see DraftOverlay),
    // corrected for the map's own rotation, same as DraftOverlay's build().
    final thetaRad = (draft.direction + _currentBearing) * pi / 180;
    // Tangential component of the drag, perpendicular to the current
    // handle direction, turned into an angle change around the marker.
    final tangential =
        details.delta.dx * cos(thetaRad) + details.delta.dy * sin(thetaRad);
    final deltaAngleDeg = tangential / DraftOverlay.handleDistancePx * 180 / pi;
    final newDirection = ((draft.direction + deltaAngleDeg) % 360 + 360) % 360;
    setState(() => _draft = draft.copyWith(direction: newDirection));
    // The persistent bottom sheet's builder isn't re-invoked by this State's
    // own setState (per showBottomSheet's own docs) — force it to rebuild so
    // DraftSheet's "Salva su OpenStreetMap" enabled-state, which depends on
    // the live direction, picks up the new value.
    _sheetController?.setState?.call(() {});
  }

  Future<void> _onMarkerTap(SpeedCamera camera) async {
    final uid = ref.read(authStateProvider).value?.uid;
    final isOwner = uid != null && camera.createdBy == uid;
    if (isOwner) {
      await _startDraft(
        position: LatLng(camera.lat, camera.lng),
        existing: camera,
      );
      return;
    }
    // Cameras with a live OpenStreetMap node (Overpass-imported, or a user
    // report already published) can have their maxspeed/direction corrected
    // directly on OSM once the user is signed in (features.md 4).
    if (camera.osmId != null && await OsmAuthService().isLinked()) {
      if (!mounted) return;
      await _startDraft(
        position: LatLng(camera.lat, camera.lng),
        existing: camera,
      );
      return;
    }
    // Any camera with a known direction opens the (read-only, aside from
    // the local "verso contrario" toggle and OSM ignore/unignore) edit
    // sheet instead of the plain details one, so that toggle stays
    // reachable regardless of ownership/OSM login.
    if (camera.direction != null) {
      await _startDraft(
        position: LatLng(camera.lat, camera.lng),
        existing: camera,
      );
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
    await _startDraft(position: _controller.camera.center);
  }

  void _onNavigationModePressed() {
    if (_draft != null) return;
    Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (context) => const NavigationScreen()));
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
        onCenter: (camera) {
          _controller.move(LatLng(camera.lat, camera.lng), _currentZoom);
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
            const Text('VaPian!'),
          ],
        ),
        actions: [
          if ((ref.watch(pendingSyncCountProvider).value ?? 0) > 0)
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Tooltip(
                message:
                    '${ref.watch(pendingSyncCountProvider).value} modifiche in attesa di sincronizzazione',
                child: Badge(
                  label: Text('${ref.watch(pendingSyncCountProvider).value}'),
                  child: const Icon(Icons.cloud_off),
                ),
              ),
            ),
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
          FlutterMap(
            mapController: _controller,
            options: MapOptions(
              initialCenter: _initialPosition,
              initialZoom: _currentZoom,
              interactionOptions: MapStyleConfig.interactionOptions,
              onPositionChanged: _onPositionChanged,
              onLongPress: (tapPosition, point) => _startDraft(position: point),
            ),
            children: [
              if (_activeMbtilesZone != null)
                OfflineVectorTileLayer(
                  key: ValueKey(_activeMbtilesZone),
                  zone: _activeMbtilesZone!,
                )
              else
                TileLayer(
                  urlTemplate: TileSourceConfig.onlineRasterUrlTemplate,
                  subdomains: TileSourceConfig.onlineRasterSubdomains,
                  userAgentPackageName: TileSourceConfig.userAgentPackageName,
                ),
              if (_myLocation != null)
                CurrentLocationMarkerLayer(position: _myLocation!),
              MarkerLayer(markers: markers),
              const OsmAttribution(),
            ],
          ),
          if (draft != null && _draftScreenPosition != null)
            DraftOverlay(
              draft: draft,
              markerPosition: _draftScreenPosition!,
              bearing: _currentBearing,
              onMarkerPanUpdate: _onMarkerPanUpdate,
              onHandlePanUpdate: _onHandlePanUpdate,
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
          ? Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                FloatingActionButton.small(
                  heroTag: 'addCameraFab',
                  onPressed: _onFabPressed,
                  tooltip: 'Segnala autovelox',
                  child: const Icon(Icons.add),
                ),
                const SizedBox(height: 12),
                FloatingActionButton(
                  heroTag: 'navigationModeFab',
                  onPressed: _onNavigationModePressed,
                  tooltip: 'Modalità di navigazione',
                  child: const Icon(Icons.navigation),
                ),
              ],
            )
          : null,
    );
  }
}
