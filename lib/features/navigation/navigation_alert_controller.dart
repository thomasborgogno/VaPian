import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:location/location.dart' as loc;

import '../../core/utils/alert_distance_calculator.dart';
import '../../core/utils/geo.dart';
import '../../core/utils/proximity_calculator.dart';
import '../../core/utils/tolerance_filter.dart';
import '../../data/models/speed_camera.dart';
import '../../data/models/user_settings.dart';
import '../../data/repositories/blacklist_repository.dart';
import '../../data/repositories/speed_camera_repository.dart';
import '../../data/services/navigation_tts_service.dart';
import 'driving_alert_state.dart';

/// Phone-side proximity engine for the navigation mode — a Dart port of the
/// native Android Auto `CameraProximityService`, scoped to the lifetime of
/// `NavigationScreen` (see PLAN.md: this is a deliberate, phone-only
/// extension of the native-first driving-logic decision, not a bridge to the
/// Kotlin service). Tracks GPS, matches the nearest ahead-facing camera,
/// speaks a TTS alert once per camera entering range, and exposes the quick
/// "Segnala"/"Ignora" actions.
class NavigationAlertController extends ChangeNotifier {
  NavigationAlertController({
    required SpeedCameraRepository cameraRepository,
    required BlacklistRepository blacklistRepository,
    NavigationTtsService? ttsService,
    loc.Location? location,
  })  : _cameraRepository = cameraRepository,
        _blacklistRepository = blacklistRepository,
        _tts = ttsService ?? NavigationTtsService(),
        _location = location ?? loc.Location();

  static const double _searchRadiusMeters = 3000;
  static const double _requeryThresholdMeters = 500;
  static const int _locationIntervalMs = 2000;

  /// Below this speed, the GPS course has no reliable movement vector to
  /// derive from — hold the last known heading instead of following the
  /// raw, noisy `data.heading` while stopped (e.g. at a red light).
  static const double _minHeadingSpeedKmh = 5.0;

  final SpeedCameraRepository _cameraRepository;
  final BlacklistRepository _blacklistRepository;
  final NavigationTtsService _tts;
  final loc.Location _location;

  DrivingAlertState _state = const DrivingAlertState();
  DrivingAlertState get state => _state;

  /// Latest known GPS position — null until the first location fix arrives.
  /// Used by the map views to keep the camera centered on the driver.
  double? get currentLat => _currentLat;
  double? get currentLng => _currentLng;

  UserSettings _settings = const UserSettings();
  Set<String> _ignoredIds = const {};
  final Set<String> _sessionIgnoredIds = {};
  final Set<String> _announcedIds = {};

  double? _currentLat;
  double? _currentLng;
  ({double lat, double lng})? _lastQueryPoint;
  List<SpeedCamera> _nearbyCameras = const [];

  StreamSubscription<loc.LocationData>? _locationSub;
  StreamSubscription<List<SpeedCamera>>? _camerasSub;

  /// Latest settings from `userSettingsProvider` — pushed in by
  /// `NavigationScreen` on every rebuild, since this controller isn't itself
  /// a Riverpod notifier (its lifecycle is tied 1:1 to the screen).
  void updateSettings(UserSettings settings) {
    _settings = settings;
  }

  /// Latest permanent blacklist from `ignoredCameraIdsProvider`.
  void updateIgnoredIds(Set<String> ids) {
    _ignoredIds = ids;
  }

  /// Requests location permission/service and starts the GPS stream.
  /// Returns false if permission/service couldn't be obtained.
  Future<bool> start() async {
    if (!await _ensureLocationReady()) return false;
    await _location.changeSettings(
      accuracy: loc.LocationAccuracy.high,
      interval: _locationIntervalMs,
    );
    _locationSub = _location.onLocationChanged.listen(_onLocation);
    return true;
  }

  Future<bool> _ensureLocationReady() async {
    try {
      if (!await _location.serviceEnabled()) {
        if (!await _location.requestService()) return false;
      }
      var permission = await _location.hasPermission();
      if (permission == loc.PermissionStatus.denied) {
        permission = await _location.requestPermission();
      }
      return permission == loc.PermissionStatus.granted ||
          permission == loc.PermissionStatus.grantedLimited;
    } catch (_) {
      return false;
    }
  }

  void _onLocation(loc.LocationData data) {
    final lat = data.latitude;
    final lng = data.longitude;
    if (lat == null || lng == null) return;
    _currentLat = lat;
    _currentLng = lng;
    final speedKmh = (data.speed ?? 0) * 3.6;
    final headingDeg = speedKmh >= _minHeadingSpeedKmh
        ? (data.heading ?? _state.headingDeg)
        : _state.headingDeg;

    _maybeRequeryCameras(lat, lng);
    final candidate = _pickCandidate(lat, lng, headingDeg);
    _applyCandidate(candidate, lat: lat, lng: lng, speedKmh: speedKmh, headingDeg: headingDeg);
  }

