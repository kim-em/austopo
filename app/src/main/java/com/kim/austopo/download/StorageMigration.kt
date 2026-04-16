package com.kim.austopo.download

import android.content.Context
import android.util.Log
import java.io.File

/**
 * One-shot migration from the legacy `offline_tiles/` directory to the new
 * pinned layout. All tiles written by the legacy `OfflineTileStore` came from
 * the offline-region download path — they are all pinned — so this is just a
 * directory rename.
 *
 * Safe to call on every startup; it's a no-op once the legacy dir is gone.
 */
object StorageMigration {

    private const val TAG = "StorageMigration"

    fun migrateIfNeeded(context: Context): MigrationResult = migrateIfNeeded(context.filesDir)

    /** Context-free entry point, used by both the app and unit tests. */
    fun migrateIfNeeded(filesDir: File): MigrationResult {
        val legacy = File(filesDir, StorageManager.LEGACY_OFFLINE_DIR)
        val pinned = File(filesDir, StorageManager.PINNED_DIR)
        if (!legacy.exists()) return MigrationResult.NothingToDo

        // If pinned already has content, merge by moving contents rather than a top-level rename.
        return try {
            if (!pinned.exists() || pinned.list().isNullOrEmpty()) {
                pinned.parentFile?.mkdirs()
                pinned.delete()  // remove empty placeholder dir if present
                val ok = legacy.renameTo(pinned)
                if (ok) {
                    Log.i(TAG, "Migrated legacy offline_tiles → offline_tiles_pinned (rename)")
                    MigrationResult.Renamed
                } else {
                    // Fall through to file-by-file move
                    moveTree(legacy, pinned)
                    legacy.deleteRecursively()
                    MigrationResult.Merged
                }
            } else {
                moveTree(legacy, pinned)
                legacy.deleteRecursively()
                Log.i(TAG, "Migrated legacy offline_tiles → offline_tiles_pinned (merge)")
                MigrationResult.Merged
            }
        } catch (e: Exception) {
            Log.w(TAG, "Migration failed", e)
            MigrationResult.Failed(e.message ?: "unknown error")
        }
    }

    private fun moveTree(src: File, dst: File) {
        if (!src.isDirectory) return
        dst.mkdirs()
        for (child in src.listFiles() ?: emptyArray()) {
            val target = File(dst, child.name)
            if (child.isDirectory) {
                moveTree(child, target)
            } else {
                target.parentFile?.mkdirs()
                if (target.exists()) {
                    // Pinned content wins — a legacy file is always at least
                    // as old as whatever the user already has in the pinned
                    // store. Delete the legacy copy and move on.
                    child.delete()
                    continue
                }
                if (!child.renameTo(target)) {
                    child.copyTo(target, overwrite = false)
                    child.delete()
                }
            }
        }
    }

    sealed class MigrationResult {
        data object NothingToDo : MigrationResult()
        data object Renamed : MigrationResult()
        data object Merged : MigrationResult()
        data class Failed(val reason: String) : MigrationResult()
    }
}
