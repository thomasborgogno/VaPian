import 'package:flutter/material.dart';

import 'speed_limit_presets.dart';

/// Bottom sheet for placing/editing a camera draft on the map: pick a speed
/// limit, then save/cancel/delete, plus the pending-report-only actions
/// (approve, publish to OSM) when applicable. Also hosts the local-only
/// "verso contrario" direction toggle and, for OpenStreetMap-sourced
/// cameras, the ignore/unignore action — both available even when the rest
/// of the sheet is read-only (not owned, no OpenStreetMap login).
class DraftSheet extends StatefulWidget {
  const DraftSheet({
    super.key,
    required this.initialLimit,
    required this.onCancel,
    this.onSave,
    this.limitEditable = true,
    this.onDelete,
    this.onApprove,
    this.onPublishToOsm,
    this.onSaveOsmEdit,
    this.originalLimit,
    this.originalDirection,
    this.currentDirection = 0,
    this.hasKnownDirection = false,
    this.directionReversed = false,
    this.onDirectionReversedChanged,
    this.onSaveReversedOnly,
    this.isIgnored = false,
    this.onIgnore,
    this.onUnignore,
  });

  final int initialLimit;
  final VoidCallback onCancel;

  /// Null when this camera can't be saved by the current user (read-only
  /// tier: not owned, no OpenStreetMap login) — the generic "Salva" action
  /// is hidden entirely rather than shown disabled.
  final ValueChanged<int>? onSave;

  /// Whether the limit chips respond to taps — false in the read-only tier,
  /// where the limit is shown for context but can't be changed.
  final bool limitEditable;

  final VoidCallback? onDelete;

  /// Non-null when editing a pending user camera — confirms it as correct
  /// without publishing to OSM (features.md 2.1).
  final ValueChanged<int>? onApprove;

  /// Non-null only when editing an own pending report with OpenStreetMap
  /// linked (features.md 4) — takes the currently selected limit, like
  /// [onSave].
  final ValueChanged<int>? onPublishToOsm;

  /// Non-null when editing a camera that already has a live OpenStreetMap
  /// node (features.md 4) — takes the currently selected limit. Shown as its
  /// own secondary action alongside (not instead of) [onSave], since saving
  /// locally and publishing to OSM are now separate choices — only enabled
  /// once the OSM-relevant data (limit/direction) has actually changed from
  /// [originalLimit]/[originalDirection].
  final ValueChanged<int>? onSaveOsmEdit;

  /// The camera's `limit`/`direction` as they were when editing started —
  /// compared against the live-edited values to decide whether
  /// [onSaveOsmEdit] should be enabled. [currentDirection] is re-passed by
  /// the caller (via `PersistentBottomSheetController.setState`) whenever
  /// the direction handle moves, since this sheet has no other way to
  /// observe that external change.
  final int? originalLimit;
  final int? originalDirection;
  final double currentDirection;

  /// True when this camera has a known `direction` — gates whether the
  /// "verso contrario" toggle appears at all.
  final bool hasKnownDirection;
  final bool directionReversed;

  /// Non-null (whenever [hasKnownDirection] is true) — called on every
  /// toggle so the caller can live-preview the change (e.g. the draft
  /// overlay's arrow), without persisting anything yet. The actual save
  /// happens via whichever save action fires (they read back the latest
  /// value from the caller's own state), or via [onSaveReversedOnly] in the
  /// read-only tier where no other save action exists.
  final ValueChanged<bool>? onDirectionReversedChanged;

  /// Non-null only in the read-only tier (no [onSave]/[onSaveOsmEdit]) when
  /// [hasKnownDirection] is true — the only persistable action left, so it
  /// gets its own "Salva" button instead of being silently unreachable.
  final VoidCallback? onSaveReversedOnly;

  final bool isIgnored;

  /// Non-null for an OpenStreetMap-sourced camera (features.md 2.1) —
  /// shown alongside the reversed toggle so this action stays reachable now
  /// that such cameras open this sheet instead of the read-only details one.
  final VoidCallback? onIgnore;
  final VoidCallback? onUnignore;

  @override
  State<DraftSheet> createState() => _DraftSheetState();
}

class _DraftSheetState extends State<DraftSheet> {
  late int _limit = widget.initialLimit;
  late bool _directionReversed = widget.directionReversed;

  @override
  Widget build(BuildContext context) {
    final canSave = widget.onSave != null;
    final canSaveReversedOnly = !canSave && widget.onSaveReversedOnly != null;
    final hasOsmChanges =
        widget.onSaveOsmEdit != null &&
        (_limit != widget.originalLimit ||
            widget.currentDirection.round() != widget.originalDirection);

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
                          onSelected: widget.limitEditable
                              ? (_) => setState(() => _limit = preset)
                              : null,
                        ),
                      ),
                    )
                    .toList(),
              ),
            ),
            if (widget.hasKnownDirection) ...[
              const SizedBox(height: 4),
              SwitchListTile(
                contentPadding: EdgeInsets.zero,
                title: const Text('Senso di marcia contrario'),
                subtitle: const Text(
                  'Rispetto alla direzione della telecamera (marker viola)',
                ),
                value: _directionReversed,
                onChanged: (value) {
                  setState(() => _directionReversed = value);
                  widget.onDirectionReversedChanged?.call(value);
                },
              ),
            ],
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
                    child: Text(
                      (canSave || canSaveReversedOnly) ? 'Annulla' : 'Chiudi',
                    ),
                  ),
                ),
                if (canSave) ...[
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: () => widget.onSave!(_limit),
                      child: const Text('Salva'),
                    ),
                  ),
                ] else if (canSaveReversedOnly) ...[
                  const SizedBox(width: 12),
                  Expanded(
                    child: FilledButton(
                      onPressed: widget.onSaveReversedOnly,
                      child: const Text('Salva'),
                    ),
                  ),
                ],
              ],
            ),
            if (widget.onSaveOsmEdit != null) ...[
              const SizedBox(height: 4),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: hasOsmChanges
                      ? () => widget.onSaveOsmEdit!(_limit)
                      : null,
                  icon: const Icon(Icons.public),
                  label: const Text('Salva su OpenStreetMap'),
                ),
              ),
            ],
            if (widget.onIgnore != null || widget.onUnignore != null) ...[
              const SizedBox(height: 4),
              SizedBox(
                width: double.infinity,
                child: widget.isIgnored
                    ? OutlinedButton.icon(
                        onPressed: widget.onUnignore,
                        icon: const Icon(Icons.visibility),
                        label: const Text('Togli dagli ignorati'),
                      )
                    : OutlinedButton.icon(
                        onPressed: widget.onIgnore,
                        icon: const Icon(Icons.visibility_off),
                        label: const Text('Ignora questo velox'),
                      ),
              ),
            ],
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
