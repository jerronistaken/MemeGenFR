package com.example.facialrecog

import android.content.Context
import android.util.Log
import org.json.JSONArray
import java.io.File

object GestureStore {
    private val gestures = mutableListOf<GestureFeatureVector>()
    private var loaded = false

    fun load(context: Context) {
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
            Log.e("GestureStore", "Load failed", e)
        }
    }

    fun save(context: Context) {
        try {
            val arr = JSONArray()
            gestures.forEach { arr.put(it.toJson()) }
            File(context.filesDir, "gestures.json").writeText(arr.toString())
        } catch (e: Exception) {
            Log.e("GestureStore", "Save failed", e)
        }
    }

    fun add(context: Context, vector: GestureFeatureVector) {
        gestures.add(vector)
        save(context)
    }

    fun remove(context: Context, uri: String) {
        gestures.removeAll { it.imageUri == uri }
        save(context)
    }

    fun all(): List<GestureFeatureVector> = gestures.toList()

    fun clear(context: Context) {
        gestures.clear()
        save(context)
    }
}