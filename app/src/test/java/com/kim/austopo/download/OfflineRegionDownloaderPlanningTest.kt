package com.kim.austopo.download

import com.kim.austopo.geo.TileCoverage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Planning step of [OfflineRegionDownloader] must clip the requested
 * bbox to each fetcher's extent before enumerating tiles. Otherwise a
 * cross-state selection asks every server for every tile in the full
 * bbox — pointless 404s, and inflated tileCount in saved metadata.
 */
class OfflineRegionDownloaderPlanningTest {

    private lateinit var tempDir: File
    private lateinit var storage: StorageManager
    private lateinit var pinned: PinnedTileStore
    private lateinit var regions: OfflineRegionStore
    private lateinit var downloader: OfflineRegionDownloader

    @Before fun setUp() {
        tempDir = File.createTempFile("austopo-planning", "").apply { delete(); mkdirs() }
        storage = StorageManager(tempDir)
        pinned = PinnedTileStore(storage)
        regions = OfflineRegionStore(FakeContext(tempDir), storage)
        downloader = OfflineRegionDownloader(storage, pinned, regions)
    }

    @After fun tearDown() {
        downloader.cancel()
        tempDir.deleteRecursively()
    }

    private fun fakeFetcher(name: String, minX: Double, maxX: Double, minY: Double, maxY: Double): TileFetcher =
        TileFetcher(
            baseUrl = "https://test.invalid/tiles",
            extentMinX = minX, extentMaxX = maxX,
            extentMinY = minY, extentMaxY = maxY,
            cacheName = name
        )

    @Test fun `cross-state bbox yields two plans with disjoint tile sets`() {
        // Two fetchers with adjacent-but-non-overlapping X extents.
        val west = fakeFetcher("tiles_w", minX = 15_500_000.0, maxX = 16_000_000.0,
            minY = -4_500_000.0, maxY = -4_000_000.0)
        val east = fakeFetcher("tiles_e", minX = 16_000_000.0, maxX = 16_500_000.0,
            minY = -4_500_000.0, maxY = -4_000_000.0)

        // Request spans both extents.
        val lod = 10
        val plans = downloader.planWork(
            minMX = 15_500_000.0, minMY = -4_500_000.0,
            maxMX = 16_500_000.0, maxMY = -4_000_000.0,
            lodMin = lod, lodMax = lod,
            fetcherEntries = listOf(
                OfflineRegionDownloader.Entry(west),
                OfflineRegionDownloader.Entry(east)
            )
        )

        assertEquals(2, plans.size)

        val westPlan = plans.single { it.entry.fetcher === west }
        val eastPlan = plans.single { it.entry.fetcher === east }

        // Each plan's clipped bbox must be inside its own extent.
        assertTrue(westPlan.clippedBbox.maxMx <= west.extentMaxX)
        assertTrue(eastPlan.clippedBbox.minMx >= east.extentMinX)

        // Tile columns between the two plans may overlap by at most one boundary
        // column (a tile whose extent straddles 16 000 000 mx). Without clipping
        // the two sets would be identical and large — that was the bug.
        val westCols = westPlan.tiles.map { it.col }.toSet()
        val eastCols = eastPlan.tiles.map { it.col }.toSet()
        val boundaryOverlap = westCols.intersect(eastCols).size
        assertTrue("overlap should be ≤ 1 boundary tile, got $boundaryOverlap",
            boundaryOverlap <= 1)

        // Each plan's tile count equals the count of its clipped bbox.
        val westExpected = TileCoverage.count(
            westPlan.clippedBbox.minMx, westPlan.clippedBbox.minMy,
            westPlan.clippedBbox.maxMx, westPlan.clippedBbox.maxMy,
            lod, lod
        )
        val eastExpected = TileCoverage.count(
            eastPlan.clippedBbox.minMx, eastPlan.clippedBbox.minMy,
            eastPlan.clippedBbox.maxMx, eastPlan.clippedBbox.maxMy,
            lod, lod
        )
        assertEquals(westExpected, westPlan.tiles.size)
        assertEquals(eastExpected, eastPlan.tiles.size)
    }

    @Test fun `fetcher entirely outside request bbox produces no plan`() {
        val far = fakeFetcher("tiles_far",
            minX = 10_000_000.0, maxX = 11_000_000.0,
            minY = -3_000_000.0, maxY = -2_000_000.0)
        val plans = downloader.planWork(
            minMX = 15_000_000.0, minMY = -4_500_000.0,
            maxMX = 16_500_000.0, maxMY = -4_000_000.0,
            lodMin = 10, lodMax = 10,
            fetcherEntries = listOf(OfflineRegionDownloader.Entry(far))
        )
        assertEquals(0, plans.size)
    }

    @Test fun `partial overlap uses clipped bbox, not the unclipped request`() {
        val fetcher = fakeFetcher("tiles_x",
            minX = 16_000_000.0, maxX = 16_200_000.0,
            minY = -4_500_000.0, maxY = -4_300_000.0)
        // Request is larger than the fetcher on the x side.
        val plans = downloader.planWork(
            minMX = 15_000_000.0, minMY = -4_500_000.0,
            maxMX = 17_000_000.0, maxMY = -4_300_000.0,
            lodMin = 10, lodMax = 10,
            fetcherEntries = listOf(OfflineRegionDownloader.Entry(fetcher))
        )
        assertEquals(1, plans.size)
        val plan = plans.single()
        // Clipped bbox must match the fetcher's extent on the x axis.
        assertEquals(16_000_000.0, plan.clippedBbox.minMx, 1e-6)
        assertEquals(16_200_000.0, plan.clippedBbox.maxMx, 1e-6)
        val clippedCount = TileCoverage.count(
            16_000_000.0, -4_500_000.0, 16_200_000.0, -4_300_000.0, 10, 10
        )
        val unclippedCount = TileCoverage.count(
            15_000_000.0, -4_500_000.0, 17_000_000.0, -4_300_000.0, 10, 10
        )
        assertEquals(clippedCount, plan.tiles.size)
        assertTrue("plan must be strictly smaller than the unclipped count",
            plan.tiles.size < unclippedCount)
    }

    /** Minimal Context stub for OfflineRegionStore, which only needs filesDir. */
    private class FakeContext(private val dir: File) : android.content.ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
