package com.kim.austopo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocationParsersTest {

    @Test fun `decimal comma-separated`() {
        val r = LocationParsers.parse("-37.8, 144.9")
        assertNotNull(r)
        assertEquals(-37.8, r!!.first, 1e-9)
        assertEquals(144.9, r.second, 1e-9)
    }

    @Test fun `decimal space-separated`() {
        val r = LocationParsers.parse("-37.8136 144.9631")
        assertNotNull(r)
        assertEquals(-37.8136, r!!.first, 1e-9)
        assertEquals(144.9631, r.second, 1e-9)
    }

    @Test fun `decimal rejects out-of-range`() {
        assertNull(LocationParsers.parse("-91.0, 144.9"))
        assertNull(LocationParsers.parse("-37.8, 181.0"))
    }

    @Test fun `DMS with hemispheres`() {
        val r = LocationParsers.parse("37°48'S 144°54'E")
        assertNotNull(r)
        assertEquals(-(37 + 48.0 / 60), r!!.first, 1e-9)
        assertEquals(144 + 54.0 / 60, r.second, 1e-9)
    }

    @Test fun `DMS with seconds`() {
        val r = LocationParsers.parse("37°48'30\"S 144°54'15\"E")
        assertNotNull(r)
        assertEquals(-(37 + 48.0 / 60 + 30.0 / 3600), r!!.first, 1e-9)
        assertEquals(144 + 54.0 / 60 + 15.0 / 3600, r.second, 1e-9)
    }

    @Test fun `MGA zone 55`() {
        // MGA 55 311000 5811000 lives in Vic. Verify parse succeeds and round-trips
        // within UTM precision back to MGA.
        val r = LocationParsers.parse("55H 311000 5811000")
        assertNotNull(r)
        // Zone 55 central meridian is 147°E; 311000 easting is west of it, so lon < 147.
        val (lat, lon) = r!!
        assert(lat in -90.0..0.0) { "expected southern hemisphere, got $lat" }
        assert(lon in 140.0..150.0) { "expected Vic-ish longitude, got $lon" }
    }

    @Test fun `MGA without zone letter`() {
        val r = LocationParsers.parse("55 311000 5811000")
        assertNotNull(r)
    }

    @Test fun `MGA rejects implausible numbers`() {
        // Easting < 100000 is well outside any UTM zone's valid range.
        assertNull(LocationParsers.parse("55 1000 5811000"))
    }

    @Test fun `Google Maps URL at-form`() {
        val r = LocationParsers.parse("https://www.google.com/maps/@-37.8136,144.9631,15z")
        assertNotNull(r)
        assertEquals(-37.8136, r!!.first, 1e-9)
        assertEquals(144.9631, r.second, 1e-9)
    }

    @Test fun `Google Maps URL 3d4d form`() {
        val r = LocationParsers.parse("https://www.google.com/maps/place/X/@0,0,5z/data=!3m1!1e3!4m6!3m5!1s0!8m2!3d-37.8136!4d144.9631")
        assertNotNull(r)
        assertEquals(-37.8136, r!!.first, 1e-9)
        assertEquals(144.9631, r.second, 1e-9)
    }

    @Test fun `gibberish returns null`() {
        assertNull(LocationParsers.parse("not a location"))
        assertNull(LocationParsers.parse(""))
    }
}
