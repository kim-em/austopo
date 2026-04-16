package com.kim.austopo.data

import com.kim.austopo.geo.Utm

/**
 * Parses a user-supplied location string into (latitude, longitude) in WGS84
 * degrees. Tries several formats in order; returns null if none match.
 *
 * Supported formats:
 *  1. Google Maps URL (matches `@lat,lon[,zoom]` or `!3dLAT!4dLON`)
 *  2. Decimal: "-37.8, 144.9" or "-37.8 144.9"
 *  3. DMS with hemisphere: "37°48'S 144°54'E", also with seconds and with
 *     plain spaces ("37 48 S 144 54 E").
 *  4. MGA grid ref: "55H 311000 5811000" or "55 311000 5811000"
 *
 * Australian convention: decimals order is lat, lon (south is negative).
 */
object LocationParsers {

    fun parse(input: String): Pair<Double, Double>? {
        val s = input.trim()
        if (s.isEmpty()) return null
        return parseGoogleUrl(s)
            ?: parseMga(s)
            ?: parseDms(s)
            ?: parseDecimal(s)
    }

    // --- Google Maps URL ---

    private val googleAt = Regex("""[/@](-?\d+\.\d+),(-?\d+\.\d+)""")
    private val google3d4d = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")

    internal fun parseGoogleUrl(s: String): Pair<Double, Double>? {
        if (!s.contains("google.") && !s.contains("maps.app.goo.gl")) return null
        google3d4d.find(s)?.let { m ->
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }
        googleAt.find(s)?.let { m ->
            return m.groupValues[1].toDouble() to m.groupValues[2].toDouble()
        }
        return null
    }

    // --- Decimal degrees (lat, lon) ---

    private val decimal = Regex("""^(-?\d+(?:\.\d+)?)[,\s]+(-?\d+(?:\.\d+)?)$""")

    internal fun parseDecimal(s: String): Pair<Double, Double>? {
        val m = decimal.matchEntire(s) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    // --- DMS with hemispheres ---

    // Captures: deg, min, (optional sec), hemisphere — for both coordinates.
    // The minute marker (' or ′) is optional and consumed when present; the
    // seconds group is optional and may itself carry a trailing " or ″.
    private val dms = Regex(
        """^\s*(\d+)\s*°\s*(\d+)\s*['′]?\s*(?:(\d+(?:\.\d+)?)\s*["″]?)?\s*([NS])\s+""" +
            """(\d+)\s*°\s*(\d+)\s*['′]?\s*(?:(\d+(?:\.\d+)?)\s*["″]?)?\s*([EW])\s*$""",
        RegexOption.IGNORE_CASE
    )

    internal fun parseDms(s: String): Pair<Double, Double>? {
        val m = dms.matchEntire(s) ?: return null
        val latDeg = m.groupValues[1].toDouble()
        val latMin = m.groupValues[2].toDouble()
        val latSec = m.groupValues[3].ifEmpty { "0" }.toDouble()
        val latHemi = m.groupValues[4].uppercase()
        val lonDeg = m.groupValues[5].toDouble()
        val lonMin = m.groupValues[6].toDouble()
        val lonSec = m.groupValues[7].ifEmpty { "0" }.toDouble()
        val lonHemi = m.groupValues[8].uppercase()
        val lat = (latDeg + latMin / 60.0 + latSec / 3600.0) * (if (latHemi == "S") -1 else 1)
        val lon = (lonDeg + lonMin / 60.0 + lonSec / 3600.0) * (if (lonHemi == "W") -1 else 1)
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    // --- MGA grid ref ---

    // "55H 311000 5811000" or "55 311000 5811000" or "55 311000.5 5811000.3"
    private val mga = Regex("""^\s*(\d{1,2})\s*[A-Za-z]?\s+(\d+(?:\.\d+)?)\s+(\d+(?:\.\d+)?)\s*$""")

    internal fun parseMga(s: String): Pair<Double, Double>? {
        val m = mga.matchEntire(s) ?: return null
        val zone = m.groupValues[1].toInt()
        if (zone !in 1..60) return null
        val easting = m.groupValues[2].toDouble()
        val northing = m.groupValues[3].toDouble()
        // Guard against decimals being misread as MGA (e.g. "37 8" isn't MGA)
        if (easting < 100_000.0 || northing < 1_000_000.0) return null
        return Utm.mgaToWgs84(zone, easting, northing)
    }
}
