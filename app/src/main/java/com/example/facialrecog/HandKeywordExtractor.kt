package com.example.facialrecog

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Converts MediaPipe [HandLandmarkerResult] into semantic keyword strings
 * using a wrist-distance heuristic for finger extension detection.
 *
 * The heuristic is orientation-agnostic: a finger is considered extended
 * when its tip is farther from the wrist than its PIP joint, and PIP is
 * farther from the wrist than MCP (with 15% tolerance buffer).
 *
 * Possible keywords:
 *   four_fingers, fist, thumbs_up, thumbs_down, pointing,
 *   point_left, point_right, point_up, point_down,
 *   peace_sign, pinky_up, ok_sign, double_gun
 *
 * Owner: Person C
 */
object HandKeywordExtractor {

    // MediaPipe hand landmark indices
    private const val WRIST      = 0
    private const val THUMB_CMC  = 1
    private const val THUMB_MCP  = 2
    private const val THUMB_TIP  = 4
    private const val INDEX_MCP  = 5
    private const val INDEX_PIP  = 6
    private const val INDEX_TIP  = 8
    private const val MIDDLE_MCP = 9
    private const val MIDDLE_PIP = 10
    private const val MIDDLE_TIP = 12
    private const val RING_MCP   = 13
    private const val RING_PIP   = 14
    private const val RING_TIP   = 16
    private const val PINKY_MCP  = 17
    private const val PINKY_PIP  = 18
    private const val PINKY_TIP  = 20

    fun extractHandKeywords(
        result: HandLandmarkerResult?,
        isFrontCameraMirrored: Boolean
    ): List<String> {
        if (result == null) return emptyList()
        val hands = result.landmarks()
        if (hands.isEmpty()) return emptyList()

        val keywords = mutableListOf<String>()
        val hand = hands[0]
        if (hand.size < 21) return keywords

        fun x(i: Int) = hand[i].x()
        fun y(i: Int) = hand[i].y()

        fun isExtended(tip: Int, pip: Int, mcp: Int): Boolean {
            val wristDist = { i: Int -> distance(x(i), y(i), x(WRIST), y(WRIST)) }
            return wristDist(tip) > wristDist(pip) && wristDist(pip) > wristDist(mcp) * 0.85f
        }

        val indexExt  = isExtended(INDEX_TIP,  INDEX_PIP,  INDEX_MCP)
        val middleExt = isExtended(MIDDLE_TIP, MIDDLE_PIP, MIDDLE_MCP)
        val ringExt   = isExtended(RING_TIP,   RING_PIP,   RING_MCP)
        val pinkyExt  = isExtended(PINKY_TIP,  PINKY_PIP,  PINKY_MCP)

        // Thumb: extended if tip is far from index MCP
        val thumbExt = distance(x(THUMB_TIP), y(THUMB_TIP), x(INDEX_MCP), y(INDEX_MCP)) >
                distance(x(THUMB_MCP), y(THUMB_MCP), x(INDEX_MCP), y(INDEX_MCP)) * 0.9f

        val extendedCount = listOf(indexExt, middleExt, ringExt, pinkyExt).count { it }

        // Open palm: all 4 fingers extended
        if (extendedCount == 4) keywords += "four_fingers"

        // Fist: no fingers and no thumb extended
        if (extendedCount == 0 && !thumbExt) keywords += "fist"

        // Thumbs up: only thumb extended, fist otherwise
        if (thumbExt && extendedCount == 0) keywords += "thumbs_up"

        // Thumbs down: thumb extended downward (higher Y = lower on screen)
        if (thumbExt && extendedCount == 0 && y(THUMB_TIP) > y(WRIST)) keywords += "thumbs_down"

        // Pointing: only index finger extended
        if (indexExt && !middleExt && !ringExt && !pinkyExt) {
            keywords += "pointing"
            val dx = x(INDEX_TIP) - x(INDEX_MCP)
            val dy = y(INDEX_TIP) - y(INDEX_MCP)
            val dir = if (abs(dx) > abs(dy)) {
                if (dx > 0) "right" else "left"
            } else {
                if (dy > 0) "down" else "up"
            }
            // Correct for front camera mirror
            val correctedDir = when (dir) {
                "left"  -> if (isFrontCameraMirrored) "right" else "left"
                "right" -> if (isFrontCameraMirrored) "left"  else "right"
                else    -> dir
            }
            keywords += "point_$correctedDir"
        }

        // Peace / Victory sign: index + middle only
        if (indexExt && middleExt && !ringExt && !pinkyExt) keywords += "peace_sign"

        // Pinky up: only pinky extended
        if (pinkyExt && !indexExt && !middleExt && !ringExt) keywords += "pinky_up"

        // OK sign: thumb tip close to index tip, other fingers extended
        val thumbIndexDist = distance(x(THUMB_TIP), y(THUMB_TIP), x(INDEX_TIP), y(INDEX_TIP))
        val handSize = distance(x(WRIST), y(WRIST), x(MIDDLE_MCP), y(MIDDLE_MCP))
        if (thumbIndexDist < handSize * 0.35f && middleExt && ringExt && pinkyExt) {
            keywords += "ok_sign"
        }

        // Double gun: both hands in gun shape pointing upward
        if (hands.size >= 2) {
            var gunCount = 0
            for (h in hands) {
                if (h.size < 21) continue
                fun hx(i: Int) = h[i].x()
                fun hy(i: Int) = h[i].y()
                fun hDist(a: Int, b: Int) = distance(hx(a), hy(a), hx(b), hy(b))
                fun hWristDist(i: Int) = distance(hx(i), hy(i), hx(WRIST), hy(WRIST))

                val hIndexExt  = hWristDist(INDEX_TIP)  > hWristDist(INDEX_PIP)  && hWristDist(INDEX_PIP)  > hWristDist(INDEX_MCP)  * 0.85f
                val hMiddleExt = hWristDist(MIDDLE_TIP) > hWristDist(MIDDLE_PIP) && hWristDist(MIDDLE_PIP) > hWristDist(MIDDLE_MCP) * 0.85f
                val hRingExt   = hWristDist(RING_TIP)   > hWristDist(RING_PIP)   && hWristDist(RING_PIP)   > hWristDist(RING_MCP)   * 0.85f
                val hPinkyExt  = hWristDist(PINKY_TIP)  > hWristDist(PINKY_PIP)  && hWristDist(PINKY_PIP)  > hWristDist(PINKY_MCP)  * 0.85f
                val hThumbExt  = hDist(THUMB_TIP, INDEX_MCP) > hDist(THUMB_MCP, INDEX_MCP) * 0.9f

                val isGun        = hIndexExt && hThumbExt && !hMiddleExt && !hRingExt && !hPinkyExt
                val isPointingUp = hy(INDEX_TIP) < hy(INDEX_MCP)

                if (isGun && isPointingUp) gunCount++
            }
            if (gunCount >= 2) keywords += "double_gun"
        }

        return keywords.distinct()
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx
        val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}
