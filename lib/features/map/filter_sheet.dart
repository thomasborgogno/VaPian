import 'package:flutter/material.dart';

import 'camera_filter.dart';
import 'speed_limit_presets.dart';

/// Filter sheet for the map's camera markers: a speed-limit whitelist
/// (+ "show unknown limit") and a separate "show ignored only" mode. Changes
/// apply live via [onFilterChanged] as the user toggles chips/switches, so
/// the map underneath updates without waiting for the sheet to close —
/// same pattern as `_RegionPickerSheet` in osm_sync_section.dart.
class FilterSheet extends StatefulWidget {
  const FilterSheet({super.key, required this.initialFilter, required this.onFilterChanged});

  final CameraFilter initialFilter;
  final ValueChanged<CameraFilter> onFilterChanged;

  @override
  State<FilterSheet> createState() => _FilterSheetState();
}

class _FilterSheetState extends State<FilterSheet> {
  late Set<int> _limits = {...widget.initialFilter.limits};
  late bool _showUnknownLimit = widget.initialFilter.showUnknownLimit;
  late bool _showIgnoredOnly = widget.initialFilter.showIgnoredOnly;

  void _emit() {
    widget.onFilterChanged(
      CameraFilter(
        limits: _limits,
        showUnknownLimit: _showUnknownLimit,
        showIgnoredOnly: _showIgnoredOnly,
      ),
    );
  }

  void _clear() {
    setState(() {
      _limits = {};
      _showUnknownLimit = false;
      _showIgnoredOnly = false;
    });
    _emit();
  }

  @override
  Widget build(BuildContext context) {
    final isActive = _limits.isNotEmpty || _showIgnoredOnly;

    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Text('Filtra autovelox', style: Theme.of(context).textTheme.titleMedium),
                if (isActive) ...[
                  const SizedBox(width: 8),
                  Chip(
                    label: const Text('Attivo'),
                    visualDensity: VisualDensity.compact,
                    labelStyle: TextStyle(color: Theme.of(context).colorScheme.onPrimaryContainer),
                    backgroundColor: Theme.of(context).colorScheme.primaryContainer,
                  ),
                ],
              ],
            ),
            const SizedBox(height: 16),
            const Text('Limite di velocità'),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 4,
              children: speedLimitPresets
                  .map(
                    (preset) => FilterChip(
                      label: Text('$preset'),
                      selected: _limits.contains(preset),
                      onSelected: _showIgnoredOnly
                          ? null
                          : (selected) {
                              setState(() {
                                if (selected) {
                                  _limits.add(preset);
                                } else {
                                  _limits.remove(preset);
                                }
                              });
                              _emit();
                            },
                    ),
                  )
                  .toList(),
            ),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Mostra velox senza limite noto'),
              value: _showUnknownLimit,
              onChanged: _showIgnoredOnly
                  ? null
                  : (value) {
                      setState(() => _showUnknownLimit = value);
                      _emit();
                    },
            ),
            const Divider(),
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: const Text('Mostra solo velox ignorati'),
              subtitle: const Text('Tocca un velox ignorato per ripristinarlo'),
              value: _showIgnoredOnly,
              onChanged: (value) {
                setState(() => _showIgnoredOnly = value);
                _emit();
              },
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: isActive ? _clear : null,
                    child: const Text('Cancella filtro'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: const Text('Fatto'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
