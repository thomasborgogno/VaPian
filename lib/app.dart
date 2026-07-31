import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/utils/device_form_factor.dart';
import 'data/providers.dart';
import 'features/auth/login_screen.dart';
import 'features/map/map_screen.dart';
import 'features/navigation/navigation_screen.dart';

class App extends ConsumerWidget {
  const App({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    ref.watch(syncServiceProvider);
    final authState = ref.watch(authStateProvider);

    return MaterialApp(
      title: 'VaPian!',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(seedColor: Colors.teal),
      ),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.teal,
          brightness: Brightness.dark,
        ),
      ),
      themeMode: ThemeMode.system,
      home: authState.when(
        data: (user) => user == null ? const LoginScreen() : const _AutoNavigationLauncher(),
        loading: () =>
            const Scaffold(body: Center(child: CircularProgressIndicator())),
        error: (error, _) =>
            Scaffold(body: Center(child: Text('Errore: $error'))),
      ),
    );
  }
}

/// Wraps `MapScreen` (the real `home`) and, right after a successful login,
/// jumps straight into navigation mode if the app is opened on a tablet in
/// landscape — the expected in-car mounting orientation. Pushing (rather
/// than swapping `home`) keeps `NavigationScreen`'s existing exit button
/// (`Navigator.pop`) working unchanged.
class _AutoNavigationLauncher extends StatefulWidget {
  const _AutoNavigationLauncher();

  @override
  State<_AutoNavigationLauncher> createState() => _AutoNavigationLauncherState();
}

class _AutoNavigationLauncherState extends State<_AutoNavigationLauncher> {
  bool _checked = false;

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (_checked) return;
    _checked = true;
    final isTablet = isTabletFormFactor(context);
    final isLandscape = MediaQuery.orientationOf(context) == Orientation.landscape;
    if (isTablet && isLandscape) {
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted) return;
        Navigator.of(context).push(MaterialPageRoute(builder: (context) => const NavigationScreen()));
      });
    }
  }

  @override
  Widget build(BuildContext context) => const MapScreen();
}
