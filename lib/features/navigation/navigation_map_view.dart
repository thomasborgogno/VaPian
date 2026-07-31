import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:latlong2/latlong.dart';

import '../../core/constants/italian_macro_zones.dart';
import '../../core/constants/tile_source_config.dart';
import '../../core/utils/geo.dart';
import '../map/current_location_marker.dart';
import '../map/map_viewport.dart';
import '../map/marker_style.dart';
import '../map/offline_vector_tile_layer.dart';
import '../map/osm_attribution.dart';
import '../map/speed_camera_marker_icons.dart';
import 'navigation_alert_controller.dart';

/// Navigation-mode map view (variants 1 "mappa normale" / 2 "mappa amoled"):
/// a `flutter_map` that follows the driver's position/heading and shows the
/// active alert camera, if any.
///
/// Two things `GoogleMap` gave this screen for free have no `flutter_map`
/// equivalent and are approximated here:
///  - the native "my location" blue dot standing in for the virtual car —
///    replaced by [CurrentLocationMarkerLayer], a plain marker fed by the
///    same GPS fix.
///  - the forward-tilted 3D camera (`tilt: 67.5°`) — `flutter_map` is a flat
///    2D renderer with no camera tilt at all, so [_pseudoTiltTransform]
///    fakes a forward lean with a perspective `Transform` on the whole map
///    widget. It's a cosmetic approximation, not a real 3D camera.
class NavigationMapView extends StatefulWidget {
  const NavigationMapView({
    super.key,
    required this.controller,
    required this.amoled,
    this.downloadedZones = const {},
  });

  final NavigationAlertController controller;
  final bool amoled;

  /// Macro-zones with local vector tiles available offline
  /// (`downloadedZonesProvider`) — passed in rather than read directly
  /// since this widget isn't itself a `ConsumerWidget`.
  final Set<String> downloadedZones;

  @override
  State<NavigationMapView> createState() => _NavigationMapViewState();
}

class _NavigationMapViewState extends State<NavigationMapView> {
  static const double _navigationZoom = 17;

  /// Fraction of the screen height the driver marker sits below the true
  /// screen center — approximates `GoogleMap`'s old `padding: top: 45%`,
  /// which left more room above to see the road ahead.
  static const double _verticalOffsetFraction = 0.225;

  /// How often the camera is nudged forward between real GPS fixes (which
  /// only arrive every 2s) — frequent enough that the motion reads as
  /// continuous rather than a hitch-then-jump every fix.
  static const Duration _extrapolationTick = Duration(milliseconds: 100);

  /// Below this speed, don't extrapolate at all — matches the controller's
  /// own stationary-heading threshold, so a stopped car doesn't drift.
  static const double _minExtrapolationSpeedKmh = 3.0;

  final MapController _mapController = MapController();
  List<Marker> _cameraMarkers = [];
  Timer? _extrapolationTimer;

