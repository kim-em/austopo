package com.kim.topoview.ui

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.os.Environment
import android.widget.*
import com.kim.topoview.data.MapSheetRepository
import com.kim.topoview.data.SheetStatus
import java.io.File

class CacheManagementActivity : Activity() {

    private lateinit var repository: MapSheetRepository
    private lateinit var listLayout: LinearLayout
    private lateinit var totalSizeText: TextView

    private val mapsDir: File
        get() = File(Environment.getExternalStorageDirectory(), "TopoMaps")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        refreshList()
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

    private fun addSheetRow(sheet: com.kim.topoview.data.MapSheet, size: Long) {
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

    private fun confirmDelete(sheet: com.kim.topoview.data.MapSheet) {
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

    private fun deleteSheet(sheet: com.kim.topoview.data.MapSheet) {
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

    private fun getSheetSize(sheet: com.kim.topoview.data.MapSheet): Long {
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
}
