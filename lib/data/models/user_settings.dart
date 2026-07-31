import 'package:cloud_firestore/cloud_firestore.dart';

enum AlertDistanceMode {
  fixed,
  dynamicDistance;

  String get value => this == AlertDistanceMode.fixed ? 'fixed' : 'dynamic';

  static AlertDistanceMode fromValue(String value) =>
      value == 'dynamic' ? AlertDistanceMode.dynamicDistance : AlertDistanceMode.fixed;
}

class UserSettings {
  const UserSettings({
    this.alertDistanceMode = AlertDistanceMode.fixed,
    this.alertDistanceMeters = 300,
    this.toleranceFilterEnabled = true,
    this.osmLinked = false,
    this.lastOsmSyncAt,
    this.osmSyncRegions = const [],
    this.offlineMapZones = const [],
  });

  factory UserSettings.fromMap(Map<String, dynamic> map) {
    return UserSettings(
      alertDistanceMode:
          AlertDistanceMode.fromValue(map['alertDistanceMode'] as String? ?? 'fixed'),
      alertDistanceMeters: map['alertDistanceMeters'] as int? ?? 300,
      toleranceFilterEnabled: map['toleranceFilterEnabled'] as bool? ?? true,
      osmLinked: map['osmLinked'] as bool? ?? false,
      lastOsmSyncAt: (map['lastOsmSyncAt'] as Timestamp?)?.toDate(),
      osmSyncRegions: (map['osmSyncRegions'] as List<dynamic>?)?.cast<String>() ?? const [],
      offlineMapZones: (map['offlineMapZones'] as List<dynamic>?)?.cast<String>() ?? const [],
    );
  }

  /// 100-1000 meters, per features.md 2.2.
  final AlertDistanceMode alertDistanceMode;
  final int alertDistanceMeters;
  final bool toleranceFilterEnabled;
  final bool osmLinked;
  final DateTime? lastOsmSyncAt;

  /// Italian regions (see `italianRegions`) selected for the manual OSM
  /// sync — multi-select, persisted so the choice survives across sessions.
  final List<String> osmSyncRegions;

  /// Geofabrik macro-zones (see `italianMacroZones`) downloaded for offline
  /// map tiles + primed local camera data (see
  /// `OfflineMapsSection`/`OfflineTileService`) — separate from
  /// [osmSyncRegions], which is about pulling fresh Overpass data at the
  /// finer 20-region granularity, not about what's available offline.
  final List<String> offlineMapZones;

  Map<String, dynamic> toMap() {
    return {
      'alertDistanceMode': alertDistanceMode.value,
      'alertDistanceMeters': alertDistanceMeters,
      'toleranceFilterEnabled': toleranceFilterEnabled,
      'osmLinked': osmLinked,
      'osmSyncRegions': osmSyncRegions,
      'offlineMapZones': offlineMapZones,
      if (lastOsmSyncAt != null) 'lastOsmSyncAt': Timestamp.fromDate(lastOsmSyncAt!),
    };
  }

  UserSettings copyWith({
    AlertDistanceMode? alertDistanceMode,
    int? alertDistanceMeters,
    bool? toleranceFilterEnabled,
    bool? osmLinked,
    DateTime? lastOsmSyncAt,
    List<String>? osmSyncRegions,
    List<String>? offlineMapZones,
  }) {
    return UserSettings(
      alertDistanceMode: alertDistanceMode ?? this.alertDistanceMode,
      alertDistanceMeters: alertDistanceMeters ?? this.alertDistanceMeters,
      toleranceFilterEnabled: toleranceFilterEnabled ?? this.toleranceFilterEnabled,
      osmLinked: osmLinked ?? this.osmLinked,
      lastOsmSyncAt: lastOsmSyncAt ?? this.lastOsmSyncAt,
      osmSyncRegions: osmSyncRegions ?? this.osmSyncRegions,
      offlineMapZones: offlineMapZones ?? this.offlineMapZones,
    );
  }
}
