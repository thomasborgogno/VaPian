import 'package:autovelox_app/data/services/osm_auth_service.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('generateCodeVerifier', () {
    test('is URL-safe (no padding, no reserved base64 chars)', () {
      final verifier = generateCodeVerifier();
      expect(verifier, isNot(contains('=')));
      expect(verifier, isNot(contains('+')));
      expect(verifier, isNot(contains('/')));
    });

    test('is different on every call', () {
      expect(generateCodeVerifier(), isNot(generateCodeVerifier()));
    });
  });

  group('codeChallengeFor', () {
    test('is deterministic for the same verifier', () {
      const verifier = 'fixed-test-verifier';
      expect(codeChallengeFor(verifier), codeChallengeFor(verifier));
    });

    test('differs between different verifiers', () {
      expect(codeChallengeFor('verifier-a'), isNot(codeChallengeFor('verifier-b')));
    });

    test('is URL-safe', () {
      final challenge = codeChallengeFor(generateCodeVerifier());
      expect(challenge, isNot(contains('=')));
      expect(challenge, isNot(contains('+')));
      expect(challenge, isNot(contains('/')));
    });
  });
}
