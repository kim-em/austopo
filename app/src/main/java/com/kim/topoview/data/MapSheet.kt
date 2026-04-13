package com.kim.topoview.data

import com.kim.topoview.CoordinateConverter

enum class SheetStatus {
    AVAILABLE,    // known but not downloaded
    DOWNLOADING,  // download in progress
    CACHED,       // downloaded, may be cleaned
    KEPT          // downloaded, user-protected
}

data class MapSheet(
    val id: String,              // e.g. "nsw:8930-1S", "getlost:vic-25k:8324-3"
    val name: String,            // e.g. "KATOOMBA", "FEATHERTOP"
    val source: String,          // e.g. "nsw", "getlost_vic_25k"
    val scale: Int,              // e.g. 25000
    val minLon: Double,          // WGS84 bounding box
    val minLat: Double,
    val maxLon: Double,
    val maxLat: Double,
    val downloadUrl: String? = null,
    var status: SheetStatus = SheetStatus.AVAILABLE,
    var localPath: String? = null,
    // Polygon geometry for non-rectangular sheets (NSW).
    // List of (lon, lat) pairs. If null, use the bounding box.
    val polygon: List<Pair<Double, Double>>? = null
) {
    /** Bounding box in Web Mercator: (minMX, minMY, maxMX, maxMY). */
    val bboxMercator: DoubleArray by lazy {
        val (mx0, my0) = CoordinateConverter.wgs84ToWebMercator(minLat, minLon)
        val (mx1, my1) = CoordinateConverter.wgs84ToWebMercator(maxLat, maxLon)
        doubleArrayOf(
            minOf(mx0, mx1), minOf(my0, my1),
            maxOf(mx0, mx1), maxOf(my0, my1)
        )
    }

    /** Polygon in Web Mercator, or bbox corners if no polygon. */
    val polygonMercator: List<Pair<Double, Double>> by lazy {
        if (polygon != null) {
            polygon.map { (lon, lat) ->
                val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
                Pair(mx, my)
            }
        } else {
            val b = bboxMercator
            listOf(
                Pair(b[0], b[1]), Pair(b[2], b[1]),
                Pair(b[2], b[3]), Pair(b[0], b[3])
            )
        }
    }

    /** Point-in-polygon test using ray casting. Works with polygon or bbox. */
    fun containsWgs84(lat: Double, lon: Double): Boolean {
        val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
        return containsMercator(mx, my)
    }

    fun containsMercator(mx: Double, my: Double): Boolean {
        // Quick bbox reject
        val b = bboxMercator
        if (mx < b[0] || mx > b[2] || my < b[1] || my > b[3]) return false

        // Ray casting
        val poly = polygonMercator
        var inside = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if (((yi > my) != (yj > my)) &&
                (mx < (xj - xi) * (my - yi) / (yj - yi) + xi)
            ) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    val isNsw: Boolean get() = source == "nsw"
    val isLocal: Boolean get() = status == SheetStatus.CACHED || status == SheetStatus.KEPT
}
