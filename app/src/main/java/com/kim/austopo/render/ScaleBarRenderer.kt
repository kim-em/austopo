package com.kim.austopo.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.kim.austopo.CoordinateConverter
import com.kim.austopo.MapCamera
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Draws a scale bar at the bottom-centre of the viewport.
 *
 * Picks the largest length from {1, 2, 5} × 10^k metres whose on-screen width
 * is ≤ MAX_FRACTION of the viewport width. Labels with "m" or "km".
 *
 * Mercator is a conformal projection, so `1 / camera.zoom` is *Mercator*
 * metres per pixel, not ground metres. Multiply by cos(latitude) to get
 * real-world ground metres at the current viewport centre.
 */
class ScaleBarRenderer {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        textSize = 28f
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 0f, 0f, Color.WHITE)
    }
    private val tmpRect = Rect()

    fun draw(canvas: Canvas, camera: MapCamera) {
        if (camera.viewWidth == 0 || camera.viewHeight == 0) return

        val (latDeg, _) = CoordinateConverter.webMercatorToWgs84(0.0, camera.centerY)
        val cosLat = cos(latDeg * Math.PI / 180.0)
        if (cosLat <= 0.0) return

        val metersPerPixel = camera.metersPerPixel() * cosLat
        if (metersPerPixel <= 0.0 || !metersPerPixel.isFinite()) return

        val maxBarPixels = camera.viewWidth * MAX_FRACTION
        val maxMeters = maxBarPixels * metersPerPixel
        val chosenMeters = pickNiceLength(maxMeters) ?: return

        val barPixels = (chosenMeters / metersPerPixel).toFloat()
        val barH = 10f
        val margin = 28f
        val cx = camera.viewWidth / 2f
        val left = cx - barPixels / 2f
        val right = cx + barPixels / 2f
        val top = camera.viewHeight - margin - barH
        val bottom = camera.viewHeight - margin

        // Filled bar with white outline for contrast on any background.
        canvas.drawRect(left, top, right, bottom, barPaint)
        canvas.drawRect(left, top, right, bottom, borderPaint)
        // Tick marks at the ends.
        canvas.drawLine(left, top - 4f, left, bottom + 4f, barPaint)
        canvas.drawLine(right, top - 4f, right, bottom + 4f, barPaint)

        canvas.drawText(formatLabel(chosenMeters), cx, top - 8f, textPaint)
    }

    /** Largest value from {1, 2, 5} × 10^k that is ≤ [maxMeters]. Null if maxMeters ≤ 0. */
    internal fun pickNiceLength(maxMeters: Double): Double? {
        if (maxMeters <= 0.0 || !maxMeters.isFinite()) return null
        val exponent = floor(log10(maxMeters)).toInt()
        val base = 10.0.pow(exponent)
        // Try 5, 2, 1 descending so we pick the largest that fits.
        for (mult in intArrayOf(5, 2, 1)) {
            val v = mult * base
            if (v <= maxMeters) return v
        }
        return base  // fallback, shouldn't be reached
    }

    private fun formatLabel(meters: Double): String {
        return if (meters >= 1000.0) {
            val km = meters / 1000.0
            if (km == km.toLong().toDouble()) "${km.toLong()} km" else "%.1f km".format(km)
        } else {
            "${meters.toInt()} m"
        }
    }

    companion object {
        private const val MAX_FRACTION = 0.25f
    }
}
