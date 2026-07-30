import 'package:flutter/material.dart';
import 'package:google_maps_flutter/google_maps_flutter.dart';

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

  GoogleMapController? _mapController;
  Set<Marker> _cameraMarkers = {};

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onControllerChanged);
    _rebuildCameraMarker();
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onControllerChanged);
    super.dispose();
  }

  void _onControllerChanged() {
    _followUser();
    _rebuildCameraMarker();
  }

  Future<void> _followUser() async {
    final map = _mapController;
    final lat = widget.controller.currentLat;
    final lng = widget.controller.currentLng;
    if (map == null || lat == null || lng == null) return;
    await map.animateCamera(
      CameraUpdate.newCameraPosition(
        CameraPosition(
          target: LatLng(lat, lng),
          zoom: 17,
          bearing: widget.controller.state.headingDeg,
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
        tilt: 45,
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
