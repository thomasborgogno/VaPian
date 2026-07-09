import 'package:firebase_auth/firebase_auth.dart';
import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../core/widgets/user_avatar.dart';
import '../../data/models/user_settings.dart';
import '../../data/providers.dart';

class SettingsScreen extends ConsumerWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final settingsAsync = ref.watch(userSettingsProvider);
    final user = ref.watch(authStateProvider).value;

    return Scaffold(
      appBar: AppBar(title: const Text('Impostazioni')),
      body: settingsAsync.when(
        data: (settings) => _SettingsBody(initialSettings: settings, user: user),
        loading: () => const Center(child: CircularProgressIndicator()),
        error: (error, _) => Center(child: Text('Errore: $error')),
      ),
    );
  }
}

/// Owns the local editable copy of [UserSettings]: the Firestore stream
/// stays the source of truth on load, but re-deriving the UI from every
/// stream echo after our own writes would fight the slider mid-drag.
class _SettingsBody extends ConsumerStatefulWidget {
  const _SettingsBody({required this.initialSettings, required this.user});

  final UserSettings initialSettings;
  final User? user;

  @override
  ConsumerState<_SettingsBody> createState() => _SettingsBodyState();
}

class _SettingsBodyState extends ConsumerState<_SettingsBody> {
  late UserSettings _settings = widget.initialSettings;

  void _applyChange(UserSettings Function(UserSettings current) transform) {
    final updated = transform(_settings);
    setState(() => _settings = updated);
    final uid = ref.read(authStateProvider).value?.uid;
    if (uid != null) {
      ref.read(settingsRepositoryProvider).updateSettings(uid, updated);
    }
  }

  @override
  Widget build(BuildContext context) {
    final isDynamic = _settings.alertDistanceMode == AlertDistanceMode.dynamicDistance;

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        ListTile(
          contentPadding: EdgeInsets.zero,
          leading: UserAvatar(user: widget.user, radius: 24),
          title: Text(
            widget.user?.displayName ?? (widget.user?.isAnonymous ?? true ? 'Ospite' : 'Utente'),
          ),
          subtitle: widget.user?.email != null ? Text(widget.user!.email!) : null,
          trailing: OutlinedButton.icon(
            onPressed: () => ref.read(authServiceProvider).signOut(),
            icon: const Icon(Icons.logout),
            label: const Text('Esci'),
          ),
        ),
        const Divider(height: 32),
        Text('Distanza di preavviso', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        SegmentedButton<AlertDistanceMode>(
          segments: const [
            ButtonSegment(value: AlertDistanceMode.fixed, label: Text('Fissa')),
            ButtonSegment(value: AlertDistanceMode.dynamicDistance, label: Text('Dinamica')),
          ],
          selected: {_settings.alertDistanceMode},
          onSelectionChanged: (selection) =>
              _applyChange((s) => s.copyWith(alertDistanceMode: selection.first)),
        ),
        const SizedBox(height: 16),
        if (isDynamic)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 8),
            child: Text(
              'La distanza di preavviso viene calcolata in tempo reale in base allo '
              'scostamento tra la velocità istantanea e il limite del velox.',
              style: Theme.of(context).textTheme.bodySmall,
            ),
          )
        else ...[
          Text('${_settings.alertDistanceMeters} metri'),
          Slider(
            value: _settings.alertDistanceMeters.toDouble(),
            min: 100,
            max: 1000,
            divisions: 18,
            label: '${_settings.alertDistanceMeters} m',
            onChanged: (value) =>
                _applyChange((s) => s.copyWith(alertDistanceMeters: value.round())),
          ),
        ],
        const Divider(height: 32),
        SwitchListTile(
          contentPadding: EdgeInsets.zero,
          title: const Text('Filtro tolleranza intelligente'),
          subtitle: const Text(
            'Nessun avviso sonoro se la velocità è inferiore di almeno il 10% al limite.',
          ),
          value: _settings.toleranceFilterEnabled,
          onChanged: (value) => _applyChange((s) => s.copyWith(toleranceFilterEnabled: value)),
        ),
      ],
    );
  }
}
