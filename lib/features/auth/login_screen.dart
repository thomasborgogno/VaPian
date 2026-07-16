import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../data/providers.dart';

class LoginScreen extends ConsumerStatefulWidget {
  const LoginScreen({super.key});

  @override
  ConsumerState<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends ConsumerState<LoginScreen> {
  bool _isLoading = false;
  String? _error;

  Future<void> _runAuthAction(Future<void> Function() action, String failureLabel) async {
    setState(() {
      _isLoading = true;
      _error = null;
    });
    try {
      await action();
    } catch (e) {
      if (mounted) setState(() => _error = '$failureLabel: $e');
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final authService = ref.read(authServiceProvider);

    return Scaffold(
      body: SafeArea(
        child: Align(
          alignment: const Alignment(0, -0.4),
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Image.asset('assets/icon/foreground.png', width: 200, height: 200),
                const SizedBox(height: 16),
                Text('VaPian!', style: Theme.of(context).textTheme.headlineMedium),
                const SizedBox(height: 32),
                if (_isLoading)
                  const CircularProgressIndicator()
                else
                  Column(
                    children: [
                      FilledButton.icon(
                        onPressed: () => _runAuthAction(
                          () => authService.signInWithGoogle(),
                          'Accesso con Google non riuscito',
                        ),
                        icon: const Icon(Icons.login),
                        label: const Text('Accedi con Google'),
                      ),
                      const SizedBox(height: 12),
                      OutlinedButton(
                        onPressed: () => _runAuthAction(
                          () => authService.signInAsGuest(),
                          'Accesso come ospite non riuscito',
                        ),
                        child: const Text('Continua come ospite'),
                      ),
                    ],
                  ),
                if (_error != null) ...[
                  const SizedBox(height: 16),
                  Text(
                    _error!,
                    style: TextStyle(color: Theme.of(context).colorScheme.error),
                    textAlign: TextAlign.center,
                  ),
                ],
              ],
            ),
          ),
        ),
      ),
    );
  }
}
