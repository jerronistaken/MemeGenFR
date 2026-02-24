package com.example.facialrecog

import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import kotlin.math.abs
import kotlin.math.sqrt

// ══════════════════════════════════════════════════════════════════════
//  POSE / FACE / HAND KEYWORD EXTRACTORS  (unchanged from original)
// ══════════════════════════════════════════════════════════════════════

object PoseKeywordExtractor {
    fun extractPoseKeywords(pose: Pose): List<String> {
        val lm = pose.allPoseLandmarks.associateBy { it.landmarkType }
        fun y(type: Int): Float? = lm[type]?.position?.y
        fun x(type: Int): Float? = lm[type]?.position?.x
        val keywords = mutableListOf<String>()

        val lwy  = y(PoseLandmark.LEFT_WRIST)
        val rwy  = y(PoseLandmark.RIGHT_WRIST)
        val lsy  = y(PoseLandmark.LEFT_SHOULDER)
        val rsy  = y(PoseLandmark.RIGHT_SHOULDER)

        val leftHandUp  = lwy != null && lsy != null && lwy < lsy
        val rightHandUp = rwy != null && rsy != null && rwy < rsy

        if (leftHandUp && rightHandUp) keywords += "hands_up"
        else if (leftHandUp)           keywords += "left_hand_raised"
        else if (rightHandUp)          keywords += "right_hand_raised"

        val lwx = x(PoseLandmark.LEFT_WRIST)
        val rwx = x(PoseLandmark.RIGHT_WRIST)
        val lsx = x(PoseLandmark.LEFT_SHOULDER)
        val rsx = x(PoseLandmark.RIGHT_SHOULDER)

        if (lwx != null && rwx != null && lsx != null && rsx != null) {
            val sw = abs(rsx - lsx)
            val ww = abs(rwx - lwx)
            if (sw > 0 && ww / sw > 1.6f) keywords += "arms_outstretched"
        }

        if (lsy != null && rsy != null) {
            val diff = rsy - lsy
            if (abs(diff) > 35f) keywords += if (diff > 0) "lean_left" else "lean_right"
        }

        return keywords
    }
}

object FaceBaseline {
    private val mouthOpenSamples  = mutableListOf<Float>()
    private val mouthCurveSamples = mutableListOf<Float>()
    private val browRaiseSamples  = mutableListOf<Float>()
    private val browFurrowSamples = mutableListOf<Float>()

    var neutralMouthOpen:  Float? = null; private set
    var neutralMouthCurve: Float? = null; private set
    var neutralBrowRaise:  Float? = null; private set
    var neutralBrowFurrow: Float? = null; private set

    var threshSurprisedMouthOpen: Float = 50f;  private set
    var threshAngryFurrow:        Float = -9f;  private set
    var threshSadFurrow:          Float = -12f; private set

    val isCalibrated: Boolean get() = neutralMouthOpen != null
    val sampleCount:  Int     get() = mouthOpenSamples.size
    val targetSamples = 60

    fun addSample(mouthOpen: Float?, mouthCurve: Float?, browRaise: Float?, browFurrow: Float?) {
        if (isCalibrated) return
        mouthOpen?.let  { mouthOpenSamples.add(it) }
        mouthCurve?.let { mouthCurveSamples.add(it) }
        browRaise?.let  { browRaiseSamples.add(it) }
        browFurrow?.let { browFurrowSamples.add(it) }
        if (mouthOpenSamples.size >= targetSamples) computeBaseline()
    }

    private fun computeBaseline() {
        neutralMouthOpen  = mouthOpenSamples.median()
        neutralMouthCurve = mouthCurveSamples.median()
        neutralBrowRaise  = browRaiseSamples.median()
        neutralBrowFurrow = browFurrowSamples.median()

        val nMouthOpen  = neutralMouthOpen!!
        val nBrowFurrow = neutralBrowFurrow!!

        threshSurprisedMouthOpen = nMouthOpen + 25f
        threshAngryFurrow        = nBrowFurrow + 3.5f
        threshSadFurrow          = nBrowFurrow - 3f

        android.util.Log.d("FaceBaseline", "=== BASELINE COMPUTED ===\n" +
                "neutralMouthOpen=$nMouthOpen surpriseThresh=$threshSurprisedMouthOpen\n" +
                "neutralBrowFurrow=$nBrowFurrow angryThresh=$threshAngryFurrow sadThresh=$threshSadFurrow")
    }

    fun reset() {
        mouthOpenSamples.clear(); mouthCurveSamples.clear()
        browRaiseSamples.clear(); browFurrowSamples.clear()
        neutralMouthOpen = null; neutralMouthCurve = null
        neutralBrowRaise = null; neutralBrowFurrow = null
    }

    private fun List<Float>.median(): Float {
        val sorted = sorted()
        return if (sorted.size % 2 == 0)
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
        else sorted[sorted.size / 2]
    }
}

