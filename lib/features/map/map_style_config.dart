import 'package:flutter_map/flutter_map.dart';

/// `flutter_map` interaction toggles in one place, so they're easy to find
/// and tweak without hunting through `MapScreen`'s widget tree.
///
/// Google Maps' native chrome this app used to rely on (3D buildings,
/// traffic layer, satellite/hybrid style, indoor floor plans, the built-in
/// zoom/my-location buttons) has no equivalent in `flutter_map`'s plain 2D
/// tile renderer and isn't reproduced — those were cosmetic extras, not
/// core to the crowdsourced-camera map.
class MapStyleConfig {
  const MapStyleConfig._();

  /// Two-finger map rotation — the draft-marker/handle drag math
  /// (map_screen.dart) factors in the current camera bearing, so it stays
  /// correct even while the map isn't north-up.
  static const InteractionOptions interactionOptions = InteractionOptions(
    flags: InteractiveFlag.all,
  );
}
