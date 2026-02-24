package com.example.facialrecog

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.sqrt

// ══════════════════════════════════════════════════════════════════════
//  LIVE FEATURE BUILDER  (assembled in the analysis loop)
// ══════════════════════════════════════════════════════════════════════

object LiveFeatureBuilder {

    fun build(
        faces: List<Face>,
        pose: Pose?,
        handResult: HandLandmarkerResult?
    ): GestureFeatureVector {

        // ── Face ──────────────────────────────────────────────────────
        val f           = faces.firstOrNull()
        val smile       = f?.smilingProbability ?: 0f
        val yaw         = (f?.headEulerAngleY ?: 0f) / 90f
        val pitch       = (f?.headEulerAngleX ?: 0f) / 90f
        val roll        = (f?.headEulerAngleZ ?: 0f) / 90f

        val mouthPoints = f?.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points
        val upperLip    = f?.getContour(FaceContour.UPPER_LIP_TOP)?.points
        val faceContour = f?.getContour(FaceContour.FACE)?.points
        val leftBrow    = f?.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points
        val rightBrow   = f?.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points

        // Normalize mouth-open by face height so scale-invariant
        val faceHeight = faceContour?.let { it.maxOf { p -> p.y } - it.minOf { p -> p.y } }
            ?.takeIf { it > 0f } ?: 1f

        val rawMouthOpen = if (mouthPoints != null && upperLip != null &&
            mouthPoints.isNotEmpty() && upperLip.isNotEmpty())
            mouthPoints[mouthPoints.size / 2].y - upperLip[upperLip.size / 2].y
        else 0f
        val mouthOpen  = rawMouthOpen / faceHeight

        val mouthCurve = if (mouthPoints != null && mouthPoints.size >= 3) {
            val lc = mouthPoints.first().y; val rc = mouthPoints.last().y
            val cy = mouthPoints[mouthPoints.size / 2].y
            ((lc + rc) / 2f - cy) / faceHeight
        } else 0f

        val browRaise = if (leftBrow != null && rightBrow != null && faceContour != null) {
            val topY   = faceContour.minOf { it.y }
            val avgBrowY = (leftBrow.minOf { it.y } + rightBrow.minOf { it.y }) / 2f
            (avgBrowY - topY) / faceHeight
        } else 0f

        val browFurrow = if (leftBrow != null && rightBrow != null &&
            leftBrow.isNotEmpty() && rightBrow.isNotEmpty()) {
            val liY = leftBrow.maxByOrNull  { it.x }?.y ?: 0f
            val loY = leftBrow.minByOrNull  { it.x }?.y ?: 0f
            val riY = rightBrow.minByOrNull { it.x }?.y ?: 0f
            val roY = rightBrow.maxByOrNull { it.x }?.y ?: 0f
            ((liY - loY) + (riY - roY)) / 2f / faceHeight
        } else 0f

        // ── Pose ──────────────────────────────────────────────────────
        val lm = pose?.allPoseLandmarks?.associateBy { it.landmarkType }.orEmpty()
        val lwy  = lm[PoseLandmark.LEFT_WRIST]?.position?.y  ?: 0f
        val rwy  = lm[PoseLandmark.RIGHT_WRIST]?.position?.y ?: 0f
        val lsy  = lm[PoseLandmark.LEFT_SHOULDER]?.position?.y  ?: 0f
        val rsy  = lm[PoseLandmark.RIGHT_SHOULDER]?.position?.y ?: 0f
        val lsx  = lm[PoseLandmark.LEFT_SHOULDER]?.position?.x  ?: 0f
        val rsx  = lm[PoseLandmark.RIGHT_SHOULDER]?.position?.x ?: 0f
        val lwx  = lm[PoseLandmark.LEFT_WRIST]?.position?.x  ?: 0f
        val rwx  = lm[PoseLandmark.RIGHT_WRIST]?.position?.x ?: 0f

        val sw = abs(rsx - lsx).takeIf { it > 0f } ?: 1f
        val leftWristRelY  = (lwy - lsy) / sw
        val rightWristRelY = (rwy - rsy) / sw
        val wristSpread    = abs(rwx - lwx) / sw

        // ── Hand ──────────────────────────────────────────────────────
        val handLandmarks = FloatArray(42)
        handResult?.landmarks()?.firstOrNull()?.let { hand ->
            if (hand.size >= 21) {
                // Center & normalize by hand size (wrist→middle-MCP)
                val wristX = hand[0].x(); val wristY = hand[0].y()
                val handSize = sqrt(
                    (hand[9].x() - wristX).pow2() + (hand[9].y() - wristY).pow2()
                ).takeIf { it > 0f } ?: 1f
                for (i in 0 until 21) {
                    handLandmarks[i * 2]     = (hand[i].x() - wristX) / handSize
                    handLandmarks[i * 2 + 1] = (hand[i].y() - wristY) / handSize
                }
            }
        }

        return GestureFeatureVector(
            mouthOpen      = mouthOpen,
            mouthCurve     = mouthCurve,
            browFurrow     = browFurrow,
            browRaise      = browRaise,
            smileProb      = smile,
            yaw            = yaw,
            pitch          = pitch,
            roll           = roll,
            leftWristRelY  = leftWristRelY,
            rightWristRelY = rightWristRelY,
            wristSpread    = wristSpread,
            handLandmarks  = handLandmarks
        )
    }

    private fun Float.pow2() = this * this
}