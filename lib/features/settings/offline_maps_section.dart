import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants/italian_regions.dart';
import '../../data/providers.dart';

/// Offline map region download: which Italian regions get their vector
/// tiles (`.mbtiles`, self-hosted — see `tools/offline_tiles/README.md`)
/// and speed-camera data downloaded for full offline use. Mirrors
/// `OsmSyncSection`'s region-picker pattern, but downloads map tiles +
/// primes the local camera store (`SyncService.primeRegion`) instead of
/// syncing fresh Overpass data.
class OfflineMapsSection extends ConsumerStatefulWidget {
  const OfflineMapsSection({
    super.key,
    required this.selectedRegions,
    required this.onRegionsChanged,
  });

  final List<String> selectedRegions;
  final ValueChanged<List<String>> onRegionsChanged;

  @override
  ConsumerState<OfflineMapsSection> createState() => _OfflineMapsSectionState();
}

class _OfflineMapsSectionState extends ConsumerState<OfflineMapsSection> {
  bool _isDownloading = false;
  String? _resultMessage;
  double? _currentProgress;
  String? _currentRegion;

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

  Future<void> _download() async {
    if (widget.selectedRegions.isEmpty) {
      setState(() => _resultMessage = 'Seleziona almeno una regione.');
      return;
    }
    setState(() {
      _isDownloading = true;
      _resultMessage = null;
    });

    final tileService = ref.read(offlineTileServiceProvider);
    final syncService = ref.read(syncServiceProvider);
    final failedRegions = <String>[];

    // Same failure-isolation approach as OsmSyncSection: one region's
    // download failing (e.g. the .mbtiles hasn't been published to Storage
    // yet) shouldn't abort the rest.
    for (final region in widget.selectedRegions) {
      setState(() {
        _currentRegion = region;
        _currentProgress = 0;
      });
      try {
        await tileService.downloadRegion(region).forEach((progress) {
          if (mounted) setState(() => _currentProgress = progress);
        });
        await syncService.primeRegion(region);
      } catch (_) {
        failedRegions.add(region);
      }
    }

    if (mounted) {
      setState(() {
        _isDownloading = false;
        _currentRegion = null;
        _currentProgress = null;
        _resultMessage = failedRegions.isEmpty
            ? '${widget.selectedRegions.length} regioni scaricate per l\'uso offline.'
            : 'Scaricate ${widget.selectedRegions.length - failedRegions.length} regioni. '
                'Non riuscite: ${failedRegions.join(', ')}.';
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Mappe offline', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(
          'Scarica le mappe e gli autovelox delle regioni selezionate, per usare '
          'l\'app e navigare anche senza connessione a internet.',
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
          onTap: _isDownloading ? null : _openRegionPicker,
        ),
        const SizedBox(height: 12),
        FilledButton.icon(
          onPressed: _isDownloading ? null : _download,
          icon: _isDownloading
              ? const SizedBox(
                  width: 16,
                  height: 16,
                  child: CircularProgressIndicator(strokeWidth: 2),
                )
              : const Icon(Icons.download_for_offline),
          label: const Text('Scarica per offline'),
        ),
        if (_isDownloading && _currentRegion != null) ...[
          const SizedBox(height: 8),
          Text('Scaricamento $_currentRegion...', style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 4),
          LinearProgressIndicator(value: _currentProgress),
        ],
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
            Text('Regioni da scaricare', style: Theme.of(context).textTheme.titleMedium),
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
