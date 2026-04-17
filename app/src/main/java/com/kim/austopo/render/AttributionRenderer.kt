package com.kim.austopo.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Bottom-right credit line for the tile providers currently visible in the
 * viewport. Updates dynamically based on which [TileServerRenderer]s have
 * tiles overlapping the camera (tilesTotal > 0).
 */
class AttributionRenderer {

    companion object {
        /** Map from TileFetcher.tileCacheName to the display credit. */
        private val CREDITS = mapOf(
            "tiles_nsw" to "NSW Spatial Services",
            "tiles_vic" to "Vicmap",
            "tiles_qld" to "QSpatial",
            "tiles_sa"  to "DEW SA",
            "tiles_tas" to "theLIST",
            "tiles_nt"  to "Geoscience Australia",
            "tiles_wa"  to "Geoscience Australia",
        )
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 20f
        textAlign = Paint.Align.RIGHT
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val bgRect = RectF()

    /**
     * @param visibleCacheNames the set of [TileFetcher.tileCacheName] values
     *        for renderers whose tilesTotal > 0 on the last draw pass.
     */
    fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int,
             visibleCacheNames: Set<String>) {
        if (viewWidth == 0 || viewHeight == 0) return

        val credits = visibleCacheNames
            .mapNotNull { CREDITS[it] }
            .distinct()
        if (credits.isEmpty()) return

        val text = "\u00a9 " + credits.joinToString(" \u2022 ")
        val padX = 12f
        val padY = 6f
        val textWidth = textPaint.measureText(text)
        val baselineY = viewHeight - padY - textPaint.descent()
        val top = viewHeight - padY * 2 - (textPaint.descent() - textPaint.ascent())
        bgRect.set(
            viewWidth - padX * 2 - textWidth,
            top,
            viewWidth.toFloat(),
            viewHeight.toFloat()
        )
        canvas.drawRect(bgRect, bgPaint)
        canvas.drawText(text, viewWidth - padX, baselineY, textPaint)
    }
}
