import 'package:flutter/material.dart';

import 'speed_limit_presets.dart';

/// Bottom sheet for placing/editing a camera draft on the map: pick a speed
/// limit, then save/cancel/delete, plus the pending-report-only actions
/// (approve, publish to OSM) when applicable.
class DraftSheet extends StatefulWidget {
  const DraftSheet({
    super.key,
    required this.initialLimit,
    required this.onSave,
    required this.onCancel,
    this.onDelete,
    this.onApprove,
    this.onPublishToOsm,
  });

  final int initialLimit;
  final ValueChanged<int> onSave;
  final VoidCallback onCancel;
  final VoidCallback? onDelete;

  /// Non-null when editing a pending user camera — confirms it as correct
  /// without publishing to OSM (features.md 2.1).
  final ValueChanged<int>? onApprove;

  /// Non-null only when editing an own pending report with OpenStreetMap
  /// linked (features.md 4) — takes the currently selected limit, like
  /// [onSave].
  final ValueChanged<int>? onPublishToOsm;

  @override
  State<DraftSheet> createState() => _DraftSheetState();
}

class _DraftSheetState extends State<DraftSheet> {
  late int _limit = widget.initialLimit;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Limite di velocità'),
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              child: Row(
                children: speedLimitPresets
                    .map(
                      (preset) => Padding(
                        padding: const EdgeInsets.only(right: 8),
                        child: ChoiceChip(
                          label: Text('$preset'),
                          selected: _limit == preset,
                          onSelected: (_) => setState(() => _limit = preset),
                        ),
                      ),
                    )
                    .toList(),
              ),
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                if (widget.onDelete != null) ...[
                  IconButton(
                    onPressed: widget.onDelete,
                    icon: const Icon(Icons.delete_outline),
                    tooltip: 'Elimina',
                  ),
                  const SizedBox(width: 8),
                ],
                Expanded(
                  child: OutlinedButton(
                    onPressed: widget.onCancel,
                    child: const Text('Annulla'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: () => widget.onSave(_limit),
                    child: const Text('Salva'),
                  ),
                ),
              ],
            ),
            if (widget.onApprove != null) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: FilledButton.icon(
                  onPressed: () => widget.onApprove!(_limit),
                  icon: const Icon(Icons.check_circle_outline),
                  label: const Text('Approva segnalazione'),
                ),
              ),
            ],
            if (widget.onPublishToOsm != null) ...[
              const SizedBox(height: 8),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: () => widget.onPublishToOsm!(_limit),
                  icon: const Icon(Icons.public),
                  label: const Text('Pubblica su OpenStreetMap'),
                ),
              ),
            ],
          ],
        ),
      ),
    );
  }
}
