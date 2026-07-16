class OsmConfig {
  const OsmConfig._();

  /// Production OpenStreetMap — publishing through this touches real,
  /// public map data (features.md 4), not a sandbox.
  static const String baseUrl = 'https://www.openstreetmap.org';
  static const String apiBaseUrl = 'https://api.openstreetmap.org';

  /// OAuth2 public client (PKCE, no client secret) registered at
  /// https://www.openstreetmap.org/oauth2/applications.
  static const String clientId = 'WsglTNqzI33CX2s3qkUBR8hZsHsWepuP-stXnZXhufk';

  static const String redirectScheme = 'com.bortho.autovelox';
  static const String redirectUri = '$redirectScheme://oauth2redirect';

  /// `write_api` is required to create changesets/nodes; `read_prefs` lets
  /// us read the signed-in username for the changeset `created_by` tag
  /// (osm_edit_service.dart). Accounts linked before this scope was added
  /// need to re-link to pick it up.
  static const List<String> scopes = ['write_api', 'read_prefs'];
}
