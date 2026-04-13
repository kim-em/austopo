package com.kim.topoview.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * Persistent offline tile storage. Tiles saved here are never evicted.
 * Each tile source gets its own subdirectory.
 */
class OfflineTileStore(context: Context) {

    private val baseDir = File(context.filesDir, "offline_tiles")
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    var onProgress: ((downloaded: Int, total: Int) -> Unit)? = null
    var onComplete: ((success: Boolean) -> Unit)? = null

    init {
        baseDir.mkdirs()
    }

    /** Check if a tile exists in offline storage. */
    fun hasTile(cacheName: String, lod: Int, col: Int, row: Int): Boolean {
        return tileFile(cacheName, lod, col, row).exists()
    }

    /** Load a tile from offline storage. Returns null if not saved. */
    fun loadTile(cacheName: String, lod: Int, col: Int, row: Int): Bitmap? {
        val file = tileFile(cacheName, lod, col, row)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /**
     * Calculate how many tiles would be downloaded for a region.
     * Returns list of (lod, tileCount) pairs.
     */
    fun estimateTiles(
        fetcher: TileFetcher,
        minMX: Double, minMY: Double, maxMX: Double, maxMY: Double,
        lodMin: Int, lodMax: Int
    ): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        for (lod in lodMin..lodMax) {
            val (minCol, maxRow) = fetcher.tileForMercator(minMX, minMY, lod)
            val (maxCol, minRow) = fetcher.tileForMercator(maxMX, maxMY, lod)
            val count = (maxCol - minCol + 1) * (maxRow - minRow + 1)
            result.add(Pair(lod, count))
        }
        return result
    }

    /**
     * Download and save all tiles for a region at the given LOD range.
     */
    fun downloadRegion(
        fetcher: TileFetcher,
        baseUrl: String,
        cacheName: String,
        minMX: Double, minMY: Double, maxMX: Double, maxMY: Double,
        lodMin: Int, lodMax: Int
    ) {
        scope.launch {
            // Collect all tile coordinates
            val tiles = mutableListOf<Triple<Int, Int, Int>>() // lod, col, row
            for (lod in lodMin..lodMax) {
                val (minCol, maxRow) = fetcher.tileForMercator(minMX, minMY, lod)
                val (maxCol, minRow) = fetcher.tileForMercator(maxMX, maxMY, lod)
                for (row in minRow..maxRow) {
                    for (col in minCol..maxCol) {
                        if (!hasTile(cacheName, lod, col, row)) {
                            tiles.add(Triple(lod, col, row))
                        }
                    }
                }
            }

            val total = tiles.size
            var downloaded = 0
            var failed = 0

            withContext(Dispatchers.Main) {
                onProgress?.invoke(0, total)
            }

            // Download in parallel batches of 4
            tiles.chunked(4).forEach { batch ->
                val jobs = batch.map { (lod, col, row) ->
                    async {
                        try {
                            val url = "$baseUrl/$lod/$row/$col"
                            val request = Request.Builder().url(url).build()
                            val response = client.newCall(request).execute()
                            if (response.isSuccessful) {
                                val bytes = response.body?.bytes()
                                if (bytes != null) {
                                    saveTile(cacheName, lod, col, row, bytes)
                                }
                            } else {
                                failed++
                            }
                            response.close()
                        } catch (_: Exception) {
                            failed++
                        }
                    }
                }
                jobs.awaitAll()
                downloaded += batch.size
                withContext(Dispatchers.Main) {
                    onProgress?.invoke(downloaded, total)
                }
            }

            withContext(Dispatchers.Main) {
                onComplete?.invoke(failed == 0)
            }
        }
    }

    private fun saveTile(cacheName: String, lod: Int, col: Int, row: Int, data: ByteArray) {
        val file = tileFile(cacheName, lod, col, row)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(data) }
    }

    private fun tileFile(cacheName: String, lod: Int, col: Int, row: Int): File {
        return File(baseDir, "$cacheName/$lod/$row/$col.png")
    }

    /** Total size of offline tiles in bytes. */
    fun totalSize(): Long {
        var size = 0L
        baseDir.walkTopDown().filter { it.isFile }.forEach { size += it.length() }
        return size
    }

    /** Delete all offline tiles for a cache. */
    fun clearCache(cacheName: String) {
        File(baseDir, cacheName).deleteRecursively()
    }

    /** Delete all offline tiles. */
    fun clearAll() {
        baseDir.deleteRecursively()
        baseDir.mkdirs()
    }

    fun cancel() {
        scope.cancel()
    }
}
