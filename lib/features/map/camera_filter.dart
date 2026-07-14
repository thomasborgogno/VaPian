import '../../data/models/speed_camera.dart';

/// Map-screen-local camera filter (session state, not persisted): a whitelist
/// of speed limits plus two independent toggles. When [showIgnoredOnly] is
/// on it takes over entirely — only cameras the user has ignored are shown
/// (in grey), letting them be tapped to restore; the speed-limit criteria
/// below don't apply in that mode.
class CameraFilter {
  const CameraFilter({
    this.limits = const {},
    this.showUnknownLimit = false,
    this.showIgnoredOnly = false,
  });

  /// Empty means "no restriction" — every known limit passes.
  final Set<int> limits;

  /// Whether cameras with no recorded speed limit pass the filter — only
  /// meaningful once [limits] is non-empty, defaults to false.
  final bool showUnknownLimit;

  final bool showIgnoredOnly;

  bool get isActive => limits.isNotEmpty || showIgnoredOnly;

  bool matchesLimit(SpeedCamera camera) {
    if (limits.isEmpty) return true;
    if (camera.limit == null) return showUnknownLimit;
    return limits.contains(camera.limit);
  }

  CameraFilter copyWith({
    Set<int>? limits,
    bool? showUnknownLimit,
    bool? showIgnoredOnly,
  }) {
    return CameraFilter(
      limits: limits ?? this.limits,
      showUnknownLimit: showUnknownLimit ?? this.showUnknownLimit,
      showIgnoredOnly: showIgnoredOnly ?? this.showIgnoredOnly,
    );
  }
}
