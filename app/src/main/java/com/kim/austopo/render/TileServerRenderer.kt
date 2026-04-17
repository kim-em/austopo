package com.kim.austopo.render

import android.graphics.*
import android.util.Log
import com.kim.austopo.MapCamera
import com.kim.austopo.download.TileFetcher
import com.kim.austopo.geo.StateBoundaryIndex

/**
 * Renders topo map tiles from an ArcGIS tile server.
 * Uses LOD hysteresis to avoid thrashing and pins previously-visible tiles
 * so they remain available as fallback during LOD transitions.
 */
class TileServerRenderer(val tileFetcher: TileFetcher) {

    /** Set by MapActivity after construction. */
    var boundaryIndex: StateBoundaryIndex? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    // Loading state from last draw
    var tilesTotal = 0
        private set
    var tilesLoaded = 0
        private set

    // Current LOD for hysteresis
    private var currentLod = -1

    fun draw(canvas: Canvas, camera: MapCamera) {
        if (camera.viewWidth == 0 || camera.viewHeight == 0) return

        val metersPerPixel = camera.metersPerPixel()

        val halfW = camera.halfViewW()
        val halfH = camera.halfViewH()
        val viewMinX = camera.centerX - halfW
        val viewMaxX = camera.centerX + halfW
        val viewMinY = camera.centerY - halfH
        val viewMaxY = camera.centerY + halfH

        if (viewMaxX < tileFetcher.extentMinX || viewMinX > tileFetcher.extentMaxX ||
            viewMaxY < tileFetcher.extentMinY || viewMinY > tileFetcher.extentMaxY
        ) {
            tilesTotal = 0
            tilesLoaded = 0
            return
        }

        // LOD with hysteresis to avoid threshold thrashing
        val lod = tileFetcher.bestLodWithHysteresis(metersPerPixel, currentLod)
        if (lod < tileFetcher.minLod) {
            tilesTotal = 0
            tilesLoaded = 0
            return
        }

        val clippedMinX = maxOf(viewMinX, tileFetcher.extentMinX)
        val clippedMaxX = minOf(viewMaxX, tileFetcher.extentMaxX)
        val clippedMinY = maxOf(viewMinY, tileFetcher.extentMinY)
        val clippedMaxY = minOf(viewMaxY, tileFetcher.extentMaxY)

        var drawLod = lod
        var minCol: Int
        var maxCol: Int
        var minRow: Int
        var maxRow: Int

        // If tile count exceeds limit, step down to coarser LOD instead of blanking
        while (drawLod >= tileFetcher.minLod) {
            val (mc, mxr) = tileFetcher.tileForMercator(clippedMinX, clippedMinY, drawLod)
            val (mxc, mr) = tileFetcher.tileForMercator(clippedMaxX, clippedMaxY, drawLod)
            minCol = mc; maxCol = mxc; minRow = mr; maxRow = mxr
            val count = (maxCol - minCol + 1) * (maxRow - minRow + 1)
            if (true) {
                Log.d("TileOwnership", "DRAW ${tileFetcher.stateId} drawLod=$drawLod targetLod=$lod cols=$minCol..$maxCol rows=$minRow..$maxRow count=$count")
                // Draw at this LOD
                val result = drawGrid(canvas, camera, drawLod, minCol, maxCol, minRow, maxRow)
                tilesTotal = result.first
                tilesLoaded = result.second

                // If all tiles loaded at target LOD, update current and pin them
                if (drawLod == lod && tilesLoaded == tilesTotal && tilesTotal > 0) {
                    currentLod = lod
                    pinCurrentTiles(lod, minCol, maxCol, minRow, maxRow)
                } else if (currentLod < 0) {
                    currentLod = lod
                }

                // Still request target LOD tiles if we drew at a coarser level
                if (drawLod != lod) {
                    requestTargetLod(lod, clippedMinX, clippedMaxX, clippedMinY, clippedMaxY)
                }
                return
            }
            drawLod--
        }

        // Couldn't find a LOD with <= 100 tiles
        tilesTotal = 0
        tilesLoaded = 0
    }

