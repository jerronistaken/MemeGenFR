package com.example.facialrecog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.facialrecog.ui.theme.FacialRecogTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.Pose
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.compose.ui.graphics.asImageBitmap
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FacialRecogTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    PoseExpressionApp()
                }
            }
        }
    }
}

object OverlayAssetResolver {

    private val SINGLE_KEYWORD_TO_DRAWABLE = mapOf(
        "point_right"   to R.drawable.ooo,
        "peace_sign"    to R.drawable.peace,
        "both_hands_up" to R.drawable.absolutecinema,
        "pinky_up"      to R.drawable.pinky,
        "four_fingers"  to R.drawable.four,
        "double_gun"    to R.drawable.crashout1,
        "point_up"      to R.drawable.shush,
        "fist"          to R.drawable.fist
    )

    private val COMPOUND_CONDITIONS: List<Pair<Set<String>, Int>> = listOf(
        setOf("left_hand_raised", "looking_left") to R.drawable.giveup,
    )

    fun resolve(keywords: List<String>): Int? {
        val keywordSet = keywords.toHashSet()
        for ((requiredSet, drawable) in COMPOUND_CONDITIONS) {
            if (keywordSet.containsAll(requiredSet)) return drawable
        }
        for ((keyword, drawable) in SINGLE_KEYWORD_TO_DRAWABLE) {
            if (keyword in keywordSet) return drawable
        }
        return null
    }

    fun loadBitmap(context: android.content.Context, drawableRes: Int): Bitmap? =
        try { BitmapFactory.decodeResource(context.resources, drawableRes) }
        catch (_: Exception) { null }
}

