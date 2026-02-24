package com.example.facialrecog
import com.example.facialrecog.LiveFeatureBuilder
import android.graphics.*
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraScreen(
    modifier: Modifier = Modifier,
    showDebug: Boolean,
    onKeywords: (List<String>, String) -> Unit,
    onLiveVector: (GestureFeatureVector) -> Unit,
    onHandLandmarker: (HandLandmarker?) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current

    val onKeywordsState by rememberUpdatedState(onKeywords)
    val onLiveVectorState by rememberUpdatedState(onLiveVector)
    val onHandLandmarkerState by rememberUpdatedState(onHandLandmarker)
    val showDebugState by rememberUpdatedState(showDebug)

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val poseDetector = remember {
        PoseDetection.getClient(
            PoseDetectorOptions.Builder()
                .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
                .build()
        )
    }

    val faceDetector = remember {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .enableTracking()
                .build()
        )
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

    LaunchedEffect(handLandmarker) {
        onHandLandmarkerState(handLandmarker)
    }

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
                            val result = handLandmarker.detect(mpImage)
                            bmp.recycle()
                            result
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
                                        if (handKeywords.isNotEmpty()) {
                                            append(", handKW=${handKeywords.joinToString("|")}")
                                        }
                                    }

                                    onKeywordsState(merged, dbg)

                                    if (FaceBaseline.isCalibrated) {
                                        val vec = LiveFeatureBuilder.build(
                                            faces = faces,
                                            pose = pose,
                                            handResult = handResultForGesture
                                        )
                                        onLiveVectorState(vec)
                                    }

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
        update = {
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

// Helpers
@OptIn(ExperimentalGetImage::class)
fun imageProxyToUprightBitmap(
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

@OptIn(ExperimentalGetImage::class)
private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val width = image.width
    val height = image.height

    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val yBuffer = yPlane.buffer
    val uBuffer = uPlane.buffer
    val vBuffer = vPlane.buffer

    yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()

    val out = ByteArray(width * height + width * height / 2)
    var outIndex = 0

    for (row in 0 until height) {
        val rowStart = row * yPlane.rowStride
        for (col in 0 until width) {
            out[outIndex++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
        }
    }

    for (row in 0 until height / 2) {
        val uRow = row * uPlane.rowStride
        val vRow = row * vPlane.rowStride
        for (col in 0 until width / 2) {
            out[outIndex++] = vBuffer.get(vRow + col * vPlane.pixelStride)
            out[outIndex++] = uBuffer.get(uRow + col * uPlane.pixelStride)
        }
    }

    return out
}