package com.kim.austopo.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Triggers debounced LRU eviction of the transient tile store whenever
 * [TileFetcher] writes a fresh tile. At most one eviction pass runs per
 * [DEBOUNCE_MS] window; within a pass, [TransientTileStore.evictTo] runs
 * under the [StorageManager] mutex so it cannot race with concurrent writes.
 *
 * [capBytes] is read each eviction so a live preference change takes effect
 * without re-wiring.
 */
class CacheCapEnforcer(
    private val storage: StorageManager,
    private val transientStore: TransientTileStore,
    private val capBytes: () -> Long
) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private var lastEvictMs = 0L

    fun onTileWritten() {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            if (now - lastEvictMs < DEBOUNCE_MS) return
            lastEvictMs = now
        }
        scope.launch {
            val cap = capBytes()
            storage.withLock {
                transientStore.evictTo(cap)
            }
        }
    }

    /** Force an eviction now, ignoring the debounce. */
    fun evictNow() {
        scope.launch {
            synchronized(lock) { lastEvictMs = System.currentTimeMillis() }
            val cap = capBytes()
            storage.withLock {
                transientStore.evictTo(cap)
            }
        }
    }

    fun cancel() {
        scope.cancel()
    }

    companion object {
        const val DEBOUNCE_MS = 5_000L
    }
}
