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

// Add this object anywhere in MainActivity.kt
object OverlayAssetResolver {

    private val KEYWORD_TO_DRAWABLE = mapOf(
        "smiling"    to R.drawable.overlay_crown,
        "peace_sign" to R.drawable.peace,
        "hands_up"   to R.drawable.absolutecinema,
        "pinky_up"   to R.drawable.pinky,
        "four_fingers"       to R.drawable.four,
        "double_gun" to R.drawable.crashout1,
    )

    fun resolve(keywords: List<String>): Int? {
        val keywordSet = keywords.toHashSet()
        for ((keyword, drawable) in KEYWORD_TO_DRAWABLE) {
            if (keyword in keywordSet) return drawable
        }
        return null
    }

    fun loadBitmap(context: android.content.Context, drawableRes: Int): Bitmap? =
        try {
            BitmapFactory.decodeResource(context.resources, drawableRes)
        } catch (_: Exception) { null }
}
@Composable
fun PoseExpressionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showDebug by remember { mutableStateOf(true) } // Changed default to true

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp),
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
        }

        if (!hasCameraPermission) {
            Text("Camera permission is required. Please allow it in the prompt.")
            return@Column
        }

        CameraPreview(
            modifier = Modifier
                .fillMaxWidth()
                .height(420.dp),
            showDebug = showDebug,
            onKeywords = { k, dbg ->
                keywords = k
                lastDebug = dbg
            }
        )

        Spacer(Modifier.height(12.dp))

        Text("Detected keywords:", style = MaterialTheme.typography.titleMedium)
        Text(keywords.joinToString(", "), style = MaterialTheme.typography.bodyLarge)

        Spacer(Modifier.height(6.dp))
        Text("Debug: $lastDebug", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(12.dp))

        val resolvedRes = remember(keywords) { OverlayAssetResolver.resolve(keywords) }
        val overlayBitmap = remember(resolvedRes) {
            resolvedRes?.let {
                try { BitmapFactory.decodeResource((context as android.app.Activity).resources, it) }
                catch (_: Exception) { null }
            }
        }

        if (overlayBitmap != null) {
            androidx.compose.foundation.Image(
                bitmap = overlayBitmap.asImageBitmap(),
                contentDescription = "Pose overlay",
                modifier = Modifier
                    .size(160.dp)
                    .align(Alignment.CenterHorizontally)
            )
        } else {
            Text(
                text = "Active: ${keywords.filter { it != "hand_detected" && it != "neutral" }.joinToString(", ").ifEmpty { "none" }}",
                style = MaterialTheme.typography.bodySmall
            )
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

    // Avoid stale lambdas inside camera callbacks
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
        } catch (_: Exception) {
            null
        }
    }

    // Hold references created in AndroidView
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

            container.addView(
                previewView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            container.addView(
                debugView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            // Setup camera once
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
                    } catch (_: Exception) {
                        null
                    }

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

                                    // IMPORTANT: only draw overlay when debug is ON
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
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }
                        .addOnFailureListener {
                            val merged = handKeywords.ifEmpty { listOf("pose_failed") }
                            onKeywordsState(merged, "Pose failed: ${it.javaClass.simpleName}")
                            imageProxy.close()
                        }
                }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    onKeywordsState(listOf("camera_bind_failed"), "Camera bind failed: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(ctx))

            container
        },
        // ✅ This runs every recomposition: toggles overlay reliably
        update = { _ ->
            debugViewRef.value?.visibility = if (showDebug) View.VISIBLE else View.GONE
        }
    )

    // Clean up resources
    DisposableEffect(Unit) {
        onDispose {
            try {
                cameraProviderRef.value?.unbindAll()
            } catch (_: Exception) {}

            try {
                cameraExecutor.shutdown()
            } catch (_: Exception) {}
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

    // Enhanced paint styles for better visibility
    private val faceBboxPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.GREEN
        isAntiAlias = true
    }

    private val faceContourPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
        isAntiAlias = true
    }

    private val browPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 165, 0) // Orange — distinct from yellow face dots
        isAntiAlias = true
    }
    //private val poseDotPaint = Paint().apply {
    //    style = Paint.Style.FILL
    //    color = Color.CYAN
    //    isAntiAlias = true
    //}

    private val poseLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.rgb(0, 255, 255) // Bright cyan
        isAntiAlias = true
    }

    private val wristPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.MAGENTA
        isAntiAlias = true
    }

    private val handPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.RED
        isAntiAlias = true
    }

    private val handLinePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.rgb(255, 100, 100) // Light red
        isAntiAlias = true
    }

    private val textPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        textSize = 40f
        isAntiAlias = true
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

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

    private fun mapImageToView(imgX: Float, imgY: Float): PointF {
        if (imageW == 0 || imageH == 0 || width == 0 || height == 0) {
            return PointF(0f, 0f)
        }

        // ML Kit returns coords already rotated into upright space.
        // The upright dimensions after rotation:
        val (uprightW, uprightH) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            Pair(imageH.toFloat(), imageW.toFloat())
        } else {
            Pair(imageW.toFloat(), imageH.toFloat())
        }

        // For front camera, ML Kit mirrors X relative to the upright image width
        val finalX = if (isFrontCamera) uprightW - imgX else imgX
        val finalY = imgY

        // Scale to view using FILL_CENTER
        val scaleX = width.toFloat() / uprightW
        val scaleY = height.toFloat() / uprightH
        val scale = maxOf(scaleX, scaleY)

        val offsetX = (width - uprightW * scale) / 2f
        val offsetY = (height - uprightH * scale) / 2f

        return PointF(
            offsetX + finalX * scale,
            offsetY + finalY * scale
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (imageW <= 0 || imageH <= 0 || width <= 0 || height <= 0) return

        // Draw debug info
        var yPos = 50f
        canvas.drawText("Image: ${imageW}x${imageH}", 20f, yPos, textPaint)
        yPos += 50f
        canvas.drawText("View: ${width}x${height}", 20f, yPos, textPaint)
        yPos += 50f
        canvas.drawText("Rotation: $rotationDegrees°", 20f, yPos, textPaint)
        yPos += 50f
        canvas.drawText("Front Cam: $isFrontCamera", 20f, yPos, textPaint)
        yPos += 50f

        val (rotW, rotH) = if (rotationDegrees == 90 || rotationDegrees == 270) {
            Pair(imageH, imageW)
        } else {
            Pair(imageW, imageH)
        }
        canvas.drawText("Rotated: ${rotW}x${rotH}", 20f, yPos, textPaint)
        yPos += 50f

        val scaleX = width.toFloat() / rotW
        val scaleY = height.toFloat() / rotH
        val scale = maxOf(scaleX, scaleY)
        canvas.drawText("Scale: %.2f (x:%.2f y:%.2f)".format(scale, scaleX, scaleY), 20f, yPos, textPaint)
        yPos += 50f

        canvas.drawText("Faces: ${faces.size}", 20f, yPos, textPaint)
        yPos += 50f
        pose?.let {
            canvas.drawText("Pose: ${it.allPoseLandmarks.size} landmarks", 20f, yPos, textPaint)
            yPos += 50f
        }
        handResult?.let {
            canvas.drawText("Hands: ${it.landmarks().size}", 20f, yPos, textPaint)
        }

        // Draw pose landmarks and skeleton
        pose?.let { p ->
            val landmarks = p.allPoseLandmarks.associateBy { it.landmarkType }

            fun getLandmarkPoint(type: Int): PointF? {
                val lm = landmarks[type] ?: return null
                return mapImageToView(lm.position.x, lm.position.y)
            }

            // Draw skeleton connections
            val connections = listOf(
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_ELBOW,
                PoseLandmark.LEFT_ELBOW to PoseLandmark.LEFT_WRIST,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.RIGHT_ELBOW to PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_SHOULDER to PoseLandmark.LEFT_HIP,
                PoseLandmark.RIGHT_SHOULDER to PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_HIP to PoseLandmark.LEFT_KNEE,
                PoseLandmark.LEFT_KNEE to PoseLandmark.LEFT_ANKLE,
                PoseLandmark.RIGHT_HIP to PoseLandmark.RIGHT_KNEE,
                PoseLandmark.RIGHT_KNEE to PoseLandmark.RIGHT_ANKLE
            )

            for ((start, end) in connections) {
                val p1 = getLandmarkPoint(start)
                val p2 = getLandmarkPoint(end)
                if (p1 != null && p2 != null) {
                    canvas.drawLine(p1.x, p1.y, p2.x, p2.y, poseLinePaint)
                }
            }

            // Draw landmark points
            //for (lm in p.allPoseLandmarks) {
            //    val pt = mapImageToView(lm.position.x, lm.position.y)
            //    canvas.drawCircle(pt.x, pt.y, 10f, poseDotPaint)
            //}

            // Highlight wrists
            getLandmarkPoint(PoseLandmark.LEFT_WRIST)?.let {
                canvas.drawCircle(it.x, it.y, 18f, wristPaint)
            }
            getLandmarkPoint(PoseLandmark.RIGHT_WRIST)?.let {
                canvas.drawCircle(it.x, it.y, 18f, wristPaint)
            }
        }

        // Draw face bounding boxes and contours
        for (face in faces) {
            val bbox = face.boundingBox
            val topLeft = mapImageToView(bbox.left.toFloat(), bbox.top.toFloat())
            val bottomRight = mapImageToView(bbox.right.toFloat(), bbox.bottom.toFloat())

            val left = minOf(topLeft.x, bottomRight.x)
            val top = minOf(topLeft.y, bottomRight.y)
            val right = maxOf(topLeft.x, bottomRight.x)
            val bottom = maxOf(topLeft.y, bottomRight.y)

            canvas.drawRect(left, top, right, bottom, faceBboxPaint)

            // Draw face contours
            val contourTypes = listOf(
                FaceContour.FACE,
                FaceContour.LEFT_EYE,
                FaceContour.RIGHT_EYE,
                FaceContour.LEFT_EYEBROW_TOP,       // ← add
                FaceContour.LEFT_EYEBROW_BOTTOM,    // ← add
                FaceContour.RIGHT_EYEBROW_TOP,      // ← add
                FaceContour.RIGHT_EYEBROW_BOTTOM,   // ← add
                FaceContour.NOSE_BRIDGE,
                FaceContour.NOSE_BOTTOM,            // ← add
                FaceContour.UPPER_LIP_TOP,
                FaceContour.UPPER_LIP_BOTTOM,       // ← add
                FaceContour.LOWER_LIP_TOP,          // ← add
                FaceContour.LOWER_LIP_BOTTOM,
            )

// Replace the single contour loop with this:
            val generalContours = listOf(
                FaceContour.FACE,
                FaceContour.LEFT_EYE,
                FaceContour.RIGHT_EYE,
                FaceContour.NOSE_BRIDGE,
                FaceContour.NOSE_BOTTOM,
                FaceContour.UPPER_LIP_TOP,
                FaceContour.UPPER_LIP_BOTTOM,
                FaceContour.LOWER_LIP_TOP,
                FaceContour.LOWER_LIP_BOTTOM,
            )

            for (contourType in generalContours) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 5f, faceContourPaint) // yellow
                }
            }

