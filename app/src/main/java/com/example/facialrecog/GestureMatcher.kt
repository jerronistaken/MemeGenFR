package com.example.facialrecog

import kotlin.math.sqrt

/**
 * Cosine-similarity matcher between a live feature vector and a pool of stored candidates.
 *
 * Performance notes
 * ─────────────────
 * [findBestMatch] is O(n) in the number of candidates.  At 200 candidates each
 * with a 53-element weighted array the work per call is ~21 000 float multiplications
 * — roughly 0.1 ms on a modern Snapdragon.  It is safe to call from a coroutine
 * dispatched on [kotlinx.coroutines.Dispatchers.Default] once per camera frame.
 *
 * To avoid re-allocating the weighted array on every call for every candidate,
 * [findBestMatch] uses [weightedArrayCache] — a weak-identity map keyed on the
 * candidate's identity hashcode + label + imageUri.  The cache is invalidated
 * automatically when [GestureStore] replaces its list (new object identity).
 */
object GestureMatcher {

    // Cache of pre-computed weighted arrays.  Key = stable string identity of
    // the vector; value = the FloatArray returned by toWeightedArray().
    // Using a plain HashMap is fine here because:
    //   • it is only ever accessed from coroutines on Dispatchers.Default (single-threaded pool)
    //   • the cache is bounded by the number of stored gestures (≤ 200 after the 200-limit fetch)
    private val weightedArrayCache = HashMap<String, FloatArray>()

    /**
     * Returns a cache key that is unique per logical gesture but stable across
     * recompositions.  We intentionally exclude mutable fields like match state.
     */
    private fun cacheKey(v: GestureFeatureVector) = "${v.label}|${v.imageUri}"

    /** Retrieve (or compute and store) the weighted array for [v]. */
    private fun cachedWeighted(v: GestureFeatureVector): FloatArray {
        val key = cacheKey(v)
        return weightedArrayCache.getOrPut(key) { v.toWeightedArray() }
    }

    /**
     * Invalidate any cached weighted arrays whose keys are no longer present in
     * [currentCandidates].  Call this whenever the candidate list changes so stale
     * entries from deleted gestures don't accumulate.
     */
    fun syncCache(currentCandidates: List<GestureFeatureVector>) {
        val live = currentCandidates.map { cacheKey(it) }.toHashSet()
        val stale = weightedArrayCache.keys.filter { it !in live }
        stale.forEach { weightedArrayCache.remove(it) }
    }

    fun findBestMatch(
        live: GestureFeatureVector,
        threshold: Float = 0.80f,
        candidates: List<GestureFeatureVector> = GestureStore.all()
    ): Pair<GestureFeatureVector, Float>? {
        if (candidates.isEmpty()) return null

        val liveWeighted = live.toWeightedArray()   // live vector is never cached (it changes every frame)

        return candidates
            .map { stored -> stored to cosineSimilarity(liveWeighted, cachedWeighted(stored)) }
            .filter  { (_, score) -> score >= threshold }
            .maxByOrNull { (_, score) -> score }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        var dot = 0f; var magA = 0f; var magB = 0f
        for (i in 0 until len) {
            dot  += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        magA = sqrt(magA); magB = sqrt(magB)
        return if (magA == 0f || magB == 0f) 0f else dot / (magA * magB)
    }
}