object FaceKeywordExtractor {
    fun extractFaceKeywords(faces: List<Face>): List<String> {
        if (faces.isEmpty()) return emptyList()
        val f        = faces[0]
        val keywords = mutableListOf<String>()

        val smile    = f.smilingProbability ?: 0f
        val yaw      = f.headEulerAngleY
        val pitch    = f.headEulerAngleX
        val roll     = f.headEulerAngleZ

        if (yaw > 20f)         keywords += "looking_left"
        else if (yaw < -20f)   keywords += "looking_right"
        if (pitch > 15f)       keywords += "looking_up"
        else if (pitch < -15f) keywords += "looking_down"
        if (roll > 15f)        keywords += "head_tilt_right"
        else if (roll < -15f)  keywords += "head_tilt_left"

        val mouthPoints = f.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points
        val upperLip    = f.getContour(FaceContour.UPPER_LIP_TOP)?.points
        val faceContour = f.getContour(FaceContour.FACE)?.points
        val leftBrow    = f.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points
        val rightBrow   = f.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points

        val mouthOpen = if (mouthPoints != null && upperLip != null &&
            mouthPoints.isNotEmpty() && upperLip.isNotEmpty())
            mouthPoints[mouthPoints.size / 2].y - upperLip[upperLip.size / 2].y
        else null

        val mouthCurve = if (mouthPoints != null && mouthPoints.size >= 3) {
            val lc = mouthPoints.first().y; val rc = mouthPoints.last().y
            val cy = mouthPoints[mouthPoints.size / 2].y
            ((lc + rc) / 2f) - cy
        } else null

        val browRaise = if (leftBrow != null && rightBrow != null &&
            faceContour != null && faceContour.size >= 10) {
            val faceTopY   = faceContour.minOf { it.y }
            val faceHeight = faceContour.maxOf { it.y } - faceTopY
            val avgBrowY   = (leftBrow.minOf { it.y } + rightBrow.minOf { it.y }) / 2f
            (avgBrowY - faceTopY) / faceHeight
        } else null

        val browFurrow = if (leftBrow != null && rightBrow != null &&
            leftBrow.isNotEmpty() && rightBrow.isNotEmpty()) {
            val liY = leftBrow.maxByOrNull  { it.x }?.y ?: 0f
            val loY = leftBrow.minByOrNull  { it.x }?.y ?: 0f
            val riY = rightBrow.minByOrNull { it.x }?.y ?: 0f
            val roY = rightBrow.maxByOrNull { it.x }?.y ?: 0f
            ((liY - loY) + (riY - roY)) / 2f
        } else null

        FaceBaseline.addSample(mouthOpen, mouthCurve, browRaise, browFurrow)

        if (!FaceBaseline.isCalibrated) {
            keywords += "calibrating"
            return keywords
        }

        val surpriseMouthThresh = FaceBaseline.threshSurprisedMouthOpen
        val angryFurrowThresh   = FaceBaseline.threshAngryFurrow
        val sadFurrowThresh     = FaceBaseline.threshSadFurrow
        val neutralMouthOpen    = FaceBaseline.neutralMouthOpen!!
        val neutralBrowFurrow   = FaceBaseline.neutralBrowFurrow!!

        val isSurprised = (mouthOpen != null && mouthOpen > surpriseMouthThresh) && smile < 0.05f
        if (isSurprised) keywords += "surprised"

        val isSmiling = smile > 0.7f
        if (isSmiling && !isSurprised) keywords += "smiling"

        val isAngry = (browFurrow != null && browFurrow > angryFurrowThresh) &&
                smile < 0.05f &&
                (mouthOpen != null && mouthOpen < neutralMouthOpen + 10f) && !isSurprised
        if (isAngry) keywords += "angry"

        val isSad = (browFurrow != null && browFurrow < sadFurrowThresh) &&
                smile < 0.05f && !isSurprised
        if (isSad) keywords += "sad"

        val isDisgusted = (smile > 0.015f && smile < 0.1f) &&
                (mouthOpen != null && mouthOpen < neutralMouthOpen + 5f) &&
                !isSurprised && !isSmiling
        if (isDisgusted) keywords += "disgusted"

        if (keywords.none { it in listOf("smiling", "sad", "angry", "surprised", "disgusted", "fearful") }) {
            keywords += "neutral"
        }

        return keywords
    }
}

object HandKeywordExtractor {

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

