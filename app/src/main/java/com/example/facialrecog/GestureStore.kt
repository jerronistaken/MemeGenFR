package com.example.facialrecog

import org.json.JSONArray
import java.io.File

// ══════════════════════════════════════════════════════════════════════
//  GESTURE STORE  (persists to /files/gestures.json)
// ══════════════════════════════════════════════════════════════════════

object GestureStore {

    private val gestures = mutableListOf<GestureFeatureVector>()
    private var loaded   = false

    fun load(context: android.content.Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, "gestures.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            repeat(arr.length()) { i ->
                gestures.add(GestureFeatureVector.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            android.util.Log.e("GestureStore", "Load failed", e)
        }
    }

    fun save(context: android.content.Context) {
        try {
            val arr = JSONArray()
            gestures.forEach { arr.put(it.toJson()) }
            File(context.filesDir, "gestures.json").writeText(arr.toString())
        } catch (e: Exception) {
            android.util.Log.e("GestureStore", "Save failed", e)
        }
    }

    fun add(context: android.content.Context, vector: GestureFeatureVector) {
        gestures.add(vector)
        save(context)
    }

    fun remove(context: android.content.Context, uri: String) {
        gestures.removeAll { it.imageUri == uri }
        save(context)
    }

    fun all(): List<GestureFeatureVector> = gestures.toList()

    fun clear(context: android.content.Context) {
        gestures.clear()
        save(context)
    }
}