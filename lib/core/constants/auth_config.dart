class AuthConfig {
  const AuthConfig._();

  /// OAuth 2.0 **Web** client ID from Firebase Console → Authentication →
  /// Sign-in method → Google → Web SDK configuration. Needed so the Google
  /// ID token's audience matches what Firebase Auth verifies against.
  /// TODO: replace after enabling the Google sign-in provider in Firebase.
  static const String googleServerClientId =
      '105468480792-m7ddss7mlifs3r3i343h3gbqipspnpe6.apps.googleusercontent.com';
}
