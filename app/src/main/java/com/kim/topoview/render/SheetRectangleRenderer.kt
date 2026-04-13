package com.kim.topoview.render

import android.graphics.*
import com.kim.topoview.MapCamera
import com.kim.topoview.data.MapSheet
import com.kim.topoview.data.SheetStatus

class SheetRectangleRenderer {

    private val nswFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 50, 100, 255)  // blue fill
        style = Paint.Style.FILL
    }
    private val nswStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 50, 100, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val localFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 50, 200, 80)  // green fill
        style = Paint.Style.FILL
    }
    private val localStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 50, 200, 80)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val availableFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(20, 180, 180, 180)  // gray fill
        style = Paint.Style.FILL
    }
    private val availableStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 180, 180, 180)
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }
    private val smallLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textSize = 18f
        textAlign = Paint.Align.CENTER
        setShadowLayer(2f, 1f, 1f, Color.BLACK)
    }

    private val path = Path()

    fun draw(canvas: Canvas, camera: MapCamera, sheets: List<MapSheet>) {
        for (sheet in sheets) {
            drawSheet(canvas, camera, sheet)
        }
    }

    private fun drawSheet(canvas: Canvas, camera: MapCamera, sheet: MapSheet) {
        val (fillPaint, strokePaint) = when {
            sheet.isNsw -> Pair(nswFillPaint, nswStrokePaint)
            sheet.isLocal -> Pair(localFillPaint, localStrokePaint)
            else -> Pair(availableFillPaint, availableStrokePaint)
        }

        val poly = sheet.polygonMercator
        if (poly.size < 3) return

        // Build screen-space path
        path.reset()
        val (x0, y0) = poly[0]
        path.moveTo(camera.worldToScreenX(x0), camera.worldToScreenY(y0))
        for (i in 1 until poly.size) {
            val (xi, yi) = poly[i]
            path.lineTo(camera.worldToScreenX(xi), camera.worldToScreenY(yi))
        }
        path.close()

        canvas.drawPath(path, fillPaint)
        canvas.drawPath(path, strokePaint)

        // Label — draw name if rectangle is big enough on screen
        val bbox = sheet.bboxMercator
        val screenW = (camera.worldToScreenX(bbox[2]) - camera.worldToScreenX(bbox[0]))
        val screenH = (camera.worldToScreenY(bbox[1]) - camera.worldToScreenY(bbox[3]))

        if (screenW > 60 && screenH > 30) {
            val cx = camera.worldToScreenX((bbox[0] + bbox[2]) / 2.0)
            val cy = camera.worldToScreenY((bbox[1] + bbox[3]) / 2.0)

            val paint = if (screenW > 120) labelPaint else smallLabelPaint
            canvas.drawText(sheet.name, cx, cy + paint.textSize / 3f, paint)
        }
    }

    /** Find which sheet was tapped, if any. Returns the topmost match. */
    fun hitTest(camera: MapCamera, screenX: Float, screenY: Float, sheets: List<MapSheet>): MapSheet? {
        val wx = camera.screenToWorldX(screenX)
        val wy = camera.screenToWorldY(screenY)

        // Return the smallest sheet containing the point (most specific match)
        return sheets.filter { it.containsMercator(wx, wy) }
            .minByOrNull {
                val b = it.bboxMercator
                (b[2] - b[0]) * (b[3] - b[1])
            }
    }
}
