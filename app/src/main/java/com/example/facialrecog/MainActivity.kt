package com.example.facialrecog

import android.Manifest
//import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
//import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import android.graphics.Color as AndroidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.sqrt
import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
//import androidx.compose.runtime.saveable.rememberSaveable


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

// ══════════════════════════════════════════════════════════════════════
//  GESTURE STORE  (persists to /files/gestures.json)
// ══════════════════════════════════════════════════════════════════════

object GestureStore {

    private val gestures = mutableListOf<GestureFeatureVector>()
    private var loaded   = false

    fun load(context: android.content.Context) {
        if (loaded) return
        loaded = true
        val file = File(context.filesDir, "gestures.json")
        if (!file.exists()) return
        try {
            val arr = JSONArray(file.readText())
            repeat(arr.length()) { i ->
                gestures.add(GestureFeatureVector.fromJson(arr.getJSONObject(i)))
            }
        } catch (e: Exception) {
            android.util.Log.e("GestureStore", "Load failed", e)
        }
    }

    fun save(context: android.content.Context) {
        try {
            val arr = JSONArray()
            gestures.forEach { arr.put(it.toJson()) }
            File(context.filesDir, "gestures.json").writeText(arr.toString())
        } catch (e: Exception) {
            android.util.Log.e("GestureStore", "Save failed", e)
        }
    }

    fun add(context: android.content.Context, vector: GestureFeatureVector) {
        gestures.add(vector)
        save(context)
    }

    fun remove(context: android.content.Context, uri: String) {
        gestures.removeAll { it.imageUri == uri }
        save(context)
    }

    fun all(): List<GestureFeatureVector> = gestures.toList()

    fun clear(context: android.content.Context) {
        gestures.clear()
        save(context)
    }
}

// ══════════════════════════════════════════════════════════════════════
//  GESTURE MATCHER
// ══════════════════════════════════════════════════════════════════════

object GestureMatcher {

    /**
     * Returns the best-matching stored gesture and its similarity score,
     * or null if nothing exceeds [threshold].
     */
    fun findBestMatch(
        live: GestureFeatureVector,
        threshold: Float = 0.80f
    ): Pair<GestureFeatureVector, Float>? {
        val stored = GestureStore.all()
        if (stored.isEmpty()) return null

        return stored
            .map { s -> s to cosineSimilarity(live.toWeightedArray(), s.toWeightedArray()) }
            .filter { (_, score) -> score >= threshold }
            .maxByOrNull { (_, score) -> score }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val len = minOf(a.size, b.size)
        var dot  = 0f
        var magA = 0f
        var magB = 0f
        for (i in 0 until len) {
            dot  += a[i] * b[i]
            magA += a[i] * a[i]
            magB += b[i] * b[i]
        }
        magA = sqrt(magA)
        magB = sqrt(magB)
        return if (magA == 0f || magB == 0f) 0f else dot / (magA * magB)
    }
}

// ══════════════════════════════════════════════════════════════════════
//  LIVE FEATURE BUILDER  (assembled in the analysis loop)
// ══════════════════════════════════════════════════════════════════════

object LiveFeatureBuilder {

