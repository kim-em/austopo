package com.kim.austopo.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kim.austopo.data.Bookmark
import com.kim.austopo.data.BookmarkStore
import com.kim.austopo.data.LocationParsers
import com.kim.austopo.download.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BookmarksActivity : Activity() {

    private lateinit var store: BookmarkStore
    private lateinit var adapter: BookmarkAdapter
    private lateinit var undoBar: LinearLayout
    private lateinit var undoText: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pendingUndo: Bookmark? = null
    private var undoExpiresAtMs: Long = 0L
    private val undoHider = Runnable { hideUndoBar() }

    companion object {
        const val EXTRA_CURRENT_LAT = "current_lat"
        const val EXTRA_CURRENT_LON = "current_lon"
        const val EXTRA_RESULT_LAT = "result_lat"
        const val EXTRA_RESULT_LON = "result_lon"

        private const val STATE_PENDING_UNDO = "pending_undo_json"
        private const val STATE_UNDO_EXPIRES = "undo_expires_ms"
        private const val UNDO_WINDOW_MS = 5_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val storage = StorageManager.get(this)
        store = BookmarkStore(this, storage)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Bookmarks"
            textSize = 24f
            setPadding(0, 16, 0, 16)
        })

        // Add-current-location button
        root.addView(Button(this).apply {
            text = "Add current location"
            setOnClickListener { onAddCurrent() }
        })

        // Paste field
        val pasteField = EditText(this).apply {
            hint = "Paste location (decimal, DMS, MGA, or Google Maps URL)"
            setSingleLine(false)
            minLines = 1
            maxLines = 2
        }
        root.addView(pasteField, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = 16 })

        root.addView(Button(this).apply {
            text = "Add from pasted text"
            setOnClickListener { onAddPasted(pasteField.text.toString()) }
        })

        // RecyclerView for the list
        adapter = BookmarkAdapter(
            onTap = { b -> returnSelection(b) },
            onRename = { b -> showRenameDialog(b) }
        )
        val rv = RecyclerView(this).apply {
            layoutManager = LinearLayoutManager(this@BookmarksActivity)
            adapter = this@BookmarksActivity.adapter
        }
        ItemTouchHelper(SwipeToDeleteCallback(adapter) { b -> deleteWithUndo(b) })
            .attachToRecyclerView(rv)
        root.addView(rv, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = 16 })

        // Undo bar (hidden by default)
        undoBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(24, 16, 24, 16)
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF323232.toInt())
            visibility = View.GONE
        }
        undoText = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        undoBar.addView(undoText)
        undoBar.addView(Button(this).apply {
            text = "UNDO"
            setOnClickListener { restoreUndo() }
        })
        root.addView(undoBar, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        setContentView(root)
        refresh()

        // Restore any in-flight undo that was showing when rotation destroyed
        // the previous instance. If the expiry has already passed, skip.
        if (savedInstanceState != null) {
            val json = savedInstanceState.getString(STATE_PENDING_UNDO)
            val expiresAt = savedInstanceState.getLong(STATE_UNDO_EXPIRES, 0L)
            val now = System.currentTimeMillis()
            if (json != null && expiresAt > now) {
                try {
                    val b = Bookmark.fromJson(org.json.JSONObject(json))
                    pendingUndo = b
                    undoExpiresAtMs = expiresAt
                    undoText.text = "Deleted \"${b.name}\""
                    undoBar.visibility = View.VISIBLE
                    undoBar.postDelayed(undoHider, expiresAt - now)
                } catch (_: Exception) { /* fall through */ }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val b = pendingUndo ?: return
        outState.putString(STATE_PENDING_UNDO, b.toJson().toString())
        outState.putLong(STATE_UNDO_EXPIRES, undoExpiresAtMs)
    }

    private fun refresh() {
        adapter.submit(store.load().sortedByDescending { it.createdAtMs })
    }

    private fun onAddCurrent() {
        val lat = intent.getDoubleExtra(EXTRA_CURRENT_LAT, Double.NaN)
        val lon = intent.getDoubleExtra(EXTRA_CURRENT_LON, Double.NaN)
        if (lat.isNaN() || lon.isNaN()) {
            Toast.makeText(this, "No GPS fix available", Toast.LENGTH_SHORT).show()
            return
        }
        promptForName(defaultName("Waypoint")) { name ->
            addBookmark(name, lat, lon)
        }
    }

    private fun onAddPasted(text: String) {
        val parsed = LocationParsers.parse(text)
        if (parsed == null) {
            Toast.makeText(this, "Couldn't parse that location", Toast.LENGTH_SHORT).show()
            return
        }
        promptForName(defaultName("Pasted")) { name ->
            addBookmark(name, parsed.first, parsed.second)
        }
    }

    private fun addBookmark(name: String, lat: Double, lon: Double) {
        scope.launch {
            val b = Bookmark(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                latitude = lat,
                longitude = lon,
                createdAtMs = System.currentTimeMillis()
            )
            withContext(Dispatchers.IO) { store.add(b) }
            refresh()
        }
    }

    private fun defaultName(prefix: String): String {
        val n = store.load().count { it.name.startsWith(prefix) } + 1
        return "$prefix $n"
    }

    private fun promptForName(default: String, onOk: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(default)
            setSelection(default.length)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Name this bookmark")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().ifBlank { default }
                onOk(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRenameDialog(b: Bookmark) {
        val input = EditText(this).apply {
            setText(b.name)
            setSelection(b.name.length)
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(this)
            .setTitle("Rename bookmark")
            .setView(input)
            .setPositiveButton("Save") { _, _ ->
                val newName = input.text.toString().ifBlank { b.name }
                scope.launch {
                    withContext(Dispatchers.IO) { store.rename(b.id, newName) }
                    refresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteWithUndo(b: Bookmark) {
        scope.launch {
            withContext(Dispatchers.IO) { store.remove(b.id) }
            refresh()
            showUndoBar(b)
        }
    }

    private fun showUndoBar(b: Bookmark) {
        pendingUndo = b
        undoExpiresAtMs = System.currentTimeMillis() + UNDO_WINDOW_MS
        undoText.text = "Deleted \"${b.name}\""
        undoBar.visibility = View.VISIBLE
        undoBar.removeCallbacks(undoHider)
        undoBar.postDelayed(undoHider, UNDO_WINDOW_MS)
    }

    private fun hideUndoBar() {
        pendingUndo = null
        undoExpiresAtMs = 0L
        undoBar.visibility = View.GONE
    }

    private fun restoreUndo() {
        val b = pendingUndo ?: return
        hideUndoBar()
        scope.launch {
            withContext(Dispatchers.IO) { store.add(b) }
            refresh()
        }
    }

    private fun returnSelection(b: Bookmark) {
        val data = Intent().apply {
            putExtra(EXTRA_RESULT_LAT, b.latitude)
            putExtra(EXTRA_RESULT_LON, b.longitude)
        }
        setResult(RESULT_OK, data)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Drop the orphaned message on the dead View's message queue.
        if (::undoBar.isInitialized) undoBar.removeCallbacks(undoHider)
        scope.cancel()
    }

    // --- Adapter / ViewHolder ---

    private class BookmarkAdapter(
        val onTap: (Bookmark) -> Unit,
        val onRename: (Bookmark) -> Unit
    ) : RecyclerView.Adapter<BookmarkVH>() {

        var currentList: List<Bookmark> = emptyList()
            private set

        fun submit(list: List<Bookmark>) {
            currentList = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookmarkVH {
            val ctx = parent.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(24, 32, 24, 32)
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                isClickable = true
                setBackgroundColor(Color.TRANSPARENT)
            }
            val textCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val title = TextView(ctx).apply { textSize = 18f }
            val subtitle = TextView(ctx).apply {
                textSize = 13f; setTextColor(0xFF888888.toInt())
            }
            textCol.addView(title)
            textCol.addView(subtitle)
            row.addView(textCol)
            val overflow = Button(ctx).apply {
                text = "⋮"
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
            }
            row.addView(overflow)
            return BookmarkVH(row, title, subtitle, overflow)
        }

        override fun onBindViewHolder(holder: BookmarkVH, position: Int) {
            val b = currentList[position]
            holder.title.text = b.name
            holder.subtitle.text = "%.5f, %.5f".format(b.latitude, b.longitude)
            holder.itemView.setOnClickListener { onTap(b) }
            holder.overflow.setOnClickListener { onRename(b) }
        }

        override fun getItemCount(): Int = currentList.size

        fun removeAt(position: Int): Bookmark {
            val b = currentList[position]
            currentList = currentList.toMutableList().also { it.removeAt(position) }
            notifyItemRemoved(position)
            return b
        }
    }

    private class BookmarkVH(
        root: View,
        val title: TextView,
        val subtitle: TextView,
        val overflow: Button
    ) : RecyclerView.ViewHolder(root)

    private class SwipeToDeleteCallback(
        private val adapter: BookmarkAdapter,
        private val onDelete: (Bookmark) -> Unit
    ) : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT) {

        private val bg = Paint().apply { color = Color.argb(255, 200, 50, 50) }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 38f
            textAlign = Paint.Align.RIGHT
        }
        private val bounds = Rect()

        override fun onMove(
            rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder
        ): Boolean = false

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
            val position = vh.bindingAdapterPosition
            if (position == RecyclerView.NO_POSITION) return
            val b = adapter.removeAt(position)
            onDelete(b)
        }

        override fun onChildDraw(
            c: Canvas, rv: RecyclerView,
            vh: RecyclerView.ViewHolder, dX: Float, dY: Float,
            actionState: Int, isCurrentlyActive: Boolean
        ) {
            val v = vh.itemView
            c.drawRect(v.right + dX, v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(), bg)
            if (dX < -20f) {
                c.drawText("Delete", v.right - 28f, v.top + v.height / 2f + 12f, label)
            }
            super.onChildDraw(c, rv, vh, dX, dY, actionState, isCurrentlyActive)
        }
    }
}
