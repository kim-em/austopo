package com.kim.austopo.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.kim.austopo.CoordinateConverter
import com.kim.austopo.MapCamera
import com.kim.austopo.geo.Utm
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a 1 km MGA grid overlay on top of the map.
 *
 * Zone-explicit by design: Australia straddles MGA zones 49–56, and in
 * particular Vic straddles zones 54 and 55 (boundary at 144°E). We never
 * infer a single zone from the camera centre because a viewport spanning
 * 144°E would then render the wrong grid on one side. Instead we render
 * each supported zone separately, clipped to its own 6° longitude band.
 *
 * Caching strategy: expensive work (MGA → WGS84 → Mercator) is cached in
 * **world space**. The cached viewport extends [MARGIN_FACTOR] beyond the
 * current view so small pans reuse the cache. Every draw reprojects cached
 * world coords through `camera.worldToScreen*`, which is a fast affine.
 * This avoids the bug where a screen-space cache would visibly lag behind
 * the map between rebuilds.
 */
class GridRenderer(
    /** Zones to render. Default: the pair that covers Vic. */
    private val zones: List<Int> = Utm.VIC_ZONES
) {

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 120, 180)
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
    }
    private val majorLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 0, 100, 160)
        style = Paint.Style.STROKE
        strokeWidth = 2.0f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 0, 70, 130)
        textSize = 22f
        setShadowLayer(2f, 0f, 0f, Color.WHITE)
    }
    private val reusablePath = Path()

    private var cachedMinor: List<WorldPolyline> = emptyList()
    private var cachedMajor: List<WorldPolyline> = emptyList()
    private var cachedLabels: List<WorldLabel> = emptyList()
    private var cachedBboxMinMx = 0.0
    private var cachedBboxMinMy = 0.0
    private var cachedBboxMaxMx = 0.0
    private var cachedBboxMaxMy = 0.0
    private var cachedZoom = 0f
    private var hasCache = false

    fun draw(canvas: Canvas, camera: MapCamera) {
        if (camera.viewWidth == 0 || camera.viewHeight == 0) return
        if (needsRebuild(camera)) rebuild(camera)
        drawPolylines(canvas, camera, cachedMinor, linePaint)
        drawPolylines(canvas, camera, cachedMajor, majorLinePaint)
        for (lbl in cachedLabels) {
            val sx = camera.worldToScreenX(lbl.mx)
            val sy = camera.worldToScreenY(lbl.my)
            canvas.drawText(lbl.text, sx, sy, labelPaint)
        }
    }

    /** For tests. */
    internal fun cachedPolylineCount(): Int = cachedMinor.size + cachedMajor.size
    internal fun cachedLabels(): List<WorldLabel> = cachedLabels
    internal fun cachedMinor(): List<WorldPolyline> = cachedMinor
    internal fun cachedMajor(): List<WorldPolyline> = cachedMajor

    private fun drawPolylines(
        canvas: Canvas, camera: MapCamera,
        lines: List<WorldPolyline>, paint: Paint
    ) {
        for (line in lines) {
            val coords = line.coords
            if (coords.size < 4) continue
            reusablePath.rewind()
            reusablePath.moveTo(
                camera.worldToScreenX(coords[0]),
                camera.worldToScreenY(coords[1])
            )
            var i = 2
            while (i < coords.size) {
                reusablePath.lineTo(
                    camera.worldToScreenX(coords[i]),
                    camera.worldToScreenY(coords[i + 1])
                )
                i += 2
            }
            canvas.drawPath(reusablePath, paint)
        }
    }

    private fun needsRebuild(camera: MapCamera): Boolean {
        if (!hasCache) return true
        val halfW = camera.halfViewW()
        val halfH = camera.halfViewH()
        val viewMinX = camera.centerX - halfW
        val viewMaxX = camera.centerX + halfW
        val viewMinY = camera.centerY - halfH
        val viewMaxY = camera.centerY + halfH
        if (viewMinX < cachedBboxMinMx || viewMaxX > cachedBboxMaxMx ||
            viewMinY < cachedBboxMinMy || viewMaxY > cachedBboxMaxMy
        ) return true
        val zRatio = camera.zoom / cachedZoom
        if (zRatio > 1f + REBUILD_ZOOM_FRACTION || zRatio < 1f - REBUILD_ZOOM_FRACTION) return true
        return false
    }

    private fun rebuild(camera: MapCamera) {
        // Build a cache bbox that's MARGIN_FACTOR larger than the viewport.
        val halfW = camera.halfViewW() * MARGIN_FACTOR
        val halfH = camera.halfViewH() * MARGIN_FACTOR
        val minMx = camera.centerX - halfW
        val maxMx = camera.centerX + halfW
        val minMy = camera.centerY - halfH
        val maxMy = camera.centerY + halfH
        val metersPerPixelAtCenter = camera.metersPerPixel()

        val (minor, major, labels) = planGrid(minMx, minMy, maxMx, maxMy, metersPerPixelAtCenter)

        cachedMinor = minor
        cachedMajor = major
        cachedLabels = labels
        cachedBboxMinMx = minMx
        cachedBboxMaxMx = maxMx
        cachedBboxMinMy = minMy
        cachedBboxMaxMy = maxMy
        cachedZoom = camera.zoom
        hasCache = true
    }

    /**
     * Build the grid polylines + labels for a given Mercator bbox and
     * `metersPerPixelAtCenter` (pre-margin, pre-camera). All work is in
     * world space; no camera projection. Exposed at `internal` for tests.
     */
    internal fun planGrid(
        minMx: Double, minMy: Double, maxMx: Double, maxMy: Double,
        metersPerPixelAtCenter: Double
    ): Triple<List<WorldPolyline>, List<WorldPolyline>, List<WorldLabel>> {
        // Longitude range of the bbox centre-latitude.
        val (centerLat, _) = CoordinateConverter.webMercatorToWgs84(0.0, (minMy + maxMy) / 2.0)
        val (_, wLon) = CoordinateConverter.webMercatorToWgs84(minMx, (minMy + maxMy) / 2.0)
        val (_, eLon) = CoordinateConverter.webMercatorToWgs84(maxMx, (minMy + maxMy) / 2.0)

        // 1 km in screen pixels at the centre latitude — decides label visibility.
        val metersPerPixelGround = metersPerPixelAtCenter * Math.cos(centerLat * Math.PI / 180.0)
        val kmPx = if (metersPerPixelGround > 0) 1000.0 / metersPerPixelGround else 0.0
        val labelVisible = kmPx >= LABEL_MIN_KM_PIXELS

        val minor = mutableListOf<WorldPolyline>()
        val major = mutableListOf<WorldPolyline>()
        val labels = mutableListOf<WorldLabel>()

        for (zone in zones) {
            val (zoneW, zoneE) = Utm.zoneLonRangeDeg(zone)
            if (eLon < zoneW || wLon > zoneE) continue

            val effectiveWLon = max(wLon, zoneW)
            val effectiveELon = min(eLon, zoneE)

            val sLat = CoordinateConverter.webMercatorToWgs84(0.0, minMy).first
            val nLat = CoordinateConverter.webMercatorToWgs84(0.0, maxMy).first

            val mgaCorners = listOf(
                Utm.wgs84ToMga(sLat, effectiveWLon, zone),
                Utm.wgs84ToMga(sLat, effectiveELon, zone),
                Utm.wgs84ToMga(nLat, effectiveWLon, zone),
                Utm.wgs84ToMga(nLat, effectiveELon, zone)
            )
            val minE = mgaCorners.minOf { it.first }
            val maxE = mgaCorners.maxOf { it.first }
            val minN = mgaCorners.minOf { it.second }
            val maxN = mgaCorners.maxOf { it.second }

            val minKmE = (minE / 1000.0).toLong() - 1
            val maxKmE = (maxE / 1000.0).toLong() + 1
            val minKmN = (minN / 1000.0).toLong() - 1
            val maxKmN = (maxN / 1000.0).toLong() + 1

            val kmCount = (maxKmE - minKmE) + (maxKmN - minKmN)

            // 10 km line bounds — snapped to 10 km multiples. Used by both
            // branches so the major-line positions are always phase-consistent.
            val majorStartN = ((minKmN + 9) / 10) * 10_000
            val majorEndN = (maxKmN / 10) * 10_000
            val majorStartE = ((minKmE + 9) / 10) * 10_000
            val majorEndE = (maxKmE / 10) * 10_000

            // addNorthingLines draws vertical lines (constant easting, varying
            // northing) — it iterates eastings and takes a northing range.
            // addEastingLines draws horizontal lines (constant northing) — it
            // iterates northings and takes an easting range.
            if (kmCount > MAX_LINES_PER_ZONE) {
                // Zoomed out: draw only 10 km lines (as major), snapped.
                addNorthingLines(major, labels, zone, majorStartE, majorEndE, 10_000L,
                    minN, maxN, effectiveWLon, effectiveELon, labelVisible)
                addEastingLines(major, labels, zone, majorStartN, majorEndN, 10_000L,
                    minE, maxE, effectiveWLon, effectiveELon, labelVisible)
            } else {
                // 1 km minor lines everywhere.
                addNorthingLines(minor, labels, zone, minKmE * 1000, maxKmE * 1000, 1_000L,
                    minN, maxN, effectiveWLon, effectiveELon, false)
                addEastingLines(minor, labels, zone, minKmN * 1000, maxKmN * 1000, 1_000L,
                    minE, maxE, effectiveWLon, effectiveELon, false)
                // 10 km major lines on top, with labels when zoomed in enough.
                addNorthingLines(major, labels, zone, majorStartE, majorEndE, 10_000L,
                    minN, maxN, effectiveWLon, effectiveELon, labelVisible)
                addEastingLines(major, labels, zone, majorStartN, majorEndN, 10_000L,
                    minE, maxE, effectiveWLon, effectiveELon, labelVisible)
            }
        }

        return Triple(minor, major, labels)
    }

    private fun addEastingLines(
        out: MutableList<WorldPolyline>, labels: MutableList<WorldLabel>,
        zone: Int, startN: Long, endN: Long, stepN: Long,
        minE: Double, maxE: Double,
        clipWLon: Double, clipELon: Double,
        writeLabels: Boolean
    ) {
        var northing = startN
        while (northing <= endN) {
            val coords = buildMgaPolyline(
                zone = zone,
                fromE = minE, toE = maxE, northing = northing.toDouble(),
                isEasting = false,
                clipWLon = clipWLon, clipELon = clipELon
            )
            if (coords != null) out += WorldPolyline(coords)
            if (writeLabels) {
                val midE = (minE + maxE) / 2.0
                val (lat, lon) = Utm.mgaToWgs84(zone, midE, northing.toDouble())
                if (lon in clipWLon..clipELon) {
                    val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
                    labels += WorldLabel(mx, my, "${northing / 1000} km N z$zone")
                }
            }
            northing += stepN
        }
    }

    private fun addNorthingLines(
        out: MutableList<WorldPolyline>, labels: MutableList<WorldLabel>,
        zone: Int, startE: Long, endE: Long, stepE: Long,
        minN: Double, maxN: Double,
        clipWLon: Double, clipELon: Double,
        writeLabels: Boolean
    ) {
        var easting = startE
        while (easting <= endE) {
            val coords = buildMgaPolyline(
                zone = zone,
                fromE = easting.toDouble(), toE = easting.toDouble(),
                northing = minN,
                isEasting = true, minN = minN, maxN = maxN,
                clipWLon = clipWLon, clipELon = clipELon
            )
            if (coords != null) out += WorldPolyline(coords)
            if (writeLabels) {
                val midN = (minN + maxN) / 2.0
                val (lat, lon) = Utm.mgaToWgs84(zone, easting.toDouble(), midN)
                if (lon in clipWLon..clipELon) {
                    val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
                    labels += WorldLabel(mx, my, "${easting / 1000} km E z$zone")
                }
            }
            easting += stepE
        }
    }

    /**
     * Build a single grid line as a polyline in Mercator world coordinates.
     * Returns a DoubleArray of stride-2 (mx, my, mx, my, ...) or null if
     * every sample fell outside the clip longitude range.
     */
    private fun buildMgaPolyline(
        zone: Int,
        fromE: Double, toE: Double, northing: Double,
        isEasting: Boolean,
        minN: Double = 0.0, maxN: Double = 0.0,
        clipWLon: Double, clipELon: Double
    ): DoubleArray? {
        val xs = DoubleArray(SAMPLES_PER_LINE * 2 + 2)
        var written = 0
        for (i in 0..SAMPLES_PER_LINE) {
            val t = i.toDouble() / SAMPLES_PER_LINE
            val e = if (isEasting) fromE else fromE + (toE - fromE) * t
            val n = if (isEasting) minN + (maxN - minN) * t else northing
            val (lat, lon) = Utm.mgaToWgs84(zone, e, n)
            if (lon < clipWLon || lon > clipELon) continue
            val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
            xs[written++] = mx
            xs[written++] = my
        }
        if (written < 4) return null
        return xs.copyOf(written)
    }

    /** Mercator world coords, stride-2. */
    data class WorldPolyline(val coords: DoubleArray) {
        override fun equals(other: Any?): Boolean =
            other is WorldPolyline && coords.contentEquals(other.coords)
        override fun hashCode(): Int = coords.contentHashCode()
    }

    data class WorldLabel(val mx: Double, val my: Double, val text: String)

    companion object {
        private const val MARGIN_FACTOR = 1.5
        private const val REBUILD_ZOOM_FRACTION = 0.10f
        private const val SAMPLES_PER_LINE = 4
        private const val MAX_LINES_PER_ZONE = 400
        private const val LABEL_MIN_KM_PIXELS = 60.0
    }
}
