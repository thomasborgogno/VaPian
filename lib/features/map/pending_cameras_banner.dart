import 'package:flutter/material.dart';

/// Shown when the current user has own pending reports awaiting review
/// (features.md 4) — tapping "Rivedi" opens `PendingReviewSheet`.
class PendingCamerasBanner extends StatelessWidget {
  const PendingCamerasBanner({super.key, required this.count, required this.onReview});

  final int count;
  final VoidCallback onReview;

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      bottom: false,
      child: Material(
        color: Theme.of(context).colorScheme.primaryContainer,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
          child: Row(
            children: [
              const Icon(Icons.pending_actions, size: 18),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  count == 1
                      ? 'Hai 1 elemento in attesa di revisione.'
                      : 'Hai $count elementi in attesa di revisione.',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ),
              TextButton(onPressed: onReview, child: const Text('Rivedi')),
            ],
          ),
        ),
      ),
    );
  }
}
