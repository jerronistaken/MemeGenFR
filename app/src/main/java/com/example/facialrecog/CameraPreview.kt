package com.example.facialrecog

import android.view.View
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import java.util.concurrent.Executors

// ══════════════════════════════════════════════════════════════════════
//  CAMERA PREVIEW  (extended to emit live feature vectors)
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    showDebug: Boolean,
    onKeywords: (List<String>, String) -> Unit,
    onLiveVector: (GestureFeatureVector) -> Unit,
    onHandLandmarker: (HandLandmarker?) -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context        = LocalContext.current

    val onKeywordsState      by rememberUpdatedState(onKeywords)
    val onLiveVectorState    by rememberUpdatedState(onLiveVector)
    val onHandLandmarkerState by rememberUpdatedState(onHandLandmarker)
    val showDebugState       by rememberUpdatedState(showDebug)

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    val poseDetector = remember {
        val opts = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.STREAM_MODE)
            .build()
        PoseDetection.getClient(opts)
    }

    val faceDetector = remember {
        val opts = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .enableTracking()
            .build()
        FaceDetection.getClient(opts)
    }

    val handLandmarker = remember {
        try {
            val opts = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(
                    BaseOptions.builder()
                        .setModelAssetPath("hand_landmarker.task")
                        .build()
                )
                .setRunningMode(RunningMode.IMAGE)
                .setNumHands(2)
                .build()
            HandLandmarker.createFromOptions(context, opts)
        } catch (_: Exception) { null }
    }

    // Share the hand landmarker reference upward so StaticImageExtractor can reuse it
    LaunchedEffect(handLandmarker) { onHandLandmarkerState(handLandmarker) }

    val previewViewRef  = remember { mutableStateOf<PreviewView?>(null) }
    val debugViewRef    = remember { mutableStateOf<DebugLandmarkView?>(null) }
    val cameraProviderRef = remember { mutableStateOf<ProcessCameraProvider?>(null) }

    AndroidView(
        modifier = modifier,
        factory  = { ctx ->
            val container = FrameLayout(ctx)

            val previewView = PreviewView(ctx).apply {
                scaleType         = PreviewView.ScaleType.FILL_CENTER
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

                val isFrontCamera  = true
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage == null) { imageProxy.close(); return@setAnalyzer }

                    val rotation   = imageProxy.imageInfo.rotationDegrees
                    val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

                    val handResultForGesture: HandLandmarkerResult? = try {
                        if (handLandmarker != null) {
                            val mirrorX = isFrontCamera
                            val bmp = imageProxyToUprightBitmap(imageProxy, rotation, mirrorX)
                            val mp  = BitmapImageBuilder(bmp).build()
                            val r   = handLandmarker.detect(mp)
                            bmp.recycle()
                            r
                        } else null
                    } catch (_: Exception) { null }

                    val handKeywords = HandKeywordExtractor.extractHandKeywords(
                        result                = handResultForGesture,
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

                                    // ── Emit live feature vector ──────────────────
                                    if (FaceBaseline.isCalibrated) {
                                        val vec = LiveFeatureBuilder.build(faces, pose, handResultForGesture)
                                        onLiveVectorState(vec)
                                    }

                                    if (showDebugState) {
                                        debugView.post {
                                            debugView.visibility = View.VISIBLE
                                            debugView.update(
                                                faces          = faces,
                                                pose           = pose,
                                                handResult     = handResultForGesture,
                                                imageW         = imageProxy.width,
                                                imageH         = imageProxy.height,
                                                rotationDegrees = rotation,
                                                isFrontCamera  = isFrontCamera
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
            try { cameraExecutor.shutdown() }            catch (_: Exception) {}
        }
    }
}