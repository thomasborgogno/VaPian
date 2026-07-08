import 'package:cloud_firestore/cloud_firestore.dart';

class IgnoredCamera {
  const IgnoredCamera({required this.cameraId, this.ignoredAt});

  factory IgnoredCamera.fromMap(String cameraId, Map<String, dynamic> map) {
    return IgnoredCamera(
      cameraId: cameraId,
      ignoredAt: (map['ignoredAt'] as Timestamp?)?.toDate(),
    );
  }

  factory IgnoredCamera.fromFirestore(DocumentSnapshot<Map<String, dynamic>> doc) {
    return IgnoredCamera.fromMap(doc.id, doc.data() ?? const {});
  }

  final String cameraId;
  final DateTime? ignoredAt;

  Map<String, dynamic> toMap() {
    return {
      if (ignoredAt != null) 'ignoredAt': Timestamp.fromDate(ignoredAt!),
    };
  }
}