    fun build(
        faces: List<Face>,
        pose: Pose?,
        handResult: HandLandmarkerResult?
    ): GestureFeatureVector {

        // ── Face ──────────────────────────────────────────────────────
        val f           = faces.firstOrNull()
        val smile       = f?.smilingProbability ?: 0f
        val yaw         = (f?.headEulerAngleY ?: 0f) / 90f
        val pitch       = (f?.headEulerAngleX ?: 0f) / 90f
        val roll        = (f?.headEulerAngleZ ?: 0f) / 90f

        val mouthPoints = f?.getContour(FaceContour.LOWER_LIP_BOTTOM)?.points
        val upperLip    = f?.getContour(FaceContour.UPPER_LIP_TOP)?.points
        val faceContour = f?.getContour(FaceContour.FACE)?.points
        val leftBrow    = f?.getContour(FaceContour.LEFT_EYEBROW_TOP)?.points
        val rightBrow   = f?.getContour(FaceContour.RIGHT_EYEBROW_TOP)?.points

        // Normalize mouth-open by face height so scale-invariant
        val faceHeight = faceContour?.let { it.maxOf { p -> p.y } - it.minOf { p -> p.y } }
            ?.takeIf { it > 0f } ?: 1f

        val rawMouthOpen = if (mouthPoints != null && upperLip != null &&
            mouthPoints.isNotEmpty() && upperLip.isNotEmpty())
            mouthPoints[mouthPoints.size / 2].y - upperLip[upperLip.size / 2].y
        else 0f
        val mouthOpen  = rawMouthOpen / faceHeight

        val mouthCurve = if (mouthPoints != null && mouthPoints.size >= 3) {
            val lc = mouthPoints.first().y; val rc = mouthPoints.last().y
            val cy = mouthPoints[mouthPoints.size / 2].y
            ((lc + rc) / 2f - cy) / faceHeight
        } else 0f

        val browRaise = if (leftBrow != null && rightBrow != null && faceContour != null) {
            val topY   = faceContour.minOf { it.y }
            val avgBrowY = (leftBrow.minOf { it.y } + rightBrow.minOf { it.y }) / 2f
            (avgBrowY - topY) / faceHeight
        } else 0f

        val browFurrow = if (leftBrow != null && rightBrow != null &&
            leftBrow.isNotEmpty() && rightBrow.isNotEmpty()) {
            val liY = leftBrow.maxByOrNull  { it.x }?.y ?: 0f
            val loY = leftBrow.minByOrNull  { it.x }?.y ?: 0f
            val riY = rightBrow.minByOrNull { it.x }?.y ?: 0f
            val roY = rightBrow.maxByOrNull { it.x }?.y ?: 0f
            ((liY - loY) + (riY - roY)) / 2f / faceHeight
        } else 0f

        // ── Pose ──────────────────────────────────────────────────────
        val lm = pose?.allPoseLandmarks?.associateBy { it.landmarkType }.orEmpty()
        val lwy  = lm[PoseLandmark.LEFT_WRIST]?.position?.y  ?: 0f
        val rwy  = lm[PoseLandmark.RIGHT_WRIST]?.position?.y ?: 0f
        val lsy  = lm[PoseLandmark.LEFT_SHOULDER]?.position?.y  ?: 0f
        val rsy  = lm[PoseLandmark.RIGHT_SHOULDER]?.position?.y ?: 0f
        val lsx  = lm[PoseLandmark.LEFT_SHOULDER]?.position?.x  ?: 0f
        val rsx  = lm[PoseLandmark.RIGHT_SHOULDER]?.position?.x ?: 0f
        val lwx  = lm[PoseLandmark.LEFT_WRIST]?.position?.x  ?: 0f
        val rwx  = lm[PoseLandmark.RIGHT_WRIST]?.position?.x ?: 0f

        val sw = abs(rsx - lsx).takeIf { it > 0f } ?: 1f
        val leftWristRelY  = (lwy - lsy) / sw
        val rightWristRelY = (rwy - rsy) / sw
        val wristSpread    = abs(rwx - lwx) / sw

        // ── Hand ──────────────────────────────────────────────────────
        val handLandmarks = FloatArray(42)
        handResult?.landmarks()?.firstOrNull()?.let { hand ->
            if (hand.size >= 21) {
                // Center & normalize by hand size (wrist→middle-MCP)
                val wristX = hand[0].x(); val wristY = hand[0].y()
                val handSize = sqrt(
                    (hand[9].x() - wristX).pow2() + (hand[9].y() - wristY).pow2()
                ).takeIf { it > 0f } ?: 1f
                for (i in 0 until 21) {
                    handLandmarks[i * 2]     = (hand[i].x() - wristX) / handSize
                    handLandmarks[i * 2 + 1] = (hand[i].y() - wristY) / handSize
                }
            }
        }

        return GestureFeatureVector(
            mouthOpen      = mouthOpen,
            mouthCurve     = mouthCurve,
            browFurrow     = browFurrow,
            browRaise      = browRaise,
            smileProb      = smile,
            yaw            = yaw,
            pitch          = pitch,
            roll           = roll,
            leftWristRelY  = leftWristRelY,
            rightWristRelY = rightWristRelY,
            wristSpread    = wristSpread,
            handLandmarks  = handLandmarks
        )
    }

    private fun Float.pow2() = this * this
}

// ══════════════════════════════════════════════════════════════════════
//  STATIC IMAGE FEATURE EXTRACTOR  (for uploaded photos)
// ══════════════════════════════════════════════════════════════════════

object StaticImageExtractor {

