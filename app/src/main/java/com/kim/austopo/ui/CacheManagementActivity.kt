package com.kim.austopo.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.*
import com.kim.austopo.MapActivity
import com.kim.austopo.data.MapSheetRepository
import com.kim.austopo.data.SheetStatus
import com.kim.austopo.download.PinnedTileStore
import com.kim.austopo.download.StorageManager
import com.kim.austopo.download.TransientTileStore
import com.kim.austopo.util.MapsDir
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class CacheManagementActivity : Activity() {

    private lateinit var repository: MapSheetRepository
    private lateinit var listLayout: LinearLayout
    private lateinit var totalSizeText: TextView
    private lateinit var tileUsageText: TextView

    private lateinit var storage: StorageManager
    private lateinit var pinnedStore: PinnedTileStore
    private lateinit var transientStore: TransientTileStore
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val mapsDir: File
        get() = MapsDir.forContext(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        storage = StorageManager.get(this)
        pinnedStore = PinnedTileStore(storage)
        transientStore = TransientTileStore(storage)
        repository = MapSheetRepository(this)
        repository.loadAll()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Cache Management"
            textSize = 24f
            setPadding(0, 16, 0, 16)
        })

        // -- Tile cache section --
        root.addView(TextView(this).apply {
            text = "Tile cache"
            textSize = 18f
            setPadding(0, 16, 0, 8)
            setTextColor(0xFF4CAF50.toInt())
        })

        tileUsageText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 8)
        }
        root.addView(tileUsageText)

        val prefs = getSharedPreferences("austopo_sheets", MODE_PRIVATE)
        val currentMb = prefs.getInt("cache_max_mb", MapActivity.DEFAULT_CACHE_MAX_MB)
        val sliderLabel = TextView(this).apply {
            text = "Max transient cache: $currentMb MB"
            textSize = 14f
        }
        root.addView(sliderLabel)

        root.addView(SeekBar(this).apply {
            max = (MAX_CAP_MB - MIN_CAP_MB) / STEP_MB
            progress = (currentMb.coerceIn(MIN_CAP_MB, MAX_CAP_MB) - MIN_CAP_MB) / STEP_MB
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, p: Int, fromUser: Boolean) {
                    val mb = MIN_CAP_MB + p * STEP_MB
                    sliderLabel.text = "Max transient cache: $mb MB"
                    if (fromUser) prefs.edit().putInt("cache_max_mb", mb).apply()
                }
                override fun onStartTrackingTouch(bar: SeekBar?) {}
                override fun onStopTrackingTouch(bar: SeekBar?) {}
            })
        })

        root.addView(Button(this).apply {
            text = "Clear transient cache now"
            setOnClickListener { clearTransientCache() }
        })

        // -- Local sheets section --
        root.addView(TextView(this).apply {
            text = "Downloaded sheets"
            textSize = 18f
            setPadding(0, 24, 0, 8)
            setTextColor(0xFF4CAF50.toInt())
        })

        totalSizeText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
            setTextColor(0xFF888888.toInt())
        }
        root.addView(totalSizeText)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }
        buttonRow.addView(Button(this).apply {
            text = "Clean Cache"
            setOnClickListener { cleanCache() }
        })
        root.addView(buttonRow)

        val scrollView = ScrollView(this)
        listLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(listLayout)
        root.addView(scrollView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        setContentView(root)
        refreshTileUsage()
        refreshList()
    }

    private fun refreshTileUsage() {
        scope.launch {
            val transient = kotlinx.coroutines.withContext(Dispatchers.IO) { transientStore.totalSize() }
            val pinned = kotlinx.coroutines.withContext(Dispatchers.IO) { pinnedStore.totalSize() }
            tileUsageText.text = "Transient: ${formatSize(transient)} · " +
                "Pinned (offline regions): ${formatSize(pinned)}"
        }
    }

    private fun clearTransientCache() {
        scope.launch {
            storage.withLock {
                transientStore.clearAll()
            }
            refreshTileUsage()
            Toast.makeText(this@CacheManagementActivity, "Transient cache cleared", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshList() {
        listLayout.removeAllViews()

        val localSheets = repository.getSheets()
            .filter { it.status == SheetStatus.CACHED || it.status == SheetStatus.KEPT }
            .sortedWith(compareBy({ it.status != SheetStatus.KEPT }, { it.name }))

        var totalSize = 0L

        if (localSheets.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No downloaded sheets."
                textSize = 16f
                setPadding(0, 32, 0, 0)
            })
        }

        // Kept section
        val keptSheets = localSheets.filter { it.status == SheetStatus.KEPT }
        if (keptSheets.isNotEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "Kept"
                textSize = 18f
                setPadding(0, 16, 0, 8)
                setTextColor(0xFF4CAF50.toInt())
            })
            for (sheet in keptSheets) {
                val size = getSheetSize(sheet)
                totalSize += size
                addSheetRow(sheet, size)
            }
        }

        // Cached section
        val cachedSheets = localSheets.filter { it.status == SheetStatus.CACHED }
        if (cachedSheets.isNotEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "Cached"
                textSize = 18f
                setPadding(0, 16, 0, 8)
                setTextColor(0xFF888888.toInt())
            })
            for (sheet in cachedSheets) {
                val size = getSheetSize(sheet)
                totalSize += size
                addSheetRow(sheet, size)
            }
        }

        totalSizeText.text = "Total: ${formatSize(totalSize)}"
    }

    private fun addSheetRow(sheet: com.kim.austopo.data.MapSheet, size: Long) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 12, 16, 12)
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textLayout.addView(TextView(this@CacheManagementActivity).apply {
            text = sheet.name
            textSize = 16f
        })
        textLayout.addView(TextView(this@CacheManagementActivity).apply {
            text = "${sheet.source} | ${formatSize(size)}"
            textSize = 12f
            setTextColor(0xFF888888.toInt())
        })
        row.addView(textLayout)

        // Toggle keep button
        row.addView(Button(this).apply {
            text = if (sheet.status == SheetStatus.KEPT) "Unkeep" else "Keep"
            setOnClickListener {
                repository.setKept(sheet, sheet.status != SheetStatus.KEPT)
                refreshList()
            }
        })

        // Delete button
        row.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener { confirmDelete(sheet) }
        })

        listLayout.addView(row)
    }

    private fun confirmDelete(sheet: com.kim.austopo.data.MapSheet) {
        AlertDialog.Builder(this)
            .setTitle("Delete ${sheet.name}?")
            .setMessage("This will remove the downloaded map file.")
            .setPositiveButton("Delete") { _, _ ->
                deleteSheet(sheet)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSheet(sheet: com.kim.austopo.data.MapSheet) {
        val path = sheet.localPath ?: return
        val jsonFile = File(path)
        if (jsonFile.exists()) {
            // Also delete the image file
            try {
                val json = org.json.JSONObject(jsonFile.readText())
                val imageFile = File(jsonFile.parentFile, json.getString("image"))
                imageFile.delete()
            } catch (_: Exception) {}
            jsonFile.delete()
        }
        sheet.status = SheetStatus.AVAILABLE
        sheet.localPath = null
    }

    private fun cleanCache() {
        val cached = repository.getSheets()
            .filter { it.status == SheetStatus.CACHED }

        if (cached.isEmpty()) {
            Toast.makeText(this, "No cached sheets to clean", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Clean cache?")
            .setMessage("Remove ${cached.size} non-kept sheet(s)?")
            .setPositiveButton("Clean") { _, _ ->
                for (sheet in cached) {
                    deleteSheet(sheet)
                }
                refreshList()
                Toast.makeText(this, "Cleaned ${cached.size} sheets", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun getSheetSize(sheet: com.kim.austopo.data.MapSheet): Long {
        val path = sheet.localPath ?: return 0
        val jsonFile = File(path)
        var size = jsonFile.length()
        try {
            val json = org.json.JSONObject(jsonFile.readText())
            val imageFile = File(jsonFile.parentFile, json.getString("image"))
            size += imageFile.length()
        } catch (_: Exception) {}
        return size
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        private const val MIN_CAP_MB = 50
        private const val MAX_CAP_MB = 2000
        private const val STEP_MB = 50
    }
}
