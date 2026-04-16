package com.kim.austopo.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Zone-explicit UTM / MGA conversions using the GRS80 ellipsoid.
 *
 * MGA2020 (the Australian realisation of UTM) uses GRS80, identical to WGS84
 * for all practical purposes at < 1 m over the 6° zone width. The Redfern
 * series is good to < 10 cm inside a zone, which is far better than the
 * 1 km granularity needed for a display grid.
 *
 * Callers MUST pass the zone explicitly. Inferring zone from longitude near
 * a boundary is wrong: a viewport spanning zones 54 and 55 must be handled
 * by rendering each zone's grid separately and clipping to its 6° band.
 */
object Utm {

    // GRS80 ellipsoid
    private const val A = 6378137.0
    private const val F = 1.0 / 298.257222101

    // Derived
    private val E2 = 2 * F - F * F                // first eccentricity squared
    private val EP2 = E2 / (1 - E2)               // second eccentricity squared

    // UTM
    private const val K0 = 0.9996
    private const val FE = 500_000.0
    private const val FN_S = 10_000_000.0          // southern hemisphere false northing

    /** MGA zones used by Victoria. Western Vic is 54, eastern Vic is 55. */
    val VIC_ZONES: List<Int> = listOf(54, 55)

    /**
     * Longitude of the central meridian of a zone, in degrees. Zone 55 → 147°E.
     */
    fun centralMeridianDeg(zone: Int): Double = zone * 6.0 - 183.0

    /**
     * Longitude range (west, east) in degrees that a zone validly covers.
     * Outside this band, forward projection is increasingly distorted.
     */
    fun zoneLonRangeDeg(zone: Int): Pair<Double, Double> {
        val cm = centralMeridianDeg(zone)
        return Pair(cm - 3.0, cm + 3.0)
    }

    /**
     * Zone for a longitude, if the point lies inside a 6° band. Use this only
     * when you already know the point is comfortably inside a zone's band.
     *
     * Returns `null` for longitudes outside the [-180°, 180°) range (the
     * antimeridian `180.0` itself maps to zone 1 of the next revolution,
     * not a real UTM zone).
     */
    fun mgaZoneForLongitude(lonDeg: Double): Int? {
        if (lonDeg < -180.0 || lonDeg >= 180.0) return null
        return ((lonDeg + 180.0) / 6.0).toInt() + 1
    }

    /**
     * Forward projection: WGS84 (lat, lon in degrees) → MGA (easting, northing in metres).
     * Southern hemisphere (negative lat) is handled by the southern false northing.
     * Valid approximately within the 6° band; error grows rapidly outside.
     */
    fun wgs84ToMga(latDeg: Double, lonDeg: Double, zone: Int): Pair<Double, Double> {
        val phi = latDeg * PI / 180.0
        val lambda = lonDeg * PI / 180.0
        val lambda0 = centralMeridianDeg(zone) * PI / 180.0

        val sinPhi = sin(phi)
        val cosPhi = cos(phi)
        val tanPhi = tan(phi)

        val n = A / sqrt(1 - E2 * sinPhi * sinPhi)
        val t = tanPhi * tanPhi
        val c = EP2 * cosPhi * cosPhi
        val a = cosPhi * (lambda - lambda0)

        val m = A * (
            (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256) * phi
                - (3 * E2 / 8 + 3 * E2 * E2 / 32 + 45 * E2 * E2 * E2 / 1024) * sin(2 * phi)
                + (15 * E2 * E2 / 256 + 45 * E2 * E2 * E2 / 1024) * sin(4 * phi)
                - (35 * E2 * E2 * E2 / 3072) * sin(6 * phi)
        )

        val a2 = a * a
        val a3 = a2 * a
        val a4 = a3 * a
        val a5 = a4 * a
        val a6 = a5 * a

        val easting = FE + K0 * n * (
            a + (1 - t + c) * a3 / 6.0
                + (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a5 / 120.0
            )

        val northingRaw = K0 * (
            m + n * tanPhi * (
                a2 / 2.0
                    + (5 - t + 9 * c + 4 * c * c) * a4 / 24.0
                    + (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a6 / 720.0
                )
            )

        val northing = if (latDeg < 0) FN_S + northingRaw else northingRaw

        return Pair(easting, northing)
    }

    /**
     * Inverse projection: MGA (zone, easting, northing) → WGS84 (lat, lon in degrees).
     * Assumes southern hemisphere (Australia); northern points aren't relevant here.
     */
    fun mgaToWgs84(zone: Int, easting: Double, northing: Double): Pair<Double, Double> {
        val lambda0 = centralMeridianDeg(zone) * PI / 180.0

        val x = easting - FE
        val y = northing - FN_S   // southern hemisphere

        val m = y / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * E2 * E2 / 64 - 5 * E2 * E2 * E2 / 256))

        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu +
            (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * sin(4 * mu) +
            (151 * e1 * e1 * e1 / 96) * sin(6 * mu) +
            (1097 * e1 * e1 * e1 * e1 / 512) * sin(8 * mu)

        val sinPhi1 = sin(phi1)
        val cosPhi1 = cos(phi1)
        val tanPhi1 = tan(phi1)

        val n1 = A / sqrt(1 - E2 * sinPhi1 * sinPhi1)
        val t1 = tanPhi1 * tanPhi1
        val c1 = EP2 * cosPhi1 * cosPhi1
        val r1 = A * (1 - E2) / Math.pow(1 - E2 * sinPhi1 * sinPhi1, 1.5)
        val d = x / (n1 * K0)

        val d2 = d * d
        val d3 = d2 * d
        val d4 = d3 * d
        val d5 = d4 * d
        val d6 = d5 * d

        val phi = phi1 - (n1 * tanPhi1 / r1) * (
            d2 / 2.0
                - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d4 / 24.0
                + (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d6 / 720.0
            )

        val lambda = lambda0 + (
            d
                - (1 + 2 * t1 + c1) * d3 / 6.0
                + (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d5 / 120.0
            ) / cosPhi1

        return Pair(phi * 180.0 / PI, lambda * 180.0 / PI)
    }
}
