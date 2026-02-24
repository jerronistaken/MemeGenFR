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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.facialrecog.CameraPreview
import com.example.facialrecog.FaceBaseline
import com.example.facialrecog.GestureFeatureVector
import com.example.facialrecog.GestureMatcher
import com.example.facialrecog.GestureStore
import com.example.facialrecog.OverlayAssetResolver
import com.example.facialrecog.StaticImageExtractor
import com.example.facialrecog.saveImageToGallery
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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