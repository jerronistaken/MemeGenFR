package com.example.facialrecog

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.facialrecog.ui.library.GestureLibraryDialog
import com.example.facialrecog.ui.library.TeachButtonsRow
import com.example.facialrecog.ui.library.TeachGestureDialog
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun PoseExpressionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // Load saved gestures
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

    // Live detection state
    var keywords by remember { mutableStateOf(listOf("no_detection_yet")) }
    var lastDebug by remember { mutableStateOf("Waiting for frames...") }
    var liveVector by remember { mutableStateOf<GestureFeatureVector?>(null) }

    // Matching state
    var matchedGesture by remember { mutableStateOf<GestureFeatureVector?>(null) }
    var matchScore by remember { mutableStateOf(0f) }

    LaunchedEffect(liveVector) {
        val vec = liveVector ?: return@LaunchedEffect
        val result = GestureMatcher.findBestMatch(vec, threshold = 0.80f)
        matchedGesture = result?.first
        matchScore = result?.second ?: 0f
    }

    val matchedBitmap = remember(matchedGesture) {
        val uri = matchedGesture?.imageUri ?: return@remember null
        try {
            context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                BitmapFactory.decodeStream(it)
            }
        } catch (_: Exception) {
            null
        }
    }

    val fallbackRes = remember(keywords) { OverlayAssetResolver.resolve(keywords) }
    val fallbackBitmap = remember(fallbackRes) {
        fallbackRes?.let {
            try { BitmapFactory.decodeResource(context.resources, it) }
            catch (_: Exception) { null }
        }
    }

    val overlayBitmap = matchedBitmap ?: fallbackBitmap
    var saveSuccess by remember(overlayBitmap) { mutableStateOf<Boolean?>(null) }

    // Teach flow state
    var pendingBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var showLabelDialog by remember { mutableStateOf(false) }
    var labelInput by remember { mutableStateOf("") }
    var isExtracting by remember { mutableStateOf(false) }
    var handLandmarkerRef by remember { mutableStateOf<HandLandmarker?>(null) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        pendingUri = uri
        try {
            context.contentResolver.openInputStream(uri)?.use {
                pendingBitmap = BitmapFactory.decodeStream(it)
            }
            showLabelDialog = (pendingBitmap != null)
        } catch (_: Exception) {
            Toast.makeText(context, "Could not open image", Toast.LENGTH_SHORT).show()
        }
    }

    val activeKeywords = if (!FaceBaseline.isCalibrated) {
        "Calibrating… ${FaceBaseline.sampleCount}/${FaceBaseline.targetSamples}"
    } else {
        keywords
            .filter {
                it !in listOf(
                    "hand_detected",
                    "neutral",
                    "no_detection_yet",
                    "no_pose_face_hand",
                    "calibrating"
                )
            }
            .joinToString(", ")
            .ifEmpty { "none" }
    }

    // Dialogs
    TeachGestureDialog(
        visible = showLabelDialog,
        pendingBitmap = pendingBitmap,
        labelInput = labelInput,
        isExtracting = isExtracting,
        onLabelChange = { labelInput = it },
        onSave = {
            val bmp = pendingBitmap ?: return@TeachGestureDialog
            val uri = pendingUri ?: return@TeachGestureDialog
            isExtracting = true
            scope.launch {
                val vector = withContext(Dispatchers.IO) {
                    StaticImageExtractor.extract(
                        context = context,
                        bitmap = bmp,
                        handLandmarker = handLandmarkerRef,
                        imageUri = uri.toString(),
                        label = labelInput.trim()
                    )
                }
                GestureStore.add(context, vector)
                isExtracting = false
                showLabelDialog = false
                pendingBitmap = null
                pendingUri = null
                labelInput = ""
                Toast.makeText(context, "Gesture saved!", Toast.LENGTH_SHORT).show()
            }
        },
        onCancel = {
            showLabelDialog = false
            pendingBitmap = null
            pendingUri = null
            labelInput = ""
        }
    )

    GestureLibraryDialog(
        visible = showLibrary,
        gestures = GestureStore.all(),
        onRemove = { gesture ->
            GestureStore.remove(context, gesture.imageUri)
        },
        onClose = { showLibrary = false }
    )

    // Main UI
    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
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

        TeachButtonsRow(
            libraryCount = GestureStore.all().size,
            hasMatch = matchedGesture != null,
            matchScore = matchScore,
            onTeachClick = { imagePicker.launch("image/*") },
            onLibraryClick = { showLibrary = true }
        )

        if (!hasCameraPermission) {
            Text("Camera permission is required.")
            return@Column
        }

        CameraScreen(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp),
            showDebug = showDebug,
            onKeywords = { k, dbg ->
                keywords = k
                lastDebug = dbg
            },
            onLiveVector = { vec -> liveVector = vec },
            onHandLandmarker = { hl -> handLandmarkerRef = hl }
        )

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Column {
                val statusText = if (matchedGesture != null) {
                    "Matched: \"${matchedGesture!!.label}\" (${(matchScore * 100).toInt()}%)"
                } else {
                    "Active: $activeKeywords"
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (showDebug) {
                    Text(
                        text = lastDebug,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(140.dp),
            contentAlignment = Alignment.Center
        ) {
            if (overlayBitmap != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(
                        bitmap = overlayBitmap.asImageBitmap(),
                        contentDescription = "Pose overlay",
                        modifier = Modifier.size(120.dp)
                    )
                    val sourceLabel = when {
                        matchedBitmap != null -> "📸 Learned match"
                        fallbackBitmap != null -> "🔑 Keyword match"
                        else -> ""
                    }
                    if (sourceLabel.isNotEmpty()) {
                        Text(
                            text = sourceLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                Text(
                    text = "No gesture matched",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(8.dp))

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
                    color = if (success) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }
        }
    }
}