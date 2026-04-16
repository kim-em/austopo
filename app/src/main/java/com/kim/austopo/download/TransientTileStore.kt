package com.kim.austopo.download

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Evictable tile store for ad-hoc tile fetches. Replaces the OkHttp disk
 * cache so that there is exactly one transient tile cache and eviction is
 * under our control.
 *
 * Layout: `filesDir/offline_tiles_transient/<cacheName>/<lod>/<row>/<col>.png`
 *
 * Eviction ([evictTo]) deletes least-recently-modified files under this root
 * until total size is under the cap. [PinnedTileStore] lives under a separate
 * directory and is never touched here — the protection is structural.
 */
class TransientTileStore(private val storage: StorageManager) {

    private val baseDir: File get() = storage.transientDir

    fun hasTile(cacheName: String, lod: Int, col: Int, row: Int): Boolean =
        tileFile(cacheName, lod, col, row).exists()

    fun loadTile(cacheName: String, lod: Int, col: Int, row: Int): Bitmap? {
        val file = tileFile(cacheName, lod, col, row)
        if (!file.exists()) return null
        // Touch lastModified on read so eviction treats hot tiles as recent.
        file.setLastModified(System.currentTimeMillis())
        return BitmapFactory.decodeFile(file.absolutePath)
    }

    /** Must be called from inside [StorageManager.withLock]. */
    fun write(cacheName: String, lod: Int, col: Int, row: Int, bytes: ByteArray) {
        storage.writeAtomic(tileFile(cacheName, lod, col, row), bytes)
    }

    fun totalSize(): Long = storage.totalSize(baseDir)

    /**
     * Delete oldest files (by lastModified) until total size ≤ [maxBytes].
     * Must be called from inside [StorageManager.withLock].
     * Returns the number of files deleted.
     */
    fun evictTo(maxBytes: Long): Int {
        if (!baseDir.exists()) return 0
        val files = baseDir.walkTopDown()
            .filter { it.isFile }
            .toMutableList()
        var total = files.sumOf { it.length() }
        if (total <= maxBytes) return 0
        files.sortBy { it.lastModified() }
        var deleted = 0
        for (f in files) {
            if (total <= maxBytes) break
            val len = f.length()
            if (f.delete()) {
                total -= len
                deleted++
            }
        }
        return deleted
    }

    fun clearAll() {
        baseDir.deleteRecursively()
        baseDir.mkdirs()
    }

    fun tileFile(cacheName: String, lod: Int, col: Int, row: Int): File =
        File(baseDir, "$cacheName/$lod/$row/$col.png")
}
