package com.bortho.autovelox.carapp.util

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1:1 port of `lib/core/utils/geo.dart` — both clients must index/query
 * `speed_cameras.geohash` with the exact same bit-interleaved base32 encoding,
 * otherwise the two sides would silently miss each other's cameras.
 */
object GeoHashUtil {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    private const val EARTH_RADIUS_METERS = 6371000.0

    // Approximate geohash cell width (meters) for each precision length.
    private val cellWidthMeters = doubleArrayOf(
        5009400.0, // 1
        1252300.0, // 2
        156500.0, // 3
        39100.0, // 4
        4890.0, // 5
        1220.0, // 6
        152.9, // 7
        38.2, // 8
        4.77, // 9
    )

    fun encode(latitude: Double, longitude: Double, precision: Int = 9): String {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        val buffer = StringBuilder()
        var bit = 0
        var ch = 0
        var evenBit = true
        while (buffer.length < precision) {
            if (evenBit) {
                val mid = (lngMin + lngMax) / 2
                if (longitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    lngMin = mid
                } else {
                    lngMax = mid
                }
            } else {
                val mid = (latMin + latMax) / 2
                if (latitude >= mid) {
                    ch = ch or (1 shl (4 - bit))
                    latMin = mid
                } else {
                    latMax = mid
                }
            }
            evenBit = !evenBit
            if (bit < 4) {
                bit++
            } else {
                buffer.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return buffer.toString()
    }

    private fun precisionForRadiusMeters(radiusMeters: Double): Int {
        for (i in cellWidthMeters.indices.reversed()) {
            if (cellWidthMeters[i] >= radiusMeters) return i + 1
        }
        return 1
    }

    data class QueryBounds(val start: String, val end: String)

    /**
     * Single-cell prefix range for a Firestore `geohash` field query — same
     * approximation as `geohashQueryBounds` in `lib/core/utils/geo.dart`.
     * `end` appends `` (above every valid base32 char) so the range
     * matches every full-precision hash that starts with this prefix, not
     * just an exact (never-stored) truncated value.
     *
     * This only covers the one geohash cell containing (`latitude`,
     * `longitude`) — a point within `radiusMeters` can easily fall in a
     * neighboring cell (geohash cells are fixed-position rectangles,
     * unrelated to the query point's location inside them). Prefer
     * [neighborPrefixes], which covers this cell plus its 8 neighbors, for
     * any "cameras near me" query.
     */
    fun queryBounds(latitude: Double, longitude: Double, radiusMeters: Double): QueryBounds {
        val precision = precisionForRadiusMeters(radiusMeters)
        val hash = encode(latitude, longitude, precision)
        return QueryBounds(hash, "$hash")
    }

    private data class CellBounds(
        val latMin: Double,
        val latMax: Double,
        val lngMin: Double,
        val lngMax: Double,
    )

    /**
     * Decodes a geohash back to the lat/lng bounding box it represents,
     * using the same bit-interleaving as [encode] (so the two stay in
     * sync) -- 1:1 port of `_geohashBounds` in `lib/core/utils/geo.dart`.
     */
    private fun decodeBounds(hash: String): CellBounds {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        var evenBit = true
        for (char in hash) {
            val index = BASE32.indexOf(char)
            for (n in 4 downTo 0) {
                val bit = (index shr n) and 1
                if (evenBit) {
                    val mid = (lngMin + lngMax) / 2
                    if (bit == 1) lngMin = mid else lngMax = mid
                } else {
                    val mid = (latMin + latMax) / 2
                    if (bit == 1) latMin = mid else latMax = mid
                }
                evenBit = !evenBit
            }
        }
        return CellBounds(latMin, latMax, lngMin, lngMax)
    }

    private fun wrapLng(lng: Double): Double {
        var wrapped = lng
        while (wrapped > 180) wrapped -= 360
        while (wrapped < -180) wrapped += 360
        return wrapped
    }

    /**
     * Geohash prefixes for the cell containing (`latitude`, `longitude`)
     * plus its 8 neighbors, at the precision [precisionForRadiusMeters]
     * picks for `radiusMeters` -- the standard GeoFire-style "9-cell"
     * coverage so a point within the radius isn't missed just because it
     * sits in an adjacent cell rather than the center one (see
     * [queryBounds]). 1:1 port of `geohashNeighborPrefixes` in
     * `lib/core/utils/geo.dart`.
     *
     * Callers query each prefix as its own Firestore range
     * (`>= prefix && <= prefix + ""`) and merge the results client-side.
     */
    fun neighborPrefixes(latitude: Double, longitude: Double, radiusMeters: Double): Set<String> {
        val precision = precisionForRadiusMeters(radiusMeters)
        val centerHash = encode(latitude, longitude, precision)
        val bounds = decodeBounds(centerHash)
        val latHeight = bounds.latMax - bounds.latMin
        val lngWidth = bounds.lngMax - bounds.lngMin
        val centerLat = (bounds.latMin + bounds.latMax) / 2
        val centerLng = (bounds.lngMin + bounds.lngMax) / 2

        val prefixes = linkedSetOf(centerHash)
        for (dLat in -1..1) {
            for (dLng in -1..1) {
                if (dLat == 0 && dLng == 0) continue
                val neighborLat = (centerLat + dLat * latHeight).coerceIn(-90.0, 90.0)
                val neighborLng = wrapLng(centerLng + dLng * lngWidth)
                prefixes.add(encode(neighborLat, neighborLng, precision))
            }
        }
        return prefixes
    }

    fun haversineDistanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLng = Math.toRadians(lng2 - lng1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2) * sin(dLng / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return EARTH_RADIUS_METERS * c
    }
}
