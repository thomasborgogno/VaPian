import 'package:flutter/material.dart';

/// features.md 2.2 Smart Reminder: shown when the global OSM sync is more
/// than 7 days old (or has never run) — dismissing it is per-session only,
/// it comes back next time the map screen is rebuilt while still stale.
class SyncReminderBanner extends StatelessWidget {
  const SyncReminderBanner({super.key, required this.onSync, required this.onDismiss});

  final VoidCallback onSync;
  final VoidCallback onDismiss;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      bottom: false,
      child: Material(
        color: Theme.of(context).colorScheme.secondaryContainer,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Row(
            children: [
              Expanded(
                child: Text(
                  'I dati degli autovelox potrebbero non essere aggiornati. '
                  'Sincronizza ora con OpenStreetMap.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              TextButton(onPressed: onSync, child: const Text('Sincronizza')),
              IconButton(
                icon: const Icon(Icons.close),
                iconSize: 18,
                onPressed: onDismiss,
              ),
            ],
          ),
        ),
      ),
    );
  }
}
