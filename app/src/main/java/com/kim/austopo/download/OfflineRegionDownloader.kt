package com.kim.austopo.download

import com.kim.austopo.geo.TileCoverage
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Downloads tiles for an offline region. Multiplexes across all tile servers
 * whose extent intersects the region, so a region spanning NSW/Vic downloads
 * from both servers under a single progress stream.
 *
 * Replaces the per-fetcher callback-reassignment loop that lived in
 * OfflineTileStore + MapActivity (where callbacks were clobbered when a
 * region spanned two fetchers).
 */
class OfflineRegionDownloader(
    private val storage: StorageManager,
    private val pinnedStore: PinnedTileStore,
    private val regionStore: OfflineRegionStore
) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor(UserAgentInterceptor)
        .build()

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Start a download. [listener.onProgress] is called on the main thread.
     * [listener.onComplete] is called once at the end, with the region metadata
     * actually persisted (one OfflineRegion per fetcher that had at least one
     * intersecting tile).
     */
    fun download(
        name: String,
        minMX: Double, minMY: Double, maxMX: Double, maxMY: Double,
        lodMin: Int, lodMax: Int,
        fetcherEntries: List<Entry>,
        listener: Listener
    ): Job {
        return scope.launch {
            val plans = planWork(minMX, minMY, maxMX, maxMY, lodMin, lodMax, fetcherEntries)

            val total = plans.sumOf { it.tiles.size }
            val downloaded = AtomicInteger(0)
            val failed = AtomicInteger(0)

            withContext(Dispatchers.Main) {
                listener.onProgress(0, total)
            }

            // Download all plans in parallel, batched.
            val jobs = plans.map { plan ->
                async {
                    plan.tiles.chunked(BATCH).forEach { batch ->
                        batch.map { coord ->
                            async {
                                val ok = fetchAndSave(plan.entry, coord)
                                if (ok) downloaded.incrementAndGet() else failed.incrementAndGet()
                            }
                        }.awaitAll()
                        withContext(Dispatchers.Main) {
                            listener.onProgress(downloaded.get() + failed.get(), total)
                        }
                    }
                }
            }
            jobs.awaitAll()

            // Persist region metadata for each fetcher that contributed.
            val savedRegions = mutableListOf<OfflineRegion>()
            for (plan in plans) {
                val b = plan.clippedBbox
                val region = OfflineRegion(
                    name = name,
                    minMX = b.minMx, minMY = b.minMy, maxMX = b.maxMx, maxMY = b.maxMy,
                    lodMin = lodMin, lodMax = lodMax,
                    cacheName = plan.entry.fetcher.tileCacheName,
                    tileCount = TileCoverage.count(b.minMx, b.minMy, b.maxMx, b.maxMy, lodMin, lodMax),
                    sizeBytes = pinnedStore.sizeFor(plan.entry.fetcher.tileCacheName)
                )
                regionStore.addSuspending(region)
                savedRegions += region
            }

            withContext(Dispatchers.Main) {
                listener.onComplete(failed.get() == 0, savedRegions)
            }
        }
    }

    /**
     * Build one [Plan] per fetcher whose extent intersects the requested
     * bbox. Each plan's tile list comes from the bbox **clipped to that
     * fetcher's extent**, so a cross-state selection only asks each server
     * for tiles it actually owns.
     *
     * Exposed at `internal` visibility for tests.
     */
    internal fun planWork(
        minMX: Double, minMY: Double, maxMX: Double, maxMY: Double,
        lodMin: Int, lodMax: Int,
        fetcherEntries: List<Entry>
    ): List<Plan> = fetcherEntries.mapNotNull { entry ->
        val f = entry.fetcher
        // Respect servers that forbid bulk pre-fetching (e.g. OpenTopoMap for WA).
        if (!f.bulkDownloadAllowed) return@mapNotNull null
        val cMinX = maxOf(minMX, f.extentMinX)
        val cMaxX = minOf(maxMX, f.extentMaxX)
        val cMinY = maxOf(minMY, f.extentMinY)
        val cMaxY = minOf(maxMY, f.extentMaxY)
        if (cMinX >= cMaxX || cMinY >= cMaxY) return@mapNotNull null

        val tiles = mutableListOf<TileCoverage.TileCoord>()
        for (coord in TileCoverage.coverage(cMinX, cMinY, cMaxX, cMaxY, lodMin, lodMax)) {
            if (!pinnedStore.hasTile(f.tileCacheName, coord.lod, coord.col, coord.row)) {
                tiles += coord
            }
        }
        Plan(entry, tiles, ClippedBbox(cMinX, cMinY, cMaxX, cMaxY))
    }

    private suspend fun fetchAndSave(entry: Entry, coord: TileCoverage.TileCoord): Boolean {
        return try {
            val url = entry.fetcher.tileUrl(coord.lod, coord.col, coord.row)
            val request = Request.Builder().url(url).build()
            val response = client.newCall(request).execute()
            val ok = response.isSuccessful
            if (ok) {
                val bytes = response.body?.bytes()
                if (bytes != null) {
                    storage.withLock {
                        pinnedStore.write(entry.fetcher.tileCacheName, coord.lod, coord.col, coord.row, bytes)
                    }
                } else {
                    response.close()
                    return false
                }
            }
            response.close()
            ok
        } catch (_: Exception) {
            false
        }
    }

    fun cancel() {
        scope.cancel()
    }

    data class Entry(val fetcher: TileFetcher)

    interface Listener {
        fun onProgress(done: Int, total: Int)
        fun onComplete(success: Boolean, regions: List<OfflineRegion>)
    }

    internal data class ClippedBbox(
        val minMx: Double, val minMy: Double,
        val maxMx: Double, val maxMy: Double
    )

    internal data class Plan(
        val entry: Entry,
        val tiles: List<TileCoverage.TileCoord>,
        val clippedBbox: ClippedBbox
    )

    companion object {
        private const val BATCH = 4
    }
}