    private fun drawGrid(
        canvas: Canvas, camera: MapCamera,
        lod: Int, minCol: Int, maxCol: Int, minRow: Int, maxRow: Int
    ): Pair<Int, Int> {
        var total = 0
        var loaded = 0

        val myState = tileFetcher.stateId
        val idx = boundaryIndex

        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                // Check if this tile belongs to a different state
                val skip = if (idx != null && myState.isNotEmpty()) {
                    val owner = idx.ownerForTile(lod, col, row)
                    owner != null && owner != myState
                } else false

                // Always fetch the tile (even if we won't draw it) so it's
                // in our cache as a fallback parent for finer-LOD tiles
                // that ARE in our state.
                val bitmap = tileFetcher.getTile(lod, col, row)

                if (skip) {
                    Log.d("TileOwnership", "SKIP ${tileFetcher.stateId} lod=$lod col=$col row=$row owner=${idx?.ownerForTile(lod, col, row)}")
                    continue
                }
                if (bitmap == null) {
                    Log.d("TileOwnership", "NULL ${tileFetcher.stateId} lod=$lod col=$col row=$row owner=${idx?.ownerForTile(lod, col, row)}")
                }

                total++
                if (bitmap != null) {
                    loaded++
                    drawTile(canvas, camera, bitmap, col, row, lod)
                } else {
                    drawFallbackTile(canvas, camera, col, row, lod)
                }
            }
        }

        return Pair(total, loaded)
    }

    /** Request tiles at the target LOD (without drawing) so they load in background. */
    private fun requestTargetLod(
        lod: Int,
        clippedMinX: Double, clippedMaxX: Double,
        clippedMinY: Double, clippedMaxY: Double
    ) {
        val (minCol, maxRow) = tileFetcher.tileForMercator(clippedMinX, clippedMinY, lod)
        val (maxCol, minRow) = tileFetcher.tileForMercator(clippedMaxX, clippedMaxY, lod)
        val count = (maxCol - minCol + 1) * (maxRow - minRow + 1)
        if (count > 400) return
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                tileFetcher.getTile(lod, col, row) // triggers async fetch
            }
        }
    }

    private fun pinCurrentTiles(
        lod: Int, minCol: Int, maxCol: Int, minRow: Int, maxRow: Int
    ) {
        val keys = mutableSetOf<String>()
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                keys.add("$lod/$row/$col")
            }
        }
        tileFetcher.pinTiles(keys)
    }

    private fun drawFallbackTile(
        canvas: Canvas, camera: MapCamera,
        col: Int, row: Int, lod: Int
    ) {
        // Try parent tiles (zoom-in case: upscale coarser tile into this slot).
        // Uses peekTile (cache-only) to avoid triggering async fetches at every
        // parent LOD, which causes split-LOD artifacts (different parts of the
        // screen rendering from different LODs as fetches complete in random order).
        // After the loop, we trigger ONE getTile at the coarsest parent LOD to
        // ensure parents eventually load on cold start.
        for (fallbackLod in (lod - 1) downTo tileFetcher.minLod) {
            val scale = lod - fallbackLod
            val parentCol = col shr scale
            val parentRow = row shr scale
            val parentBitmap = tileFetcher.peekTile(fallbackLod, parentCol, parentRow) ?: continue

            val divisor = 1 shl scale
            val subCol = col - (parentCol shl scale)
            val subRow = row - (parentRow shl scale)
            val subSize = TileFetcher.TILE_SIZE / divisor

            val srcLeft = subCol * subSize
            val srcTop = subRow * subSize
            val src = Rect(srcLeft, srcTop, srcLeft + subSize, srcTop + subSize)

            val bounds = tileFetcher.tileBounds(col, row, lod)
            val dst = RectF(
                camera.worldToScreenX(bounds[0]),
                camera.worldToScreenY(bounds[3]),
                camera.worldToScreenX(bounds[2]),
                camera.worldToScreenY(bounds[1])
            )

            canvas.drawBitmap(parentBitmap, src, dst, paint)
            return
        }

        // No cached parent found. Trigger a fetch at ONE coarse LOD so parents
        // eventually load (prevents permanent grey on cold-start deep zoom).
        // Using minLod ensures all tile slots request the SAME coarse tile,
        // so when it arrives the entire viewport fills in consistently.
        val coarseScale = lod - tileFetcher.minLod
        if (coarseScale > 0) {
            val coarseCol = col shr coarseScale
            val coarseRow = row shr coarseScale
            tileFetcher.getTile(tileFetcher.minLod, coarseCol, coarseRow)
        }

        // Try child tiles (zoom-out case: downscale composited children)
        for (childLod in (lod + 1)..minOf(lod + 3, TileFetcher.LOD_RESOLUTIONS.size - 1)) {
            val scale = childLod - lod
            val factor = 1 shl scale
            val baseChildCol = col * factor
            val baseChildRow = row * factor
            var anyChild = false
            for (cr in 0 until factor) {
                for (cc in 0 until factor) {
                    if (tileFetcher.peekTile(childLod, baseChildCol + cc, baseChildRow + cr) != null) {
                        anyChild = true
                        break
                    }
                }
                if (anyChild) break
            }
            if (!anyChild) continue

            val bounds = tileFetcher.tileBounds(col, row, lod)
            val dstLeft = camera.worldToScreenX(bounds[0])
            val dstTop = camera.worldToScreenY(bounds[3])
            val dstRight = camera.worldToScreenX(bounds[2])
            val dstBottom = camera.worldToScreenY(bounds[1])
            val cellW = (dstRight - dstLeft) / factor
            val cellH = (dstBottom - dstTop) / factor

            val src = Rect(0, 0, TileFetcher.TILE_SIZE, TileFetcher.TILE_SIZE)
            for (cr in 0 until factor) {
                for (cc in 0 until factor) {
                    val childBitmap = tileFetcher.peekTile(childLod, baseChildCol + cc, baseChildRow + cr) ?: continue
                    val dst = RectF(
                        dstLeft + cc * cellW,
                        dstTop + cr * cellH,
                        dstLeft + (cc + 1) * cellW,
                        dstTop + (cr + 1) * cellH
                    )
                    canvas.drawBitmap(childBitmap, src, dst, paint)
                }
            }
            return
        }
    }

    private fun drawTile(
        canvas: Canvas, camera: MapCamera,
        bitmap: Bitmap, col: Int, row: Int, lod: Int
    ) {
        val bounds = tileFetcher.tileBounds(col, row, lod)

        val screenLeft = camera.worldToScreenX(bounds[0])
        val screenTop = camera.worldToScreenY(bounds[3])
        val screenRight = camera.worldToScreenX(bounds[2])
        val screenBottom = camera.worldToScreenY(bounds[1])

        val src = Rect(0, 0, bitmap.width, bitmap.height)
        val dst = RectF(screenLeft, screenTop, screenRight, screenBottom)
        canvas.drawBitmap(bitmap, src, dst, paint)
    }

    fun recycle() {
        tileFetcher.recycle()
    }
}
