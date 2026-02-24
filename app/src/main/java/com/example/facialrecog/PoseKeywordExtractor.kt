package com.example.facialrecog

import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs

/**
 * Converts ML Kit [Pose] detection results into semantic keyword strings
 * based on the relative positions of body landmarks.
 *
 * Possible keywords:
 *   hands_up, left_hand_raised, right_hand_raised,
 *   arms_outstretched, lean_left, lean_right
 *
 * Owner: Person C
 */
object PoseKeywordExtractor {

    fun extractPoseKeywords(pose: Pose): List<String> {
        val lm = pose.allPoseLandmarks.associateBy { it.landmarkType }

        fun y(type: Int): Float? = lm[type]?.position?.y
        fun x(type: Int): Float? = lm[type]?.position?.x

        val keywords = mutableListOf<String>()

        val leftWristY    = y(PoseLandmark.LEFT_WRIST)
        val rightWristY   = y(PoseLandmark.RIGHT_WRIST)
        val leftShoulderY = y(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderY = y(PoseLandmark.RIGHT_SHOULDER)

        // Wrist above shoulder = hand raised (lower Y = higher on screen)
        val leftHandUp  = leftWristY  != null && leftShoulderY  != null && leftWristY  < leftShoulderY
        val rightHandUp = rightWristY != null && rightShoulderY != null && rightWristY < rightShoulderY

        when {
            leftHandUp && rightHandUp -> keywords += "hands_up"
            leftHandUp               -> keywords += "left_hand_raised"
            rightHandUp              -> keywords += "right_hand_raised"
        }

        // Arms outstretched: wrist span > 1.6× shoulder span
        val leftWristX    = x(PoseLandmark.LEFT_WRIST)
        val rightWristX   = x(PoseLandmark.RIGHT_WRIST)
        val leftShoulderX = x(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderX = x(PoseLandmark.RIGHT_SHOULDER)

        if (leftWristX != null && rightWristX != null &&
            leftShoulderX != null && rightShoulderX != null) {
            val shoulderWidth = abs(rightShoulderX - leftShoulderX)
            val wristWidth    = abs(rightWristX - leftWristX)
            if (shoulderWidth > 0 && wristWidth / shoulderWidth > 1.6f) {
                keywords += "arms_outstretched"
            }
        }

        // Body lean: significant vertical difference between shoulders
        if (leftShoulderY != null && rightShoulderY != null) {
            val diff = rightShoulderY - leftShoulderY
            if (abs(diff) > 35f) keywords += if (diff > 0) "lean_left" else "lean_right"
        }

        return keywords
    }
}
