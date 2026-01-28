package com.example.facialrecog

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.core.*
import androidx.camera.view.PreviewView
import androidx.compose.ui.viewinterop.AndroidView
import com.example.facialrecog.ui.theme.FacialRecogTheme
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.abs

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

@Composable
fun PoseExpressionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    // Request permission on first launch
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            ActivityCompat.requestPermissions(
                (context as ComponentActivity),
                arrayOf(Manifest.permission.CAMERA),
                1001
            )
        }
    }

    // Re-check permission after request
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
            .padding(12.dp)
    ) {
        Text(
            text = "Live Pose + Face Keywords",
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(8.dp))

        if (!hasCameraPermission) {
            Text("Camera permission is required. Please allow it in the prompt.")
            Spacer(Modifier.height(8.dp))
            return@Column
        }

        // Camera Preview + Analyzer
        CameraPreview(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            onKeywords = { k, dbg ->
                keywords = k
                lastDebug = dbg
            }
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Detected keywords:",
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = keywords.joinToString(", "),
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(Modifier.height(6.dp))
        Text(
            text = "Debug: $lastDebug",
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                modifier = Modifier.weight(1f),
                onClick = {
                    val query = keywords.joinToString(" ")
                    // Google Images search
                    val url = "https://www.google.com/search?tbm=isch&q=" + Uri.encode(query)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }
            ) {
                Text("Search Images")
            }

            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = {
                    // quick “pose” hint for better results
                    val query = (keywords + "pose").joinToString(" ")
                    val url = "https://www.google.com/search?tbm=isch&q=" + Uri.encode(query)
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    context.startActivity(intent)
                }
            ) {
                Text("Search + 'pose'")
            }
        }
    }
}

@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onKeywords: (List<String>, String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    // ML Kit Pose detector
    val poseDetector = remember {
        val options = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(options)
    }

    // ML Kit Face detector (fast + basic probabilities)
    val faceDetector = remember {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) {
                        imageProxy.close()
                        return@setAnalyzer
                    }

                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                    // Run pose first, then face; merge keywords
                    poseDetector.process(inputImage)
                        .addOnSuccessListener { pose ->
                            val poseKeywords = PoseKeywordExtractor.extractPoseKeywords(pose)

                            faceDetector.process(inputImage)
                                .addOnSuccessListener { faces ->
                                    val faceKeywords = FaceKeywordExtractor.extractFaceKeywords(faces)
                                    val merged = (poseKeywords + faceKeywords)
                                        .distinct()
                                        .ifEmpty { listOf("no_pose_or_face") }

                                    val dbg = "poseLandmarks=${pose.allPoseLandmarks.size}, faces=${faces.size}"
                                    onKeywords(merged, dbg)
                                }
                                .addOnFailureListener {
                                    val merged = poseKeywords.ifEmpty { listOf("pose_only") }
                                    onKeywords(merged, "Face failed: ${it.javaClass.simpleName}")
                                }
                                .addOnCompleteListener {
                                    imageProxy.close()
                                }
                        }
                        .addOnFailureListener {
                            onKeywords(listOf("pose_failed"), "Pose failed: ${it.javaClass.simpleName}")
                            imageProxy.close()
                        }
                }

                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        analysis
                    )
                } catch (e: Exception) {
                    onKeywords(listOf("camera_bind_failed"), "Camera bind failed: ${e.message}")
                }

            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

object PoseKeywordExtractor {
    fun extractPoseKeywords(pose: com.google.mlkit.vision.pose.Pose): List<String> {
        val lm = pose.allPoseLandmarks.associateBy { it.landmarkType }

        fun y(type: Int): Float? = lm[type]?.position?.y
        fun x(type: Int): Float? = lm[type]?.position?.x

        val keywords = mutableListOf<String>()

        // Basic: hands up / raised
        val leftWristY = y(com.google.mlkit.vision.pose.PoseLandmark.LEFT_WRIST)
        val rightWristY = y(com.google.mlkit.vision.pose.PoseLandmark.RIGHT_WRIST)
        val leftShoulderY = y(com.google.mlkit.vision.pose.PoseLandmark.LEFT_SHOULDER)
        val rightShoulderY = y(com.google.mlkit.vision.pose.PoseLandmark.RIGHT_SHOULDER)

        // In image coordinates, smaller Y = higher on screen
        val leftHandUp = leftWristY != null && leftShoulderY != null && leftWristY < leftShoulderY
        val rightHandUp = rightWristY != null && rightShoulderY != null && rightWristY < rightShoulderY

        if (leftHandUp && rightHandUp) keywords += "hands_up"
        else if (leftHandUp) keywords += "left_hand_raised"
        else if (rightHandUp) keywords += "right_hand_raised"

        // Arms wide (approx)
        val leftWristX = x(com.google.mlkit.vision.pose.PoseLandmark.LEFT_WRIST)
        val rightWristX = x(com.google.mlkit.vision.pose.PoseLandmark.RIGHT_WRIST)
        val leftShoulderX = x(com.google.mlkit.vision.pose.PoseLandmark.LEFT_SHOULDER)
        val rightShoulderX = x(com.google.mlkit.vision.pose.PoseLandmark.RIGHT_SHOULDER)

        if (leftWristX != null && rightWristX != null && leftShoulderX != null && rightShoulderX != null) {
            val shoulderWidth = abs(rightShoulderX - leftShoulderX)
            val wristWidth = abs(rightWristX - leftWristX)
            if (shoulderWidth > 0 && wristWidth / shoulderWidth > 1.6f) {
                keywords += "arms_outstretched"
            }
        }

        // Simple: leaning left/right using shoulder slope
        if (leftShoulderY != null && rightShoulderY != null) {
            val diff = rightShoulderY - leftShoulderY
            if (abs(diff) > 35f) { // tweak as needed per device resolution
                keywords += if (diff > 0) "lean_left" else "lean_right"
            }
        }

        return keywords
    }
}

object FaceKeywordExtractor {
    fun extractFaceKeywords(faces: List<com.google.mlkit.vision.face.Face>): List<String> {
        if (faces.isEmpty()) return emptyList()

        val f = faces[0] // just take the biggest/first for MVP
        val keywords = mutableListOf<String>()

        val smile = f.smilingProbability
        val leftEye = f.leftEyeOpenProbability
        val rightEye = f.rightEyeOpenProbability

        if (smile != null && smile > 0.7f) keywords += "smiling"
        if (leftEye != null && rightEye != null && leftEye < 0.2f && rightEye < 0.2f) keywords += "eyes_closed"

        // Head direction (EulerY ~ left/right turn)
        val yaw = f.headEulerAngleY
        if (yaw > 15f) keywords += "looking_left"
        else if (yaw < -15f) keywords += "looking_right"

        // EulerZ ~ tilt
        val roll = f.headEulerAngleZ
        if (roll > 15f) keywords += "head_tilt_right"
        else if (roll < -15f) keywords += "head_tilt_left"

        return keywords
    }
}
