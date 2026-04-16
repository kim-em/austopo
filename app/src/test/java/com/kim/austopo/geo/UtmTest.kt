package com.kim.austopo.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class UtmTest {

    @Test fun `central meridian of each zone`() {
        assertEquals(141.0, Utm.centralMeridianDeg(54), 1e-9)
        assertEquals(147.0, Utm.centralMeridianDeg(55), 1e-9)
        assertEquals(153.0, Utm.centralMeridianDeg(56), 1e-9)
    }

    @Test fun `zone lon range spans 6 degrees`() {
        val (west, east) = Utm.zoneLonRangeDeg(55)
        assertEquals(144.0, west, 1e-9)
        assertEquals(150.0, east, 1e-9)
    }

    @Test fun `zone from longitude inside a band`() {
        assertEquals(54, Utm.mgaZoneForLongitude(142.0))
        assertEquals(55, Utm.mgaZoneForLongitude(145.0))
        assertEquals(56, Utm.mgaZoneForLongitude(151.0))
    }

    @Test fun `zone for lon at boundaries`() {
        assertEquals(1, Utm.mgaZoneForLongitude(-180.0))
        assertEquals(60, Utm.mgaZoneForLongitude(179.999))
    }

    @Test fun `zone for out-of-range lon is null`() {
        assertEquals(null, Utm.mgaZoneForLongitude(180.0))
        assertEquals(null, Utm.mgaZoneForLongitude(-180.01))
        assertEquals(null, Utm.mgaZoneForLongitude(181.0))
    }

    @Test fun `roundtrip on central meridian at equator has zero easting offset`() {
        // Lambda = lambda0 on the equator: easting should be exactly the false easting (500000).
        val (e, n) = Utm.wgs84ToMga(0.0, 147.0, 55)
        assertEquals(500_000.0, e, 1e-6)
        // Northern hemisphere at the equator → northing ≈ 0 (no southern offset applied)
        assertEquals(0.0, n, 1e-6)
    }

    @Test fun `roundtrip Melbourne`() {
        // Melbourne CBD (approx) in zone 55.
        val lat = -37.8136
        val lon = 144.9631
        val (e, n) = Utm.wgs84ToMga(lat, lon, 55)
        // Sanity: published MGA94/MGA2020 for central Melbourne is within ~500m of this.
        assertTrue("easting $e out of plausible range", e in 300_000.0..330_000.0)
        assertTrue("northing $n out of plausible range", n in 5_810_000.0..5_820_000.0)
        val (lat2, lon2) = Utm.mgaToWgs84(55, e, n)
        assertEquals(lat, lat2, 1e-8)
        assertEquals(lon, lon2, 1e-8)
    }

    @Test fun `roundtrip Sydney in zone 56`() {
        // Sydney Opera House
        val lat = -33.8568
        val lon = 151.2153
        val (e, n) = Utm.wgs84ToMga(lat, lon, 56)
        assertTrue("easting $e out of plausible range", e in 300_000.0..400_000.0)
        assertTrue("northing $n out of plausible range", n in 6_240_000.0..6_260_000.0)
        val (lat2, lon2) = Utm.mgaToWgs84(56, e, n)
        assertEquals(lat, lat2, 1e-8)
        assertEquals(lon, lon2, 1e-8)
    }

    @Test fun `roundtrip Horsham in zone 54 western Vic`() {
        // Horsham is west of 144°E → zone 54.
        val lat = -36.7167
        val lon = 142.1997
        val (e, n) = Utm.wgs84ToMga(lat, lon, 54)
        val (lat2, lon2) = Utm.mgaToWgs84(54, e, n)
        assertEquals(lat, lat2, 1e-8)
        assertEquals(lon, lon2, 1e-8)
    }

    @Test fun `points just either side of zone 54-55 boundary roundtrip in their own zone`() {
        // Exactly on the 144°E boundary.
        val lat = -37.0
        for ((lon, zone) in listOf(143.9 to 54, 144.1 to 55)) {
            val (e, n) = Utm.wgs84ToMga(lat, lon, zone)
            val (lat2, lon2) = Utm.mgaToWgs84(zone, e, n)
            assertEquals(lat, lat2, 1e-8)
            assertEquals(lon, lon2, 1e-8)
        }
    }

    @Test fun `roundtrip precision is sub-centimetre across a grid of Vic points`() {
        var maxErrM = 0.0
        for (latInt in -39..-34) {
            for (lonInt in 141..149) {
                val zone = if (lonInt < 144) 54 else 55
                val lat = latInt.toDouble()
                val lon = lonInt.toDouble()
                val (e, n) = Utm.wgs84ToMga(lat, lon, zone)
                val (lat2, lon2) = Utm.mgaToWgs84(zone, e, n)
                // ~1 degree latitude ≈ 111 km; turn error in degrees into metres for comparison.
                val errM = maxOf(abs(lat - lat2), abs(lon - lon2)) * 111_000.0
                if (errM > maxErrM) maxErrM = errM
            }
        }
        assertTrue("worst roundtrip error was $maxErrM m, expected < 0.01 m", maxErrM < 0.01)
    }
}
