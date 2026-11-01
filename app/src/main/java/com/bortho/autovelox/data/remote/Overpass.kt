package com.bortho.autovelox.data.remote

import com.bortho.autovelox.core.geo.GeoHash
import com.bortho.autovelox.core.model.CameraSource
import com.bortho.autovelox.core.model.CameraStatus
import com.bortho.autovelox.core.model.SpeedCamera
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class OverpassException(message: String) : Exception(message)

/** An endpoint failed in a way worth retrying on another instance. */
internal class RetryableOverpassFailure(val reason: String) : Exception(reason)

private val COMPASS = mapOf(
    "N" to 0, "NNE" to 23, "NE" to 45, "ENE" to 68, "E" to 90, "ESE" to 113, "SE" to 135, "SSE" to 158,
    "S" to 180, "SSW" to 203, "SW" to 225, "WSW" to 248, "W" to 270, "WNW" to 293, "NW" to 315, "NNW" to 338,
)

/** OSM `maxspeed`: a km/h number, tolerating a trailing unit ("50 mph" reads as 50). Null if unparseable. */
fun parseMaxSpeedKmh(raw: String?): Int? = raw?.let { Regex("\\d+").find(it)?.value?.toIntOrNull() }

/** OSM `direction`: a numeric bearing or a compass abbreviation. */
fun parseDirectionDegrees(raw: String?): Int? {
    val trimmed = raw?.trim() ?: return null
    trimmed.toIntOrNull()?.let { return ((it % 360) + 360) % 360 }
    return COMPASS[trimmed.uppercase()]
}

/** Builds a [SpeedCamera] list from an Overpass JSON response (`out body` of nodes). */
fun parseOverpassResponse(body: String): List<SpeedCamera> {
    val root = Json.parseToJsonElement(body).jsonObject
    val remark = root["remark"]?.jsonPrimitive?.contentOrNull
    if (remark != null && remark.contains("runtime error")) throw RetryableOverpassFailure(remark)
    val elements = root["elements"]?.jsonArray ?: return emptyList()
    return elements.mapNotNull { element ->
        val node = element.jsonObject
        val id = node["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
        val lat = node["lat"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val lng = node["lon"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
        val tags: JsonObject = node["tags"] as? JsonObject ?: JsonObject(emptyMap())
        fun tag(name: String) = (tags[name] as? JsonPrimitive)?.contentOrNull
        SpeedCamera(
            id = SpeedCamera.osmCameraId(id),
            lat = lat,
            lng = lng,
            limit = parseMaxSpeedKmh(tag("maxspeed")),
            direction = parseDirectionDegrees(tag("direction")),
            status = CameraStatus.APPROVED,
            source = CameraSource.OPENSTREETMAP,
            geohash = GeoHash.encode(lat, lng),
            osmNodeId = id,
        )
    }
}

/** Query for one Italian region (matched on `name:it`, which is the plain name for all 20). */
fun overpassQuery(region: String): String = """
[out:json][timeout:60];
area["name:it"="$region"]["admin_level"="4"]["boundary"="administrative"]->.searchArea;
(
  node["highway"="speed_camera"](area.searchArea);
  node["enforcement"="maxspeed"](area.searchArea);
);
out body;
""".trimIndent()
