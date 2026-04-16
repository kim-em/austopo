package com.kim.austopo.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScaleBarRendererTest {

    private val r = ScaleBarRenderer()

    @Test fun `picks largest of 1, 2, 5 times 10^k below max`() {
        assertEquals(5.0, r.pickNiceLength(7.5)!!, 0.0)
        assertEquals(2.0, r.pickNiceLength(4.9)!!, 0.0)
        assertEquals(1.0, r.pickNiceLength(1.5)!!, 0.0)
        assertEquals(50.0, r.pickNiceLength(99.0)!!, 0.0)
        assertEquals(100.0, r.pickNiceLength(199.0)!!, 0.0)
        assertEquals(200.0, r.pickNiceLength(400.0)!!, 0.0)
        assertEquals(500.0, r.pickNiceLength(999.0)!!, 0.0)
        assertEquals(1000.0, r.pickNiceLength(1999.0)!!, 0.0)
        assertEquals(5000.0, r.pickNiceLength(9000.0)!!, 0.0)
    }

    @Test fun `returns exact value when max is exactly on a tick`() {
        assertEquals(10.0, r.pickNiceLength(10.0)!!, 0.0)
        assertEquals(500.0, r.pickNiceLength(500.0)!!, 0.0)
    }

    @Test fun `returns null for non-positive input`() {
        assertNull(r.pickNiceLength(0.0))
        assertNull(r.pickNiceLength(-1.0))
        assertNull(r.pickNiceLength(Double.NaN))
    }
}
