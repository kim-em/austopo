package com.kim.austopo.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Durable tile store for user-saved offline regions. Never evicted.
 *
 * Layout: `filesDir/offline_tiles_pinned/<cacheName>/<lod>/<row>/<col>.png`
 * (matches the legacy OfflineTileStore layout under a renamed root).
 *
 * Writes must go through [StorageManager.withLock] via [write]; reads are
 * safe without the lock because writes are atomic.
 */
class PinnedTileStore(private val storage: StorageManager) {

    private val baseDir: File get() = storage.pinnedDir

    fun hasTile(cacheName: String, lod: Int, col: Int, row: Int): Boolean =
        tileFile(cacheName, lod, col, row).exists()

    fun loadTile(cacheName: String, lod: Int, col: Int, row: Int): Bitmap? {
        val file = tileFile(cacheName, lod, col, row)
        if (!file.exists()) return null
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    fun loadBytes(cacheName: String, lod: Int, col: Int, row: Int): ByteArray? {
        val file = tileFile(cacheName, lod, col, row)
        if (!file.exists()) return null
        return file.readBytes()
    }

    /** Must be called from inside [StorageManager.withLock]. */
    fun write(cacheName: String, lod: Int, col: Int, row: Int, bytes: ByteArray) {
        storage.writeAtomic(tileFile(cacheName, lod, col, row), bytes)
    }

    fun totalSize(): Long = storage.totalSize(baseDir)

    /** Bytes used under a single cache (e.g. "tiles_nsw"). */
    fun sizeFor(cacheName: String): Long = storage.totalSize(File(baseDir, cacheName))

    fun tileFile(cacheName: String, lod: Int, col: Int, row: Int): File =
        File(baseDir, "$cacheName/$lod/$row/$col.png")

    /** Remove all pinned tiles for a given cacheName. Must be called under the lock. */
    fun clear(cacheName: String) {
        File(baseDir, cacheName).deleteRecursively()
    }
}
