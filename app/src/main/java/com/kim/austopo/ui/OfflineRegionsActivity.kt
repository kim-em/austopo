package com.kim.austopo.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.*
import com.kim.austopo.download.OfflineRegion
import com.kim.austopo.download.OfflineRegionStore
import com.kim.austopo.download.PinnedTileStore
import com.kim.austopo.download.StorageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class OfflineRegionsActivity : Activity() {

    private lateinit var regionStore: OfflineRegionStore
    private lateinit var pinnedStore: PinnedTileStore
    private lateinit var listLayout: LinearLayout
    private lateinit var totalSizeText: TextView
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val storage = StorageManager.get(this)
        regionStore = OfflineRegionStore(this, storage)
        pinnedStore = PinnedTileStore(storage)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        root.addView(TextView(this).apply {
            text = "Offline Regions"
            textSize = 24f
            setPadding(0, 16, 0, 16)
        })

        totalSizeText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 0, 0, 16)
            setTextColor(0xFF888888.toInt())
        }
        root.addView(totalSizeText)

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
        refreshList()
    }

    private fun refreshList() {
        listLayout.removeAllViews()

        val regions = regionStore.load()
        val totalSize = pinnedStore.totalSize()
        totalSizeText.text = "Total offline: ${formatSize(totalSize)}"

        if (regions.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "No offline regions saved.\nUse \"Save Offline\" from the map to save a region."
                textSize = 16f
                setPadding(0, 32, 0, 0)
            })
            return
        }

        for (region in regions) {
            addRegionRow(region)
        }
    }

    private fun addRegionRow(region: OfflineRegion) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }

        val textLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        textLayout.addView(TextView(this@OfflineRegionsActivity).apply {
            text = region.name
            textSize = 18f
        })
        textLayout.addView(TextView(this@OfflineRegionsActivity).apply {
            text = "${region.tileCount} tiles | LOD ${region.lodMin}-${region.lodMax} | ${region.cacheName.removePrefix("tiles_").uppercase()}"
            textSize = 13f
            setTextColor(0xFF888888.toInt())
        })
        row.addView(textLayout)

        row.addView(Button(this).apply {
            text = "Delete"
            setOnClickListener { confirmDelete(region) }
        })

        listLayout.addView(row)
    }

    private fun confirmDelete(region: OfflineRegion) {
        AlertDialog.Builder(this)
            .setTitle("Delete \"${region.name}\"?")
            .setMessage("This will remove the offline metadata for this region. " +
                "Tiles shared with other regions are kept.")
            .setPositiveButton("Delete") { _, _ ->
                scope.launch {
                    regionStore.removeSuspending(region.name)
                    refreshList()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> "${bytes / (1024 * 1024)} MB"
        }
    }
}
