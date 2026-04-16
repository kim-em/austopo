package com.kim.austopo.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class TransientTileStoreTest {

    private lateinit var filesDir: File
    private lateinit var storage: StorageManager
    private lateinit var store: TransientTileStore

    @Before fun setUp() {
        filesDir = File.createTempFile("austopo-transient", "").apply {
            delete()
            mkdirs()
        }
        storage = StorageManager(filesDir)
        store = TransientTileStore(storage)
    }

    @After fun tearDown() {
        filesDir.deleteRecursively()
    }

    /** Writes a tile with given bytes and forces its lastModified timestamp. */
    private fun write(cacheName: String, lod: Int, col: Int, row: Int, bytes: ByteArray, mtimeMs: Long) {
        store.write(cacheName, lod, col, row, bytes)
        store.tileFile(cacheName, lod, col, row).setLastModified(mtimeMs)
    }

    @Test fun `evictTo deletes oldest tiles first`() {
        // 4 tiles of ~1 KB, mtimes spread over seconds.
        val kb = ByteArray(1024) { 7 }
        write("tiles_nsw", 14, 1, 1, kb, mtimeMs = 1_000L)
        write("tiles_nsw", 14, 2, 1, kb, mtimeMs = 2_000L)
        write("tiles_nsw", 14, 3, 1, kb, mtimeMs = 3_000L)
        write("tiles_nsw", 14, 4, 1, kb, mtimeMs = 4_000L)

        val before = store.totalSize()
        assertEquals(4096, before)

        // Cap to 2 KB → should delete the two oldest.
        val deleted = store.evictTo(2 * 1024)
        assertEquals(2, deleted)
        assertTrue(store.totalSize() <= 2 * 1024)
        assertFalse("oldest tile should be gone", store.tileFile("tiles_nsw", 14, 1, 1).exists())
        assertFalse("2nd-oldest tile should be gone", store.tileFile("tiles_nsw", 14, 2, 1).exists())
        assertTrue("newer tile should survive", store.tileFile("tiles_nsw", 14, 3, 1).exists())
        assertTrue("newest tile should survive", store.tileFile("tiles_nsw", 14, 4, 1).exists())
    }

    @Test fun `evictTo is a no-op under cap`() {
        val kb = ByteArray(512) { 3 }
        write("tiles_nsw", 14, 1, 1, kb, mtimeMs = 1_000L)
        write("tiles_nsw", 14, 2, 1, kb, mtimeMs = 2_000L)
        val deleted = store.evictTo(10 * 1024)
        assertEquals(0, deleted)
        assertEquals(1024, store.totalSize())
    }

    @Test fun `evictTo does not touch pinned dir`() {
        // Create a sibling pinned file that should never be considered for eviction.
        val pinnedDir = File(filesDir, StorageManager.PINNED_DIR)
        pinnedDir.mkdirs()
        val pinnedFile = File(pinnedDir, "tiles_nsw/14/1/1.png").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(2048) { 5 })
        }

        val kb = ByteArray(1024) { 7 }
        write("tiles_nsw", 14, 2, 1, kb, mtimeMs = 1_000L)

        store.evictTo(0)
        assertFalse("transient tile should be evicted", store.tileFile("tiles_nsw", 14, 2, 1).exists())
        assertTrue("pinned tile should survive", pinnedFile.exists())
    }

}
