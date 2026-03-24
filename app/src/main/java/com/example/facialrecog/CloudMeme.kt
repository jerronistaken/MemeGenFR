package com.example.facialrecog.cloud

import com.example.facialrecog.GestureFeatureVector

data class CloudMeme(
    val id: String = "",
    val ownerUid: String = "",
    val ownerEmail: String = "",
    val label: String = "",
    val imageUrl: String = "",
    val storagePath: String = "",
    val createdAt: Long = 0L,
    val isPublic: Boolean = true,

    val mouthOpen: Double = 0.0,
    val mouthCurve: Double = 0.0,
    val browFurrow: Double = 0.0,
    val browRaise: Double = 0.0,
    val smileProb: Double = 0.0,
    val yaw: Double = 0.0,
    val pitch: Double = 0.0,
    val roll: Double = 0.0,
    val leftWristRelY: Double = 0.0,
    val rightWristRelY: Double = 0.0,
    val wristSpread: Double = 0.0,
    val handLandmarks: List<Double> = emptyList()
) {
    fun toGestureFeatureVector(): GestureFeatureVector {
        return GestureFeatureVector(
            mouthOpen = mouthOpen.toFloat(),
            mouthCurve = mouthCurve.toFloat(),
            browFurrow = browFurrow.toFloat(),
            browRaise = browRaise.toFloat(),
            smileProb = smileProb.toFloat(),
            yaw = yaw.toFloat(),
            pitch = pitch.toFloat(),
            roll = roll.toFloat(),
            leftWristRelY = leftWristRelY.toFloat(),
            rightWristRelY = rightWristRelY.toFloat(),
            wristSpread = wristSpread.toFloat(),
            handLandmarks = handLandmarks.map { it.toFloat() }.toFloatArray(),
            imageUri = imageUrl,
            label = label
        )
    }
}