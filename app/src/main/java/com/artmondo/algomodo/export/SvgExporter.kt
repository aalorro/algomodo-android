package com.artmondo.algomodo.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object SvgExporter {
    fun export(context: Context, svgContent: String, fileName: String): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "$fileName.svg")
                put(MediaStore.Downloads.MIME_TYPE, "image/svg+xml")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Algomodo")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    os.write(svgContent.toByteArray(Charsets.UTF_8))
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                context.contentResolver.update(it, values, null, null)
            }
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "Algomodo"
            )
            dir.mkdirs()
            val file = File(dir, "$fileName.svg")
            FileOutputStream(file).use { os ->
                os.write(svgContent.toByteArray(Charsets.UTF_8))
            }
            Uri.fromFile(file)
        }
    }
}
