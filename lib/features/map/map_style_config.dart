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

  /// Custom JSON style from https://mapstyle.withgoogle.com/ — hides
  /// Google's own point-of-interest markers/labels so the only pins on the
  /// map are speed cameras; everything else (roads, water, labels) keeps
  /// the default Google Maps look.
  static const String styleJson = '''
[
  {"featureType": "poi", "elementType": "labels", "stylers": [{"visibility": "off"}]}
]
''';

  /// Pure-black "AMOLED" style for the navigation mode's dark map variant —
  /// grey/white roads and labels on a black background, everything else
  /// (POIs, transit) hidden to keep it a minimal, glare-free driving view.
  static const String amoledMapStyleJson = '''
[
  {"elementType": "geometry", "stylers": [{"color": "#000000"}]},
  {"elementType": "labels.icon", "stylers": [{"visibility": "off"}]},
  {"elementType": "labels.text.fill", "stylers": [{"color": "#8a8a8a"}]},
  {"elementType": "labels.text.stroke", "stylers": [{"color": "#000000"}]},
  {"featureType": "administrative", "elementType": "geometry", "stylers": [{"color": "#1a1a1a"}]},
  {"featureType": "poi", "stylers": [{"visibility": "off"}]},
  {"featureType": "landscape.man_made", "stylers": [{"visibility": "off"}]},
  {"featureType": "road", "elementType": "geometry", "stylers": [{"color": "#2a2a2a"}]},
  {"featureType": "road", "elementType": "geometry.stroke", "stylers": [{"color": "#0f0f0f"}]},
  {"featureType": "road", "elementType": "labels.text.fill", "stylers": [{"color": "#c8c8c8"}]},
  {"featureType": "road.highway", "elementType": "geometry", "stylers": [{"color": "#4a4a4a"}]},
  {"featureType": "road.highway", "elementType": "labels.text.fill", "stylers": [{"color": "#ffffff"}]},
  {"featureType": "transit", "stylers": [{"visibility": "off"}]},
  {"featureType": "water", "elementType": "geometry", "stylers": [{"color": "#000000"}]}
]
''';
}