    fun extractHandKeywords(result: HandLandmarkerResult?, isFrontCameraMirrored: Boolean): List<String> {
        if (result == null) return emptyList()
        val hands = result.landmarks()
        if (hands.isEmpty()) return emptyList()

        val keywords = mutableListOf<String>()
        val hand = hands[0]
        if (hand.size < 21) return keywords

        fun x(i: Int) = hand[i].x()
        fun y(i: Int) = hand[i].y()

        fun isExtended(tip: Int, pip: Int, mcp: Int): Boolean {
            val wd = { i: Int -> distance(x(i), y(i), x(WRIST), y(WRIST)) }
            return wd(tip) > wd(pip) && wd(pip) > wd(mcp) * 0.85f
        }

        val indexExt  = isExtended(INDEX_TIP,  INDEX_PIP,  INDEX_MCP)
        val middleExt = isExtended(MIDDLE_TIP, MIDDLE_PIP, MIDDLE_MCP)
        val ringExt   = isExtended(RING_TIP,   RING_PIP,   RING_MCP)
        val pinkyExt  = isExtended(PINKY_TIP,  PINKY_PIP,  PINKY_MCP)
        val thumbExt  = distance(x(THUMB_TIP), y(THUMB_TIP), x(INDEX_MCP), y(INDEX_MCP)) >
                distance(x(THUMB_MCP), y(THUMB_MCP), x(INDEX_MCP), y(INDEX_MCP)) * 0.9f

        val extendedCount = listOf(indexExt, middleExt, ringExt, pinkyExt).count { it }

        if (hands.size >= 2) {
            val bothOpen = hands.count { h ->
                if (h.size < 21) return@count false
                fun hwd(i: Int) = distance(h[i].x(), h[i].y(), h[WRIST].x(), h[WRIST].y())
                listOf(INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
                    .zip(listOf(INDEX_PIP, MIDDLE_PIP, RING_PIP, PINKY_PIP))
                    .zip(listOf(INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP))
                    .all { (tipPip, mcp) ->
                        hwd(tipPip.first) > hwd(tipPip.second) &&
                                hwd(tipPip.second) > hwd(mcp) * 0.85f
                    }
            }
            if (bothOpen >= 2) keywords += "both_hands_up"
        }

        if (extendedCount == 4) keywords += "four_fingers"
        if (extendedCount == 0 && !thumbExt) keywords += "fist"
        if (thumbExt && extendedCount == 0) keywords += "thumbs_up"
        if (thumbExt && extendedCount == 0 && y(THUMB_TIP) > y(WRIST)) keywords += "thumbs_down"

        if (indexExt && !middleExt && !ringExt && !pinkyExt) {
            keywords += "pointing"
            val dx = x(INDEX_TIP) - x(INDEX_MCP)
            val dy = y(INDEX_TIP) - y(INDEX_MCP)
            val dir = if (abs(dx) > abs(dy)) { if (dx > 0) "right" else "left" }
            else                   { if (dy > 0) "down"  else "up"   }
            val correctedDir = when (dir) {
                "left"  -> if (isFrontCameraMirrored) "right" else "left"
                "right" -> if (isFrontCameraMirrored) "left"  else "right"
                else    -> dir
            }
            keywords += "point_$correctedDir"
        }

        if (indexExt && middleExt && !ringExt && !pinkyExt) keywords += "peace_sign"
        if (pinkyExt && !indexExt && !middleExt && !ringExt) keywords += "pinky_up"

        val thumbIndexDist = distance(x(THUMB_TIP), y(THUMB_TIP), x(INDEX_TIP), y(INDEX_TIP))
        val handSize       = distance(x(WRIST), y(WRIST), x(MIDDLE_MCP), y(MIDDLE_MCP))
        if (thumbIndexDist < handSize * 0.35f && middleExt && ringExt && pinkyExt) keywords += "ok_sign"

        if (hands.size >= 2) {
            var gunCount = 0
            for (h in hands) {
                if (h.size < 21) continue
                fun hx(i: Int) = h[i].x()
                fun hy(i: Int) = h[i].y()
                fun hd(a: Int, b: Int) = distance(hx(a), hy(a), hx(b), hy(b))
                fun hwd(i: Int) = distance(hx(i), hy(i), hx(WRIST), hy(WRIST))
                val hIndex  = hwd(INDEX_TIP)  > hwd(INDEX_PIP)  && hwd(INDEX_PIP)  > hwd(INDEX_MCP)  * 0.85f
                val hMiddle = hwd(MIDDLE_TIP) > hwd(MIDDLE_PIP) && hwd(MIDDLE_PIP) > hwd(MIDDLE_MCP) * 0.85f
                val hRing   = hwd(RING_TIP)   > hwd(RING_PIP)   && hwd(RING_PIP)   > hwd(RING_MCP)   * 0.85f
                val hPinky  = hwd(PINKY_TIP)  > hwd(PINKY_PIP)  && hwd(PINKY_PIP)  > hwd(PINKY_MCP)  * 0.85f
                val hThumb  = hd(THUMB_TIP, INDEX_MCP) > hd(THUMB_MCP, INDEX_MCP) * 0.9f
                if (hIndex && hThumb && !hMiddle && !hRing && !hPinky && hy(INDEX_TIP) < hy(INDEX_MCP))
                    gunCount++
            }
            if (gunCount >= 2) keywords += "double_gun"
        }

        return keywords.distinct()
    }

    private fun distance(ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = ax - bx; val dy = ay - by
        return sqrt(dx * dx + dy * dy)
    }
}