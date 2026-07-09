import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants/italian_regions.dart';
import '../../data/providers.dart';
import '../../data/services/overpass_service.dart';

/// Manual OSM sync (features.md 2.2): downloads speed cameras for each
/// selected Italian region — one Overpass call per region, sequentially, to
/// avoid a single whole-country query timing out — and upserts them into
/// `speed_cameras`. The region selection itself is owned by the parent
/// (persisted in `UserSettings.osmSyncRegions`); this widget only reads and
/// toggles it, so there's a single source of truth for the settings doc.
class OsmSyncSection extends ConsumerStatefulWidget {
  const OsmSyncSection({
    super.key,
    required this.selectedRegions,
    required this.onRegionsChanged,
  });

  final List<String> selectedRegions;
  final ValueChanged<List<String>> onRegionsChanged;

  @override
  ConsumerState<OsmSyncSection> createState() => _OsmSyncSectionState();
}

class _OsmSyncSectionState extends ConsumerState<OsmSyncSection> {
  bool _isSyncing = false;
  String? _resultMessage;

  Future<void> _openRegionPicker() {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _RegionPickerSheet(
        initialSelectedRegions: widget.selectedRegions,
        onRegionsChanged: widget.onRegionsChanged,
      ),
    );
  }

  Future<void> _sync() async {
    if (widget.selectedRegions.isEmpty) {
      setState(() => _resultMessage = 'Seleziona almeno una regione.');
      return;
    }
    setState(() {
      _isSyncing = true;
      _resultMessage = null;
    });

    final overpass = OverpassService();
    final repository = ref.read(speedCameraRepositoryProvider);
    var totalSynced = 0;
    final failedRegions = <String>[];

    // One region's failure (e.g. a 504 from an overloaded Overpass server)
    // does not abort the rest — otherwise a single bad region silently ate
    // every other region too, which looked like "sync did nothing".
    for (final region in widget.selectedRegions) {
      try {
        final cameras = await overpass.fetchSpeedCameras(region);
        for (final camera in cameras) {
          await repository.upsertOsmCamera(camera);
        }
        totalSynced += cameras.length;
      } catch (_) {
        failedRegions.add(region);
      }
    }

    if (failedRegions.length < widget.selectedRegions.length) {
      // Record the sync even on a partial failure — the Smart Reminder is
      // about "have you synced recently", not "did every region succeed".
      await ref.read(settingsRepositoryProvider).recordSync(camerasSynced: totalSynced);
    }

    if (mounted) {
      setState(() {
        _resultMessage = failedRegions.isEmpty
            ? 'Sincronizzati $totalSynced autovelox in ${widget.selectedRegions.length} '
                'region${widget.selectedRegions.length == 1 ? 'e' : 'i'}.'
            : 'Sincronizzati $totalSynced autovelox. Non riuscite: ${failedRegions.join(', ')} '
                '(riprova più tardi, Overpass potrebbe essere sovraccarico).';
        _isSyncing = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Sincronizzazione OpenStreetMap', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(
          'Scarica gli autovelox ufficiali OSM (highway=speed_camera, enforcement=maxspeed) '
          'per le regioni selezionate.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(
            widget.selectedRegions.isEmpty
                ? 'Nessuna regione selezionata'
                : widget.selectedRegions.join(', '),
          ),
          subtitle: Text('${widget.selectedRegions.length} regioni selezionate'),
          trailing: const Icon(Icons.chevron_right),
          onTap: _isSyncing ? null : _openRegionPicker,
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: _isSyncing ? null : _sync,
          icon: _isSyncing
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.cloud_sync),
          label: const Text('Sincronizza ora'),
        ),
        if (_resultMessage != null) ...[
          const SizedBox(height: 8),
          Text(_resultMessage!, style: Theme.of(context).textTheme.bodySmall),
        ],
      ],
    );
  }
}

class _RegionPickerSheet extends StatefulWidget {
  const _RegionPickerSheet({
    required this.initialSelectedRegions,
    required this.onRegionsChanged,
  });

  final List<String> initialSelectedRegions;
  final ValueChanged<List<String>> onRegionsChanged;

  @override
  State<_RegionPickerSheet> createState() => _RegionPickerSheetState();
}

class _RegionPickerSheetState extends State<_RegionPickerSheet> {
  late final List<String> _selected = [...widget.initialSelectedRegions];

  void _toggle(String region, bool selected) {
    setState(() => selected ? _selected.add(region) : _selected.remove(region));
    widget.onRegionsChanged(_selected);
  }

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Regioni da sincronizzare', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: italianRegions
                  .map(
                    (region) => FilterChip(
                      label: Text(region),
                      selected: _selected.contains(region),
                      onSelected: (selected) => _toggle(region, selected),
                    ),
                  )
                  .toList(),
            ),
            const SizedBox(height: 16),
            SizedBox(
              width: double.infinity,
              child: FilledButton(
                onPressed: () => Navigator.of(context).pop(),
                child: const Text('Fatto'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
