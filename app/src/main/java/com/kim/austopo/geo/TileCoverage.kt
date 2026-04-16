package com.kim.austopo.geo

import com.kim.austopo.download.TileFetcher
import kotlin.math.floor

/**
 * Canonical mapping from a Web Mercator bounding box + LOD to the set of
 * tiles that cover it. All callers that need the tile set for a region
 * (download, protection-against-eviction, estimate) MUST go through this.
 *
 * Avoid inventing a second geometry: a second enumeration drifting in
 * floating-point can disagree with the first, causing eviction to delete
 * pinned tiles or downloads to miss tiles.
 */
object TileCoverage {

    /** A tile identifier at a given LOD. */
    data class TileCoord(val lod: Int, val col: Int, val row: Int)

    /** Inclusive tile-column and tile-row ranges at a given LOD. */
    data class TileRange(val lod: Int, val minCol: Int, val maxCol: Int, val minRow: Int, val maxRow: Int) {
        val count: Int get() = (maxCol - minCol + 1) * (maxRow - minRow + 1)
        operator fun contains(t: TileCoord): Boolean =
            t.lod == lod && t.col in minCol..maxCol && t.row in minRow..maxRow
    }

    /**
     * Tile range that covers [minMx, maxMx] × [minMy, maxMy] at the given LOD.
     * Bounds are inclusive; boundary tiles that only touch the bbox at an edge
     * are included (matches the existing downloadRegion enumeration).
     */
    fun rangeFor(
        minMx: Double, minMy: Double,
        maxMx: Double, maxMy: Double,
        lod: Int
    ): TileRange {
        val clampedLod = lod.coerceIn(0, TileFetcher.LOD_RESOLUTIONS.size - 1)
        val res = TileFetcher.LOD_RESOLUTIONS[clampedLod]
        val tileSize = res * TileFetcher.TILE_SIZE
        val minCol = floor((minMx - TileFetcher.ORIGIN_X) / tileSize).toInt()
        val maxCol = floor((maxMx - TileFetcher.ORIGIN_X) / tileSize).toInt()
        // Mercator Y increases up; tile rows increase down, so swap min/max
        val minRow = floor((TileFetcher.ORIGIN_Y - maxMy) / tileSize).toInt()
        val maxRow = floor((TileFetcher.ORIGIN_Y - minMy) / tileSize).toInt()
        return TileRange(clampedLod, minCol, maxCol, minRow, maxRow)
    }

    /** All tile coordinates covering the bbox at a single LOD. */
    fun coverage(
        minMx: Double, minMy: Double,
        maxMx: Double, maxMy: Double,
        lod: Int
    ): Sequence<TileCoord> {
        val r = rangeFor(minMx, minMy, maxMx, maxMy, lod)
        return sequence {
            for (row in r.minRow..r.maxRow) {
                for (col in r.minCol..r.maxCol) {
                    yield(TileCoord(r.lod, col, row))
                }
            }
        }
    }

    /** All tile coordinates covering the bbox across an inclusive LOD range. */
    fun coverage(
        minMx: Double, minMy: Double,
        maxMx: Double, maxMy: Double,
        lodMin: Int, lodMax: Int
    ): Sequence<TileCoord> = sequence {
        for (lod in lodMin..lodMax) {
            yieldAll(coverage(minMx, minMy, maxMx, maxMy, lod))
        }
    }

    /** Total tile count across an inclusive LOD range. */
    fun count(
        minMx: Double, minMy: Double,
        maxMx: Double, maxMy: Double,
        lodMin: Int, lodMax: Int
    ): Int {
        var total = 0
        for (lod in lodMin..lodMax) {
            total += rangeFor(minMx, minMy, maxMx, maxMy, lod).count
        }
        return total
    }
}
