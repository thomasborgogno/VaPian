import 'dart:convert';
import 'dart:math';

import 'package:crypto/crypto.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_web_auth_2/flutter_web_auth_2.dart';
import 'package:http/http.dart' as http;

import '../../core/constants/osm_config.dart';

class OsmAuthException implements Exception {
  OsmAuthException(this.message);

  final String message;

  @override
  String toString() => message;
}

String generateCodeVerifier() {
  final random = Random.secure();
  final bytes = List<int>.generate(64, (_) => random.nextInt(256));
  return base64Url.encode(bytes).replaceAll('=', '');
}

String codeChallengeFor(String codeVerifier) {
  final digest = sha256.convert(utf8.encode(codeVerifier));
  return base64Url.encode(digest.bytes).replaceAll('=', '');
}

/// OAuth2 Authorization Code + PKCE login against **production**
/// OpenStreetMap (features.md 4) — a public client, so no client secret is
/// embedded in the app. The access token is the only thing persisted, in
/// the platform keychain via [FlutterSecureStorage], never in Firestore.
class OsmAuthService {
  OsmAuthService({http.Client? client, FlutterSecureStorage? storage})
      : _client = client ?? http.Client(),
        _storage = storage ?? const FlutterSecureStorage();

  final http.Client _client;
  final FlutterSecureStorage _storage;

  static const String _tokenKey = 'osm_access_token';

  Future<bool> isLinked() async => (await readAccessToken()) != null;

  Future<String?> readAccessToken() => _storage.read(key: _tokenKey);

  Future<void> signIn() async {
    final codeVerifier = generateCodeVerifier();
    final codeChallenge = codeChallengeFor(codeVerifier);

    final authorizeUrl = Uri.parse('${OsmConfig.baseUrl}/oauth2/authorize').replace(
      queryParameters: {
        'client_id': OsmConfig.clientId,
        'redirect_uri': OsmConfig.redirectUri,
        'response_type': 'code',
        'scope': OsmConfig.scopes.join(' '),
        'code_challenge': codeChallenge,
        'code_challenge_method': 'S256',
      },
    );

    final result = await FlutterWebAuth2.authenticate(
      url: authorizeUrl.toString(),
      callbackUrlScheme: OsmConfig.redirectScheme,
    );

    final code = Uri.parse(result).queryParameters['code'];
    if (code == null) {
      throw OsmAuthException('OpenStreetMap non ha restituito un codice di autorizzazione.');
    }

    final tokenResponse = await _client.post(
      Uri.parse('${OsmConfig.baseUrl}/oauth2/token'),
      body: {
        'client_id': OsmConfig.clientId,
        'grant_type': 'authorization_code',
        'code': code,
        'redirect_uri': OsmConfig.redirectUri,
        'code_verifier': codeVerifier,
      },
    );

    if (tokenResponse.statusCode != 200) {
      throw OsmAuthException('Scambio del token OSM non riuscito (${tokenResponse.statusCode}).');
    }

    final accessToken = jsonDecode(tokenResponse.body)['access_token'] as String?;
    if (accessToken == null) {
      throw OsmAuthException('Risposta OSM senza access_token.');
    }
    await _storage.write(key: _tokenKey, value: accessToken);
  }

  Future<void> signOut() => _storage.delete(key: _tokenKey);
}
