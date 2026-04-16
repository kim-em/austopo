package com.kim.austopo.download

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Directly exercises [StorageMigration] against a temp dir, without an
 * Android Context. We build a fake `legacy`/`pinned` sibling pair by using
 * a real files-dir under `java.io.tmpdir`.
 */
class StorageMigrationTest {

    private lateinit var tempDir: File

    @Before fun setUp() {
        tempDir = File.createTempFile("austopo-migration", "").apply {
            delete()
            mkdirs()
        }
    }

    @After fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun seedLegacy(legacyContents: Map<String, ByteArray>): StorageMigration.MigrationResult {
        val legacy = File(tempDir, StorageManager.LEGACY_OFFLINE_DIR)
        legacy.mkdirs()
        for ((path, bytes) in legacyContents) {
            val f = File(legacy, path)
            f.parentFile?.mkdirs()
            f.writeBytes(bytes)
        }
        return StorageMigration.migrateIfNeeded(tempDir)
    }

    @Test fun `no legacy dir → nothing to do`() {
        val result = StorageMigration.migrateIfNeeded(tempDir)
        assertEquals(StorageMigration.MigrationResult.NothingToDo, result)
    }

    @Test fun `legacy present, pinned absent → rename`() {
        val result = seedLegacy(
            mapOf(
                "tiles_nsw/14/9000/14000.png" to byteArrayOf(1, 2, 3),
                "tiles_vic/12/5000/8000.png" to byteArrayOf(4, 5)
            )
        )
        assertEquals(StorageMigration.MigrationResult.Renamed, result)
        val moved = File(tempDir, "${StorageManager.PINNED_DIR}/tiles_nsw/14/9000/14000.png")
        assertTrue("tile should land in pinned dir", moved.exists())
        assertEquals(3, moved.length())
        assertFalse(
            "legacy dir should be gone",
            File(tempDir, StorageManager.LEGACY_OFFLINE_DIR).exists()
        )
    }

    @Test fun `legacy present, pinned has content → merge`() {
        // Pre-populate pinned
        val pinned = File(tempDir, StorageManager.PINNED_DIR)
        File(pinned, "tiles_nsw/14/9000/14001.png").apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(9, 9)) }
        val result = seedLegacy(
            mapOf(
                "tiles_nsw/14/9000/14000.png" to byteArrayOf(1, 2, 3)
            )
        )
        assertEquals(StorageMigration.MigrationResult.Merged, result)
        assertTrue(File(tempDir, "${StorageManager.PINNED_DIR}/tiles_nsw/14/9000/14000.png").exists())
        assertTrue(File(tempDir, "${StorageManager.PINNED_DIR}/tiles_nsw/14/9000/14001.png").exists())
        assertFalse(File(tempDir, StorageManager.LEGACY_OFFLINE_DIR).exists())
    }

    @Test fun `merge never overwrites an existing pinned file`() {
        // Pre-populate pinned with the user's existing (newer) content.
        val pinned = File(tempDir, StorageManager.PINNED_DIR)
        val pinnedBytes = byteArrayOf(7, 7, 7, 7)
        val pinnedFile = File(pinned, "tiles_nsw/14/9000/14000.png").apply {
            parentFile?.mkdirs()
            writeBytes(pinnedBytes)
        }
        // Legacy dir has the same path with different (older) bytes.
        val result = seedLegacy(
            mapOf(
                "tiles_nsw/14/9000/14000.png" to byteArrayOf(1, 2, 3)
            )
        )
        assertEquals(StorageMigration.MigrationResult.Merged, result)
        // The pinned file must retain its original bytes; the legacy bytes must NOT overwrite.
        assertTrue(pinnedFile.exists())
        assertEquals(pinnedBytes.size.toLong(), pinnedFile.length())
        assertTrue("pinned bytes should be preserved", pinnedFile.readBytes().contentEquals(pinnedBytes))
        assertFalse("legacy dir should be gone", File(tempDir, StorageManager.LEGACY_OFFLINE_DIR).exists())
    }
}
