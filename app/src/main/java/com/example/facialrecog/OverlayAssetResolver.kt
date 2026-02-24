package com.example.facialrecog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * Maps detected gesture/expression keywords to drawable resource IDs,
 * and provides a helper to decode those resources into Bitmaps.
 *
 * To add a new overlay: add an entry to [KEYWORD_TO_DRAWABLE] with
 * the trigger keyword and the corresponding R.drawable resource.
 *
 * Owner: Person D
 */
object OverlayAssetResolver {

    private val KEYWORD_TO_DRAWABLE = mapOf(
        "peace_sign" to R.drawable.peace,
        "hands_up"   to R.drawable.absolutecinema,
        "pinky_up"   to R.drawable.pinky,
        "four_fingers"       to R.drawable.four,
        "double_gun" to R.drawable.crashout1,
    )

    /** Returns the first matching drawable resource ID, or null if no keyword matches. */
    fun resolve(keywords: List<String>): Int? {
        val keywordSet = keywords.toHashSet()
        for ((keyword, drawable) in KEYWORD_TO_DRAWABLE) {
            if (keyword in keywordSet) return drawable
        }
        return null
    }

    /** Decodes a drawable resource into a Bitmap, returning null on failure. */
    fun loadBitmap(context: Context, drawableRes: Int): Bitmap? =
        try {
            BitmapFactory.decodeResource(context.resources, drawableRes)
        } catch (_: Exception) {
            null
        }
}
