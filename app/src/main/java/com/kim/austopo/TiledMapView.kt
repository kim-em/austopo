package com.kim.austopo

import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import com.kim.austopo.data.MapSheet
import com.kim.austopo.data.MapSheetRepository
import com.kim.austopo.render.AttributionRenderer
import com.kim.austopo.render.GridRenderer
import com.kim.austopo.render.LocalSheetRenderer
import com.kim.austopo.render.ScaleBarRenderer
import com.kim.austopo.render.SheetRectangleRenderer
import com.kim.austopo.render.TileServerRenderer
import java.io.File

class TiledMapView(context: Context) : View(context) {

    val camera = MapCamera(context) { invalidate() }
    private val localRenderer = LocalSheetRenderer()
    private val rectangleRenderer = SheetRectangleRenderer()
    private val scaleBarRenderer = ScaleBarRenderer()
    private val gridRenderer = GridRenderer()
    private val attributionRenderer = AttributionRenderer()
    var showKmGrid = false
    val tileServerRenderers = mutableListOf<TileServerRenderer>()

    var repository: MapSheetRepository? = null
    var onSheetTapped: ((MapSheet) -> Unit)? = null
    /** Fired on any confirmed single-tap (including taps that hit a sheet). */
    var onMapTap: (() -> Unit)? = null
    var showSheetRectangles = false

    // Region selection mode
    var selectionMode = false
    var onRegionSelected: ((minMX: Double, minMY: Double, maxMX: Double, maxMY: Double) -> Unit)? = null
    private var selStartX = 0f
    private var selStartY = 0f
    private var selEndX = 0f
    private var selEndY = 0f
    private var selDragging = false
    private val selFillPaint = Paint().apply {
        color = Color.argb(40, 255, 165, 0) // orange fill
        style = Paint.Style.FILL
    }
    private val selStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 165, 0)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val selLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        textAlign = Paint.Align.CENTER
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    // GPS position in Web Mercator (null if not available)
    var gpsMX: Double? = null
        private set
    var gpsMY: Double? = null
        private set
    private var gpsAccuracyMeters: Float = 0f

    private val progressPaint = Paint().apply {
        color = Color.argb(200, 76, 175, 80)  // green
        style = Paint.Style.FILL
    }

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

    // Tap detection for sheet rectangles and toolbar-restore
    private val tapDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // Always notify — MapActivity restores the overlay toolbar on tap,
                // regardless of whether a sheet was hit.
                onMapTap?.invoke()
                if (!showSheetRectangles) return true
                repository ?: return true
                val sheets = getVisibleSheets()
                val hit = rectangleRenderer.hitTest(camera, e.x, e.y, sheets)
                if (hit != null) {
                    onSheetTapped?.invoke(hit)
                }
                return true
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
        if (selectionMode) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    selStartX = event.x
                    selStartY = event.y
                    selEndX = event.x
                    selEndY = event.y
                    selDragging = true
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    if (selDragging) {
                        selEndX = event.x
                        selEndY = event.y
                        invalidate()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (selDragging) {
                        selDragging = false
                        selEndX = event.x
                        selEndY = event.y
                        // Convert screen rect to world coordinates
                        val x1 = camera.screenToWorldX(minOf(selStartX, selEndX))
                        val x2 = camera.screenToWorldX(maxOf(selStartX, selEndX))
                        val y1 = camera.screenToWorldY(maxOf(selStartY, selEndY)) // screen Y is inverted
                        val y2 = camera.screenToWorldY(minOf(selStartY, selEndY))
                        // Only fire if the selection is big enough (not just a tap)
                        val screenW = Math.abs(selEndX - selStartX)
                        val screenH = Math.abs(selEndY - selStartY)
                        if (screenW > 50 && screenH > 50) {
                            onRegionSelected?.invoke(x1, y1, x2, y2)
                        }
                        invalidate()
                    }
                }
            }
            return true
        }
        tapDetector.onTouchEvent(event)
        return camera.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.DKGRAY)

        // Draw tile server imagery
        for (renderer in tileServerRenderers) {
            renderer.draw(canvas, camera)
        }

        // Draw local sheet imagery
        localRenderer.draw(canvas, camera)

        // Draw sheet rectangles (index view)
        if (showSheetRectangles) {
            val sheets = getVisibleSheets()
            if (sheets.isNotEmpty()) {
                rectangleRenderer.draw(canvas, camera, sheets)
            }
        }

        // Selection rectangle
        if (selectionMode && (selDragging || selStartX != selEndX)) {
            val left = minOf(selStartX, selEndX)
            val top = minOf(selStartY, selEndY)
            val right = maxOf(selStartX, selEndX)
            val bottom = maxOf(selStartY, selEndY)
            canvas.drawRect(left, top, right, bottom, selFillPaint)
            canvas.drawRect(left, top, right, bottom, selStrokePaint)
            canvas.drawText("Drag to select region", width / 2f, 60f, selLabelPaint)
        } else if (selectionMode) {
            canvas.drawText("Drag to select region", width / 2f, 60f, selLabelPaint)
        }

        // 1 km MGA grid (drawn before GPS so the blue dot sits on top)
        if (showKmGrid) gridRenderer.draw(canvas, camera)

        // GPS overlay on top
        drawGpsOverlay(canvas)

        // Scale bar (bottom-centre, above the progress bar so it isn't covered)
        scaleBarRenderer.draw(canvas, camera)

        // Tile provider attribution (bottom-right, always visible)
        attributionRenderer.draw(canvas, width, height)

        // Tile loading progress bar
        drawProgressBar(canvas)
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

    private fun drawProgressBar(canvas: Canvas) {
        var total = 0
        var loaded = 0
        for (renderer in tileServerRenderers) {
            total += renderer.tilesTotal
            loaded += renderer.tilesLoaded
        }
        if (total == 0 || loaded >= total) return

        val barHeight = 4f
        val fraction = loaded.toFloat() / total
        val barWidth = width * fraction
        canvas.drawRect(0f, height - barHeight, barWidth, height.toFloat(), progressPaint)
    }

    fun recycle() {
        localRenderer.recycle()
        tileServerRenderers.forEach { it.recycle() }
    }
}