@Composable
fun PoseExpressionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showDebug by remember { mutableStateOf(false) }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            ActivityCompat.requestPermissions(
                (context as ComponentActivity),
                arrayOf(Manifest.permission.CAMERA),
                1001
            )
        }
    }

    DisposableEffect(Unit) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ ->
            hasCameraPermission =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var keywords by remember { mutableStateOf(listOf("no_detection_yet")) }
    var lastDebug by remember { mutableStateOf("Waiting for frames...") }

    val resolvedRes = remember(keywords) { OverlayAssetResolver.resolve(keywords) }
    val overlayBitmap = remember(resolvedRes) {
        resolvedRes?.let {
            try { BitmapFactory.decodeResource((context as android.app.Activity).resources, it) }
            catch (_: Exception) { null }
        }
    }
    var saveSuccess by remember(resolvedRes) { mutableStateOf<Boolean?>(null) }

    val activeKeywords = if (!FaceBaseline.isCalibrated) {
        "Calibrating... ${FaceBaseline.sampleCount}/${FaceBaseline.targetSamples}"
    } else {
        keywords
            .filter { it != "hand_detected" && it != "neutral" && it != "no_detection_yet"
                    && it != "no_pose_face_hand" && it != "calibrating" }
            .joinToString(", ")
            .ifEmpty { "none" }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
    ) {

        // ── TOP BAR ─────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "MemeGenFR",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { showDebug = !showDebug }) {
                Text(if (showDebug) "Debug: ON" else "Debug: OFF")
            }
            TextButton(onClick = { FaceBaseline.reset() }) {
                Text("Recalibrate")
            }
        }

        if (!hasCameraPermission) {
            Text("Camera permission is required.")
            return@Column
        }

        // ── CAMERA PREVIEW ───────────────────────────────────────
        CameraPreview(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            showDebug = showDebug,
            onKeywords = { k, dbg ->
                keywords = k
                lastDebug = dbg
            }
        )

        Spacer(Modifier.height(10.dp))

        // ── DETECTED KEYWORDS — fixed 2-line height so it never jumps ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Column {
                Text(
                    text = "Active: $activeKeywords",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                if (showDebug) {
                    Text(
                        text = lastDebug,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── OVERLAY IMAGE — fixed slot, shows placeholder when empty ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            if (overlayBitmap != null) {
                androidx.compose.foundation.Image(
                    bitmap = overlayBitmap.asImageBitmap(),
                    contentDescription = "Pose overlay",
                    modifier = Modifier.size(130.dp)
                )
            } else {
                Text(
                    text = "No gesture matched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── SAVE BUTTON — fixed slot, greyed out when no overlay ──
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            contentAlignment = Alignment.Center
        ) {
            Button(
                onClick = {
                    if (overlayBitmap != null) {
                        val saved = saveImageToGallery(context, overlayBitmap)
                        saveSuccess = saved
                        Toast.makeText(
                            context,
                            if (saved) "Saved to Pictures/MemeGenFR!" else "Save failed",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                enabled = overlayBitmap != null,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("⬇ Save Image")
            }
        }

        // ── SAVE STATUS — fixed slot so nothing shifts ───────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            saveSuccess?.let { success ->
                Text(
                    text = if (success) "✓ Saved!" else "✗ Failed to save",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) androidx.compose.ui.graphics.Color(0xFF4CAF50)
                    else androidx.compose.ui.graphics.Color(0xFFF44336)
                )
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    showDebug: Boolean,
    onKeywords: (List<String>, String) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val onKeywordsState by rememberUpdatedState(onKeywords)
    val showDebugState by rememberUpdatedState(showDebug)

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val poseDetector = remember {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    val handLandmarker = remember {
        try {
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("hand_landmarker.task")
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .build()
            HandLandmarker.createFromOptions(context, options)
        } catch (_: Exception) { null }
    }

    val previewViewRef = remember { mutableStateOf<PreviewView?>(null) }
    val debugViewRef = remember { mutableStateOf<DebugLandmarkView?>(null) }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val container = FrameLayout(ctx)

            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
            previewViewRef.value = previewView

            val debugView = DebugLandmarkView(ctx).apply {
                visibility = if (showDebug) View.VISIBLE else View.GONE
            }
            debugViewRef.value = debugView

            container.addView(previewView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            container.addView(debugView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                cameraProviderRef.value = cameraProvider

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                val isFrontCamera = true
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                    val handResultForGesture: HandLandmarkerResult? = try {
                        if (handLandmarker != null) {
                            val bmp = imageProxyToUprightBitmap(
                                imageProxy = imageProxy,
                                rotationDegrees = rotation,
                                mirrorX = isFrontCamera
                            )
                            val mpImage: MPImage = BitmapImageBuilder(bmp).build()
                            val r = handLandmarker.detect(mpImage)
                            bmp.recycle()
                            r
                        } else null
                    } catch (_: Exception) { null }

                    val handKeywords = HandKeywordExtractor.extractHandKeywords(
                        result = handResultForGesture,
                        isFrontCameraMirrored = isFrontCamera
                    )

                    poseDetector.process(inputImage)
                        .addOnSuccessListener { pose ->
                            val poseKeywords = PoseKeywordExtractor.extractPoseKeywords(pose)

                            faceDetector.process(inputImage)
                                .addOnSuccessListener { faces ->
                                    val faceKeywords = FaceKeywordExtractor.extractFaceKeywords(faces)

                                    val merged = (poseKeywords + faceKeywords + handKeywords)
                                        .distinct()
                                        .ifEmpty { listOf("no_pose_face_hand") }

                                    val dbg = buildString {
                                        append("pose=${pose.allPoseLandmarks.size}, faces=${faces.size}")
                                        if (handLandmarker == null) append(", hands=MODEL_MISSING")
                                        else append(", hands=${handResultForGesture?.handedness()?.size ?: 0}")
                                        if (handKeywords.isNotEmpty()) append(", handKW=${handKeywords.joinToString("|")}")
                                    }

                                    onKeywordsState(merged, dbg)

                                    if (showDebugState) {
                                        debugView.post {
                                            debugView.visibility = View.VISIBLE
                                            debugView.update(
                                                faces = faces,
                                                pose = pose,
                                                handResult = handResultForGesture,
                                                imageW = imageProxy.width,
                                                imageH = imageProxy.height,
                                                rotationDegrees = rotation,
                                                isFrontCamera = isFrontCamera
                                            )
                                        }
                                    }
                                }
                                .addOnFailureListener {
                                    val merged = (poseKeywords + handKeywords).distinct()
                                        .ifEmpty { listOf("pose_and_hand_only") }
                                    onKeywordsState(merged, "Face failed: ${it.javaClass.simpleName}")
                                }
                                .addOnCompleteListener { imageProxy.close() }
                        }
                        .addOnFailureListener {
                            val merged = handKeywords.ifEmpty { listOf("pose_failed") }
                            onKeywordsState(merged, "Pose failed: ${it.javaClass.simpleName}")
                            imageProxy.close()
                        }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
                } catch (e: Exception) {
                    onKeywordsState(listOf("camera_bind_failed"), "Camera bind failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))

            container
        },
        update = { _ ->
            debugViewRef.value?.visibility = if (showDebug) View.VISIBLE else View.GONE
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            try { cameraProviderRef.value?.unbindAll() } catch (_: Exception) {}
            try { cameraExecutor.shutdown() } catch (_: Exception) {}
        }
    }
}

class DebugLandmarkView(context: android.content.Context) : View(context) {

    private var faces: List<Face> = emptyList()
    private var pose: Pose? = null
    private var handResult: HandLandmarkerResult? = null
    private var imageW = 0
    private var imageH = 0
    private var rotationDegrees = 0
    private var isFrontCamera = true

    private val faceBboxPaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 6f; color = Color.GREEN; isAntiAlias = true
    }
    private val faceContourPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.YELLOW; isAntiAlias = true
    }
    private val browPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.rgb(255, 165, 0); isAntiAlias = true
    }
    private val poseLinePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 8f; color = Color.rgb(0, 255, 255); isAntiAlias = true
    }
    private val wristPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.MAGENTA; isAntiAlias = true
    }
    private val handPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.RED; isAntiAlias = true
    }
    private val handLinePaint = Paint().apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = Color.rgb(255, 100, 100); isAntiAlias = true
    }
    private val textPaint = Paint().apply {
        style = Paint.Style.FILL; color = Color.WHITE; textSize = 40f; isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
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
        val scale = maxOf(scaleX, scaleY)
        val offsetX = (width - uprightW * scale) / 2f
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
        val scale = maxOf(scaleX, scaleY)
        canvas.drawText("Scale: %.2f (x:%.2f y:%.2f)".format(scale, scaleX, scaleY), 20f, yPos, textPaint); yPos += 50f
        canvas.drawText("Faces: ${faces.size}", 20f, yPos, textPaint); yPos += 50f
        pose?.let { canvas.drawText("Pose: ${it.allPoseLandmarks.size} landmarks", 20f, yPos, textPaint); yPos += 50f }
        handResult?.let { canvas.drawText("Hands: ${it.landmarks().size}", 20f, yPos, textPaint) }

        // Draw pose skeleton
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

        // Draw face contours
        for (face in faces) {
            val bbox = face.boundingBox
            val topLeft     = mapImageToView(bbox.left.toFloat(),  bbox.top.toFloat())
            val bottomRight = mapImageToView(bbox.right.toFloat(), bbox.bottom.toFloat())
            val left   = minOf(topLeft.x, bottomRight.x)
            val top    = minOf(topLeft.y, bottomRight.y)
            val right  = maxOf(topLeft.x, bottomRight.x)
            val bottom = maxOf(topLeft.y, bottomRight.y)
            canvas.drawRect(left, top, right, bottom, faceBboxPaint)

            val generalContours = listOf(
                FaceContour.FACE, FaceContour.LEFT_EYE, FaceContour.RIGHT_EYE,
                FaceContour.NOSE_BRIDGE, FaceContour.NOSE_BOTTOM,
                FaceContour.UPPER_LIP_TOP, FaceContour.UPPER_LIP_BOTTOM,
                FaceContour.LOWER_LIP_TOP, FaceContour.LOWER_LIP_BOTTOM,
            )
            for (contourType in generalContours) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 5f, faceContourPaint)
                }
            }

            val browContours = listOf(
                FaceContour.LEFT_EYEBROW_TOP,  FaceContour.LEFT_EYEBROW_BOTTOM,
                FaceContour.RIGHT_EYEBROW_TOP, FaceContour.RIGHT_EYEBROW_BOTTOM,
            )
            for (contourType in browContours) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 8f, browPaint)
                }
            }

            val smile = face.smilingProbability
            if (smile != null && smile > 0.5f) {
                canvas.drawText("😊 ${(smile * 100).toInt()}%", left, top - 10f, textPaint)
            }
        }

        // Draw hand landmarks
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
                for (lm in hand) {
                    canvas.drawCircle(lm.x() * width, lm.y() * height, 10f, handPaint)
                }
            }
        }
    }
}

