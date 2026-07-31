import 'dart:convert';

/// Kind of local change recorded in `sync_queue` and replayed by
/// [SyncService] against Firestore once connectivity returns.
///
/// Deliberately entity-level rather than one value per repository method
/// (`addCamera`/`updateStatus`/`updateCamera`/... all become
/// [upsertCamera]): the local `speed_cameras`/`ignored_cameras` row already
/// holds the current state for its id, so replaying just means "push
/// whatever is in the local row for this id right now" — which also makes
/// repeated edits to the same id naturally coalesce into one pending op
/// (see `_enqueueUpsert`/`_enqueueDelete` in the repositories).
enum SyncOpType {
  upsertCamera,
  deleteCamera,
  upsertIgnoredCamera,
  deleteIgnoredCamera,
  queueIgnore,
  dismissQueuedIgnore;

  static SyncOpType fromValue(String value) => SyncOpType.values.byName(value);
}

/// A pending `sync_queue` row, decoded from its `op_type`/`payload` columns.
class SyncQueueOp {
  const SyncQueueOp({
    required this.rowId,
    required this.type,
    required this.targetId,
    required this.payload,
    required this.createdAt,
    required this.attempts,
  });

  factory SyncQueueOp.fromRow(Map<String, Object?> row) {
    return SyncQueueOp(
      rowId: row['id'] as int,
      type: SyncOpType.fromValue(row['op_type'] as String),
      targetId: row['target_id'] as String,
      payload: jsonDecode(row['payload'] as String) as Map<String, dynamic>,
      createdAt: DateTime.fromMillisecondsSinceEpoch(row['created_at'] as int),
      attempts: row['attempts'] as int,
    );
  }

  final int rowId;
  final SyncOpType type;
  final String targetId;

  /// Extra fields the plain [targetId] doesn't carry — `{'uid': ...}` for
  /// per-user ops (ignored/queued cameras), empty for camera ops.
  final Map<String, dynamic> payload;
  final DateTime createdAt;
  final int attempts;

  static String encodePayload(Map<String, dynamic> payload) => jsonEncode(payload);
}