  /// Re-subscribes to `watchNear` only once the driver has moved far enough
  /// from the last query point — same 500m/3000m thresholds as the native
  /// `CameraProximityService`, to avoid re-querying Firestore every tick.
  void _maybeRequeryCameras(double lat, double lng) {
    final last = _lastQueryPoint;
    if (last != null &&
        haversineDistanceMeters(last.lat, last.lng, lat, lng) < _requeryThresholdMeters) {
      return;
    }
    _lastQueryPoint = (lat: lat, lng: lng);
    _camerasSub?.cancel();
    _camerasSub = _cameraRepository.watchNear(lat, lng, _searchRadiusMeters).listen((cameras) {
      _nearbyCameras = cameras;
    });
  }

  SpeedCamera? _pickCandidate(double lat, double lng, double headingDeg) {
    SpeedCamera? best;
    var bestDistance = double.infinity;
    for (final camera in _nearbyCameras) {
      if (camera.status != SpeedCameraStatus.approved) continue;
      if (_ignoredIds.contains(camera.id) || _sessionIgnoredIds.contains(camera.id)) continue;
      if (!isAheadInTravelDirection(
        userLat: lat,
        userLng: lng,
        headingDeg: headingDeg,
        camera: camera,
      )) {
        continue;
      }
      final distance = haversineDistanceMeters(lat, lng, camera.lat, camera.lng);
      if (distance < bestDistance) {
        bestDistance = distance;
        best = camera;
      }
    }
    return best;
  }

  void _applyCandidate(
    SpeedCamera? camera, {
    required double lat,
    required double lng,
    required double speedKmh,
    required double headingDeg,
  }) {
    if (camera == null) {
      _state = DrivingAlertState(speedKmh: speedKmh, headingDeg: headingDeg);
      notifyListeners();
      return;
    }

    final distance = haversineDistanceMeters(lat, lng, camera.lat, camera.lng);
    final threshold = alertDistanceMeters(
      settings: _settings,
      speedKmh: speedKmh,
      limitKmh: camera.limit,
    );
    final withinRange = distance <= threshold;
    final overLimit = camera.limit != null && speedKmh > camera.limit!;

    if (!withinRange) {
      _announcedIds.remove(camera.id);
    }

    _state = DrivingAlertState(
      camera: camera,
      distanceMeters: distance,
      speedKmh: speedKmh,
      headingDeg: headingDeg,
      withinRange: withinRange,
      overLimit: overLimit,
    );
    notifyListeners();

    if (withinRange && _announcedIds.add(camera.id)) {
      if (shouldPlayAudio(
        speedKmh: speedKmh,
        limitKmh: camera.limit,
        toleranceFilterEnabled: _settings.toleranceFilterEnabled,
      )) {
        _tts.speak(_alertPhrase(distance, camera.limit));
      }
    }
  }

  /// Same Italian phrasing as the native `CameraProximityService.speakAlert`,
  /// for a consistent voice-alert experience across Android Auto and phone.
  String _alertPhrase(double distanceMeters, int? limitKmh) {
    final distanceText = distanceMeters >= 1000
        ? '${(distanceMeters / 1000).round()} chilometri'
        : '${distanceMeters.round()} metri';
    final limitText = limitKmh != null ? ' Limite $limitKmh chilometri orari.' : '';
    return 'Ocio! autovelox a $distanceText.$limitText';
  }

  /// Quick "Segnala Velox": inserts a pending report at the current GPS
  /// position, to be reviewed/adjusted later via the already-implemented
  /// `PendingReviewSheet` + map draft flow once navigation mode is closed.
  Future<void> reportCameraNow(String uid) async {
    final lat = _currentLat;
    final lng = _currentLng;
    if (lat == null || lng == null) return;
    await _cameraRepository.addCamera(
      SpeedCamera(
        id: '',
        lat: lat,
        lng: lng,
        limit: estimateSpeedLimitKmh(_state.speedKmh),
        direction: _state.headingDeg.round(),
        status: SpeedCameraStatus.pending,
        source: SpeedCameraSource.user,
        geohash: encodeGeohash(lat, lng, precision: 9),
        createdBy: uid,
      ),
    );
  }

  /// Quick "Ignora Velox" — only for OSM-sourced cameras (features.md 3.3.1):
  /// suppresses it immediately for the rest of this drive, and queues it for
  /// phone-side blacklist confirmation once out of navigation mode.
  Future<void> ignoreActiveCamera(String uid) async {
    final camera = _state.camera;
    if (camera == null || camera.source != SpeedCameraSource.openstreetmap) return;
    _sessionIgnoredIds.add(camera.id);
    _announcedIds.remove(camera.id);
    _state = _state.copyWith(
      clearCamera: true,
      clearDistance: true,
      withinRange: false,
      overLimit: false,
    );
    notifyListeners();
    await _blacklistRepository.queueIgnore(uid, camera.id);
  }

  @override
  void dispose() {
    _locationSub?.cancel();
    _camerasSub?.cancel();
    _tts.stop();
    super.dispose();
  }
}
