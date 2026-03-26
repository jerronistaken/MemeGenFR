package com.example.facialrecog

import android.graphics.Bitmap
import androidx.collection.LruCache

/**
 * Process-wide, in-memory LRU cache for decoded bitmaps.
 *
 * Sizing rationale
 * ─────────────────
 * We store thumbnails decoded at MAX_PX × MAX_PX (see LocalImageStore.loadBitmapScaled).
 * At 256 × 256 × 4 bytes/pixel that is ~256 KB per entry.
 * A 24-entry cache therefore occupies at most ~6 MB — well within Android's
 * typical 64–256 MB heap budget and far below the 1/8-of-heap ceiling used
 * by the system's own BitmapCache.
 *
 * Thread safety
 * ─────────────
 * [LruCache] is internally synchronised, so concurrent reads and writes from
 * the IO dispatcher are safe without additional locking.
 *
 * Cache invalidation
 * ──────────────────
 * Local file paths are stable (they never change once written), and Firebase
 * Storage download URLs are content-addressed (a new upload gets a new path),
 * so keys never go stale during a single app session.
 * Call [evict] if you delete a local gesture, or [clear] on sign-out to free
 * memory and prevent one user's cache from bleeding into another session.
 */
object BitmapCache {

    /** Maximum number of decoded bitmaps to keep in memory simultaneously. */
    private const val MAX_ENTRIES = 24

    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap) = 1   // count by entries, not bytes
    }

    /** Returns the cached bitmap for [key], or null if not present. */
    fun get(key: String): Bitmap? = cache[key]

    /** Stores [bitmap] under [key], replacing any existing entry. */
    fun put(key: String, bitmap: Bitmap) {
        cache.put(key, bitmap)
    }

    /** Removes the entry for [key] (e.g. after a local gesture is deleted). */
    fun evict(key: String) {
        cache.remove(key)
    }

    /** Drops all cached entries (e.g. on sign-out). */
    fun clear() {
        cache.evictAll()
    }
}