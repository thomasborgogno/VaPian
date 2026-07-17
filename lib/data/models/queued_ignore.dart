import 'package:cloud_firestore/cloud_firestore.dart';

/// A camera the user tapped "Ignora" on from Android Auto (features.md 3.3.1):
/// queued natively in `users/{uid}/queued_ignores`, awaiting phone-side
/// confirmation before becoming a permanent `IgnoredCamera`.
class QueuedIgnore {
  const QueuedIgnore({required this.cameraId, this.queuedAt});

  factory QueuedIgnore.fromMap(String cameraId, Map<String, dynamic> map) {
    return QueuedIgnore(
      cameraId: cameraId,
      queuedAt: (map['queuedAt'] as Timestamp?)?.toDate(),
    );
  }

  factory QueuedIgnore.fromFirestore(DocumentSnapshot<Map<String, dynamic>> doc) {
    return QueuedIgnore.fromMap(doc.id, doc.data() ?? const {});
  }

  final String cameraId;
  final DateTime? queuedAt;

  Map<String, dynamic> toMap() {
    return {
      if (queuedAt != null) 'queuedAt': Timestamp.fromDate(queuedAt!),
    };
  }
}
