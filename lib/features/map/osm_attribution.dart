import 'package:flutter/material.dart';

import '../../core/constants/tile_source_config.dart';

/// Small, semi-transparent OSM attribution — required by the Tile Usage
/// Policy, but kept unobtrusive rather than `flutter_map`'s default
/// `SimpleAttributionWidget` (a full-width opaque bar with a "flutter_map |"
/// prefix we don't need to show).
class OsmAttribution extends StatelessWidget {
  const OsmAttribution({super.key, this.alignment = Alignment.bottomLeft});

  final Alignment alignment;

  @override
  Widget build(BuildContext context) {
    return Align(
      alignment: alignment,
      child: DecoratedBox(
        decoration: BoxDecoration(
          color: Colors.white.withValues(alpha: 0.35),
          borderRadius: BorderRadius.only(topRight: Radius.circular(4)),
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 1),
          child: Text(
            TileSourceConfig.attribution,
            style: const TextStyle(fontSize: 9, color: Colors.black),
          ),
        ),
      ),
    );
  }
}
