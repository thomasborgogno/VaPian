import 'package:flutter/widgets.dart';

/// Heuristic used to distinguish tablet from phone form factor: Android's
/// own resource-qualifier threshold (`sw600dp`) — a device whose shortest
/// side is at least 600 logical pixels is treated as a tablet.
bool isTabletFormFactor(BuildContext context) {
  return MediaQuery.sizeOf(context).shortestSide >= 600;
}
