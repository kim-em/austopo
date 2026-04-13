package com.kim.topoview

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.tan

object CoordinateConverter {

    const val HALF_CIRCUMFERENCE = 20037508.342789244

    fun wgs84ToWebMercator(lat: Double, lon: Double): Pair<Double, Double> {
        val x = lon * HALF_CIRCUMFERENCE / 180.0
        val y = ln(tan((90.0 + lat) * PI / 360.0)) * HALF_CIRCUMFERENCE / PI
        return Pair(x, y)
    }

    fun webMercatorToWgs84(mx: Double, my: Double): Pair<Double, Double> {
        val lon = mx * 180.0 / HALF_CIRCUMFERENCE
        val lat = atan(exp(my * PI / HALF_CIRCUMFERENCE)) * 360.0 / PI - 90.0
        return Pair(lat, lon)
    }

    fun webMercatorToPixel(
        mx: Double, my: Double, meta: MapMetadata
    ): Pair<Double, Double> {
        val px = (mx - meta.originX) / meta.pixelSizeX
        val py = (my - meta.originY) / meta.pixelSizeY
        return Pair(px, py)
    }

    fun pixelToWebMercator(
        px: Double, py: Double, meta: MapMetadata
    ): Pair<Double, Double> {
        val mx = px * meta.pixelSizeX + meta.originX
        val my = py * meta.pixelSizeY + meta.originY
        return Pair(mx, my)
    }

    fun wgs84ToPixel(
        lat: Double, lon: Double, meta: MapMetadata
    ): Pair<Double, Double> {
        val (mx, my) = wgs84ToWebMercator(lat, lon)
        return webMercatorToPixel(mx, my, meta)
    }

    fun isInBounds(px: Double, py: Double, meta: MapMetadata): Boolean {
        return px >= 0 && px < meta.width && py >= 0 && py < meta.height
    }

    /** Bounding box of a MapMetadata in Web Mercator: (minMX, minMY, maxMX, maxMY). */
    fun metadataBboxMercator(meta: MapMetadata): DoubleArray {
        // Top-left corner is origin; pixelSizeY is negative (Y goes down in pixels, up in Mercator)
        val x0 = meta.originX
        val y0 = meta.originY
        val x1 = meta.originX + meta.width * meta.pixelSizeX
        val y1 = meta.originY + meta.height * meta.pixelSizeY
        return doubleArrayOf(
            minOf(x0, x1), minOf(y0, y1),
            maxOf(x0, x1), maxOf(y0, y1)
        )
    }
}
