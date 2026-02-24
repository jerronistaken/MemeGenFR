package com.example.facialrecog

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark
import android.graphics.Color as AndroidColor

// ══════════════════════════════════════════════════════════════════════
//  DEBUG VIEW  (unchanged from original)
// ══════════════════════════════════════════════════════════════════════

class DebugLandmarkView(context: android.content.Context) : View(context) {

    private var faces: List<Face> = emptyList()
    private var pose: Pose? = null
    private var handResult: HandLandmarkerResult? = null
    private var imageW = 0
    private var imageH = 0
    private var rotationDegrees = 0
    private var isFrontCamera = true

    private val faceBboxPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = AndroidColor.GREEN; isAntiAlias = true
    }
    private val faceContourPaint = Paint().apply {
        style = Paint.Style.FILL; color = AndroidColor.YELLOW; isAntiAlias = true
    }
    private val browPaint = Paint().apply {
        style = Paint.Style.FILL; color = AndroidColor.rgb(255, 165, 0); isAntiAlias = true
    }
    private val poseLinePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; color = AndroidColor.rgb(0, 255, 255); isAntiAlias = true
    }
    private val wristPaint = Paint().apply {
        style = Paint.Style.FILL; color = AndroidColor.MAGENTA; isAntiAlias = true
    }
    private val handPaint = Paint().apply {
        style = Paint.Style.FILL; color = AndroidColor.RED; isAntiAlias = true
    }
    private val handLinePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = AndroidColor.rgb(255, 100, 100); isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        style = Paint.Style.FILL; color = AndroidColor.WHITE; textSize = 40f; isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, AndroidColor.BLACK)
    }

    fun update(
        faces: List<Face>, pose: Pose?, handResult: HandLandmarkerResult?,
        imageW: Int, imageH: Int, rotationDegrees: Int, isFrontCamera: Boolean
    ) {
        this.faces = faces; this.pose = pose; this.handResult = handResult
        this.imageW = imageW; this.imageH = imageH
        this.rotationDegrees = rotationDegrees; this.isFrontCamera = isFrontCamera
        postInvalidate()
    }

    private fun mapImageToView(imgX: Float, imgY: Float): PointF {
        if (imageW == 0 || imageH == 0 || width == 0 || height == 0) return PointF(0f, 0f)
        val (uprightW, uprightH) = if (rotationDegrees == 90 || rotationDegrees == 270)
            Pair(imageH.toFloat(), imageW.toFloat()) else Pair(imageW.toFloat(), imageH.toFloat())
        val finalX = if (isFrontCamera) uprightW - imgX else imgX
        val scaleX = width.toFloat() / uprightW
        val scaleY = height.toFloat() / uprightH
        val scale  = maxOf(scaleX, scaleY)
        val offsetX = (width  - uprightW * scale) / 2f
        val offsetY = (height - uprightH * scale) / 2f
        return PointF(offsetX + finalX * scale, offsetY + imgY * scale)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageW <= 0 || imageH <= 0 || width <= 0 || height <= 0) return

        var yPos = 50f
        canvas.drawText("Image: ${imageW}x${imageH}", 20f, yPos, textPaint); yPos += 50f
        canvas.drawText("View: ${width}x${height}", 20f, yPos, textPaint); yPos += 50f
        canvas.drawText("Rotation: $rotationDegrees°", 20f, yPos, textPaint); yPos += 50f
        canvas.drawText("Front Cam: $isFrontCamera", 20f, yPos, textPaint); yPos += 50f
        val (rotW, rotH) = if (rotationDegrees == 90 || rotationDegrees == 270)
            Pair(imageH, imageW) else Pair(imageW, imageH)
        canvas.drawText("Rotated: ${rotW}x${rotH}", 20f, yPos, textPaint); yPos += 50f
        val scaleX = width.toFloat() / rotW
        val scaleY = height.toFloat() / rotH
        val scale  = maxOf(scaleX, scaleY)
        canvas.drawText("Scale: %.2f (x:%.2f y:%.2f)".format(scale, scaleX, scaleY), 20f, yPos, textPaint); yPos += 50f
        canvas.drawText("Faces: ${faces.size}", 20f, yPos, textPaint); yPos += 50f
        pose?.let { canvas.drawText("Pose: ${it.allPoseLandmarks.size} landmarks", 20f, yPos, textPaint); yPos += 50f }
        handResult?.let { canvas.drawText("Hands: ${it.landmarks().size}", 20f, yPos, textPaint) }

        pose?.let { p ->
            val landmarks = p.allPoseLandmarks.associateBy { it.landmarkType }
            fun getLandmarkPoint(type: Int): PointF? {
                val lm = landmarks[type] ?: return null
                return mapImageToView(lm.position.x, lm.position.y)
            }
            val connections = listOf(
                PoseLandmark.LEFT_SHOULDER  to PoseLandmark.LEFT_ELBOW,
                PoseLandmark.LEFT_ELBOW     to PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.RIGHT_ELBOW    to PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_SHOULDER  to PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP       to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_SHOULDER  to PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_HIP       to PoseLandmark.LEFT_KNEE,
                PoseLandmark.LEFT_KNEE      to PoseLandmark.LEFT_ANKLE,
                PoseLandmark.RIGHT_HIP      to PoseLandmark.RIGHT_KNEE,
                PoseLandmark.RIGHT_KNEE     to PoseLandmark.RIGHT_ANKLE
            )
            for ((start, end) in connections) {
                val p1 = getLandmarkPoint(start); val p2 = getLandmarkPoint(end)
                if (p1 != null && p2 != null) canvas.drawLine(p1.x, p1.y, p2.x, p2.y, poseLinePaint)
            }
            getLandmarkPoint(PoseLandmark.LEFT_WRIST)?.let  { canvas.drawCircle(it.x, it.y, 18f, wristPaint) }
            getLandmarkPoint(PoseLandmark.RIGHT_WRIST)?.let { canvas.drawCircle(it.x, it.y, 18f, wristPaint) }
        }

        for (face in faces) {
            val bbox = face.boundingBox
            val topLeft     = mapImageToView(bbox.left.toFloat(),  bbox.top.toFloat())
            val bottomRight = mapImageToView(bbox.right.toFloat(), bbox.bottom.toFloat())
            val left   = minOf(topLeft.x, bottomRight.x)
            val top    = minOf(topLeft.y, bottomRight.y)
            val right  = maxOf(topLeft.x, bottomRight.x)
            val bottom = maxOf(topLeft.y, bottomRight.y)
            canvas.drawRect(left, top, right, bottom, faceBboxPaint)

            for (contourType in listOf(
                FaceContour.FACE, FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
                FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM,
                FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
                FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM)) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 5f, faceContourPaint)
                }
            }
            for (contourType in listOf(
                FaceContour.LEFT_EYEBROW_TOP, FaceContour.LEFT_EYEBROW_BOTTOM,
                FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM)) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 8f, browPaint)
                }
            }
            val smile = face.smilingProbability
            if (smile != null && smile > 0.5f)
                canvas.drawText("😊 ${(smile * 100).toInt()}%", left, top - 10f, textPaint)
        }

        handResult?.let { result ->
            val hands = result.landmarks()
            for (handIdx in hands.indices) {
                val hand = hands[handIdx]
                val connections = listOf(
                    0 to 1, 1 to 2, 2 to 3, 3 to 4,
                    0 to 5, 5 to 6, 6 to 7, 7 to 8,
                    0 to 9, 9 to 10, 10 to 11, 11 to 12,
                    0 to 13, 13 to 14, 14 to 15, 15 to 16,
                    0 to 17, 17 to 18, 18 to 19, 19 to 20,
                    5 to 9, 9 to 13, 13 to 17
                )
                for ((start, end) in connections) {
                    if (start < hand.size && end < hand.size) {
                        canvas.drawLine(
                            hand[start].x() * width, hand[start].y() * height,
                            hand[end].x()   * width, hand[end].y()   * height,
                            handLinePaint
                        )
                    }
                }
                for (lm in hand) canvas.drawCircle(lm.x() * width, lm.y() * height, 10f, handPaint)
            }
        }
    }
}
