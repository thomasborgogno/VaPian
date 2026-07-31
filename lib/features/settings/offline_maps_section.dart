import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/constants/italian_macro_zones.dart';
import '../../data/providers.dart';

/// Offline map zone download: which of Geofabrik's 5 Italian macro-zones
/// (`italianMacroZones` — coarser than the 20 administrative regions
/// `OsmSyncSection` uses for Overpass sync, so there are far fewer, larger
/// files to generate/download) get their vector tiles (`.mbtiles`,
/// self-hosted — see `tools/offline_tiles/README.md`) and speed-camera data
/// downloaded for full offline use. Mirrors `OsmSyncSection`'s picker
/// pattern, but downloads map tiles + primes the local camera store
/// (`SyncService.primeZone`) instead of syncing fresh Overpass data.
class OfflineMapsSection extends ConsumerStatefulWidget {
  const OfflineMapsSection({
    super.key,
    required this.selectedZones,
    required this.onZonesChanged,
  });

  final List<String> selectedZones;
  final ValueChanged<List<String>> onZonesChanged;

  @override
  ConsumerState<OfflineMapsSection> createState() => _OfflineMapsSectionState();
}

class _OfflineMapsSectionState extends ConsumerState<OfflineMapsSection> {
  bool _isDownloading = false;
  String? _resultMessage;
  double? _currentProgress;
  String? _currentZone;

  Future<void> _openZonePicker() {
    return showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      builder: (context) => _ZonePickerSheet(
        initialSelectedZones: widget.selectedZones,
        onZonesChanged: widget.onZonesChanged,
      ),
    );
  }

  Future<void> _download() async {
    if (widget.selectedZones.isEmpty) {
      setState(() => _resultMessage = 'Seleziona almeno una zona.');
      return;
    }
    setState(() {
      _isDownloading = true;
      _resultMessage = null;
    });

    final tileService = ref.read(offlineTileServiceProvider);
    final syncService = ref.read(syncServiceProvider);
    final failedZones = <String>[];

    // Same failure-isolation approach as OsmSyncSection: one zone's
    // download failing (e.g. the .mbtiles hasn't been published to Storage
    // yet) shouldn't abort the rest.
    for (final zone in widget.selectedZones) {
      setState(() {
        _currentZone = zone;
        _currentProgress = 0;
      });
      try {
        await tileService.downloadZone(zone).forEach((progress) {
          if (mounted) setState(() => _currentProgress = progress);
        });
        await syncService.primeZone(zone);
      } catch (_) {
        failedZones.add(zone);
      }
    }

    // The map screens only pick a zone's local vector tiles when this
    // provider reports it as downloaded — invalidate it now that (some of)
    // the downloads just completed, rather than waiting for something else
    // to trigger a refresh.
    ref.invalidate(downloadedZonesProvider);

    if (mounted) {
      setState(() {
        _isDownloading = false;
        _currentZone = null;
        _currentProgress = null;
        _resultMessage = failedZones.isEmpty
            ? '${widget.selectedZones.length} zone scaricate per l\'uso offline.'
            : 'Scaricate ${widget.selectedZones.length - failedZones.length} zone. '
                'Non riuscite: ${failedZones.join(', ')}.';
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
          'Scarica le mappe e gli autovelox delle zone selezionate, per usare '
          'l\'app e navigare anche senza connessione a internet.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 12),
        ListTile(
          contentPadding: EdgeInsets.zero,
          title: Text(
            widget.selectedZones.isEmpty
                ? 'Nessuna zona selezionata'
                : widget.selectedZones.join(', '),
          ),
          subtitle: Text('${widget.selectedZones.length} zone selezionate'),
          trailing: const Icon(Icons.chevron_right),
          onTap: _isDownloading ? null : _openZonePicker,
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
        if (_isDownloading && _currentZone != null) ...[
          const SizedBox(height: 8),
          Text('Scaricamento $_currentZone...', style: Theme.of(context).textTheme.bodySmall),
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

class _ZonePickerSheet extends StatefulWidget {
  const _ZonePickerSheet({
    required this.initialSelectedZones,
    required this.onZonesChanged,
  });

  final List<String> initialSelectedZones;
  final ValueChanged<List<String>> onZonesChanged;

  @override
  State<_ZonePickerSheet> createState() => _ZonePickerSheetState();
}

class _ZonePickerSheetState extends State<_ZonePickerSheet> {
  late final List<String> _selected = [...widget.initialSelectedZones];

  void _toggle(String zone, bool selected) {
    setState(() => selected ? _selected.add(zone) : _selected.remove(zone));
    widget.onZonesChanged(_selected);
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
            Text('Zone da scaricare', style: Theme.of(context).textTheme.titleMedium),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: italianMacroZoneNames
                  .map(
                    (zone) => FilterChip(
                      label: Text(zone),
                      selected: _selected.contains(zone),
                      onSelected: (selected) => _toggle(zone, selected),
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
