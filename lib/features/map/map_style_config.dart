import 'package:google_maps_flutter/google_maps_flutter.dart';

import '../../data/models/user_settings.dart';

/// Every `GoogleMap` visual/behavior toggle in one place, so they're easy to
/// find and tweak without hunting through `MapScreen`'s widget tree.
class MapStyleConfig {
  const MapStyleConfig._();

  /// `mapType` is the one toggle the user controls (Impostazioni > Stile
  /// mappa); every other constant below stays developer-only.
  static MapType mapTypeFor(MapStyle style) =>
      style == MapStyle.hybrid ? MapType.hybrid : MapType.normal;

  static const bool zoomControlsEnabled =
      false; // Android on-screen +/- buttons.
  static const bool zoomGesturesEnabled = true; // pinch-to-zoom.
  static const bool compassEnabled = true;
  static const bool mapToolbarEnabled =
      false; // Android "open in Maps app" bar.
  static const bool buildingsEnabled = true; // 3D building extrusions.
  static const bool indoorViewEnabled = false; // indoor floor plans.
  static const bool trafficEnabled = false;

  /// Android-only "static snapshot" mode — keep false: the direction-handle
  /// drag overlay needs a fully interactive map.
  static const bool liteModeEnabled = false;

  static const bool myLocationEnabled = true;
  static const bool myLocationButtonEnabled = true;

  /// Two-finger map rotation — the draft-marker/handle drag math
  /// (map_screen.dart, draft_overlay.dart) factors in the current camera
  /// bearing, so it stays correct even while the map isn't north-up.
  static const bool rotateGesturesEnabled = true;
  static const bool tiltGesturesEnabled = false;

  /// Custom JSON style from https://mapstyle.withgoogle.com/ — null keeps
  /// the default Google Maps look.
  static const String? styleJson = null;
}
