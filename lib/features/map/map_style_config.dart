import 'package:google_maps_flutter/google_maps_flutter.dart';

/// Every `GoogleMap` visual/behavior toggle in one place, so they're easy to
/// find and tweak without hunting through `MapScreen`'s widget tree.
class MapStyleConfig {
  const MapStyleConfig._();

  static const MapType mapType = MapType.normal; // normal | satellite | hybrid | terrain

  static const bool zoomControlsEnabled = false; // Android on-screen +/- buttons.
  static const bool zoomGesturesEnabled = true; // pinch-to-zoom.
  static const bool compassEnabled = true;
  static const bool mapToolbarEnabled = false; // Android "open in Maps app" bar.
  static const bool buildingsEnabled = true; // 3D building extrusions.
  static const bool indoorViewEnabled = false; // indoor floor plans.
  static const bool trafficEnabled = false;

  /// Android-only "static snapshot" mode — keep false: the direction-handle
  /// drag overlay needs a fully interactive map.
  static const bool liteModeEnabled = false;

  static const bool myLocationEnabled = true;
  static const bool myLocationButtonEnabled = true;

  /// Kept off: the draft-marker drag math (map_screen.dart) assumes the map
  /// stays north-up. Re-enabling requires factoring the camera bearing into
  /// that math too.
  static const bool rotateGesturesEnabled = false;
  static const bool tiltGesturesEnabled = false;

  /// Custom JSON style from https://mapstyle.withgoogle.com/ — null keeps
  /// the default Google Maps look.
  static const String? styleJson = null;
}
