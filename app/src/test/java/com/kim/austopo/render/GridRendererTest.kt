package com.kim.austopo.render

import com.kim.austopo.CoordinateConverter
import com.kim.austopo.geo.Utm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GridRendererTest {

    /**
     * Invariant: a 10 km grid must have its lines at multiples of 10 km.
     * Previously, the zoomed-out branch started at `minKmN * 1000` with a
     * 10 km step, producing phase-shifted lines like 5,811,000 / 5,821,000.
     * This regression test asserts the snap to real 10 km multiples.
     */
    @Test fun `zoomed out 10 km lines sit at 10 km multiples in MGA`() {
        // Use a 20° × 20° bbox to guarantee we hit the "too zoomed out" branch
        // (MAX_LINES_PER_ZONE kicks in long before this).
        val (wMx, wMy) = CoordinateConverter.wgs84ToWebMercator(-45.0, 135.0)
        val (eMx, eMy) = CoordinateConverter.wgs84ToWebMercator(-35.0, 155.0)
        val minMx = minOf(wMx, eMx); val maxMx = maxOf(wMx, eMx)
        val minMy = minOf(wMy, eMy); val maxMy = maxOf(wMy, eMy)

        val r = GridRenderer(zones = Utm.VIC_ZONES)
        val (minor, major, _) = r.planGrid(minMx, minMy, maxMx, maxMy, metersPerPixelAtCenter = 10_000.0)

        // At this scale the fallback branch is active: no 1 km minor lines.
        assertTrue("expected no 1 km minor lines in zoomed-out branch, got ${minor.size}",
            minor.isEmpty())
        assertTrue("expected at least one major 10 km line", major.isNotEmpty())

        // For every line, pick a sample point, round-trip back to MGA, and
        // assert the easting OR northing lands on a multiple of 10 km. Lines
        // are either constant-easting or constant-northing by construction.
        for (line in major) {
            val coords = line.coords
            val (mx, my) = coords[0] to coords[1]
            val (lat, lon) = CoordinateConverter.webMercatorToWgs84(mx, my)
            // Match the longitude to a zone deterministically.
            val zone = Utm.VIC_ZONES.first { z ->
                val (w, e) = Utm.zoneLonRangeDeg(z)
                lon in w..e
            }
            val (e, n) = Utm.wgs84ToMga(lat, lon, zone)

            val eOn10km = kotlin.math.abs(e - Math.round(e / 10_000.0) * 10_000.0) < 200.0
            val nOn10km = kotlin.math.abs(n - Math.round(n / 10_000.0) * 10_000.0) < 200.0
            assertTrue(
                "line sample ($e, $n) z$zone should land on a 10 km multiple in either E or N " +
                    "(tolerance 200 m for UTM-to-Mercator curvature)",
                eOn10km || nOn10km
            )
        }
    }

    @Test fun `cached world coords do not move when the cache is reused`() {
        // Plan the grid for a bbox.
        val (wMx, wMy) = CoordinateConverter.wgs84ToWebMercator(-37.0, 144.5)
        val (eMx, eMy) = CoordinateConverter.wgs84ToWebMercator(-36.8, 144.7)
        val minMx = minOf(wMx, eMx); val maxMx = maxOf(wMx, eMx)
        val minMy = minOf(wMy, eMy); val maxMy = maxOf(wMy, eMy)

        val r = GridRenderer()
        val (minorA, majorA, _) =
            r.planGrid(minMx, minMy, maxMx, maxMy, metersPerPixelAtCenter = 1.0)
        val (minorB, majorB, _) =
            r.planGrid(minMx, minMy, maxMx, maxMy, metersPerPixelAtCenter = 1.0)

        // World-space output is deterministic given the same input bbox.
        assertEquals(minorA.size, minorB.size)
        assertEquals(majorA.size, majorB.size)
        for ((a, b) in minorA.zip(minorB)) {
            assertTrue(a.coords.contentEquals(b.coords))
        }
    }
}
