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

  void _toggleRegion(String region, bool selected) {
    final updated = [...widget.selectedRegions];
    selected ? updated.add(region) : updated.remove(region);
    widget.onRegionsChanged(updated);
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
    try {
      for (final region in widget.selectedRegions) {
        final cameras = await overpass.fetchSpeedCameras(region);
        for (final camera in cameras) {
          await repository.upsertOsmCamera(camera);
        }
        totalSynced += cameras.length;
      }
      await ref.read(settingsRepositoryProvider).recordSync(camerasSynced: totalSynced);
      if (mounted) {
        setState(() => _resultMessage =
            'Sincronizzati $totalSynced autovelox in ${widget.selectedRegions.length} '
            'region${widget.selectedRegions.length == 1 ? 'e' : 'i'}.');
      }
    } catch (e) {
      if (mounted) setState(() => _resultMessage = 'Sincronizzazione non riuscita: $e');
    } finally {
      if (mounted) setState(() => _isSyncing = false);
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
        Wrap(
          spacing: 8,
          runSpacing: 4,
          children: italianRegions
              .map(
                (region) => FilterChip(
                  label: Text(region),
                  selected: widget.selectedRegions.contains(region),
                  onSelected: _isSyncing ? null : (selected) => _toggleRegion(region, selected),
                ),
              )
              .toList(),
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
