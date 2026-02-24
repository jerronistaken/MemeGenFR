package com.example.facialrecog

import android.graphics.Bitmap
import android.graphics.BitmapFactory

// ══════════════════════════════════════════════════════════════════════
//  ORIGINAL OBJECTS (kept intact, still used as fallback / debug)
// ══════════════════════════════════════════════════════════════════════

object OverlayAssetResolver {

    private val SINGLE_KEYWORD_TO_DRAWABLE = mapOf(
        "point_right"   to R.drawable.ooo,
        "peace_sign"    to R.drawable.peace,
        "both_hands_up" to R.drawable.absolutecinema,
        "pinky_up"      to R.drawable.pinky,
        "four_fingers"  to R.drawable.four,
        "double_gun"    to R.drawable.crashout1,
        "point_up"      to R.drawable.shush,
        "fist"          to R.drawable.fist
    )

    private val COMPOUND_CONDITIONS: List<Pair<Set<String>, Int>> = listOf(
        setOf("left_hand_raised", "looking_left") to R.drawable.giveup,
    )

    fun resolve(keywords: List<String>): Int? {
        val keywordSet = keywords.toHashSet()
        for ((requiredSet, drawable) in COMPOUND_CONDITIONS) {
            if (keywordSet.containsAll(requiredSet)) return drawable
        }
        for ((keyword, drawable) in SINGLE_KEYWORD_TO_DRAWABLE) {
            if (keyword in keywordSet) return drawable
        }
        return null
    }

    fun loadBitmap(context: android.content.Context, drawableRes: Int): Bitmap? =
        try { BitmapFactory.decodeResource(context.resources, drawableRes) }
        catch (_: Exception) { null }
}