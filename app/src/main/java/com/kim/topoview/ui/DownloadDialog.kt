package com.kim.topoview.ui

import android.app.AlertDialog
import android.content.Context
import com.kim.topoview.data.MapSheet

object DownloadDialog {

    fun show(context: Context, sheet: MapSheet, onConfirm: () -> Unit) {
        val sizeHint = if (sheet.scale <= 25000) "~50-100 MB" else "~20-50 MB"

        AlertDialog.Builder(context)
            .setTitle("Download ${sheet.name}?")
            .setMessage(
                "Source: ${sheet.source}\n" +
                "Scale: 1:${sheet.scale}\n" +
                "Estimated size: $sizeHint\n\n" +
                "Download this map sheet?"
            )
            .setPositiveButton("Download") { _, _ -> onConfirm() }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
