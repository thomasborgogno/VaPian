package com.bortho.autovelox.osm

import com.bortho.autovelox.core.model.SpeedCamera
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Writes to OpenStreetMap on behalf of the signed-in user, one changeset per action: publish a user-reported camera as
 * a new node, or delete a node this app published. These calls change real public map data (on production), so the UI
 * always asks for confirmation first, and nothing here runs on its own.
 */
@Singleton
class OsmEditService @Inject constructor(private val account: OsmAccount) {

    /** Publishes [camera] as a new `highway=speed_camera` node and returns the node id. */
    suspend fun publish(camera: SpeedCamera): Long = inChangeset("Aggiunta autovelox segnalato dagli utenti") { changeset ->
        request("PUT", "/api/0.6/node/create", OsmProtocol.newNodeXml(changeset, camera)).trim().toLong()
    }

    /** Deletes a node this app published. [version] is the node's current version (1 right after publishing). */
    suspend fun delete(nodeId: Long, lat: Double, lng: Double, version: Long) {
        inChangeset("Rimozione autovelox segnalato dagli utenti") { changeset ->
            request("DELETE", "/api/0.6/node/$nodeId", OsmProtocol.deleteNodeXml(changeset, nodeId, version, lat, lng))
        }
    }

    private suspend fun <T> inChangeset(comment: String, block: (Long) -> T): T = withContext(Dispatchers.IO) {
        if (account.accessToken() == null) throw OsmException("Accedi con OpenStreetMap prima di modificare.")
        val author = account.username.value ?: "VaPian!"
        val id = request("PUT", "/api/0.6/changeset/create", OsmProtocol.changesetXml(author, comment)).trim().toLong()
        try {
            block(id)
        } finally {
            runCatching { request("PUT", "/api/0.6/changeset/$id/close", null) } // always close, also after a failure
        }
    }

    private fun request(method: String, path: String, body: String?): String {
        val c = (URL(OsmConfig.apiUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer ${account.accessToken()}")
            setRequestProperty("Content-Type", "text/xml")
            if (body != null) {
                doOutput = true
            }
        }
        try {
            if (body != null) c.outputStream.use { it.write(body.toByteArray()) }
            val code = c.responseCode
            if (code != 200) {
                val detail = c.errorStream?.bufferedReader()?.readText().orEmpty().take(200)
                throw OsmException("OpenStreetMap ha risposto $code. $detail".trim())
            }
            return c.inputStream.bufferedReader().readText()
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000
    }
}
