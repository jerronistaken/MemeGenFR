package com.example.facialrecog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.net.URL

object LocalImageStore {

    /**
     * Thumbnail side-length used by [loadBitmapScaled] and [loadBitmapCached].
     * Keeping thumbnails at 256 px means each cached bitmap is ~256 KB — small
     * enough that the LruCache can hold 24 of them for ~6 MB total.
     */
    private const val THUMB_PX = 256

    /**
     * Load a bitmap from [source], check [BitmapCache] first, decode on miss,
     * store the result at thumbnail resolution, and return it.
     *
     * ⚠ MUST be called from a background thread / IO dispatcher.
     */
    fun loadBitmapCached(context: Context, source: String): Bitmap? {
        BitmapCache.get(source)?.let { return it }
        val bmp = loadBitmapScaled(context, source, THUMB_PX) ?: return null
        BitmapCache.put(source, bmp)
        return bmp
    }

    /**
     * Decode [source] at reduced resolution (longest side ≤ [maxPx]).
     * Uses BitmapFactory inSampleSize so the full image is never fully loaded
     * into memory for large remote photos.
     *
     * ⚠ MUST be called from a background thread / IO dispatcher.
     */
    fun loadBitmapScaled(context: Context, source: String, maxPx: Int = THUMB_PX): Bitmap? {
        return try {
            // First pass: read dimensions only.
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStreamForSource(context, source)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            }
            val rawW = opts.outWidth.takeIf { it > 0 } ?: return loadBitmap(context, source)
            val rawH = opts.outHeight.takeIf { it > 0 } ?: return loadBitmap(context, source)

            // Compute the largest power-of-two subsample that still produces an
            // image at least [maxPx] on its longest side.
            var sample = 1
            var w = rawW; var h = rawH
            while (maxOf(w, h) > maxPx * 2) {
                sample *= 2; w /= 2; h /= 2
            }

            // Second pass: decode at the computed sample size.
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
            openStreamForSource(context, source)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Open an InputStream for any supported [source] scheme. */
    private fun openStreamForSource(context: Context, source: String): java.io.InputStream? {
        return when {
            source.startsWith("http://") || source.startsWith("https://") ->
                URL(source).openStream()

            source.startsWith("content://") ->
                context.contentResolver.openInputStream(Uri.parse(source))

            source.startsWith("file://") ->
                File(Uri.parse(source).path ?: return null).inputStream()

            else ->
                File(source).inputStream()
        }
    }

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

    /**
     * Load a bitmap from any source (local file, content URI, or http/https URL).
     *
     * ⚠ MUST be called from a background thread / IO dispatcher.
     * For remote URLs this performs a blocking network call. For local paths it
     * performs a blocking disk read. Never call this on the main thread or
     * inside a remember{} block.
     */
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

    /**
     * Download a remote image (http/https) and persist it to internal storage.
     * Returns the local absolute path on success, or null on failure.
     *
     * ⚠ MUST be called from a background thread / IO dispatcher.
     */
    fun downloadAndSave(
        context: Context,
        remoteUrl: String,
        prefix: String = "cloud"
    ): String? {
        return try {
            val bitmap = URL(remoteUrl).openStream().use { BitmapFactory.decodeStream(it) }
                ?: return null
            saveBitmapToInternalStorage(context, bitmap, prefix)
        } catch (_: Exception) {
            null
        }
    }
}