    /**
     * Runs face + hand detection on a static bitmap and returns a feature vector.
     * Call from a background coroutine.
     */
    fun extract(
        context: android.content.Context,
        bitmap: Bitmap,
        handLandmarker: HandLandmarker?,
        imageUri: String,
        label: String
    ): GestureFeatureVector {

        // ── Hands ──────────────────────────────────────────────────────
        val handResult: HandLandmarkerResult? = try {
            if (handLandmarker != null) {
                val mpImage: MPImage = BitmapImageBuilder(bitmap).build()
                handLandmarker.detect(mpImage)
            } else null
        } catch (_: Exception) { null }

        // ── Face (blocking via Tasks API) ─────────────────────────────
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val faceOptions = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .build()
        val faceDetector = FaceDetection.getClient(faceOptions)

        var faces: List<Face> = emptyList()
        val latch = java.util.concurrent.CountDownLatch(1)
        faceDetector.process(inputImage)
            .addOnSuccessListener { result -> faces = result; latch.countDown() }
            .addOnFailureListener { latch.countDown() }
        latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        faceDetector.close()

        // ── Pose ──────────────────────────────────────────────────────
        val poseOptions = PoseDetectorOptions.Builder()
            .setDetectorMode(PoseDetectorOptions.SINGLE_IMAGE_MODE)
            .build()
        val poseDetector = PoseDetection.getClient(poseOptions)
        var pose: Pose? = null
        val poseLatch = java.util.concurrent.CountDownLatch(1)
        poseDetector.process(inputImage)
            .addOnSuccessListener { result -> pose = result; poseLatch.countDown() }
            .addOnFailureListener { poseLatch.countDown() }
        poseLatch.await(3, java.util.concurrent.TimeUnit.SECONDS)
        poseDetector.close()

        val liveVec = LiveFeatureBuilder.build(
            faces     = faces,
            pose      = pose,
            handResult = handResult
        )

        return liveVec.copy(imageUri = imageUri, label = label)
    }
}

// ══════════════════════════════════════════════════════════════════════
//  ORIGINAL OBJECTS (kept intact, still used as fallback / debug)
// ══════════════════════════════════════════════════════════════════════

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

// ══════════════════════════════════════════════════════════════════════
//  MAIN ACTIVITY
// ══════════════════════════════════════════════════════════════════════

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

// ══════════════════════════════════════════════════════════════════════
//  MAIN COMPOSABLE
// ══════════════════════════════════════════════════════════════════════

