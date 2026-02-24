package com.example.facialrecog

import kotlin.math.sqrt

object GestureMatcher {

    fun findBestMatch(
        live: GestureFeatureVector,
        threshold: Float = 0.80f
    ): Pair<GestureFeatureVector, Float>? {
        val stored = GestureStore.all()
        if (stored.isEmpty()) return null

        return stored
            .map { s -> s to cosineSimilarity(live.toWeightedArray(), s.toWeightedArray()) }
            .filter { (_, score) -> score >= threshold }
            .maxByOrNull { (_, score) -> score }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        var dot = 0f
        var magA = 0f
        var magB = 0f
        for (i in 0 until len) {
            dot += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        magA = sqrt(magA)
        magB = sqrt(magB)
        return if (magA == 0f || magB == 0f) 0f else dot / (magA * magB)
    }
}