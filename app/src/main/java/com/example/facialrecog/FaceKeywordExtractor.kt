package com.example.facialrecog

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour

/**
 * Converts ML Kit [Face] detection results into semantic keyword strings.
 *
 * Uses both ML Kit's built-in classification scores (smile probability,
 * eye open probability, head Euler angles) and geometric analysis of
 * face contour points to infer emotion and head orientation.
 *
 * Possible keywords:
 *   Orientation: looking_left, looking_right, looking_up, looking_down,
 *                head_tilt_left, head_tilt_right
 *   Emotion:     smiling, sad, angry, surprised, disgusted, fearful, neutral
 *
 * Owner: Person B
 */
object FaceKeywordExtractor {

    fun extractFaceKeywords(faces: List<Face>): List<String> {
        if (faces.isEmpty()) return emptyList()
        val f = faces[0]
        val keywords = mutableListOf<String>()

        val smile    = f.smilingProbability ?: 0f
        val leftEye  = f.leftEyeOpenProbability ?: 1f
        val rightEye = f.rightEyeOpenProbability ?: 1f
        val yaw      = f.headEulerAngleY  // left/right turn
        val pitch    = f.headEulerAngleX  // up/down tilt
        val roll     = f.headEulerAngleZ  // head tilt

        // ── Head orientation ─────────────────────────────────────────
        if (yaw > 20f)         keywords += "looking_left"
        else if (yaw < -20f)   keywords += "looking_right"
        if (pitch > 15f)       keywords += "looking_up"
        else if (pitch < -15f) keywords += "looking_down"
        if (roll > 15f)        keywords += "head_tilt_right"
        else if (roll < -15f)  keywords += "head_tilt_left"

        // ── Geometry-based signals from face contours ─────────────────
        val mouthPoints = f.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points
        val upperLip    = f.getContour(FaceContour.UPPER_LIP_TOP)?.points
        val faceContour = f.getContour(FaceContour.FACE)?.points
        val leftBrow    = f.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points
        val rightBrow   = f.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points
        val leftEyePts  = f.getContour(FaceContour.LEFT_EYE)?.points

        // Mouth openness: vertical gap between upper and lower lip midpoints
        val mouthOpen = if (mouthPoints != null && upperLip != null &&
            mouthPoints.isNotEmpty() && upperLip.isNotEmpty()) {
            mouthPoints[mouthPoints.size / 2].y - upperLip[upperLip.size / 2].y
        } else null

        // Mouth curve: positive = corners higher than center = smile,
        //              negative = corners lower = frown
        val mouthCurve = if (mouthPoints != null && mouthPoints.size >= 3) {
            val centerY = mouthPoints[mouthPoints.size / 2].y
            ((mouthPoints.first().y + mouthPoints.last().y) / 2f) - centerY
        } else null

        // Brow raise: normalized height of brows relative to face height
        // Lower value = higher brows (more raised)
        val browRaise = if (leftBrow != null && rightBrow != null &&
            faceContour != null && faceContour.size >= 10) {
            val faceTopY    = faceContour.minOf { it.y }
            val faceCenterY = faceContour.maxOf { it.y }
            val faceHeight  = faceCenterY - faceTopY
            val avgBrowY    = (leftBrow.minOf { it.y } + rightBrow.minOf { it.y }) / 2f
            (avgBrowY - faceTopY) / faceHeight
        } else null

        // Brow furrow: inner brow corners pulled down = anger/worry
        val browFurrow = if (leftBrow != null && rightBrow != null &&
            leftBrow.isNotEmpty() && rightBrow.isNotEmpty()) {
            val leftInnerY  = leftBrow.maxByOrNull  { it.x }?.y ?: 0f
            val leftOuterY  = leftBrow.minByOrNull  { it.x }?.y ?: 0f
            val rightInnerY = rightBrow.minByOrNull { it.x }?.y ?: 0f
            val rightOuterY = rightBrow.maxByOrNull { it.x }?.y ?: 0f
            ((leftInnerY - leftOuterY) + (rightInnerY - rightOuterY)) / 2f
        } else null

        // ── Emotion classification ────────────────────────────────────

        // SURPRISED: mouth open + eyebrows raised
        val isSurprised = (mouthOpen != null && mouthOpen > 15f) &&
                (browRaise != null && browRaise < 0.25f)
        if (isSurprised) keywords += "surprised"

        // HAPPY / SMILING: ML Kit score OR upward mouth curve
        val isSmiling = smile > 0.65f || (mouthCurve != null && mouthCurve < -8f && smile > 0.3f)
        if (isSmiling && !isSurprised) keywords += "smiling"

        // SAD: mouth corners down + low smile probability
        val isSad = (mouthCurve != null && mouthCurve > 10f) && smile < 0.3f
        if (isSad) keywords += "sad"

        // ANGRY: furrowed brows + low smile + brows not raised high
        val isAngry = (browFurrow != null && browFurrow > 8f) &&
                smile < 0.2f &&
                (browRaise == null || browRaise > 0.28f)
        if (isAngry) keywords += "angry"

        // DISGUSTED: furrowed brow + slight mouth opening (no nose wrinkle from ML Kit)
        val isDisgusted = (browFurrow != null && browFurrow > 5f) &&
                (mouthOpen != null && mouthOpen in 3f..15f) &&
                smile < 0.2f
        if (isDisgusted && !isAngry) keywords += "disgusted"

        // FEARFUL: raised brows + slightly open mouth + not surprised
        val isFearful = (browRaise != null && browRaise < 0.22f) &&
                (mouthOpen != null && mouthOpen > 5f) &&
                smile < 0.3f && !isSurprised
        if (isFearful) keywords += "fearful"

        // NEUTRAL: no other emotion detected
        val emotionKeywords = setOf("smiling", "sad", "angry", "surprised", "disgusted", "fearful")
        if (keywords.none { it in emotionKeywords }) keywords += "neutral"

        return keywords
    }
}
