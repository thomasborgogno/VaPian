import 'package:cloud_firestore/cloud_firestore.dart';

enum AlertDistanceMode {
  fixed,
  dynamicDistance;

  String get value => this == AlertDistanceMode.fixed ? 'fixed' : 'dynamic';

  static AlertDistanceMode fromValue(String value) =>
      value == 'dynamic' ? AlertDistanceMode.dynamicDistance : AlertDistanceMode.fixed;
}

/// `GoogleMap`'s `mapType` — only the two styles exposed to the user for now
/// (see `map_style_config.dart`); `MapStyleConfig` still owns every other
/// map visual/behavior toggle.
enum MapStyle {
  normal,
  hybrid;

  String get value => this == MapStyle.hybrid ? 'hybrid' : 'normal';

  static MapStyle fromValue(String value) =>
      value == 'hybrid' ? MapStyle.hybrid : MapStyle.normal;
}

class UserSettings {
  const UserSettings({
    this.alertDistanceMode = AlertDistanceMode.fixed,
    this.alertDistanceMeters = 300,
    this.toleranceFilterEnabled = true,
    this.mapStyle = MapStyle.normal,
    this.osmLinked = false,
    this.lastOsmSyncAt,
    this.osmSyncRegions = const [],
  });

  factory UserSettings.fromMap(Map<String, dynamic> map) {
    return UserSettings(
      alertDistanceMode:
          AlertDistanceMode.fromValue(map['alertDistanceMode'] as String? ?? 'fixed'),
      alertDistanceMeters: map['alertDistanceMeters'] as int? ?? 300,
      toleranceFilterEnabled: map['toleranceFilterEnabled'] as bool? ?? true,
      mapStyle: MapStyle.fromValue(map['mapStyle'] as String? ?? 'normal'),
      osmLinked: map['osmLinked'] as bool? ?? false,
      lastOsmSyncAt: (map['lastOsmSyncAt'] as Timestamp?)?.toDate(),
      osmSyncRegions: (map['osmSyncRegions'] as List<dynamic>?)?.cast<String>() ?? const [],
    );
  }

  /// 100-1000 meters, per features.md 2.2.
  final AlertDistanceMode alertDistanceMode;
  final int alertDistanceMeters;
  final bool toleranceFilterEnabled;
  final MapStyle mapStyle;
  final bool osmLinked;
  final DateTime? lastOsmSyncAt;

  /// Italian regions (see `italianRegions`) selected for the manual OSM
  /// sync — multi-select, persisted so the choice survives across sessions.
  final List<String> osmSyncRegions;

  Map<String, dynamic> toMap() {
    return {
      'alertDistanceMode': alertDistanceMode.value,
      'alertDistanceMeters': alertDistanceMeters,
      'toleranceFilterEnabled': toleranceFilterEnabled,
      'mapStyle': mapStyle.value,
      'osmLinked': osmLinked,
      'osmSyncRegions': osmSyncRegions,
      if (lastOsmSyncAt != null) 'lastOsmSyncAt': Timestamp.fromDate(lastOsmSyncAt!),
    };
  }

  UserSettings copyWith({
    AlertDistanceMode? alertDistanceMode,
    int? alertDistanceMeters,
    bool? toleranceFilterEnabled,
    MapStyle? mapStyle,
    bool? osmLinked,
    DateTime? lastOsmSyncAt,
    List<String>? osmSyncRegions,
  }) {
    return UserSettings(
      alertDistanceMode: alertDistanceMode ?? this.alertDistanceMode,
      alertDistanceMeters: alertDistanceMeters ?? this.alertDistanceMeters,
      toleranceFilterEnabled: toleranceFilterEnabled ?? this.toleranceFilterEnabled,
      mapStyle: mapStyle ?? this.mapStyle,
      osmLinked: osmLinked ?? this.osmLinked,
      lastOsmSyncAt: lastOsmSyncAt ?? this.lastOsmSyncAt,
      osmSyncRegions: osmSyncRegions ?? this.osmSyncRegions,
    );
  }
}
