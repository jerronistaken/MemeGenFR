package com.example.facialrecog

import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions


// ══════════════════════════════════════════════════════════════════════
//  STATIC IMAGE FEATURE EXTRACTOR  (for uploaded photos)
// ══════════════════════════════════════════════════════════════════════

object StaticImageExtractor {

    /**
     * Runs face + hand detection on a static bitmap and returns a feature vector.
     * Call from a background coroutine.
     */
    fun extract(
        context: android.content.Context,
        bitmap: Bitmap,
        handLandmarker: HandLandmarker?,
        imageUri: String,
        label: String
    ): GestureFeatureVector {

        // ── Hands ──────────────────────────────────────────────────────
        val handResult: HandLandmarkerResult? = try {
            if (handLandmarker != null) {
                val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
                handLandmarker.detect(mpImage)
            } else null
        } catch (_: Exception) { null }

        // ── Face (blocking via Tasks API) ─────────────────────────────
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
        val faceDetector = FaceDetection.getClient(faceOptions)

        var faces: List<Face> = emptyList()
        val latch = java.util.concurrent.CountDownLatch(1)
        faceDetector.process(inputImage)
            .addOnSuccessListener { result -> faces = result; latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        faceDetector.close()

        // ── Pose ──────────────────────────────────────────────────────
        val poseOptions = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        val poseDetector = PoseDetection.getClient(poseOptions)
        var pose: Pose? = null
        val poseLatch = java.util.concurrent.CountDownLatch(1)
        poseDetector.process(inputImage)
            .addOnSuccessListener { result -> pose = result; poseLatch.countDown() }
            .addOnFailureListener { poseLatch.countDown() }
        poseLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        poseDetector.close()

        val liveVec = LiveFeatureBuilder.build(
            faces     = faces,
            pose      = pose,
            handResult = handResult
        )

        return liveVec.copy(imageUri = imageUri, label = label)
    }
}
