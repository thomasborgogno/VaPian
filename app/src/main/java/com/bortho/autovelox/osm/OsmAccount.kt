package com.bortho.autovelox.osm

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.bortho.autovelox.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Where OpenStreetMap lives. Production by default; a debug build can point at the OSM sandbox (see build.gradle.kts). */
object OsmConfig {
    val baseUrl: String get() = BuildConfig.OSM_BASE_URL
    val apiUrl: String get() = BuildConfig.OSM_API_URL
    val clientId: String get() = BuildConfig.OSM_CLIENT_ID
    const val REDIRECT_SCHEME = "com.bortho.autovelox"
    const val REDIRECT_URI = "$REDIRECT_SCHEME://oauth2redirect"

    /** `write_api` creates changesets and nodes; `read_prefs` gives the display name used as `created_by`. */
    const val SCOPES = "write_api read_prefs"
}

class OsmException(message: String) : Exception(message)

/**
 * The user's own OpenStreetMap account (OAuth 2 authorization code + PKCE, public client: no secret in the app),
 * separate from the Firebase/Google identity. Only needed to publish or edit nodes. The access token is the only
 * thing kept, encrypted with an Android Keystore key, and never leaves the phone.
 */
@Singleton
class OsmAccount @Inject constructor(@ApplicationContext private val context: Context) {
    private val prefs = context.getSharedPreferences("osm_account", Context.MODE_PRIVATE)

    private val _linked = MutableStateFlow(readToken() != null)
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    private val _username = MutableStateFlow(prefs.getString(KEY_NAME, null))
    val username: StateFlow<String?> = _username.asStateFlow()

    fun accessToken(): String? = readToken()

    /** Opens the OpenStreetMap authorization page in the browser; the result comes back through [handleRedirect]. */
    fun startSignIn() {
        val verifier = OsmProtocol.codeVerifier()
        prefs.edit().putString(KEY_VERIFIER, verifier).apply()
        val url = Uri.parse("${OsmConfig.baseUrl}/oauth2/authorize").buildUpon()
            .appendQueryParameter("client_id", OsmConfig.clientId)
            .appendQueryParameter("redirect_uri", OsmConfig.REDIRECT_URI)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", OsmConfig.SCOPES)
            .appendQueryParameter("code_challenge", OsmProtocol.codeChallenge(verifier))
            .appendQueryParameter("code_challenge_method", "S256")
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** True if [uri] is the OAuth redirect (so the caller knows it was consumed). */
    fun isRedirect(uri: Uri?): Boolean = uri != null && uri.scheme == OsmConfig.REDIRECT_SCHEME && uri.host == "oauth2redirect"

    /** Finishes the login: trades the code from the redirect for a token. Returns an error text, or null on success. */
    suspend fun handleRedirect(uri: Uri): String? {
        uri.getQueryParameter("error")?.let { return "OpenStreetMap ha rifiutato l'accesso ($it)." }
        val code = uri.getQueryParameter("code") ?: return "OpenStreetMap non ha restituito un codice di autorizzazione."
        val verifier = prefs.getString(KEY_VERIFIER, null) ?: return "Accesso scaduto: riprova."
        return try {
            val body = form(
                "client_id" to OsmConfig.clientId,
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to OsmConfig.REDIRECT_URI,
                "code_verifier" to verifier,
            )
            val response = withContext(Dispatchers.IO) { post("${OsmConfig.baseUrl}/oauth2/token", body) }
            val token = JSONObject(response).optString("access_token").takeIf { it.isNotEmpty() }
                ?: return "Risposta OSM senza access_token."
            saveToken(token)
            prefs.edit().remove(KEY_VERIFIER).apply()
            _linked.value = true
            refreshUsername()
            null
        } catch (e: Exception) {
            "Accesso OpenStreetMap non riuscito: ${e.message}"
        }
    }

    suspend fun refreshUsername() {
        val token = readToken() ?: return
        val name = runCatching {
            withContext(Dispatchers.IO) {
                val c = (URL("${OsmConfig.apiUrl}/api/0.6/user/details.json").openConnection() as HttpURLConnection).apply {
                    setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                try {
                    if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().readText()).getJSONObject("user").getString("display_name")
                } finally {
                    c.disconnect()
                }
            }
        }.getOrNull()
        if (name != null) {
            prefs.edit().putString(KEY_NAME, name).apply()
            _username.value = name
        }
    }

    fun signOut() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_NAME).remove(KEY_VERIFIER).apply()
        _linked.value = false
        _username.value = null
    }

    // ---- token at rest: AES-GCM with a non-exportable Keystore key ----------------------------------------------

    private fun saveToken(token: String) {
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(token.toByteArray())
        prefs.edit().putString(KEY_TOKEN, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    private fun readToken(): String? = runCatching {
        val sealed = Base64.decode(prefs.getString(KEY_TOKEN, null) ?: return null, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, IV_BYTES)) }
        String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }.getOrNull()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun form(vararg pairs: Pair<String, String>) =
        pairs.joinToString("&") { "${URLEncoder.encode(it.first, "UTF-8")}=${URLEncoder.encode(it.second, "UTF-8")}" }

    private fun post(url: String, body: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) throw OsmException("scambio del token non riuscito (${c.responseCode})")
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val KEY_TOKEN = "token"
        const val KEY_NAME = "username"
        const val KEY_VERIFIER = "pkce_verifier"
        const val KEY_ALIAS = "vapian_osm_token"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TIMEOUT_MS = 20_000
    }
}
