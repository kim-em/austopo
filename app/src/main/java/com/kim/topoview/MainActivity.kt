package com.kim.topoview

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.*
import java.io.File

class MainActivity : Activity() {

    private val mapsDir: File
        get() = File(Environment.getExternalStorageDirectory(), "TopoMaps")

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private var maps = listOf<MapMetadata>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val title = TextView(this).apply {
            text = "TopoView"
            textSize = 24f
            setPadding(0, 16, 0, 16)
        }
        layout.addView(title)

        val pathText = TextView(this).apply {
            text = "Maps directory: ${mapsDir.absolutePath}"
            textSize = 12f
            setPadding(0, 0, 0, 16)
            setTextColor(0xFF888888.toInt())
        }
        layout.addView(pathText)

        val buttonRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
        }

        val refreshButton = Button(this).apply {
            text = "Refresh"
            setOnClickListener { loadMaps() }
        }
        buttonRow.addView(refreshButton)

        layout.addView(buttonRow)

        emptyText = TextView(this).apply {
            text = "No maps found.\n\nPush .json + .jpg pairs to:\n${mapsDir.absolutePath}/\n\nUse convert_geotiff.py to prepare maps."
            textSize = 14f
            setPadding(0, 32, 0, 0)
        }
        layout.addView(emptyText)

        listView = ListView(this)
        layout.addView(listView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.MATCH_PARENT
        ))

        listView.setOnItemClickListener { _, _, position, _ ->
            val meta = maps[position]
            val intent = Intent(this, MapViewerActivity::class.java)
            intent.putExtra("json_path", File(mapsDir, "${meta.name}.json").absolutePath)
            // Find the actual JSON file for this map
            val jsonFiles = mapsDir.listFiles()?.filter { it.extension == "json" } ?: emptyList()
            for (jf in jsonFiles) {
                val m = MapMetadata.fromJsonFile(jf)
                if (m != null && m.name == meta.name) {
                    intent.putExtra("json_path", jf.absolutePath)
                    break
                }
            }
            startActivity(intent)
        }

        setContentView(layout)

        checkStoragePermission()
    }

    override fun onResume() {
        super.onResume()
        loadMaps()
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(this,
                    "Please grant file access to read maps",
                    Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        } else {
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE), 1)
            }
        }
    }

    private fun loadMaps() {
        if (!mapsDir.exists()) {
            mapsDir.mkdirs()
        }
        maps = MapMetadata.scanDirectory(mapsDir)
        if (maps.isEmpty()) {
            emptyText.visibility = TextView.VISIBLE
            listView.visibility = ListView.GONE
        } else {
            emptyText.visibility = TextView.GONE
            listView.visibility = ListView.VISIBLE
            listView.adapter = ArrayAdapter(this,
                android.R.layout.simple_list_item_2,
                android.R.id.text1,
                maps.map { it.name }).also { adapter ->
                // Use a custom adapter to show map dimensions
            }
            listView.adapter = object : BaseAdapter() {
                override fun getCount() = maps.size
                override fun getItem(pos: Int) = maps[pos]
                override fun getItemId(pos: Int) = pos.toLong()
                override fun getView(pos: Int, convertView: android.view.View?, parent: android.view.ViewGroup?): android.view.View {
                    val view = convertView as? LinearLayout ?: LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(16, 24, 16, 24)
                    }
                    view.removeAllViews()
                    val meta = maps[pos]
                    view.addView(TextView(this@MainActivity).apply {
                        text = meta.name
                        textSize = 18f
                    })
                    val sizeMb = meta.imageFile.length() / (1024 * 1024)
                    view.addView(TextView(this@MainActivity).apply {
                        text = "${meta.width} x ${meta.height} px, ${sizeMb} MB"
                        textSize = 12f
                        setTextColor(0xFF888888.toInt())
                    })
                    return view
                }
            }
        }
    }
}
