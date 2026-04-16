package com.kim.austopo.geo

import com.kim.austopo.download.TileFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileCoverageTest {

    private fun tileBounds(lod: Int, col: Int, row: Int): DoubleArray {
        val res = TileFetcher.LOD_RESOLUTIONS[lod]
        val tileSize = res * TileFetcher.TILE_SIZE
        val minX = TileFetcher.ORIGIN_X + col * tileSize
        val maxX = minX + tileSize
        val maxY = TileFetcher.ORIGIN_Y - row * tileSize
        val minY = maxY - tileSize
        return doubleArrayOf(minX, minY, maxX, maxY)
    }

    @Test fun `bbox inside a single tile yields exactly one tile`() {
        val lod = 14
        val (x, y) = Pair(15_880_000.0, -4_560_000.0) // arbitrary point inside Vic extent
        // Find the tile that contains this point, then query a tiny bbox inside it.
        val b = tileBounds(lod, ((x - TileFetcher.ORIGIN_X) / (TileFetcher.LOD_RESOLUTIONS[lod] * TileFetcher.TILE_SIZE)).toInt(),
            ((TileFetcher.ORIGIN_Y - y) / (TileFetcher.LOD_RESOLUTIONS[lod] * TileFetcher.TILE_SIZE)).toInt())
        // Shrink by 1 m on each side to ensure we're comfortably inside.
        val r = TileCoverage.rangeFor(b[0] + 1, b[1] + 1, b[2] - 1, b[3] - 1, lod)
        assertEquals(1, r.count)
    }

    @Test fun `2 by 3 range yields six tiles`() {
        val lod = 14
        val col0 = 14_000
        val row0 = 9_000
        val b0 = tileBounds(lod, col0, row0)
        val b1 = tileBounds(lod, col0 + 1, row0 + 2)
        val r = TileCoverage.rangeFor(
            minMx = b0[0] + 0.5, minMy = b1[1] + 0.5,
            maxMx = b1[2] - 0.5, maxMy = b0[3] - 0.5,
            lod = lod
        )
        assertEquals(col0, r.minCol)
        assertEquals(col0 + 1, r.maxCol)
        assertEquals(row0, r.minRow)
        assertEquals(row0 + 2, r.maxRow)
        assertEquals(6, r.count)
    }

    @Test fun `coverage enumerates every tile in the range exactly once`() {
        val lod = 13
        val r = TileCoverage.rangeFor(16_000_000.0, -4_500_000.0, 16_050_000.0, -4_450_000.0, lod)
        val expected = r.count
        val set = TileCoverage.coverage(16_000_000.0, -4_500_000.0, 16_050_000.0, -4_450_000.0, lod).toSet()
        assertEquals(expected, set.size)
    }

    @Test fun `expanding bbox can only add tiles, never remove`() {
        val lod = 12
        val base = TileCoverage.coverage(16_000_000.0, -4_500_000.0, 16_050_000.0, -4_450_000.0, lod).toSet()
        val expanded = TileCoverage.coverage(15_999_000.0, -4_501_000.0, 16_051_000.0, -4_449_000.0, lod).toSet()
        assertTrue("expanded should be a superset", expanded.containsAll(base))
    }

    @Test fun `coverage over LOD range sums each LOD's count`() {
        val bbox = doubleArrayOf(16_000_000.0, -4_500_000.0, 16_020_000.0, -4_480_000.0)
        val total = TileCoverage.count(bbox[0], bbox[1], bbox[2], bbox[3], lodMin = 11, lodMax = 13)
        val sum = (11..13).sumOf {
            TileCoverage.rangeFor(bbox[0], bbox[1], bbox[2], bbox[3], it).count
        }
        assertEquals(sum, total)
    }

    @Test fun `coverage matches existing downloadRegion enumeration for Vic bbox`() {
        // Reproduce the enumeration from OfflineTileStore.downloadRegion (lines 77-86).
        val lod = 12
        val bbox = doubleArrayOf(16_000_000.0, -4_600_000.0, 16_200_000.0, -4_400_000.0)
        val res = TileFetcher.LOD_RESOLUTIONS[lod]
        val tileSize = res * TileFetcher.TILE_SIZE
        val minCol = ((bbox[0] - TileFetcher.ORIGIN_X) / tileSize).toInt()
        val maxCol = ((bbox[2] - TileFetcher.ORIGIN_X) / tileSize).toInt()
        val minRow = ((TileFetcher.ORIGIN_Y - bbox[3]) / tileSize).toInt()
        val maxRow = ((TileFetcher.ORIGIN_Y - bbox[1]) / tileSize).toInt()

        val expected = mutableSetOf<TileCoverage.TileCoord>()
        for (row in minRow..maxRow) {
            for (col in minCol..maxCol) {
                expected += TileCoverage.TileCoord(lod, col, row)
            }
        }

        val actual = TileCoverage.coverage(bbox[0], bbox[1], bbox[2], bbox[3], lod).toSet()
        assertEquals(expected, actual)
    }
}
