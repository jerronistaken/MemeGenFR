package com.example.facialrecog

import android.content.Context
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object StaticImageExtractor {
    fun extract(
        context: Context,
        bitmap: Bitmap,
        handLandmarker: HandLandmarker?,
        imageUri: String,
        label: String
    ): GestureFeatureVector {
        val handResult: HandLandmarkerResult? = try {
            if (handLandmarker != null) {
                val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
                handLandmarker.detect(mpImage)
            } else null
        } catch (_: Exception) { null }

        val inputImage = InputImage.fromBitmap(bitmap, 0)

        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
        val faceDetector = FaceDetection.getClient(faceOptions)

        var faces: List<Face> = emptyList()
        val faceLatch = CountDownLatch(1)
        faceDetector.process(inputImage)
            .addOnSuccessListener { result -> faces = result; faceLatch.countDown() }
            .addOnFailureListener { faceLatch.countDown() }
        faceLatch.await(3, TimeUnit.SECONDS)
        faceDetector.close()

        val poseOptions = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        val poseDetector = PoseDetection.getClient(poseOptions)

        var pose: Pose? = null
        val poseLatch = CountDownLatch(1)
        poseDetector.process(inputImage)
            .addOnSuccessListener { result -> pose = result; poseLatch.countDown() }
            .addOnFailureListener { poseLatch.countDown() }
        poseLatch.await(3, TimeUnit.SECONDS)
        poseDetector.close()

        val vec = LiveFeatureBuilder.build(
            faces = faces,
            pose = pose,
            handResult = handResult
        )

        return vec.copy(imageUri = imageUri, label = label)
    }
}