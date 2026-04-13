package com.kim.topoview.render

import android.graphics.*
import com.kim.topoview.MapCamera
import com.kim.topoview.download.TileFetcher

/**
 * Renders NSW topo map tiles from the ArcGIS tile server.
 * Snaps to discrete LOD levels and draws a grid of 256x256 tiles.
 */
class TileServerRenderer(private val tileFetcher: TileFetcher) {

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(canvas: Canvas, camera: MapCamera) {
        if (camera.viewWidth == 0 || camera.viewHeight == 0) return

        val metersPerPixel = camera.metersPerPixel()

        // Check if viewport overlaps NSW extent
        val halfW = camera.halfViewW()
        val halfH = camera.halfViewH()
        val viewMinX = camera.centerX - halfW
        val viewMaxX = camera.centerX + halfW
        val viewMinY = camera.centerY - halfH
        val viewMaxY = camera.centerY + halfH

        if (viewMaxX < TileFetcher.NSW_MIN_X || viewMinX > TileFetcher.NSW_MAX_X ||
            viewMaxY < TileFetcher.NSW_MIN_Y || viewMinY > TileFetcher.NSW_MAX_Y
        ) return

        // Snap to best LOD
        val lod = tileFetcher.bestLod(metersPerPixel)
        // Don't render tiles when very zoomed out (index view territory)
        if (lod < 6) return

        // Find the tile range covering the viewport, clipped to NSW extent
        val clippedMinX = maxOf(viewMinX, TileFetcher.NSW_MIN_X)
        val clippedMaxX = minOf(viewMaxX, TileFetcher.NSW_MAX_X)
        val clippedMinY = maxOf(viewMinY, TileFetcher.NSW_MIN_Y)
        val clippedMaxY = minOf(viewMaxY, TileFetcher.NSW_MAX_Y)

        val (minCol, maxRow) = tileFetcher.tileForMercator(clippedMinX, clippedMinY, lod)
        val (maxCol, minRow) = tileFetcher.tileForMercator(clippedMaxX, clippedMaxY, lod)

        // Limit tile count to avoid excessive fetches
        val colCount = maxCol - minCol + 1
        val rowCount = maxRow - minRow + 1
        if (colCount * rowCount > 100) return

        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                val bitmap = tileFetcher.getTile(lod, col, row)
                if (bitmap != null) {
                    drawTile(canvas, camera, bitmap, col, row, lod)
                }
                // If bitmap is null, async fetch was started; onTileLoaded will trigger invalidate
            }
        }
    }

    private fun drawTile(
        canvas: Canvas, camera: MapCamera,
        bitmap: Bitmap, col: Int, row: Int, lod: Int
    ) {
        val bounds = tileFetcher.tileBounds(col, row, lod)
        // bounds: minX, minY, maxX, maxY

        val screenLeft = camera.worldToScreenX(bounds[0])
        val screenTop = camera.worldToScreenY(bounds[3])  // maxY → top of screen
        val screenRight = camera.worldToScreenX(bounds[2])
        val screenBottom = camera.worldToScreenY(bounds[1])  // minY → bottom of screen

        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val dst = RectF(screenLeft, screenTop, screenRight, screenBottom)
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    fun recycle() {
        tileFetcher.recycle()
    }
}
