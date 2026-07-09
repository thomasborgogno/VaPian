import 'package:flutter/material.dart';

import '../../data/services/osm_auth_service.dart';

/// Login/logout with the user's own OpenStreetMap account (features.md 4),
/// separate from the app's own Google/guest auth — needed only to publish
/// changesets, so it manages its own state (secure storage) rather than the
/// app's Firestore settings doc.
class OsmAuthSection extends StatefulWidget {
  const OsmAuthSection({super.key});

  @override
  State<OsmAuthSection> createState() => _OsmAuthSectionState();
}

class _OsmAuthSectionState extends State<OsmAuthSection> {
  final _authService = OsmAuthService();
  bool _isLinked = false;
  bool _isLoading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _refreshLinkedStatus();
  }

  Future<void> _refreshLinkedStatus() async {
    final linked = await _authService.isLinked();
    if (mounted) {
      setState(() {
        _isLinked = linked;
        _isLoading = false;
      });
    }
  }

  Future<void> _signIn() async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      await _authService.signIn();
    } catch (e) {
      if (mounted) setState(() => _error = 'Accesso OpenStreetMap non riuscito: $e');
    }
    await _refreshLinkedStatus();
  }

  Future<void> _signOut() async {
    await _authService.signOut();
    await _refreshLinkedStatus();
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Account OpenStreetMap', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 4),
        Text(
          'Collega il tuo account OSM per pubblicare le segnalazioni verificate sulla '
          'mappa mondiale.',
          style: Theme.of(context).textTheme.bodySmall,
        ),
        const SizedBox(height: 12),
        if (_isLoading)
          const SizedBox(
            width: 24,
            height: 24,
            child: CircularProgressIndicator(strokeWidth: 2),
          )
        else if (_isLinked)
          OutlinedButton.icon(
            onPressed: _signOut,
            icon: const Icon(Icons.link_off),
            label: const Text('Disconnetti OpenStreetMap'),
          )
        else
          FilledButton.icon(
            onPressed: _signIn,
            icon: const Icon(Icons.link),
            label: const Text('Accedi con OpenStreetMap'),
          ),
        if (_error != null) ...[
          const SizedBox(height: 8),
          Text(_error!, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        ],
      ],
    );
  }
}