object PoseKeywordExtractor {
    fun extractPoseKeywords(pose: Pose): List<String> {
        val lm = pose.allPoseLandmarks.associateBy { it.landmarkType }
        fun y(type: Int): Float? = lm[type]?.position?.y
        fun x(type: Int): Float? = lm[type]?.position?.x
        val keywords = mutableListOf<String>()

        val leftWristY     = y(PoseLandmark.LEFT_WRIST)
        val rightWristY    = y(PoseLandmark.RIGHT_WRIST)
        val leftShoulderY  = y(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderY = y(PoseLandmark.RIGHT_SHOULDER)

        val leftHandUp  = leftWristY  != null && leftShoulderY  != null && leftWristY  < leftShoulderY
        val rightHandUp = rightWristY != null && rightShoulderY != null && rightWristY < rightShoulderY

        if (leftHandUp && rightHandUp) keywords += "hands_up"
        else if (leftHandUp)           keywords += "left_hand_raised"
        else if (rightHandUp)          keywords += "right_hand_raised"

        val leftWristX     = x(PoseLandmark.LEFT_WRIST)
        val rightWristX    = x(PoseLandmark.RIGHT_WRIST)
        val leftShoulderX  = x(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderX = x(PoseLandmark.RIGHT_SHOULDER)

        if (leftWristX != null && rightWristX != null && leftShoulderX != null && rightShoulderX != null) {
            val shoulderWidth = abs(rightShoulderX - leftShoulderX)
            val wristWidth    = abs(rightWristX - leftWristX)
            if (shoulderWidth > 0 && wristWidth / shoulderWidth > 1.6f) keywords += "arms_outstretched"
        }

        if (leftShoulderY != null && rightShoulderY != null) {
            val diff = rightShoulderY - leftShoulderY
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
    val targetSamples = 60  // ~3 seconds at ~20fps

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

        android.util.Log.d("FaceBaseline", buildString {
            appendLine("=== BASELINE COMPUTED ===")
            appendLine("neutralMouthOpen=%.2f  → surpriseThresh=%.2f".format(nMouthOpen, threshSurprisedMouthOpen))
            appendLine("neutralMouthCurve=%.2f".format(neutralMouthCurve!!))
            appendLine("neutralBrowRaise=%.4f".format(neutralBrowRaise!!))
            appendLine("neutralBrowFurrow=%.2f  → angryThresh=%.2f  sadThresh=%.2f"
                .format(nBrowFurrow, threshAngryFurrow, threshSadFurrow))
        })
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
        val f = faces[0]
        val keywords = mutableListOf<String>()

        val smile    = f.smilingProbability ?: 0f
        val leftEye  = f.leftEyeOpenProbability ?: 1f
        val rightEye = f.rightEyeOpenProbability ?: 1f
        val yaw      = f.headEulerAngleY
        val pitch    = f.headEulerAngleX
        val roll     = f.headEulerAngleZ

        // --- Head orientation ---
        if (yaw > 20f)         keywords += "looking_left"
        else if (yaw < -20f)   keywords += "looking_right"
        if (pitch > 15f)       keywords += "looking_up"
        else if (pitch < -15f) keywords += "looking_down"
        if (roll > 15f)        keywords += "head_tilt_right"
        else if (roll < -15f)  keywords += "head_tilt_left"

        // --- Geometry from contours ---
        val mouthPoints = f.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points
        val upperLip    = f.getContour(FaceContour.UPPER_LIP_TOP)?.points
        val faceContour = f.getContour(FaceContour.FACE)?.points
        val leftBrow    = f.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points
        val rightBrow   = f.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points
        val leftEyePts  = f.getContour(FaceContour.LEFT_EYE)?.points

        val mouthOpen = if (mouthPoints != null && upperLip != null &&
            mouthPoints.isNotEmpty() && upperLip.isNotEmpty()) {
            mouthPoints[mouthPoints.size / 2].y - upperLip[upperLip.size / 2].y
        } else null

        val mouthCurve = if (mouthPoints != null && mouthPoints.size >= 3) {
            val leftCornerY  = mouthPoints.first().y
            val rightCornerY = mouthPoints.last().y
            val centerY      = mouthPoints[mouthPoints.size / 2].y
            ((leftCornerY + rightCornerY) / 2f) - centerY
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
            val leftInnerY  = leftBrow.maxByOrNull  { it.x }?.y ?: 0f
            val leftOuterY  = leftBrow.minByOrNull  { it.x }?.y ?: 0f
            val rightInnerY = rightBrow.minByOrNull { it.x }?.y ?: 0f
            val rightOuterY = rightBrow.maxByOrNull { it.x }?.y ?: 0f
            ((leftInnerY - leftOuterY) + (rightInnerY - rightOuterY)) / 2f
        } else null

        val eyeOpenRatio = if (leftEyePts != null && leftEyePts.size >= 4) {
            val eyeHeight = leftEyePts.maxOf { it.y } - leftEyePts.minOf { it.y }
            val eyeWidth  = leftEyePts.maxOf { it.x } - leftEyePts.minOf { it.x }
            if (eyeWidth > 0) eyeHeight / eyeWidth else null
        } else null

        // --- Debug log ---
        android.util.Log.d("FaceValues", buildString {
            appendLine("=== FACE FRAME ===")
            appendLine("smile=%.3f".format(smile))
            appendLine("leftEye=%.3f  rightEye=%.3f".format(leftEye, rightEye))
            appendLine("mouthOpen=${mouthOpen?.let  { "%.2f".format(it) } ?: "null"}")
            appendLine("mouthCurve=${mouthCurve?.let { "%.2f".format(it) } ?: "null"}")
            appendLine("browRaise=${browRaise?.let   { "%.4f".format(it) } ?: "null"}")
            appendLine("browFurrow=${browFurrow?.let { "%.2f".format(it) } ?: "null"}")
            appendLine("eyeOpenRatio=${eyeOpenRatio?.let { "%.4f".format(it) } ?: "null"}")
            leftBrow?.let { pts ->
                appendLine("leftBrow points (${pts.size}):")
                pts.forEachIndexed { i, p -> append("  [$i](%.1f,%.1f)".format(p.x, p.y)) }
                appendLine()
                appendLine("  innerY=%.2f  outerY=%.2f".format(
                    pts.maxByOrNull { it.x }?.y ?: 0f, pts.minByOrNull { it.x }?.y ?: 0f))
            } ?: appendLine("leftBrow=null")
            rightBrow?.let { pts ->
                appendLine("rightBrow points (${pts.size}):")
                pts.forEachIndexed { i, p -> append("  [$i](%.1f,%.1f)".format(p.x, p.y)) }
                appendLine()
                appendLine("  innerY=%.2f  outerY=%.2f".format(
                    pts.minByOrNull { it.x }?.y ?: 0f, pts.maxByOrNull { it.x }?.y ?: 0f))
            } ?: appendLine("rightBrow=null")
            upperLip?.let { appendLine("upperLip centerY=%.2f".format(it[it.size / 2].y)) }
                ?: appendLine("upperLip=null")
            mouthPoints?.let { pts ->
                appendLine("lowerLip centerY=%.2f  leftCornerY=%.2f  rightCornerY=%.2f"
                    .format(pts[pts.size / 2].y, pts.first().y, pts.last().y))
            } ?: appendLine("lowerLip=null")
            faceContour?.let { pts ->
                appendLine("faceHeight=%.2f  topY=%.2f  bottomY=%.2f"
                    .format(pts.maxOf { it.y } - pts.minOf { it.y }, pts.minOf { it.y }, pts.maxOf { it.y }))
            } ?: appendLine("faceContour=null")
        })

        // --- Feed calibration ---
        FaceBaseline.addSample(mouthOpen, mouthCurve, browRaise, browFurrow)

        if (!FaceBaseline.isCalibrated) {
            android.util.Log.d("FaceBaseline", "Calibrating... ${FaceBaseline.sampleCount}/${FaceBaseline.targetSamples}")
            keywords += "calibrating"
            return keywords  // ← early return while calibrating
        }

        // --- Adaptive emotion classification ---
        val surpriseMouthThresh = FaceBaseline.threshSurprisedMouthOpen
        val angryFurrowThresh   = FaceBaseline.threshAngryFurrow
        val sadFurrowThresh     = FaceBaseline.threshSadFurrow
        val neutralMouthOpen    = FaceBaseline.neutralMouthOpen!!
        val neutralBrowFurrow   = FaceBaseline.neutralBrowFurrow!!

        // SURPRISED: mouth opens well beyond neutral resting position
        val isSurprised = (mouthOpen != null && mouthOpen > surpriseMouthThresh) && smile < 0.05f
        if (isSurprised) keywords += "surprised"

        // SMILING: ML Kit score
        val isSmiling = smile > 0.7f
        if (isSmiling && !isSurprised) keywords += "smiling"

        // ANGRY: furrow less negative than neutral (face scrunches inward)
        val isAngry = (browFurrow != null && browFurrow > angryFurrowThresh) &&
                smile < 0.05f &&
                (mouthOpen != null && mouthOpen < neutralMouthOpen + 10f) &&
                !isSurprised
        if (isAngry) keywords += "angry"

        // SAD: furrow more negative than neutral
        val isSad = (browFurrow != null && browFurrow < sadFurrowThresh) &&
                smile < 0.05f && !isSurprised
        if (isSad) keywords += "sad"

        // DISGUSTED: slightly higher smile than resting + small mouth
        val isDisgusted = (smile > 0.015f && smile < 0.1f) &&
                (mouthOpen != null && mouthOpen < neutralMouthOpen + 5f) &&
                !isSurprised && !isSmiling
        if (isDisgusted) keywords += "disgusted"

        // NEUTRAL: fallback
        if (keywords.none { it in listOf("smiling", "sad", "angry", "surprised", "disgusted", "fearful") }) {
            keywords += "neutral"
        }

        return keywords  // ← fix: was missing, causing HandKeywordExtractor to be nested inside this object
    }
}

object HandKeywordExtractor {

    private const val WRIST     = 0
    private const val THUMB_CMC = 1
    private const val THUMB_MCP = 2
    private const val THUMB_TIP = 4
    private const val INDEX_MCP = 5
    private const val INDEX_PIP = 6
    private const val INDEX_TIP = 8
    private const val MIDDLE_MCP = 9
    private const val MIDDLE_PIP = 10
    private const val MIDDLE_TIP = 12
    private const val RING_MCP  = 13
    private const val RING_PIP  = 14
    private const val RING_TIP  = 16
    private const val PINKY_MCP = 17
    private const val PINKY_PIP = 18
    private const val PINKY_TIP = 20

    fun extractHandKeywords(
        result: HandLandmarkerResult?,
        isFrontCameraMirrored: Boolean
    ): List<String> {
        if (result == null) return emptyList()
        val hands = result.landmarks()
        if (hands.isEmpty()) return emptyList()

        val keywords = mutableListOf<String>()
        val hand = hands[0]
        if (hand.size < 21) return keywords

        fun x(i: Int) = hand[i].x()
        fun y(i: Int) = hand[i].y()

        fun isExtended(tip: Int, pip: Int, mcp: Int): Boolean {
            val wristDist = { i: Int -> distance(x(i), y(i), x(WRIST), y(WRIST)) }
            return wristDist(tip) > wristDist(pip) && wristDist(pip) > wristDist(mcp) * 0.85f
        }

        val indexExt  = isExtended(INDEX_TIP,  INDEX_PIP,  INDEX_MCP)
        val middleExt = isExtended(MIDDLE_TIP, MIDDLE_PIP, MIDDLE_MCP)
        val ringExt   = isExtended(RING_TIP,   RING_PIP,   RING_MCP)
        val pinkyExt  = isExtended(PINKY_TIP,  PINKY_PIP,  PINKY_MCP)
        val thumbExt  = distance(x(THUMB_TIP), y(THUMB_TIP), x(INDEX_MCP), y(INDEX_MCP)) >
                distance(x(THUMB_MCP), y(THUMB_MCP), x(INDEX_MCP), y(INDEX_MCP)) * 0.9f

        val extendedCount = listOf(indexExt, middleExt, ringExt, pinkyExt).count { it }

        // Both hands open palm
        if (hands.size >= 2) {
            val bothOpen = hands.count { h ->
                if (h.size < 21) return@count false
                fun hWristDist(i: Int) = distance(h[i].x(), h[i].y(), h[WRIST].x(), h[WRIST].y())
                listOf(INDEX_TIP, MIDDLE_TIP, RING_TIP, PINKY_TIP)
                    .zip(listOf(INDEX_PIP, MIDDLE_PIP, RING_PIP, PINKY_PIP))
                    .zip(listOf(INDEX_MCP, MIDDLE_MCP, RING_MCP, PINKY_MCP))
                    .all { (tipPip, mcp) ->
                        hWristDist(tipPip.first) > hWristDist(tipPip.second) &&
                                hWristDist(tipPip.second) > hWristDist(mcp) * 0.85f
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
            val dir = if (abs(dx) > abs(dy)) {
                if (dx > 0) "right" else "left"
            } else {
                if (dy > 0) "down" else "up"
            }
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
        val handSize = distance(x(WRIST), y(WRIST), x(MIDDLE_MCP), y(MIDDLE_MCP))
        if (thumbIndexDist < handSize * 0.35f && middleExt && ringExt && pinkyExt) keywords += "ok_sign"

        // Double gun
        if (hands.size >= 2) {
            var gunCount = 0
            for (h in hands) {
                if (h.size < 21) continue
                fun hx(i: Int) = h[i].x()
                fun hy(i: Int) = h[i].y()
                fun hDist(a: Int, b: Int) = distance(hx(a), hy(a), hx(b), hy(b))
                fun hWristDist(i: Int) = distance(hx(i), hy(i), hx(WRIST), hy(WRIST))
                val hIndexExt  = hWristDist(INDEX_TIP)  > hWristDist(INDEX_PIP)  && hWristDist(INDEX_PIP)  > hWristDist(INDEX_MCP)  * 0.85f
                val hMiddleExt = hWristDist(MIDDLE_TIP) > hWristDist(MIDDLE_PIP) && hWristDist(MIDDLE_PIP) > hWristDist(MIDDLE_MCP) * 0.85f
                val hRingExt   = hWristDist(RING_TIP)   > hWristDist(RING_PIP)   && hWristDist(RING_PIP)   > hWristDist(RING_MCP)   * 0.85f
                val hPinkyExt  = hWristDist(PINKY_TIP)  > hWristDist(PINKY_PIP)  && hWristDist(PINKY_PIP)  > hWristDist(PINKY_MCP)  * 0.85f
                val hThumbExt  = hDist(THUMB_TIP, INDEX_MCP) > hDist(THUMB_MCP, INDEX_MCP) * 0.9f
                val isGun = hIndexExt && hThumbExt && !hMiddleExt && !hRingExt && !hPinkyExt
                val isPointingUp = hy(INDEX_TIP) < hy(INDEX_MCP)
                if (isGun && isPointingUp) gunCount++
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

@OptIn(ExperimentalGetImage::class)
private fun imageProxyToUprightBitmap(
    imageProxy: ImageProxy,
    rotationDegrees: Int,
    mirrorX: Boolean
): Bitmap {
    val nv21 = yuv420888ToNv21(imageProxy)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
    val jpegBytes = out.toByteArray()
    val rawBitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
    val matrix = Matrix().apply {
        if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        if (mirrorX) postScale(-1f, 1f)
    }
    val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
    if (rotated != rawBitmap) rawBitmap.recycle()
    return rotated
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width; val height = image.height
    val yPlane = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
    yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()
    val out = ByteArray(width * height + width * height / 2)
    var outIndex = 0
    for (row in 0 until height) {
        val rowStart = row * yPlane.rowStride
        for (col in 0 until width) out[outIndex++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
    }
    for (row in 0 until height / 2) {
        val uRowStart = row * uPlane.rowStride; val vRowStart = row * vPlane.rowStride
        for (col in 0 until width / 2) {
            out[outIndex++] = vBuffer.get(vRowStart + col * vPlane.pixelStride)
            out[outIndex++] = uBuffer.get(uRowStart + col * uPlane.pixelStride)
        }
    }
    return out
}

fun View.toBitmap(): Bitmap {
    val bitmap = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
    draw(Canvas(bitmap))
    return bitmap
}

fun saveImageToGallery(
    context: android.content.Context,
    bitmap: Bitmap,
    filename: String = "MemeGenFR_${System.currentTimeMillis()}"
): Boolean {
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MemeGenFR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                }
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, contentValues, null, null)
            }
            true
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MemeGenFR")
            dir.mkdirs()
            File(dir, "$filename.png").outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
            }
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("MemeGen", "Failed to save image", e)
        false
    }
}