import 'package:shared_preferences/shared_preferences.dart';

/// Local-only (non-Firestore) navigation-mode preferences: deliberately
/// device-scoped rather than synced, since the view variant is a per-device
/// display preference, not user data — and it must be readable instantly on
/// cold start, without waiting on network/Firestore cache.
class NavigationPrefsRepository {
  static const _variantKey = 'navigation_variant';

  Future<String?> readVariant() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_variantKey);
  }

  Future<void> writeVariant(String value) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_variantKey, value);
  }
}