// Draw brows in orange so you can see them clearly
            val browContours = listOf(
                FaceContour.LEFT_EYEBROW_TOP,
                FaceContour.LEFT_EYEBROW_BOTTOM,
                FaceContour.RIGHT_EYEBROW_TOP,
                FaceContour.RIGHT_EYEBROW_BOTTOM,
            )

            for (contourType in browContours) {
                face.getContour(contourType)?.points?.forEach { point ->
                    val pt = mapImageToView(point.x, point.y)
                    canvas.drawCircle(pt.x, pt.y, 8f, browPaint) // larger + orange
                }
            }

            // Draw emotion labels
            val smile = face.smilingProbability
            val leftEye = face.leftEyeOpenProbability
            val rightEye = face.rightEyeOpenProbability

            if (smile != null && smile > 0.5f) {
                canvas.drawText("😊 ${(smile * 100).toInt()}%", left, top - 10f, textPaint)
            }
        }

        // Draw hand landmarks
        handResult?.let { result ->
            val hands = result.landmarks()
            for (handIdx in hands.indices) {
                val hand = hands[handIdx]

                // Draw hand connections
                val connections = listOf(
                    // Thumb
                    0 to 1, 1 to 2, 2 to 3, 3 to 4,
                    // Index
                    0 to 5, 5 to 6, 6 to 7, 7 to 8,
                    // Middle
                    0 to 9, 9 to 10, 10 to 11, 11 to 12,
                    // Ring
                    0 to 13, 13 to 14, 14 to 15, 15 to 16,
                    // Pinky
                    0 to 17, 17 to 18, 18 to 19, 19 to 20,
                    // Palm
                    5 to 9, 9 to 13, 13 to 17
                )

                for ((start, end) in connections) {
                    if (start < hand.size && end < hand.size) {
                        val p1x = hand[start].x() * width
                        val p1y = hand[start].y() * height
                        val p2x = hand[end].x() * width
                        val p2y = hand[end].y() * height
                        canvas.drawLine(p1x, p1y, p2x, p2y, handLinePaint)
                    }
                }

                // Draw hand landmarks
                for (lm in hand) {
                    val x = lm.x() * width
                    val y = lm.y() * height
                    canvas.drawCircle(x, y, 10f, handPaint)
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

        val leftWristY = y(PoseLandmark.LEFT_WRIST)
        val rightWristY = y(PoseLandmark.RIGHT_WRIST)
        val leftShoulderY = y(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderY = y(PoseLandmark.RIGHT_SHOULDER)

        val leftHandUp = leftWristY != null && leftShoulderY != null && leftWristY < leftShoulderY
        val rightHandUp = rightWristY != null && rightShoulderY != null && rightWristY < rightShoulderY

        if (leftHandUp && rightHandUp) keywords += "hands_up"
        else if (leftHandUp) keywords += "left_hand_raised"
        else if (rightHandUp) keywords += "right_hand_raised"

        val leftWristX = x(PoseLandmark.LEFT_WRIST)
        val rightWristX = x(PoseLandmark.RIGHT_WRIST)
        val leftShoulderX = x(PoseLandmark.LEFT_SHOULDER)
        val rightShoulderX = x(PoseLandmark.RIGHT_SHOULDER)

        if (leftWristX != null && rightWristX != null && leftShoulderX != null && rightShoulderX != null) {
            val shoulderWidth = abs(rightShoulderX - leftShoulderX)
            val wristWidth = abs(rightWristX - leftWristX)
            if (shoulderWidth > 0 && wristWidth / shoulderWidth > 1.6f) {
                keywords += "arms_outstretched"
            }
        }

        if (leftShoulderY != null && rightShoulderY != null) {
            val diff = rightShoulderY - leftShoulderY
            if (abs(diff) > 35f) keywords += if (diff > 0) "lean_left" else "lean_right"
        }

        return keywords
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
        val yaw      = f.headEulerAngleY  // left/right turn
        val pitch    = f.headEulerAngleX  // up/down tilt
        val roll     = f.headEulerAngleZ  // head tilt

        // --- Head orientation ---
        if (yaw > 20f)       keywords += "looking_left"
        else if (yaw < -20f) keywords += "looking_right"
        if (pitch > 15f)     keywords += "looking_up"
        else if (pitch < -15f) keywords += "looking_down"
        if (roll > 15f)      keywords += "head_tilt_right"
        else if (roll < -15f) keywords += "head_tilt_left"

        // --- Eye state ---
        //val bothEyesClosed = leftEye < 0.2f && rightEye < 0.2f
        //val eyesWideOpen   = leftEye > 0.85f && rightEye > 0.85f
        //if (bothEyesClosed) keywords += "eyes_closed"
        //if (eyesWideOpen)   keywords += "eyes_wide"

        // --- Geometry-based emotion inference from contours ---
        val mouthPoints  = f.getContour(com.google.mlkit.vision.face.FaceContour.LOWER_LIP_BOTTOM)?.points
        val upperLip     = f.getContour(com.google.mlkit.vision.face.FaceContour.UPPER_LIP_TOP)?.points
        val faceContour  = f.getContour(com.google.mlkit.vision.face.FaceContour.FACE)?.points
        val leftBrow     = f.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYEBROW_TOP)?.points
        val rightBrow    = f.getContour(com.google.mlkit.vision.face.FaceContour.RIGHT_EYEBROW_TOP)?.points
        val leftEyePts   = f.getContour(com.google.mlkit.vision.face.FaceContour.LEFT_EYE)?.points
        val rightEyePts  = f.getContour(com.google.mlkit.vision.face.FaceContour.RIGHT_EYE)?.points

        // Mouth openness: gap between upper and lower lip center
        val mouthOpen = if (mouthPoints != null && upperLip != null && mouthPoints.isNotEmpty() && upperLip.isNotEmpty()) {
            val lowerY = mouthPoints[mouthPoints.size / 2].y
            val upperY = upperLip[upperLip.size / 2].y
            lowerY - upperY  // positive = open
        } else null

        // Mouth corner direction: are corners up (smile) or down (sad)?
        // Lower lip contour: first and last points are the corners
        val mouthCurve = if (mouthPoints != null && mouthPoints.size >= 3) {
            val leftCornerY  = mouthPoints.first().y
            val rightCornerY = mouthPoints.last().y
            val centerY      = mouthPoints[mouthPoints.size / 2].y
            // Positive = corners higher than center = smile curve
            // Negative = corners lower than center = frown
            ((leftCornerY + rightCornerY) / 2f) - centerY
        } else null

        // Eyebrow height relative to face: raised = surprise/fear, lowered = anger
        val browRaise = if (leftBrow != null && rightBrow != null && faceContour != null && faceContour.size >= 10) {
            val faceTopY   = faceContour.minOf { it.y }
            val faceCenterY = faceContour.maxOf { it.y }
            val faceHeight = faceCenterY - faceTopY
            val leftBrowY  = leftBrow.minOf { it.y }
            val rightBrowY = rightBrow.minOf { it.y }
            val avgBrowY   = (leftBrowY + rightBrowY) / 2f
            // Normalized: 0 = brow at top of face, 1 = brow at bottom
            // Lower value = higher brow (raised)
            (avgBrowY - faceTopY) / faceHeight
        } else null

        // Eyebrow inner corners: furrowed brows (inner corners pulled down/together) = anger/worry
        val browFurrow = if (leftBrow != null && rightBrow != null && leftBrow.isNotEmpty() && rightBrow.isNotEmpty()) {
            // Inner corner of left brow (rightmost point) vs outer (leftmost)
            val leftInnerY  = leftBrow.maxByOrNull { it.x }?.y ?: 0f
            val leftOuterY  = leftBrow.minByOrNull { it.x }?.y ?: 0f
            val rightInnerY = rightBrow.minByOrNull { it.x }?.y ?: 0f
            val rightOuterY = rightBrow.maxByOrNull { it.x }?.y ?: 0f
            // Positive = inner corners lower than outer = furrowed
            ((leftInnerY - leftOuterY) + (rightInnerY - rightOuterY)) / 2f
        } else null

        // Eye openness ratio from contour points
        val eyeOpenRatio = if (leftEyePts != null && leftEyePts.size >= 4) {
            val eyeHeight = leftEyePts.maxOf { it.y } - leftEyePts.minOf { it.y }
            val eyeWidth  = leftEyePts.maxOf { it.x } - leftEyePts.minOf { it.x }
            if (eyeWidth > 0) eyeHeight / eyeWidth else null
        } else null

        // --- Classify emotions ---

        // SURPRISED: mouth open + eyebrows raised + eyes wide
        val isSurprised = (mouthOpen != null && mouthOpen > 15f) &&
                (browRaise != null && browRaise < 0.25f)
        if (isSurprised) {
            keywords += "surprised"
        }

        // HAPPY / SMILING: ML Kit smile score OR mouth curve upward
        val isSmiling = smile > 0.65f || (mouthCurve != null && mouthCurve < -8f && smile > 0.3f)
        if (isSmiling && !isSurprised) {
            keywords += "smiling"
        }

        // SAD: mouth corners down + brows may be slightly raised inner
        val isSad = (mouthCurve != null && mouthCurve > 10f) &&
                smile < 0.3f
        if (isSad) {
            keywords += "sad"
        }

        // ANGRY: furrowed brows + low smile + eyes not wide
        val isAngry = (browFurrow != null && browFurrow > 8f) &&
                smile < 0.2f &&
                (browRaise == null || browRaise > 0.28f)
        if (isAngry) {
            keywords += "angry"
        }

        // DISGUSTED: similar to angry but with mouth open slightly + nose wrinkle
        // (ML Kit doesn't give nose wrinkle, approximate with furrowed brow + slight mouth open)
        val isDisgusted = (browFurrow != null && browFurrow > 5f) &&
                (mouthOpen != null && mouthOpen in 3f..15f) &&
                smile < 0.2f
        if (isDisgusted && !isAngry) {
            keywords += "disgusted"
        }

        // FEARFUL: eyes wide + brows raised + mouth slightly open
        val isFearful =
                (browRaise != null && browRaise < 0.22f) &&
                (mouthOpen != null && mouthOpen > 5f) &&
                smile < 0.3f &&
                !isSurprised
        if (isFearful) {
            keywords += "fearful"
        }

        // NEUTRAL: nothing else triggered
        if (keywords.none { it in listOf("smiling","sad","angry","surprised","disgusted","fearful") }) {
            keywords += "neutral"
        }

        return keywords
    }
}

object HandKeywordExtractor {

    private const val WRIST = 0
    private const val THUMB_CMC = 1
    private const val THUMB_MCP = 2
    private const val THUMB_TIP = 4
    private const val INDEX_MCP = 5
    private const val INDEX_PIP = 6
    private const val INDEX_TIP = 8
    private const val MIDDLE_MCP = 9
    private const val MIDDLE_PIP = 10
    private const val MIDDLE_TIP = 12
    private const val RING_MCP = 13
    private const val RING_PIP = 14
    private const val RING_TIP = 16
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
        //keywords += "hand_detected"

        val hand = hands[0]
        if (hand.size < 21) return keywords

        fun x(i: Int) = hand[i].x()
        fun y(i: Int) = hand[i].y()

        // Finger extended: tip is further from wrist than pip, using distance
        // This works regardless of hand orientation (sideways, up, down)
        fun isExtended(tip: Int, pip: Int, mcp: Int): Boolean {
            val wristDist = { i: Int -> distance(x(i), y(i), x(WRIST), y(WRIST)) }
            return wristDist(tip) > wristDist(pip) && wristDist(pip) > wristDist(mcp) * 0.85f
        }

        val indexExt  = isExtended(INDEX_TIP,  INDEX_PIP,  INDEX_MCP)
        val middleExt = isExtended(MIDDLE_TIP, MIDDLE_PIP, MIDDLE_MCP)
        val ringExt   = isExtended(RING_TIP,   RING_PIP,   RING_MCP)
        val pinkyExt  = isExtended(PINKY_TIP,  PINKY_PIP,  PINKY_MCP)

        // Thumb: extended if tip is far from index MCP (avoids confusion with palm)
        val thumbExt = distance(x(THUMB_TIP), y(THUMB_TIP), x(INDEX_MCP), y(INDEX_MCP)) >
                distance(x(THUMB_MCP), y(THUMB_MCP), x(INDEX_MCP), y(INDEX_MCP)) * 0.9f

        val extendedCount = listOf(indexExt, middleExt, ringExt, pinkyExt).count { it }

        // Open palm: all 4 fingers extended
        if (extendedCount == 4) {
            keywords += "four_fingers"
        }

        // Fist: no fingers extended
        if (extendedCount == 0 && !thumbExt) {
            keywords += "fist"
        }

        // Thumbs up: only thumb extended, fist otherwise
        if (thumbExt && extendedCount == 0) {
            keywords += "thumbs_up"
        }

        // Thumbs down: thumb extended downward
        if (thumbExt && extendedCount == 0 && y(THUMB_TIP) > y(WRIST)) {
            keywords += "thumbs_down"
        }

        // Pointing: only index extended
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

        // Peace / Victory sign: index + middle extended only
        if (indexExt && middleExt && !ringExt && !pinkyExt) {
            keywords += "peace_sign"
        }

        // Pinky up: only pinky extended, others curled
        if (pinkyExt && !indexExt && !middleExt && !ringExt) {
            keywords += "pinky_up"
        }

        // OK sign: thumb tip close to index tip, other fingers extended
        val thumbIndexDist = distance(x(THUMB_TIP), y(THUMB_TIP), x(INDEX_TIP), y(INDEX_TIP))
        val handSize = distance(x(WRIST), y(WRIST), x(MIDDLE_MCP), y(MIDDLE_MCP))
        if (thumbIndexDist < handSize * 0.35f && middleExt && ringExt && pinkyExt) {
            keywords += "ok_sign"
        }

        // Double gun: both hands in pointing-up gesture
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

                // Gun shape: index up + thumb out, middle/ring/pinky curled
                val isGun = hIndexExt && hThumbExt && !hMiddleExt && !hRingExt && !hPinkyExt
                // Pointing upward: index tip above index MCP
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
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    yBuffer.rewind()
    uBuffer.rewind()
    vBuffer.rewind()

    val ySize = width * height
    val uvSize = width * height / 2
    val out = ByteArray(ySize + uvSize)

    var outIndex = 0
    val yRowStride = yPlane.rowStride
    val yPixelStride = yPlane.pixelStride
    for (row in 0 until height) {
        val rowStart = row * yRowStride
        for (col in 0 until width) {
            out[outIndex++] = yBuffer.get(rowStart + col * yPixelStride)
        }
    }

    val chromaWidth = width / 2
    val chromaHeight = height / 2
    val uRowStride = uPlane.rowStride
    val vRowStride = vPlane.rowStride
    val uPixelStride = uPlane.pixelStride
    val vPixelStride = vPlane.pixelStride

    for (row in 0 until chromaHeight) {
        val uRowStart = row * uRowStride
        val vRowStart = row * vRowStride
        for (col in 0 until chromaWidth) {
            val uIndex = uRowStart + col * uPixelStride
            val vIndex = vRowStart + col * vPixelStride
            out[outIndex++] = vBuffer.get(vIndex) // V
            out[outIndex++] = uBuffer.get(uIndex) // U
        }
    }

    return out
}

fun View.toBitmap(): Bitmap {
    val w = width.coerceAtLeast(1)
    val h = height.coerceAtLeast(1)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    draw(canvas)
    return bitmap
}