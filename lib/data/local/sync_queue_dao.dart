import 'package:sqflite/sqflite.dart';

import 'app_database.dart';
import 'sync_queue_op.dart';

/// Enqueue/dequeue helpers for `sync_queue`, shared by the local-first
/// repositories (writers) and [SyncService] (drainer).
class SyncQueueDao {
  const SyncQueueDao(this.db);

  final Database db;

  /// Records that [targetId]'s current local row should be pushed to
  /// Firestore. Coalesces with any already-pending op for the same
  /// `(opType, targetId)` pair — repeated edits to the same id before the
  /// next drain replay as a single "push current state" op, not one op per
  /// edit, and dropping a stale [SyncOpType.deleteCamera]/
  /// [SyncOpType.deleteIgnoredCamera] once a fresh upsert supersedes it.
  Future<void> enqueueUpsert(
    SyncOpType upsertType,
    SyncOpType deleteType,
    String targetId, {
    Map<String, dynamic> payload = const {},
  }) async {
    await db.delete(
      SyncQueueTable.name,
      where:
          '${SyncQueueTable.targetId} = ? AND ${SyncQueueTable.opType} IN (?, ?)',
      whereArgs: [targetId, upsertType.name, deleteType.name],
    );
    await db.insert(SyncQueueTable.name, {
      SyncQueueTable.opType: upsertType.name,
      SyncQueueTable.targetId: targetId,
      SyncQueueTable.payload: SyncQueueOp.encodePayload(payload),
      SyncQueueTable.createdAt: DateTime.now().millisecondsSinceEpoch,
      SyncQueueTable.attempts: 0,
    });
  }

  /// Records that [targetId] should be deleted on Firestore, replacing any
  /// pending upsert/delete for the same id (a delete always wins over a
  /// stale pending upsert).
  Future<void> enqueueDelete(
    SyncOpType upsertType,
    SyncOpType deleteType,
    String targetId, {
    Map<String, dynamic> payload = const {},
  }) async {
    await db.delete(
      SyncQueueTable.name,
      where:
          '${SyncQueueTable.targetId} = ? AND ${SyncQueueTable.opType} IN (?, ?)',
      whereArgs: [targetId, upsertType.name, deleteType.name],
    );
    await db.insert(SyncQueueTable.name, {
      SyncQueueTable.opType: deleteType.name,
      SyncQueueTable.targetId: targetId,
      SyncQueueTable.payload: SyncQueueOp.encodePayload(payload),
      SyncQueueTable.createdAt: DateTime.now().millisecondsSinceEpoch,
      SyncQueueTable.attempts: 0,
    });
  }

  Future<void> enqueue(
    SyncOpType type,
    String targetId, {
    Map<String, dynamic> payload = const {},
  }) async {
    await db.insert(SyncQueueTable.name, {
      SyncQueueTable.opType: type.name,
      SyncQueueTable.targetId: targetId,
      SyncQueueTable.payload: SyncQueueOp.encodePayload(payload),
      SyncQueueTable.createdAt: DateTime.now().millisecondsSinceEpoch,
      SyncQueueTable.attempts: 0,
    });
  }

  Future<List<SyncQueueOp>> pending() async {
    final rows = await db.query(SyncQueueTable.name, orderBy: SyncQueueTable.rowId);
    return rows.map(SyncQueueOp.fromRow).toList();
  }

  Future<int> pendingCount() async {
    final result = await db.rawQuery(
      'SELECT COUNT(*) AS count FROM ${SyncQueueTable.name}',
    );
    return Sqflite.firstIntValue(result) ?? 0;
  }

  Future<void> markAttempted(int rowId) async {
    await db.rawUpdate(
      'UPDATE ${SyncQueueTable.name} SET ${SyncQueueTable.attempts} = ${SyncQueueTable.attempts} + 1 '
      'WHERE ${SyncQueueTable.rowId} = ?',
      [rowId],
    );
  }

  Future<void> remove(int rowId) async {
    await db.delete(SyncQueueTable.name, where: '${SyncQueueTable.rowId} = ?', whereArgs: [rowId]);
  }
}
