package com.example.facialrecog

import kotlin.math.sqrt

object GestureMatcher {

    fun findBestMatch(
        live: GestureFeatureVector,
        threshold: Float = 0.80f,
        candidates: List<GestureFeatureVector> = GestureStore.all()
    ): Pair<GestureFeatureVector, Float>? {
        if (candidates.isEmpty()) return null

        return candidates
            .map { stored ->
                stored to cosineSimilarity(live.toWeightedArray(), stored.toWeightedArray())
            }
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