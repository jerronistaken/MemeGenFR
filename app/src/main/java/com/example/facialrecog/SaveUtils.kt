package com.example.facialrecog

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.View
import java.io.File


fun View.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    return bitmap
}

fun saveImageToGallery(
    context: android.content.Context,
    bitmap: Bitmap,
    filename: String = "WhatsThatMeme_${System.currentTimeMillis()}"
): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/WhatsThatMeme")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { s -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, s) }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, cv, null, null)
            }
            true
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "WhatsThatMeme")
            dir.mkdirs()
            File(dir, "$filename.png").outputStream().use { s -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, s) }
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("MemeGen", "Failed to save image", e)
        false
    }
}