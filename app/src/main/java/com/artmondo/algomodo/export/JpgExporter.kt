package com.artmondo.algomodo.export

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream

object JpgExporter {
    fun export(context: Context, bitmap: Bitmap, fileName: String, quality: Int = 92): Uri? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$fileName.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Algomodo")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, values, null, null)
            }
            uri
        } else {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                "Algomodo"
            )
            dir.mkdirs()
            val file = File(dir, "$fileName.jpg")
            FileOutputStream(file).use { os ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, os)
            }
            Uri.fromFile(file)
        }
    }
}
