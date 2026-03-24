package com.example.facialrecog

import com.example.facialrecog.cloud.CloudMeme
import org.json.JSONArray
import org.json.JSONObject

data class GestureFeatureVector(
    val mouthOpen: Float = 0f,
    val mouthCurve: Float = 0f,
    val browFurrow: Float = 0f,
    val browRaise: Float = 0f,
    val smileProb: Float = 0f,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    val leftWristRelY: Float = 0f,
    val rightWristRelY: Float = 0f,
    val wristSpread: Float = 0f,
    val handLandmarks: FloatArray = FloatArray(42),
    val imageUri: String = "",
    val label: String = ""
) {
    fun toWeightedArray(): FloatArray {
        val fw = 2.0f
        val pw = 1.5f
        val hw = 3.0f

        return floatArrayOf(
            mouthOpen * fw,
            mouthCurve * fw,
            browFurrow * fw,
            browRaise * fw,
            smileProb * fw,
            yaw * fw,
            pitch * fw,
            roll * fw,
            leftWristRelY * pw,
            rightWristRelY * pw,
            wristSpread * pw,
            *handLandmarks.map { it * hw }.toFloatArray()
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mouthOpen", mouthOpen)
        put("mouthCurve", mouthCurve)
        put("browFurrow", browFurrow)
        put("browRaise", browRaise)
        put("smileProb", smileProb)
        put("yaw", yaw)
        put("pitch", pitch)
        put("roll", roll)
        put("leftWristRelY", leftWristRelY)
        put("rightWristRelY", rightWristRelY)
        put("wristSpread", wristSpread)
        put("handLandmarks", JSONArray(handLandmarks.toTypedArray()))
        put("imageUri", imageUri)
        put("label", label)
    }

    fun toCloudMeme(
        id: String,
        ownerUid: String,
        ownerEmail: String,
        imageUrl: String,
        storagePath: String,
        isPublic: Boolean
    ): CloudMeme {
        return CloudMeme(
            id = id,
            ownerUid = ownerUid,
            ownerEmail = ownerEmail,
            label = label,
            imageUrl = imageUrl,
            storagePath = storagePath,
            createdAt = System.currentTimeMillis(),
            isPublic = isPublic,
            mouthOpen = mouthOpen.toDouble(),
            mouthCurve = mouthCurve.toDouble(),
            browFurrow = browFurrow.toDouble(),
            browRaise = browRaise.toDouble(),
            smileProb = smileProb.toDouble(),
            yaw = yaw.toDouble(),
            pitch = pitch.toDouble(),
            roll = roll.toDouble(),
            leftWristRelY = leftWristRelY.toDouble(),
            rightWristRelY = rightWristRelY.toDouble(),
            wristSpread = wristSpread.toDouble(),
            handLandmarks = handLandmarks.map { it.toDouble() }
        )
    }

    companion object {
        fun fromJson(obj: JSONObject): GestureFeatureVector {
            val arr = obj.getJSONArray("handLandmarks")
            val hl = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }

            return GestureFeatureVector(
                mouthOpen = obj.getDouble("mouthOpen").toFloat(),
                mouthCurve = obj.getDouble("mouthCurve").toFloat(),
                browFurrow = obj.getDouble("browFurrow").toFloat(),
                browRaise = obj.getDouble("browRaise").toFloat(),
                smileProb = obj.getDouble("smileProb").toFloat(),
                yaw = obj.getDouble("yaw").toFloat(),
                pitch = obj.getDouble("pitch").toFloat(),
                roll = obj.getDouble("roll").toFloat(),
                leftWristRelY = obj.getDouble("leftWristRelY").toFloat(),
                rightWristRelY = obj.getDouble("rightWristRelY").toFloat(),
                wristSpread = obj.getDouble("wristSpread").toFloat(),
                handLandmarks = hl,
                imageUri = obj.getString("imageUri"),
                label = obj.getString("label")
            )
        }
    }
}