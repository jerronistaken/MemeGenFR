package com.example.facialrecog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object LocalImageStore {

    fun saveBitmapToInternalStorage(
        context: Context,
        bitmap: Bitmap,
        prefix: String = "gesture"
    ): String {
        val dir = File(context.filesDir, "gesture_images")
        if (!dir.exists()) dir.mkdirs()

        val file = File(dir, "${prefix}_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    fun loadBitmap(context: Context, source: String): Bitmap? {
        return try {
            when {
                source.startsWith("http://") || source.startsWith("https://") -> {
                    URL(source).openStream().use { BitmapFactory.decodeStream(it) }
                }
                source.startsWith("content://") -> {
                    context.contentResolver.openInputStream(Uri.parse(source))
                        ?.use { BitmapFactory.decodeStream(it) }
                }
                source.startsWith("file://") -> {
                    File(Uri.parse(source).path ?: return null).inputStream()
                        .use { BitmapFactory.decodeStream(it) }
                }
                else -> {
                    File(source).inputStream().use { BitmapFactory.decodeStream(it) }
                }
            }
        } catch (_: Exception) {
            null
        }
    }
}