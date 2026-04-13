package com.kim.topoview

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.kim.topoview.data.MapSheet
import com.kim.topoview.data.MapSheetRepository
import com.kim.topoview.render.LocalSheetRenderer
import com.kim.topoview.render.SheetRectangleRenderer
import com.kim.topoview.render.TileServerRenderer
import java.io.File

class TiledMapView(context: Context) : View(context) {

    val camera = MapCamera(context) { invalidate() }
    private val localRenderer = LocalSheetRenderer()
    private val rectangleRenderer = SheetRectangleRenderer()
    var tileServerRenderer: TileServerRenderer? = null

    var repository: MapSheetRepository? = null
    var onSheetTapped: ((MapSheet) -> Unit)? = null

    // GPS position in Web Mercator (null if not available)
    private var gpsMX: Double? = null
    private var gpsMY: Double? = null
    private var gpsAccuracyMeters: Float = 0f

    private val gpsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        alpha = 180
    }
    private val gpsStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gpsAccuracyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLUE
        style = Paint.Style.FILL
        alpha = 30
    }

    // Tap detection for sheet rectangles
    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                repository ?: return false
                val sheets = getVisibleSheets()
                val hit = rectangleRenderer.hitTest(camera, e.x, e.y, sheets)
                if (hit != null) {
                    onSheetTapped?.invoke(hit)
                    return true
                }
                return false
            }
        })

    fun setMap(file: File, meta: MapMetadata) {
        localRenderer.setMap(file, meta)

        val bbox = localRenderer.getBboxMercator() ?: return
        camera.centerX = (bbox[0] + bbox[2]) / 2.0
        camera.centerY = (bbox[1] + bbox[3]) / 2.0
        camera.minX = bbox[0]
        camera.minY = bbox[1]
        camera.maxX = bbox[2]
        camera.maxY = bbox[3]

        if (width > 0) {
            val mapWidthMeters = bbox[2] - bbox[0]
            camera.zoom = (width / mapWidthMeters).toFloat()
        }
        invalidate()
    }

    fun setGpsPosition(lat: Double, lon: Double, accuracyMeters: Float) {
        val (mx, my) = CoordinateConverter.wgs84ToWebMercator(lat, lon)
        gpsMX = mx
        gpsMY = my
        gpsAccuracyMeters = accuracyMeters
        invalidate()
    }

    fun clearGpsPosition() {
        gpsMX = null
        gpsMY = null
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        camera.viewWidth = w
        camera.viewHeight = h
        if (oldw == 0) {
            val bbox = localRenderer.getBboxMercator()
            if (bbox != null) {
                val mapWidthMeters = bbox[2] - bbox[0]
                camera.zoom = (w / mapWidthMeters).toFloat()
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        tapDetector.onTouchEvent(event)
        return camera.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.DKGRAY)

        // Draw tile server imagery (NSW)
        tileServerRenderer?.draw(canvas, camera)

        // Draw local sheet imagery
        localRenderer.draw(canvas, camera)

        // Draw sheet rectangles (index view)
        val sheets = getVisibleSheets()
        if (sheets.isNotEmpty()) {
            rectangleRenderer.draw(canvas, camera, sheets)
        }

        // GPS overlay on top
        drawGpsOverlay(canvas)
    }

    private fun getVisibleSheets(): List<MapSheet> {
        val repo = repository ?: return emptyList()
        val halfW = camera.halfViewW()
        val halfH = camera.halfViewH()
        return repo.sheetsInView(
            camera.centerX - halfW, camera.centerY - halfH,
            camera.centerX + halfW, camera.centerY + halfH
        )
    }

    private fun drawGpsOverlay(canvas: Canvas) {
        val mx = gpsMX ?: return
        val my = gpsMY ?: return

        val screenX = camera.worldToScreenX(mx)
        val screenY = camera.worldToScreenY(my)

        if (gpsAccuracyMeters > 0) {
            val accuracyScreenPx = gpsAccuracyMeters * camera.zoom
            if (accuracyScreenPx > 10f) {
                canvas.drawCircle(screenX, screenY, accuracyScreenPx, gpsAccuracyPaint)
            }
        }

        canvas.drawCircle(screenX, screenY, 12f, gpsStrokePaint)
        canvas.drawCircle(screenX, screenY, 10f, gpsPaint)
    }

    fun recycle() {
        localRenderer.recycle()
        tileServerRenderer?.recycle()
    }
}