@Composable
fun PoseExpressionApp() {
    val context       = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope         = rememberCoroutineScope()

    // ── Load persisted gestures once ──────────────────────────────────
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { GestureStore.load(context) }
    }

    var showDebug by remember { mutableStateOf(false) }
    var showLibrary by remember { mutableStateOf(false) }

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

    // ── Live detection state ──────────────────────────────────────────
    var keywords    by remember { mutableStateOf(listOf("no_detection_yet")) }
    var lastDebug   by remember { mutableStateOf("Waiting for frames...") }
    var liveVector  by remember { mutableStateOf<GestureFeatureVector?>(null) }

    // ── Match state ───────────────────────────────────────────────────
    var matchedGesture  by remember { mutableStateOf<GestureFeatureVector?>(null) }
    var matchScore      by remember { mutableStateOf(0f) }

    // Update match whenever the live vector changes
    LaunchedEffect(liveVector) {
        val vec = liveVector ?: return@LaunchedEffect
        val result = GestureMatcher.findBestMatch(vec, threshold = 0.80f)
        matchedGesture = result?.first
        matchScore     = result?.second ?: 0f
    }

    // ── Matched image bitmap ──────────────────────────────────────────
    val matchedBitmap = remember(matchedGesture) {
        val uri = matchedGesture?.imageUri ?: return@remember null
        try {
            val stream = context.contentResolver.openInputStream(Uri.parse(uri))
            BitmapFactory.decodeStream(stream)
        } catch (_: Exception) { null }
    }

    // ── Fallback: keyword-based drawable ──────────────────────────────
    val fallbackRes    = remember(keywords) { OverlayAssetResolver.resolve(keywords) }
    val fallbackBitmap = remember(fallbackRes) {
        fallbackRes?.let {
            try { BitmapFactory.decodeResource((context as android.app.Activity).resources, it) }
            catch (_: Exception) { null }
        }
    }

    // Decide what overlay to show (learned match wins over keyword fallback)
    val overlayBitmap = matchedBitmap ?: fallbackBitmap

    var saveSuccess by remember(overlayBitmap) { mutableStateOf<Boolean?>(null) }

    // ── Image picker for teaching new gestures ────────────────────────
    var pendingBitmap    by remember { mutableStateOf<Bitmap?>(null) }
    var pendingUri       by remember { mutableStateOf<Uri?>(null) }
    var showLabelDialog  by remember { mutableStateOf(false) }
    var labelInput       by remember { mutableStateOf("") }
    var isExtracting     by remember { mutableStateOf(false) }

    // Hand landmarker reference (shared with CameraPreview via callback)
    var handLandmarkerRef by remember { mutableStateOf<HandLandmarker?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        pendingUri = uri
        try {
            val stream = context.contentResolver.openInputStream(uri)
            pendingBitmap = BitmapFactory.decodeStream(stream)
            showLabelDialog = true
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open image", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Status text ───────────────────────────────────────────────────
    val activeKeywords = if (!FaceBaseline.isCalibrated) {
        "Calibrating… ${FaceBaseline.sampleCount}/${FaceBaseline.targetSamples}"
    } else {
        keywords
            .filter { it !in listOf("hand_detected", "neutral", "no_detection_yet",
                "no_pose_face_hand", "calibrating") }
            .joinToString(", ")
            .ifEmpty { "none" }
    }

    // ══════════════════════════════════════════════════════════════════
    //  LABEL DIALOG
    // ══════════════════════════════════════════════════════════════════
    if (showLabelDialog && pendingBitmap != null) {
        AlertDialog(
            onDismissRequest = {
                showLabelDialog = false
                pendingBitmap   = null
                pendingUri      = null
                labelInput      = ""
            },
            title = { Text("Name this gesture") },
            text  = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    pendingBitmap?.let { bmp ->
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    }
                    OutlinedTextField(
                        value         = labelInput,
                        onValueChange = { labelInput = it },
                        label         = { Text("Label (e.g. 'thumbs up')") },
                        singleLine    = true,
                        modifier      = Modifier.fillMaxWidth()
                    )
                    if (isExtracting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Extracting features…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = labelInput.isNotBlank() && !isExtracting,
                    onClick = {
                        val bmp = pendingBitmap ?: return@TextButton
                        val uri = pendingUri   ?: return@TextButton
                        isExtracting = true
                        scope.launch {
                            val vector = withContext(Dispatchers.IO) {
                                StaticImageExtractor.extract(
                                    context       = context,
                                    bitmap        = bmp,
                                    handLandmarker = handLandmarkerRef,
                                    imageUri      = uri.toString(),
                                    label         = labelInput.trim()
                                )
                            }
                            GestureStore.add(context, vector)
                            isExtracting    = false
                            showLabelDialog = false
                            pendingBitmap   = null
                            pendingUri      = null
                            labelInput      = ""
                            Toast.makeText(context, "Gesture saved!", Toast.LENGTH_SHORT).show()
                        }
                    }
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showLabelDialog = false
                    pendingBitmap   = null
                    pendingUri      = null
                    labelInput      = ""
                }) { Text("Cancel") }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════
    //  GESTURE LIBRARY DIALOG
    // ══════════════════════════════════════════════════════════════════
    if (showLibrary) {
        val stored by remember { derivedStateOf { GestureStore.all() } }
        AlertDialog(
            onDismissRequest = { showLibrary = false },
            title = { Text("Gesture Library (${stored.size})") },
            text  = {
                if (stored.isEmpty()) {
                    Text("No gestures saved yet.\nUse '+ Teach' to add one.")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(stored) { gesture ->
                            Column(
                                modifier = Modifier
                                    .width(100.dp)
                                    .border(1.dp, MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp))
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val bmp = remember(gesture.imageUri) {
                                    try {
                                        val s = context.contentResolver
                                            .openInputStream(Uri.parse(gesture.imageUri))
                                        BitmapFactory.decodeStream(s)
                                    } catch (_: Exception) { null }
                                }
                                if (bmp != null) {
                                    Image(
                                        bitmap = bmp.asImageBitmap(),
                                        contentDescription = gesture.label,
                                        modifier = Modifier
                                            .size(80.dp)
                                            .clip(RoundedCornerShape(6.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(80.dp)
                                            .background(
                                                MaterialTheme.colorScheme.surfaceVariant,
                                                RoundedCornerShape(6.dp)
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) { Text("?", fontSize = 32.sp) }
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text     = gesture.label,
                                    style    = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    onClick = {
                                        GestureStore.remove(context, gesture.imageUri)
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text("Remove", fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLibrary = false }) { Text("Close") }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════
    //  MAIN LAYOUT
    // ══════════════════════════════════════════════════════════════════
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
    ) {

        // ── TOP BAR ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text       = "MemeGenFR",
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.weight(1f)
            )
            TextButton(onClick = { showDebug = !showDebug }) {
                Text(if (showDebug) "Debug: ON" else "Debug: OFF")
            }
            TextButton(onClick = { FaceBaseline.reset() }) {
                Text("Recalibrate")
            }
        }

        // ── SECOND BAR: Teach / Library ───────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp),
            verticalAlignment    = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick  = { imagePicker.launch("image/*") },
                modifier = Modifier.height(36.dp)
            ) { Text("+ Teach") }

            OutlinedButton(
                onClick  = { showLibrary = true },
                modifier = Modifier.height(36.dp)
            ) { Text("Library (${GestureStore.all().size})") }

            Spacer(Modifier.weight(1f))

            // Match confidence indicator
            if (matchedGesture != null) {
                Text(
                    text  = "Match: ${(matchScore * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        matchScore >= 0.90f -> Color(0xFF4CAF50)
                        matchScore >= 0.80f -> Color(0xFFFFC107)
                        else               -> Color(0xFFF44336)
                    }
                )
            }
        }

        if (!hasCameraPermission) {
            Text("Camera permission is required.")
            return@Column
        }

        // ── CAMERA PREVIEW ────────────────────────────────────────────
        CameraPreview(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            showDebug = showDebug,
            onKeywords = { k, dbg -> keywords = k; lastDebug = dbg },
            onLiveVector = { vec -> liveVector = vec },
            onHandLandmarker = { hl -> handLandmarkerRef = hl }
        )

        Spacer(Modifier.height(10.dp))

        // ── STATUS ROW ────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Column {
                val statusText = if (matchedGesture != null) {
                    "Matched: \"${matchedGesture!!.label}\"  (${(matchScore * 100).toInt()}%)"
                } else {
                    "Active: $activeKeywords"
                }
                Text(
                    text     = statusText,
                    style    = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (showDebug) {
                    Text(
                        text     = lastDebug,
                        style    = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── OVERLAY IMAGE ─────────────────────────────────────────────
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            if (overlayBitmap != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap             = overlayBitmap.asImageBitmap(),
                        contentDescription = "Pose overlay",
                        modifier           = Modifier.size(120.dp)
                    )
                    // Show source tag
                    val sourceLabel = when {
                        matchedBitmap != null -> "📸 Learned match"
                        fallbackBitmap != null -> "🔑 Keyword match"
                        else -> ""
                    }
                    if (sourceLabel.isNotEmpty()) {
                        Text(
                            text  = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text  = "No gesture matched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // ── SAVE BUTTON ───────────────────────────────────────────────
        Box(
            modifier         = Modifier
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
                enabled  = overlayBitmap != null,
                modifier = Modifier.fillMaxWidth(0.6f)
            ) {
                Text("⬇ Save Image")
            }
        }

        Box(
            modifier         = Modifier
                .fillMaxWidth()
                .height(24.dp),
            contentAlignment = Alignment.Center
        ) {
            saveSuccess?.let { success ->
                Text(
                    text  = if (success) "✓ Saved!" else "✗ Failed to save",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (success) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }
    }
}

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
                            val bmp = imageProxyToUprightBitmap(imageProxy, rotation, isFrontCamera)
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

// ══════════════════════════════════════════════════════════════════════
//  UTILITY FUNCTIONS  (unchanged from original)
// ══════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalGetImage::class)
private fun imageProxyToUprightBitmap(
    imageProxy: ImageProxy,
    rotationDegrees: Int,
    mirrorX: Boolean
): Bitmap {
    val nv21     = yuv420888ToNv21(imageProxy)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
    val out      = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 90, out)
    val jpegBytes  = out.toByteArray()
    val rawBitmap  = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
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
    val width   = image.width; val height = image.height
    val yPlane  = image.planes[0]; val uPlane = image.planes[1]; val vPlane = image.planes[2]
    val yBuffer = yPlane.buffer; val uBuffer = uPlane.buffer; val vBuffer = vPlane.buffer
    yBuffer.rewind(); uBuffer.rewind(); vBuffer.rewind()
    val out = ByteArray(width * height + width * height / 2)
    var idx = 0
    for (row in 0 until height) {
        val rowStart = row * yPlane.rowStride
        for (col in 0 until width) out[idx++] = yBuffer.get(rowStart + col * yPlane.pixelStride)
    }
    for (row in 0 until height / 2) {
        val uRow = row * uPlane.rowStride; val vRow = row * vPlane.rowStride
        for (col in 0 until width / 2) {
            out[idx++] = vBuffer.get(vRow + col * vPlane.pixelStride)
            out[idx++] = uBuffer.get(uRow + col * uPlane.pixelStride)
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
            val cv = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$filename.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/MemeGenFR")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv)
            uri?.let {
                context.contentResolver.openOutputStream(it)?.use { s -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, s) }
                cv.clear()
                cv.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(it, cv, null, null)
            }
            true
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "MemeGenFR")
            dir.mkdirs()
            File(dir, "$filename.png").outputStream().use { s -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, s) }
            true
        }
    } catch (e: Exception) {
        android.util.Log.e("MemeGen", "Failed to save image", e)
        false
    }
}