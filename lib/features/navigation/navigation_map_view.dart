import 'dart:async';

import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../core/utils/geo.dart';
import '../map/map_style_config.dart';
import '../map/map_viewport.dart';
import '../map/marker_style.dart';
import '../map/speed_camera_marker_icons.dart';
import 'navigation_alert_controller.dart';

/// Navigation-mode map view (variants 1 "mappa normale" / 2 "mappa amoled"):
/// a `GoogleMap` that follows the driver's position/heading and shows the
/// active alert camera, if any — the blue "my location" dot stands in for
/// the virtual car (native, always correctly rotated/positioned).
class NavigationMapView extends StatefulWidget {
  const NavigationMapView({
    super.key,
    required this.controller,
    required this.amoled,
  });

  final NavigationAlertController controller;
  final bool amoled;

  @override
  State<NavigationMapView> createState() => _NavigationMapViewState();
}

class _NavigationMapViewState extends State<NavigationMapView> {
  /// Highest tilt Google Maps actually renders at the fixed zoom=17 used in
  /// navigation mode — beyond this the SDK silently clamps it anyway, so
  /// this is effectively "as inclined as possible" for maximum forward view.
  static const double _navigationTiltDeg = 67.5;

  /// Fraction of the screen height treated as top "padding" for `GoogleMap`.
  /// `GoogleMap.padding` insets the area the SDK centers the camera target
  /// within, so top padding pushes that centered point — the my-location
  /// dot standing in for the car — down towards the bottom of the screen,
  /// leaving more room above to see the road ahead.
  static const double _topPaddingFraction = 0.45;

  /// How often the camera is nudged forward between real GPS fixes (which
  /// only arrive every 2s) — frequent enough that the motion reads as
  /// continuous rather than a hitch-then-jump every fix.
  static const Duration _extrapolationTick = Duration(milliseconds: 100);

  /// Below this speed, don't extrapolate at all — matches the controller's
  /// own stationary-heading threshold, so a stopped car doesn't drift.
  static const double _minExtrapolationSpeedKmh = 3.0;

  GoogleMapController? _mapController;
  Set<Marker> _cameraMarkers = {};
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
  /// camera frozen until the next fix arrives 2s later. `moveCamera` (not
  /// `animateCamera`) is used here since this runs on a short, frequent
  /// tick — many small instant steps already read as smooth motion, and
  /// chaining eased animations at this rate would fight itself.
  Future<void> _followUser() async {
    final map = _mapController;
    final fixLat = _fixLat;
    final fixLng = _fixLng;
    final fixAt = _fixAt;
    if (map == null || fixLat == null || fixLng == null || fixAt == null) return;

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

    await map.moveCamera(
      CameraUpdate.newCameraPosition(
        CameraPosition(
          target: LatLng(lat, lng),
          zoom: 17,
          bearing: state.headingDeg,
          tilt: _navigationTiltDeg,
        ),
      ),
    );
  }

  /// Same two-marker split as the main map (always-upright badge + a
  /// separately-rotating arrow, only when a direction is known) — see
  /// `SpeedCameraMarkerIcons`'s class doc for why a single bitmap can't do
  /// both.
  Future<void> _rebuildCameraMarker() async {
    final camera = widget.controller.state.camera;
    if (camera == null) {
      if (_cameraMarkers.isNotEmpty && mounted) {
        setState(() => _cameraMarkers = {});
      }
      return;
    }
    final color = markerColorFor(camera);
    final position = LatLng(camera.lat, camera.lng);
    final icon = await SpeedCameraMarkerIcons.iconForColor(color);
    final travelDirection = camera.monitoredTravelDirectionDeg;
    final arrow = travelDirection != null
        ? await SpeedCameraMarkerIcons.arrowForColor(color)
        : null;
    if (!mounted) return;
    setState(() {
      _cameraMarkers = {
        Marker(
          markerId: MarkerId('${camera.id}_icon'),
          position: position,
          icon: icon,
          anchor: SpeedCameraMarkerIcons.iconAnchor,
          zIndexInt: 1,
        ),
        if (arrow != null)
          Marker(
            markerId: MarkerId('${camera.id}_arrow'),
            position: position,
            icon: arrow,
            anchor: SpeedCameraMarkerIcons.arrowAnchor,
            rotation: travelDirection!.toDouble(),
            flat: true,
          ),
      };
    });
  }

  @override
  Widget build(BuildContext context) {
    final lat = widget.controller.currentLat ?? MapViewport.initial.latitude;
    final lng = widget.controller.currentLng ?? MapViewport.initial.longitude;
    return GoogleMap(
      initialCameraPosition: CameraPosition(
        target: LatLng(lat, lng),
        zoom: 17,
        tilt: _navigationTiltDeg,
      ),
      padding: EdgeInsets.only(
        top: MediaQuery.sizeOf(context).height * _topPaddingFraction,
      ),
      markers: _cameraMarkers,
      mapType: MapType.normal,
      style: widget.amoled
          ? MapStyleConfig.amoledMapStyleJson
          : MapStyleConfig.styleJson,
      zoomControlsEnabled: false,
      zoomGesturesEnabled: MapStyleConfig.zoomGesturesEnabled,
      myLocationEnabled: true,
      myLocationButtonEnabled: false,
      compassEnabled: false,
      mapToolbarEnabled: false,
      buildingsEnabled: widget.amoled ? false : MapStyleConfig.buildingsEnabled,
      trafficEnabled: MapStyleConfig.trafficEnabled,
      onMapCreated: (controller) {
        _mapController = controller;
        _followUser();
      },
    );
  }
}
