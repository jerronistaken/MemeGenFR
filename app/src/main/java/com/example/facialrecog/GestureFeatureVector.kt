package com.example.facialrecog

import org.json.JSONArray
import org.json.JSONObject

// ══════════════════════════════════════════════════════════════════════
//  DATA MODEL
// ══════════════════════════════════════════════════════════════════════


/**
 * A feature vector extracted from one frame (or a saved photo).
 * All values are normalized so that distance / cosine-similarity is meaningful
 * across different face sizes and camera distances.
 */
data class GestureFeatureVector(
    // ── Face ──────────────────────────────────────────────────────────
    val mouthOpen: Float      = 0f,   // pixels, normalized by face height
    val mouthCurve: Float     = 0f,
    val browFurrow: Float     = 0f,
    val browRaise: Float      = 0f,
    val smileProb: Float      = 0f,
    val yaw: Float            = 0f,   // head rotation in degrees / 90
    val pitch: Float          = 0f,
    val roll: Float           = 0f,
    // ── Pose ──────────────────────────────────────────────────────────
    val leftWristRelY: Float  = 0f,   // (wristY – shoulderY) / shoulder width
    val rightWristRelY: Float = 0f,
    val wristSpread: Float    = 0f,   // |wristX_L – wristX_R| / shoulder width
    // ── Hand (21 landmarks × 2 coords = 42 floats, already 0-1) ──────
    val handLandmarks: FloatArray = FloatArray(42),
    // ── Metadata ──────────────────────────────────────────────────────
    val imageUri: String      = "",
    val label: String         = ""
) {
    /** Weighted flat float array used for distance calculations */
    fun toWeightedArray(): FloatArray {
        val fw = 2.0f   // face weight
        val pw = 1.5f   // pose weight
        val hw = 3.0f   // hand weight
        return floatArrayOf(
            mouthOpen  * fw, mouthCurve * fw, browFurrow * fw,
            browRaise  * fw, smileProb  * fw,
            yaw * fw, pitch * fw, roll * fw,
            leftWristRelY  * pw,
            rightWristRelY * pw,
            wristSpread    * pw,
            *handLandmarks.map { it * hw }.toFloatArray()
        )
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("mouthOpen",      mouthOpen)
        put("mouthCurve",     mouthCurve)
        put("browFurrow",     browFurrow)
        put("browRaise",      browRaise)
        put("smileProb",      smileProb)
        put("yaw",            yaw)
        put("pitch",          pitch)
        put("roll",           roll)
        put("leftWristRelY",  leftWristRelY)
        put("rightWristRelY", rightWristRelY)
        put("wristSpread",    wristSpread)
        put("handLandmarks",  JSONArray(handLandmarks.toTypedArray()))
        put("imageUri",       imageUri)
        put("label",          label)
    }

    companion object {
        fun fromJson(obj: JSONObject): GestureFeatureVector {
            val arr = obj.getJSONArray("handLandmarks")
            val hl  = FloatArray(arr.length()) { arr.getDouble(it).toFloat() }
            return GestureFeatureVector(
                mouthOpen      = obj.getDouble("mouthOpen").toFloat(),
                mouthCurve     = obj.getDouble("mouthCurve").toFloat(),
                browFurrow     = obj.getDouble("browFurrow").toFloat(),
                browRaise      = obj.getDouble("browRaise").toFloat(),
                smileProb      = obj.getDouble("smileProb").toFloat(),
                yaw            = obj.getDouble("yaw").toFloat(),
                pitch          = obj.getDouble("pitch").toFloat(),
                roll           = obj.getDouble("roll").toFloat(),
                leftWristRelY  = obj.getDouble("leftWristRelY").toFloat(),
                rightWristRelY = obj.getDouble("rightWristRelY").toFloat(),
                wristSpread    = obj.getDouble("wristSpread").toFloat(),
                handLandmarks  = hl,
                imageUri       = obj.getString("imageUri"),
                label          = obj.getString("label")
            )
        }
    }
}