import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../../data/providers.dart';
import '../../data/repositories/navigation_prefs_repository.dart';
import 'navigation_alert_card.dart';
import 'navigation_alert_controller.dart';
import 'navigation_map_view.dart';
import 'navigation_speed_view.dart';
import 'navigation_theme.dart';

/// Declaration order is the cycle order of the view button: speed-only
/// (no map), then the black-themed map, then the light-themed map.
enum _NavigationVariant {
  speed,
  amoled,
  map;

  static _NavigationVariant fromName(String? name) =>
      values.firstWhere((v) => v.name == name, orElse: () => _NavigationVariant.speed);
}

/// Full-screen phone-side "modalità di navigazione": GPS proximity alerts
/// (TTS + visual card) plus the quick "Segnala"/"Ignora" actions, in one of
/// 3 interchangeable views (normal map, AMOLED map, speed-only animation).
/// Keeps the screen awake for the whole session via `wakelock_plus`.
class NavigationScreen extends ConsumerStatefulWidget {
  const NavigationScreen({super.key});

  @override
  ConsumerState<NavigationScreen> createState() => _NavigationScreenState();
}

class _NavigationScreenState extends ConsumerState<NavigationScreen> {
  late final NavigationAlertController _controller;
  late final NavigationPrefsRepository _prefsRepo;
  _NavigationVariant _variant = _NavigationVariant.speed;
  bool _starting = true;

  @override
  void initState() {
    super.initState();
    _controller = NavigationAlertController(
      cameraRepository: ref.read(speedCameraRepositoryProvider),
      blacklistRepository: ref.read(blacklistRepositoryProvider),
    );
    _prefsRepo = ref.read(navigationPrefsRepositoryProvider);
    _controller.addListener(_onControllerChanged);
    WakelockPlus.enable();
    _loadVariant();
    _start();
  }

  Future<void> _loadVariant() async {
    final saved = await _prefsRepo.readVariant();
    if (!mounted) return;
    setState(() => _variant = _NavigationVariant.fromName(saved));
  }

  Future<void> _start() async {
    final ok = await _controller.start();
    if (!mounted) return;
    setState(() => _starting = false);
    if (!ok) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('Permesso posizione necessario per la navigazione.')),
      );
    }
  }

  void _onControllerChanged() {
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    WakelockPlus.disable();
    _controller.removeListener(_onControllerChanged);
    _controller.dispose();
    super.dispose();
  }

  NavigationButtonColors get _buttonColors => switch (_variant) {
        _NavigationVariant.map => NavigationButtonColors.light,
        _NavigationVariant.amoled || _NavigationVariant.speed => NavigationButtonColors.dark,
      };

  void _cycleView() {
    final next = _NavigationVariant.values[(_variant.index + 1) % _NavigationVariant.values.length];
    setState(() => _variant = next);
    unawaited(_prefsRepo.writeVariant(next.name));
  }

  Future<void> _report() async {
    final uid = ref.read(authStateProvider).value?.uid;
    if (uid == null) return;
    await _controller.reportCameraNow(uid);
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Segnalazione salvata: confermala uscendo dalla navigazione.')),
    );
  }

  Future<void> _ignore() async {
    final uid = ref.read(authStateProvider).value?.uid;
    if (uid == null) return;
    await _controller.ignoreActiveCamera(uid);
    if (!mounted) return;
    ScaffoldMessenger.of(
      context,
    ).showSnackBar(const SnackBar(content: Text('Velox ignorato per questa guida.')));
  }

  @override
  Widget build(BuildContext context) {
    // `ref.watch` (rather than `ref.listen`) so the controller also gets
    // today's already-known value on the very first build, not just future
    // changes — `WidgetRef.listen` has no `fireImmediately` option.
    final settings = ref.watch(userSettingsProvider).value;
    if (settings != null) _controller.updateSettings(settings);
    final ignoredIds = ref.watch(ignoredCameraIdsProvider).value;
    if (ignoredIds != null) _controller.updateIgnoredIds(ignoredIds);

    return Scaffold(
      body: _starting
          ? const Center(child: CircularProgressIndicator())
          : Stack(
              fit: StackFit.expand,
              children: [
                switch (_variant) {
                  _NavigationVariant.map => NavigationMapView(controller: _controller, amoled: false),
                  _NavigationVariant.amoled => NavigationMapView(controller: _controller, amoled: true),
                  _NavigationVariant.speed => NavigationSpeedView(controller: _controller),
                },
                NavigationAlertCard(
                  state: _controller.state,
                  onReport: _report,
                  onIgnore: _ignore,
                  onCycleView: _cycleView,
                  onExit: () => Navigator.of(context).pop(),
                  colors: _buttonColors,
                ),
              ],
            ),
    );
  }
}
