import 'package:cloud_firestore/cloud_firestore.dart';

enum SpeedCameraStatus {
  approved,
  pending;

  static SpeedCameraStatus fromValue(String value) =>
      SpeedCameraStatus.values.byName(value);
}

enum SpeedCameraSource {
  user,
  openstreetmap;

  static SpeedCameraSource fromValue(String value) =>
      SpeedCameraSource.values.byName(value);
}

class SpeedCamera {
  const SpeedCamera({
    required this.id,
    required this.lat,
    required this.lng,
    this.limit,
    required this.status,
    required this.source,
    required this.geohash,
    this.direction,
    this.createdBy,
    this.createdAt,
    this.updatedAt,
  });

  factory SpeedCamera.fromMap(String id, Map<String, dynamic> map) {
    return SpeedCamera(
      id: id,
      lat: (map['lat'] as num).toDouble(),
      lng: (map['lng'] as num).toDouble(),
      limit: map['limit'] as int?,
      direction: map['direction'] as int?,
      status: SpeedCameraStatus.fromValue(map['status'] as String),
      source: SpeedCameraSource.fromValue(map['source'] as String),
      geohash: map['geohash'] as String,
      createdBy: map['createdBy'] as String?,
      createdAt: (map['createdAt'] as Timestamp?)?.toDate(),
      updatedAt: (map['updatedAt'] as Timestamp?)?.toDate(),
    );
  }

  factory SpeedCamera.fromFirestore(DocumentSnapshot<Map<String, dynamic>> doc) {
    return SpeedCamera.fromMap(doc.id, doc.data()!);
  }

  final String id;
  final double lat;
  final double lng;
  final int? limit;
  final int? direction;
  final SpeedCameraStatus status;
  final SpeedCameraSource source;
  final String geohash;
  final String? createdBy;
  final DateTime? createdAt;
  final DateTime? updatedAt;

  Map<String, dynamic> toMap() {
    return {
      'lat': lat,
      'lng': lng,
      if (limit != null) 'limit': limit,
      if (direction != null) 'direction': direction,
      'status': status.name,
      'source': source.name,
      'geohash': geohash,
      if (createdBy != null) 'createdBy': createdBy,
      if (createdAt != null) 'createdAt': Timestamp.fromDate(createdAt!),
      if (updatedAt != null) 'updatedAt': Timestamp.fromDate(updatedAt!),
    };
  }

  SpeedCamera copyWith({
    double? lat,
    double? lng,
    int? limit,
    int? direction,
    SpeedCameraStatus? status,
    SpeedCameraSource? source,
    String? geohash,
    String? createdBy,
    DateTime? createdAt,
    DateTime? updatedAt,
  }) {
    return SpeedCamera(
      id: id,
      lat: lat ?? this.lat,
      lng: lng ?? this.lng,
      limit: limit ?? this.limit,
      direction: direction ?? this.direction,
      status: status ?? this.status,
      source: source ?? this.source,
      geohash: geohash ?? this.geohash,
      createdBy: createdBy ?? this.createdBy,
      createdAt: createdAt ?? this.createdAt,
      updatedAt: updatedAt ?? this.updatedAt,
    );
  }
}
