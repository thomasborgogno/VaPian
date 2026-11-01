package com.bortho.autovelox.data.remote

import com.bortho.autovelox.core.model.SpeedCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads `highway=speed_camera` / `enforcement=maxspeed` nodes from public
 * Overpass instances, one Italian region at a time (the whole country in one
 * query risks timeouts). When an instance is overloaded, unreachable or times
 * out, the next one is tried — they all serve the same global data.
 */
@Singleton
class OverpassClient @Inject constructor() {

    suspend fun fetchSpeedCameras(region: String): List<SpeedCamera> = withContext(Dispatchers.IO) {
        val query = overpassQuery(region)
        val failures = mutableListOf<String>()
        for (endpoint in ENDPOINTS) {
            try {
                return@withContext fetchFrom(endpoint, query)
            } catch (failure: RetryableOverpassFailure) {
                failures += "${URL(endpoint).host}: ${failure.reason}"
            }
        }
        throw OverpassException(
            "Nessun server Overpass ha risposto per $region (${failures.joinToString("; ")}). Riprova tra qualche minuto.",
        )
    }

    private fun fetchFrom(endpoint: String, query: String): List<SpeedCamera> {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 90_000
            // Overpass answers 406 to generic library user agents.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            try {
                connection.outputStream.use { it.write("data=${URLEncoder.encode(query, "UTF-8")}".toByteArray()) }
            } catch (e: IOException) {
                throw RetryableOverpassFailure("errore di rete (${e.message})")
            }
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                throw RetryableOverpassFailure("errore di rete (${e.message})")
            }
            if (code == 400) throw OverpassException("Overpass ha rifiutato la query (400)")
            if (code != 200) throw RetryableOverpassFailure("HTTP $code")
            val body = try {
                connection.inputStream.bufferedReader().use { it.readText() }
            } catch (e: IOException) {
                throw RetryableOverpassFailure("errore di rete (${e.message})")
            }
            return try {
                parseOverpassResponse(body)
            } catch (e: SerializationException) {
                // An overloaded instance can answer 200 with an HTML error page.
                throw RetryableOverpassFailure("risposta non valida")
            } catch (e: IllegalArgumentException) {
                throw RetryableOverpassFailure("risposta non valida")
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        val ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
        private const val USER_AGENT = "VaPian/1.0 (com.bortho.autovelox)"
    }
}