  double? _fixLat;
  double? _fixLng;
  DateTime? _fixAt;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerChanged);
    _rebuildCameraMarker();
    _extrapolationTimer = Timer.periodic(_extrapolationTick, (_) => _followUser());
  }

  @override
  void dispose() {
    _extrapolationTimer?.cancel();
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  void _onControllerChanged() {
    final lat = widget.controller.currentLat;
    final lng = widget.controller.currentLng;
    if (lat != null && lng != null) {
      _fixLat = lat;
      _fixLng = lng;
      _fixAt = DateTime.now();
    }
    _followUser();
    _rebuildCameraMarker();
  }

  /// Between GPS fixes, dead-reckons the displayed position forward from the
  /// last real fix using current speed/heading, instead of leaving the
  /// camera frozen until the next fix arrives 2s later.
  void _followUser() {
    final fixLat = _fixLat;
    final fixLng = _fixLng;
    final fixAt = _fixAt;
    if (fixLat == null || fixLng == null || fixAt == null || !mounted) return;

    final state = widget.controller.state;
    var lat = fixLat;
    var lng = fixLng;
    if (state.speedKmh >= _minExtrapolationSpeedKmh) {
      final elapsedSeconds = DateTime.now().difference(fixAt).inMilliseconds / 1000;
      final distanceMeters = (state.speedKmh / 3.6) * elapsedSeconds;
      final projected = destinationPoint(fixLat, fixLng, state.headingDeg, distanceMeters);
      lat = projected.lat;
      lng = projected.lng;
    }

    final verticalOffsetPx = MediaQuery.sizeOf(context).height * _verticalOffsetFraction;
    _mapController.move(
      LatLng(lat, lng),
      _navigationZoom,
      offset: Offset(0, verticalOffsetPx),
    );
    _mapController.rotate(state.headingDeg);
  }

  /// Same two-marker split as the main map (always-upright badge + a
  /// separately-rotating arrow, only when a direction is known) — see
  /// `SpeedCameraMarkerIcons`'s class doc.
  void _rebuildCameraMarker() {
    final camera = widget.controller.state.camera;
    if (camera == null) {
      if (_cameraMarkers.isNotEmpty && mounted) {
        setState(() => _cameraMarkers = []);
      }
      return;
    }
    final color = markerColorFor(camera);
    final position = LatLng(camera.lat, camera.lng);
    final travelDirection = camera.monitoredTravelDirectionDeg;
    if (!mounted) return;
    setState(() {
      _cameraMarkers = [
        if (travelDirection != null)
          Marker(
            point: position,
            alignment: Alignment.topCenter,
            width: SpeedCameraArrowMarker.width,
            height: SpeedCameraArrowMarker.height,
            child: SpeedCameraArrowMarker(color: color, rotationDegrees: travelDirection.toDouble()),
          ),
        Marker(
          point: position,
          rotate: true,
          width: SpeedCameraIconMarker.size,
          height: SpeedCameraIconMarker.size,
          child: SpeedCameraIconMarker(color: color),
        ),
      ];
    });
  }

  /// Fakes a forward camera tilt with a perspective `Transform` on the
  /// whole map widget — see the class doc for why this exists instead of a
  /// real 3D camera.
  Widget _pseudoTiltTransform({required Widget child}) {
    const tiltRadians = 0.35;
    return Transform(
      alignment: Alignment.bottomCenter,
      transform: Matrix4.identity()
        ..setEntry(3, 2, 0.0009)
        ..rotateX(tiltRadians),
      child: child,
    );
  }

  @override
  Widget build(BuildContext context) {
    final lat = widget.controller.currentLat ?? MapViewport.initial.latitude;
    final lng = widget.controller.currentLng ?? MapViewport.initial.longitude;
    final zone = zoneContaining(lat, lng);
    final activeZone =
        (zone != null && widget.downloadedZones.contains(zone.name)) ? zone.name : null;

    Widget baseLayer = activeZone != null
        ? OfflineVectorTileLayer(key: ValueKey(activeZone), zone: activeZone)
        : TileLayer(
            urlTemplate: TileSourceConfig.onlineRasterUrlTemplate,
            subdomains: TileSourceConfig.onlineRasterSubdomains,
            userAgentPackageName: TileSourceConfig.userAgentPackageName,
          );
    if (widget.amoled) baseLayer = _darkened(baseLayer);

    final map = FlutterMap(
      mapController: _mapController,
      options: MapOptions(
        initialCenter: LatLng(lat, lng),
        initialZoom: _navigationZoom,
        interactionOptions: const InteractionOptions(flags: InteractiveFlag.none),
      ),
      children: [
        baseLayer,
        CurrentLocationMarkerLayer(position: LatLng(lat, lng)),
        MarkerLayer(markers: _cameraMarkers),
        const OsmAttribution(),
      ],
    );

    return ClipRect(child: _pseudoTiltTransform(child: map));
  }

  /// Approximates the old AMOLED `GoogleMap` JSON style (dark roads on a
  /// near-black background) by darkening/desaturating whichever base map
  /// layer is active — a plain color-matrix filter, not a true custom dark
  /// basemap (that needs its own dark theme, same idea as
  /// `OfflineMapTheme` but not built yet).
  Widget _darkened(Widget layer) {
    return ColorFiltered(
      colorFilter: const ColorFilter.matrix(<double>[
        0.25, 0, 0, 0, 0,
        0, 0.25, 0, 0, 0,
        0, 0, 0.25, 0, 0,
        0, 0, 0, 1, 0,
      ]),
      child: layer,
    );
  }
}
