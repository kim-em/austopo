package com.kim.austopo.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * Permanent bottom-right credit line for the upstream tile providers. Required
 * by the CC-BY licences our state and Commonwealth tile sources publish under,
 * and a baseline expectation of any map UI built on third-party tiles.
 *
 * The text never changes (we always credit every provider, regardless of which
 * region the camera is looking at) — keeps the geometry stable, and avoids the
 * footgun of crediting only the one server whose tiles happen to be visible.
 */
class AttributionRenderer {

    private val text =
        "\u00a9 NSW Spatial Services \u2022 Vicmap \u2022 QSpatial \u2022 " +
        "DEW SA \u2022 the LIST \u2022 Geoscience Australia (CC BY)"

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 22f
        textAlign = Paint.Align.RIGHT
    }
    private val bgPaint = Paint().apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }
    private val bgRect = RectF()

    fun draw(canvas: Canvas, viewWidth: Int, viewHeight: Int) {
        if (viewWidth == 0 || viewHeight == 0) return
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
