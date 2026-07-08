import 'package:cloud_firestore/cloud_firestore.dart';

/// Global "when did anyone last sync from OSM Overpass" marker, backing the
/// Smart Reminder banner in features.md 2.2.
class SyncMetadata {
  const SyncMetadata({this.lastSyncAt, this.camerasSynced = 0});

  factory SyncMetadata.fromMap(Map<String, dynamic> map) {
    return SyncMetadata(
      lastSyncAt: (map['lastSyncAt'] as Timestamp?)?.toDate(),
      camerasSynced: map['camerasSynced'] as int? ?? 0,
    );
  }

  final DateTime? lastSyncAt;
  final int camerasSynced;

  Map<String, dynamic> toMap() {
    return {
      if (lastSyncAt != null) 'lastSyncAt': Timestamp.fromDate(lastSyncAt!),
      'camerasSynced': camerasSynced,
    };
  }

  bool isStale({Duration staleAfter = const Duration(days: 7)}) {
    final syncedAt = lastSyncAt;
    if (syncedAt == null) return true;
    return DateTime.now().difference(syncedAt) > staleAfter;
  }
}
