import 'package:flutter/material.dart';

/// Button color palette tied to the active navigation map variant, not the
/// system light/dark theme: the map itself can be night-black (AMOLED) or
/// the black speed-only view while the phone's system theme is still
/// light, so `Theme.of(context)` would otherwise pick colors that clash
/// with what's underneath.
class NavigationButtonColors {
  const NavigationButtonColors({
    required this.filledBackground,
    required this.filledForeground,
    required this.outlinedForeground,
    required this.outlinedBorder,
    required this.roundIconBackground,
    required this.roundIconForeground,
  });

  final Color filledBackground;
  final Color filledForeground;
  final Color outlinedForeground;
  final Color outlinedBorder;
  final Color roundIconBackground;
  final Color roundIconForeground;

  /// Matches the "mappa normale" variant's light Google Maps style.
  static const light = NavigationButtonColors(
    filledBackground: Colors.teal,
    filledForeground: Colors.white,
    outlinedForeground: Colors.black87,
    outlinedBorder: Colors.black45,
    roundIconBackground: Colors.white,
    roundIconForeground: Colors.black87,
  );

  /// Matches the AMOLED map variant and the black speed-only view.
  static const dark = NavigationButtonColors(
    filledBackground: Colors.teal,
    filledForeground: Colors.black,
    outlinedForeground: Colors.white,
    outlinedBorder: Colors.white54,
    roundIconBackground: Colors.black87,
    roundIconForeground: Colors.white,
  );
}
