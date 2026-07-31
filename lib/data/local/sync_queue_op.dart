import 'dart:convert';

/// Kind of write recorded in `sync_queue`, replayed by [SyncService] against
/// the matching `SpeedCameraRepository`/`BlacklistRepository` Firestore call
/// once connectivity returns. One value per remote write the app can make
/// while offline.
enum SyncOpType {
  addCamera,
  updateStatus,
  updateCamera,
  updateDirectionReversed,
  deleteCamera,
  ignoreCamera,
  unignoreCamera,
  queueIgnore;

  static SyncOpType fromValue(String value) => SyncOpType.values.byName(value);
}

/// A pending `sync_queue` row, decoded from its `op_type`/`payload` columns.
///
/// [payload] is intentionally a raw `Map<String, dynamic>` (not a typed
/// class per op) — each op only needs a handful of primitive fields and
/// [SyncService] already knows, from [type], which ones to expect.
class SyncQueueOp {
  const SyncQueueOp({
    required this.rowId,
    required this.type,
    required this.payload,
    required this.createdAt,
    required this.attempts,
  });

  factory SyncQueueOp.fromRow(Map<String, Object?> row) {
    return SyncQueueOp(
      rowId: row['id'] as int,
      type: SyncOpType.fromValue(row['op_type'] as String),
      payload: jsonDecode(row['payload'] as String) as Map<String, dynamic>,
      createdAt: DateTime.fromMillisecondsSinceEpoch(row['created_at'] as int),
      attempts: row['attempts'] as int,
    );
  }

  final int rowId;
  final SyncOpType type;
  final Map<String, dynamic> payload;
  final DateTime createdAt;
  final int attempts;

  static String encodePayload(Map<String, dynamic> payload) => jsonEncode(payload);
}
