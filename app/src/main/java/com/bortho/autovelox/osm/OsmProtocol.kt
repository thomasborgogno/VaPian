package com.bortho.autovelox.osm

import com.bortho.autovelox.core.model.SpeedCamera
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** Pure pieces of the OpenStreetMap protocol (PKCE and the API 0.6 XML payloads), kept free of Android for unit tests. */
object OsmProtocol {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** PKCE (RFC 7636) code verifier: 64 random bytes, base64url without padding. */
    fun codeVerifier(random: SecureRandom = SecureRandom()): String =
        ByteArray(64).also(random::nextBytes).let(b64::encodeToString)

    /** PKCE `S256` challenge for [verifier]. */
    fun codeChallenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("\"", "&quot;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    fun changesetXml(createdBy: String, comment: String): String =
        "<osm><changeset><tag k=\"created_by\" v=\"${escape(createdBy)}\"/><tag k=\"comment\" v=\"${escape(comment)}\"/></changeset></osm>"

    /** New node for [camera]: `maxspeed` / `direction` are left out when unknown rather than invented. */
    fun newNodeXml(changesetId: Long, camera: SpeedCamera): String {
        val tags = StringBuilder("<tag k=\"highway\" v=\"speed_camera\"/>")
        camera.limit?.let { tags.append("<tag k=\"maxspeed\" v=\"$it\"/>") }
        camera.direction?.let { tags.append("<tag k=\"direction\" v=\"$it\"/>") }
        return "<osm><node changeset=\"$changesetId\" lat=\"${camera.lat}\" lon=\"${camera.lng}\">$tags</node></osm>"
    }

    /** The API replaces a node's tags wholesale, so the full current tag set is sent back. */
    fun updateNodeXml(changesetId: Long, nodeId: Long, version: Long, lat: Double, lon: Double, tags: Map<String, String>): String {
        val tagsXml = tags.entries.joinToString("") { "<tag k=\"${escape(it.key)}\" v=\"${escape(it.value)}\"/>" }
        return "<osm><node id=\"$nodeId\" version=\"$version\" changeset=\"$changesetId\" lat=\"$lat\" lon=\"$lon\">$tagsXml</node></osm>"
    }

    /** Deleting needs the current version, and (undocumented) the OSM parser also wants lat/lon on the element. */
    fun deleteNodeXml(changesetId: Long, nodeId: Long, version: Long, lat: Double, lon: Double): String =
        "<osm><node id=\"$nodeId\" version=\"$version\" changeset=\"$changesetId\" lat=\"$lat\" lon=\"$lon\"/></osm>"
}
