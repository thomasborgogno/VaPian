import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/models/user_settings.dart';
import '../../data/providers.dart';

/// Small map-corner toggle (bottom-left) to switch between normal and hybrid
/// `GoogleMap` style — the one map-style choice exposed to the user, see
/// `map_style_config.dart`.
class MapStyleToggle extends ConsumerWidget {
  const MapStyleToggle({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final style =
        ref.watch(userSettingsProvider).value?.mapStyle ?? MapStyle.normal;
    final isHybrid = style == MapStyle.hybrid;

    return Container(
      height: 38,
      width: 38,
      decoration: const BoxDecoration(
        boxShadow: [BoxShadow(color: Colors.black12, blurRadius: 2)],
      ),
      child: Material(
        color: Colors.white.withAlpha(200),
        child: IconButton(
          style: IconButton.styleFrom(shape: const RoundedRectangleBorder()),
          tooltip: isHybrid ? 'Mappa normale' : 'Mappa ibrida',
          icon: Icon(
            isHybrid ? Icons.map_outlined : Icons.satellite_alt_outlined,
            size: 20,
            color: Colors.black54,
          ),
          onPressed: () {
            final uid = ref.read(authStateProvider).value?.uid;
            if (uid == null) return;
            final current =
                ref.read(userSettingsProvider).value ?? const UserSettings();
            final next = isHybrid ? MapStyle.normal : MapStyle.hybrid;
            ref
                .read(settingsRepositoryProvider)
                .updateSettings(uid, current.copyWith(mapStyle: next));
          },
        ),
      ),
    );
  }
}
