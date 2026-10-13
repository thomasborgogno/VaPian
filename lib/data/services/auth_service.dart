import 'package:firebase_auth/firebase_auth.dart';
import 'package:google_sign_in/google_sign_in.dart';

import '../../core/constants/auth_config.dart';

/// Two entry points, per product decision: "Continua come ospite" (anonymous
/// Firebase Auth, used only for `createdBy`/blacklist scoping) or "Accedi
/// con Google". Signing into Google while already a guest *links* the
/// credential instead of replacing the account, so the guest's uid — and
/// anything already written under it (reports, blacklist) — is preserved.
class AuthService {
  AuthService({FirebaseAuth? firebaseAuth, GoogleSignIn? googleSignIn})
      : _firebaseAuth = firebaseAuth ?? FirebaseAuth.instance,
        _googleSignIn = googleSignIn ?? GoogleSignIn.instance;

  final FirebaseAuth _firebaseAuth;
  final GoogleSignIn _googleSignIn;
  bool _googleSignInInitialized = false;

  Stream<User?> get authStateChanges => _firebaseAuth.authStateChanges();

  User? get currentUser => _firebaseAuth.currentUser;

  Future<UserCredential> signInAsGuest() => _firebaseAuth.signInAnonymously();

  /// Returns null if the user cancels the Google sign-in flow.
  Future<UserCredential?> signInWithGoogle() async {
    await _ensureGoogleSignInInitialized();
    try {
      final account = await _googleSignIn.authenticate();
      final credential = GoogleAuthProvider.credential(
        idToken: account.authentication.idToken,
      );
      final current = _firebaseAuth.currentUser;
      if (current != null && current.isAnonymous) {
        return await current.linkWithCredential(credential);
      }
      return await _firebaseAuth.signInWithCredential(credential);
    } on GoogleSignInException catch (e) {
      if (e.code == GoogleSignInExceptionCode.canceled) return null;
      rethrow;
    }
  }

  Future<void> _ensureGoogleSignInInitialized() async {
    if (_googleSignInInitialized) return;
    await _googleSignIn.initialize(
      serverClientId: AuthConfig.googleServerClientId,
    );
    _googleSignInInitialized = true;
  }

  Future<void> signOut() async {
    await _googleSignIn.signOut();
    await _firebaseAuth.signOut();
  }
}
