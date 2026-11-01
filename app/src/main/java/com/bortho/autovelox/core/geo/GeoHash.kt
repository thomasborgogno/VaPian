package com.bortho.autovelox.core.geo

/**
 * Base32 geohash encoding plus the "cell and its 8 neighbors" prefix cover
 * used for proximity queries (Firestore range queries and local `LIKE`).
 * Hashes stored in `speed_cameras.geohash` are always full precision (9).
 */
object GeoHash {
    private const val BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    const val FULL_PRECISION = 9

    /** Approximate cell width in meters for precision 1..9. */
    private val cellWidthMeters = doubleArrayOf(
        5_009_400.0, 1_252_300.0, 156_500.0, 39_100.0, 4_890.0, 1_220.0, 152.9, 38.2, 4.77,
    )

    fun encode(latitude: Double, longitude: Double, precision: Int = FULL_PRECISION): String {
        var latMin = -90.0
        var latMax = 90.0
        var lngMin = -180.0
        var lngMax = 180.0
        val out = StringBuilder()
        var bit = 0
        var ch = 0
        var evenBit = true
        while (out.length < precision) {
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
                out.append(BASE32[ch])
                bit = 0
                ch = 0
            }
        }
        return out.toString()
    }

    private data class Cell(val latMin: Double, val latMax: Double, val lngMin: Double, val lngMax: Double)

    private fun decodeCell(hash: String): Cell {
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
        return Cell(latMin, latMax, lngMin, lngMax)
    }

    /** Longest precision whose cell is still at least [radiusMeters] wide. */
    fun precisionForRadius(radiusMeters: Double): Int {
        for (i in cellWidthMeters.indices.reversed()) {
            if (cellWidthMeters[i] >= radiusMeters) return i + 1
        }
        return 1
    }

    private fun wrapLng(lng: Double): Double {
        var wrapped = lng
        while (wrapped > 180) wrapped -= 360
        while (wrapped < -180) wrapped += 360
        return wrapped
    }

    /**
     * Prefixes of the cell containing the point plus its 8 neighbors, at the
     * precision suited to [radiusMeters]: a point within the radius can sit in
     * an adjacent cell, so the center cell alone would miss it.
     */
    fun neighborPrefixes(latitude: Double, longitude: Double, radiusMeters: Double): Set<String> {
        val precision = precisionForRadius(radiusMeters)
        val centerHash = encode(latitude, longitude, precision)
        val cell = decodeCell(centerHash)
        val latHeight = cell.latMax - cell.latMin
        val lngWidth = cell.lngMax - cell.lngMin
        val centerLat = (cell.latMin + cell.latMax) / 2
        val centerLng = (cell.lngMin + cell.lngMax) / 2

        val prefixes = linkedSetOf(centerHash)
        for (dLat in -1..1) {
            for (dLng in -1..1) {
                if (dLat == 0 && dLng == 0) continue
                prefixes.add(
                    encode(
                        (centerLat + dLat * latHeight).coerceIn(-90.0, 90.0),
                        wrapLng(centerLng + dLng * lngWidth),
                        precision,
                    ),
                )
            }
        }
        return prefixes
    }
}
