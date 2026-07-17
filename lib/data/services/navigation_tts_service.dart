import 'package:flutter_tts/flutter_tts.dart';

/// Speaks proximity alerts for the phone-side navigation mode — a thin
/// wrapper around `FlutterTts` so `NavigationAlertController` stays testable
/// (same pattern as `OsmAuthService`/`OsmEditService`). Italian voice only,
/// mirroring the native Android Auto `CameraProximityService`'s TTS.
class NavigationTtsService {
  NavigationTtsService({FlutterTts? tts}) : _tts = tts ?? FlutterTts() {
    _ready = _tts.setLanguage('it-IT');
  }

  final FlutterTts _tts;
  late final Future<void> _ready;

  Future<void> speak(String text) async {
    await _ready;
    await _tts.speak(text);
  }

  Future<void> stop() => _tts.stop();
}
