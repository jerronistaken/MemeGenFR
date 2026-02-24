package com.example.facialrecog

import android.content.Context
import android.graphics.*
import android.view.View
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseLandmark

/**
 * A custom [View] that draws real-time debug overlays on top of the camera preview:
 *   - Face bounding boxes and contour points (yellow), with brows highlighted in orange
 *   - Body pose skeleton (cyan lines) with wrist highlights (magenta)
 *   - Hand landmark dots (red) and bone connections
 *   - On-screen metadata text (image size, rotation, landmark counts)
 *
 * Visibility is toggled by the Debug ON/OFF button in [PoseExpressionApp].
 *
 * Owner: Person D
 */
class DebugLandmarkView(context: Context) : View(context) {

    private var faces: List<Face> = emptyList()
    private var pose: Pose? = null
    private var handResult: HandLandmarkerResult? = null

    private var imageW = 0
    private var imageH = 0
    private var rotationDegrees = 0
    private var isFrontCamera = true

    // ── Paint styles ──────────────────────────────────────────────────────────

    private val faceBboxPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 6f
        color = Color.GREEN; isAntiAlias = true
    }

    private val faceContourPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.YELLOW; isAntiAlias = true
    }

    private val browPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 165, 0) // orange
        isAntiAlias = true
    }

    private val poseLinePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f
        color = Color.rgb(0, 255, 255); isAntiAlias = true
    }

    private val wristPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.MAGENTA; isAntiAlias = true
    }

    private val handPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.RED; isAntiAlias = true
    }

    private val handLinePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f
        color = Color.rgb(255, 100, 100); isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.WHITE
        textSize = 40f; isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun update(
        faces: List<Face>,
        pose: Pose?,
        handResult: HandLandmarkerResult?,
        imageW: Int,
        imageH: Int,
        rotationDegrees: Int,
        isFrontCamera: Boolean
    ) {
        this.faces = faces
        this.pose = pose
        this.handResult = handResult
        this.imageW = imageW
        this.imageH = imageH
        this.rotationDegrees = rotationDegrees
        this.isFrontCamera = isFrontCamera
        postInvalidate()
    }

    // ── Coordinate mapping ────────────────────────────────────────────────────

    /**
     * Maps a point from ML Kit's upright image coordinate space to this View's
     * screen coordinate space, accounting for rotation, front-camera mirroring,
     * and CameraX FILL_CENTER scaling.
     */
    private fun mapImageToView(imgX: Float, imgY: Float): PointF {
        if (imageW == 0 || imageH == 0 || width == 0 || height == 0) return PointF(0f, 0f)

        val (uprightW, uprightH) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            Pair(imageH.toFloat(), imageW.toFloat())
        } else {
            Pair(imageW.toFloat(), imageH.toFloat())
        }

        val finalX = if (isFrontCamera) uprightW - imgX else imgX

        val scaleX = width.toFloat() / uprightW
        val scaleY = height.toFloat() / uprightH
        val scale  = maxOf(scaleX, scaleY)

        val offsetX = (width  - uprightW * scale) / 2f
        val offsetY = (height - uprightH * scale) / 2f

        return PointF(offsetX + finalX * scale, offsetY + imgY * scale)
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageW <= 0 || imageH <= 0 || width <= 0 || height <= 0) return

        drawDebugText(canvas)
        drawPoseSkeleton(canvas)
        drawFaceOverlays(canvas)
        drawHandOverlays(canvas)
    }

    private fun drawDebugText(canvas: Canvas) {
        val (rotW, rotH) = if (rotationDegrees == 90 || rotationDegrees == 270)
            Pair(imageH, imageW) else Pair(imageW, imageH)

        val scaleX = width.toFloat() / rotW
        val scaleY = height.toFloat() / rotH
        val scale  = maxOf(scaleX, scaleY)

        var y = 50f
        fun line(text: String) { canvas.drawText(text, 20f, y, textPaint); y += 50f }

        line("Image: ${imageW}x${imageH}")
        line("View: ${width}x${height}")
        line("Rotation: $rotationDegrees°")
        line("Front Cam: $isFrontCamera")
        line("Rotated: ${rotW}x${rotH}")
        line("Scale: %.2f (x:%.2f y:%.2f)".format(scale, scaleX, scaleY))
        line("Faces: ${faces.size}")
        pose?.let { line("Pose: ${it.allPoseLandmarks.size} landmarks") }
        handResult?.let { line("Hands: ${it.landmarks().size}") }
    }

    private fun drawPoseSkeleton(canvas: Canvas) {
        val p = pose ?: return
        val landmarks = p.allPoseLandmarks.associateBy { it.landmarkType }

        fun pt(type: Int): PointF? {
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
            val p1 = pt(start); val p2 = pt(end)
            if (p1 != null && p2 != null) canvas.drawLine(p1.x, p1.y, p2.x, p2.y, poseLinePaint)
        }

        pt(PoseLandmark.LEFT_WRIST)?.let  { canvas.drawCircle(it.x, it.y, 18f, wristPaint) }
        pt(PoseLandmark.RIGHT_WRIST)?.let { canvas.drawCircle(it.x, it.y, 18f, wristPaint) }
    }

    private fun drawFaceOverlays(canvas: Canvas) {
        for (face in faces) {
            val bbox = face.boundingBox
            val tl = mapImageToView(bbox.left.toFloat(),  bbox.top.toFloat())
            val br = mapImageToView(bbox.right.toFloat(), bbox.bottom.toFloat())

            canvas.drawRect(
                minOf(tl.x, br.x), minOf(tl.y, br.y),
                maxOf(tl.x, br.x), maxOf(tl.y, br.y),
                faceBboxPaint
            )

            // General contours in yellow
            val generalContours = listOf(
                FaceContour.FACE, FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
                FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM,
                FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
                FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM
            )
            for (contourType in generalContours) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 5f, faceContourPaint)
                }
            }

            // Brows in orange for visibility
            val browContours = listOf(
                FaceContour.LEFT_EYEBROW_TOP,  FaceContour.LEFT_EYEBROW_BOTTOM,
                FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM
            )
            for (contourType in browContours) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 8f, browPaint)
                }
            }

            // Smile label
            val smile = face.smilingProbability
            if (smile != null && smile > 0.5f) {
                val left = minOf(tl.x, br.x)
                val top  = minOf(tl.y, br.y)
                canvas.drawText("😊 ${(smile * 100).toInt()}%", left, top - 10f, textPaint)
            }
        }
    }

    private fun drawHandOverlays(canvas: Canvas) {
        val result = handResult ?: return
        for (hand in result.landmarks()) {
            val connections = listOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 4,           // Thumb
                0 to 5, 5 to 6, 6 to 7, 7 to 8,            // Index
                0 to 9, 9 to 10, 10 to 11, 11 to 12,       // Middle
                0 to 13, 13 to 14, 14 to 15, 15 to 16,     // Ring
                0 to 17, 17 to 18, 18 to 19, 19 to 20,     // Pinky
                5 to 9, 9 to 13, 13 to 17                   // Palm
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

            for (lm in hand) {
                canvas.drawCircle(lm.x() * width, lm.y() * height, 10f, handPaint)
            }
        }
    }
}
