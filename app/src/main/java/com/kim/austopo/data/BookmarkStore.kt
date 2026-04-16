package com.kim.austopo.data

import android.content.Context
import com.kim.austopo.download.StorageManager
import org.json.JSONArray
import java.io.File

/**
 * Persists a list of bookmarks as JSON in `filesDir/bookmarks.json`.
 * Mutations are serialised through [StorageManager.withLock] so concurrent
 * edits from different activities can't clobber each other.
 */
class BookmarkStore(context: Context, private val storage: StorageManager) {

    private val file: File = File(context.filesDir, "bookmarks.json")

    fun load(): List<Bookmark> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { Bookmark.fromJson(arr.getJSONObject(it)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun add(bookmark: Bookmark) = storage.withLock {
        val list = load().toMutableList()
        list += bookmark
        save(list)
    }

    suspend fun remove(id: String) = storage.withLock {
        save(load().filter { it.id != id })
    }

    suspend fun rename(id: String, newName: String) = storage.withLock {
        save(load().map { if (it.id == id) it.copy(name = newName) else it })
    }

    private fun save(list: List<Bookmark>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        storage.writeAtomic(file, arr.toString())
    }
}
