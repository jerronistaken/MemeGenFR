package com.example.facialrecog

import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.abs
import kotlin.math.sqrt

object LiveFeatureBuilder {
    fun build(
        faces: List<Face>,
        pose: Pose?,
        handResult: HandLandmarkerResult?
    ): GestureFeatureVector {
        // paste your existing implementation here (unchanged)
        // from object LiveFeatureBuilder in MainActivity.kt
        TODO("Paste your existing LiveFeatureBuilder.build(...) implementation")
    }
}