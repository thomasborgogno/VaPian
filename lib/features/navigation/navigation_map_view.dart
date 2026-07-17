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
  GoogleMapController? _mapController;
  Marker? _cameraMarker;

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
          tilt: 45,
        ),
      ),
    );
  }

  Future<void> _rebuildCameraMarker() async {
    final camera = widget.controller.state.camera;
    if (camera == null) {
      if (_cameraMarker != null && mounted)
        setState(() => _cameraMarker = null);
      return;
    }
    final icon = await SpeedCameraMarkerIcons.forColor(
      markerColorFor(camera),
      hasDirection: camera.direction != null,
    );
    if (!mounted) return;
    setState(() {
      _cameraMarker = Marker(
        markerId: MarkerId(camera.id),
        position: LatLng(camera.lat, camera.lng),
        icon: icon,
        anchor: SpeedCameraMarkerIcons.anchor,
        rotation: camera.direction?.toDouble() ?? 0,
        flat: true,
      );
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
      markers: {?_cameraMarker},
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
