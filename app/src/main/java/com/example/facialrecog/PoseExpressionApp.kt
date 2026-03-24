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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.example.facialrecog.auth.AuthRepository
import com.example.facialrecog.cloud.CloudRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

@Composable
fun PoseExpressionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val authRepository = remember { AuthRepository() }
    val cloudRepository = remember { CloudRepository() }

    var currentUser by remember { mutableStateOf<FirebaseUser?>(FirebaseAuth.getInstance().currentUser) }
    var authBusy by remember { mutableStateOf(false) }
    var authError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            currentUser = auth.currentUser
        }
        authRepository.addAuthStateListener(listener)
        onDispose { authRepository.removeAuthStateListener(listener) }
    }

    if (currentUser == null) {
        AuthGate(
            isLoading = authBusy,
            errorMessage = authError,
            onLogin = { email, password ->
                scope.launch {
                    authBusy = true
                    authError = null
                    val result = authRepository.signIn(email, password)
                    authBusy = false
                    result.exceptionOrNull()?.let { authError = it.message ?: "Sign in failed" }
                }
            },
            onRegister = { email, password ->
                scope.launch {
                    authBusy = true
                    authError = null
                    val result = authRepository.register(email, password)
                    authBusy = false
                    result.exceptionOrNull()?.let { authError = it.message ?: "Registration failed" }
                }
            }
        )
        return
    }

    var localGestures by remember { mutableStateOf<List<GestureFeatureVector>>(emptyList()) }
    var communityGestures by remember { mutableStateOf<List<GestureFeatureVector>>(emptyList()) }
    var communitySyncing by remember { mutableStateOf(false) }
    var localVersion by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { GestureStore.load(context) }
        localGestures = GestureStore.all()
    }

    LaunchedEffect(currentUser?.uid) {
        communitySyncing = true
        val result = cloudRepository.fetchPublicGestures()
        communityGestures = result.getOrDefault(emptyList())
        communitySyncing = false
        result.exceptionOrNull()?.let {
            Toast.makeText(context, "Community sync failed: ${it.message}", Toast.LENGTH_SHORT).show()
        }
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
                context as ComponentActivity,
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
    var liveVector by remember { mutableStateOf<GestureFeatureVector?>(null) }

    val allCandidates by remember(localGestures, communityGestures, localVersion) {
        derivedStateOf {
            val localKeys = localGestures.map { "${it.label}|${it.imageUri}" }.toSet()
            val dedupedCommunity = communityGestures.filterNot { "${it.label}|${it.imageUri}" in localKeys }
            localGestures + dedupedCommunity
        }
    }

    var matchedGesture by remember { mutableStateOf<GestureFeatureVector?>(null) }
    var matchScore by remember { mutableStateOf(0f) }

    LaunchedEffect(liveVector, allCandidates) {
        val vec = liveVector ?: return@LaunchedEffect
        val result = GestureMatcher.findBestMatch(
            live = vec,
            threshold = 0.80f,
            candidates = allCandidates
        )
        matchedGesture = result?.first
        matchScore = result?.second ?: 0f
    }

    var matchedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(matchedGesture?.imageUri) {
        matchedBitmap = matchedGesture?.imageUri?.let { loadBitmapFromAnySource(context, it) }
    }

    val fallbackRes = remember(keywords) { OverlayAssetResolver.resolve(keywords) }
    val fallbackBitmap = remember(fallbackRes) {
        fallbackRes?.let {
            try {
                BitmapFactory.decodeResource((context as android.app.Activity).resources, it)
            } catch (_: Exception) {
                null
            }
        }
    }

    val overlayBitmap = matchedBitmap ?: fallbackBitmap
    var saveSuccess by remember(overlayBitmap) { mutableStateOf<Boolean?>(null) }

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
            context.contentResolver.openInputStream(uri)?.use { stream ->
                pendingBitmap = BitmapFactory.decodeStream(stream)
            }
            showLabelDialog = pendingBitmap != null
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

    if (showLabelDialog && pendingBitmap != null) {
        AlertDialog(
            onDismissRequest = {
                showLabelDialog = false
                pendingBitmap = null
                pendingUri = null
                labelInput = ""
            },
            title = { Text("Name this gesture") },
            text = {
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
                        value = labelInput,
                        onValueChange = { labelInput = it },
                        label = { Text("Label") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (isExtracting) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Text("Saving locally + uploading to Firebase...")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = labelInput.isNotBlank() && !isExtracting,
                    onClick = {
                        val bmp = pendingBitmap ?: return@TextButton
                        val uri = pendingUri ?: return@TextButton

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
                            localGestures = GestureStore.all()
                            localVersion++

                            val uploadResult = cloudRepository.uploadMeme(
                                localImageUri = uri.toString(),
                                vector = vector,
                                isPublic = true
                            )

                            isExtracting = false
                            showLabelDialog = false
                            pendingBitmap = null
                            pendingUri = null
                            labelInput = ""

                            if (uploadResult.isSuccess) {
                                Toast.makeText(
                                    context,
                                    "Saved locally and uploaded to Firebase",
                                    Toast.LENGTH_SHORT
                                ).show()

                                val refresh = cloudRepository.fetchPublicGestures()
                                communityGestures = refresh.getOrDefault(communityGestures)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Saved locally, but Firebase upload failed: ${uploadResult.exceptionOrNull()?.message}",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                    }
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showLabelDialog = false
                        pendingBitmap = null
                        pendingUri = null
                        labelInput = ""
                    }
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showLibrary) {
        val stored by remember(localGestures, localVersion) {
            derivedStateOf { localGestures }
        }

        AlertDialog(
            onDismissRequest = { showLibrary = false },
            title = { Text("My Local Library (${stored.size})") },
            text = {
                if (stored.isEmpty()) {
                    Text("No gestures saved yet.\nUse '+ Teach' to add one.")
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(stored) { gesture ->
                            Column(
                                modifier = Modifier
                                    .width(100.dp)
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(6.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                val bmp = remember(gesture.imageUri) {
                                    try {
                                        context.contentResolver.openInputStream(Uri.parse(gesture.imageUri))
                                            ?.use { BitmapFactory.decodeStream(it) }
                                    } catch (_: Exception) {
                                        null
                                    }
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
                                    ) {
                                        Text("?", fontSize = 32.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = gesture.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 2,
                                    textAlign = TextAlign.Center,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))

                                TextButton(
                                    onClick = {
                                        GestureStore.remove(context, gesture.imageUri)
                                        localGestures = GestureStore.all()
                                        localVersion++
                                    },
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.height(24.dp)
                                ) {
                                    Text(
                                        text = "Remove",
                                        fontSize = 10.sp,
                                        color = MaterialTheme.colorScheme.error
                                    )
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

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
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

                TextButton(
                    onClick = {
                        authError = null
                        authBusy = false
                        authRepository.signOut()}
                ) {
                    Text("Logout")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("+ Teach")
                }

                OutlinedButton(
                    onClick = { showLibrary = true },
                    modifier = Modifier.height(36.dp)
                ) {
                    Text("Library (${localGestures.size})")
                }

                Spacer(modifier = Modifier.weight(1f))

                val communityText = if (communitySyncing) {
                    "Syncing..."
                } else {
                    "Cloud ${communityGestures.size}"
                }

                Text(
                    text = communityText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!hasCameraPermission) {
                Text("Camera permission is required.")
                return@Surface
            }

            CameraPreview(
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

            Spacer(modifier = Modifier.height(10.dp))

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

            Spacer(modifier = Modifier.height(8.dp))

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
                            matchedBitmap != null -> "📸 Learned/cloud match"
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

            Spacer(modifier = Modifier.height(8.dp))

            if (matchedGesture != null) {
                Text(
                    text = "Match confidence: ${(matchScore * 100).toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        matchScore >= 0.90f -> Color(0xFF4CAF50)
                        matchScore >= 0.80f -> Color(0xFFFFC107)
                        else -> Color(0xFFF44336)
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

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
}

@Composable
private fun AuthGate(
    isLoading: Boolean,
    errorMessage: String?,
    onLogin: (email: String, password: String) -> Unit,
    onRegister: (email: String, password: String) -> Unit
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var isRegisterMode by rememberSaveable { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "MemeGenFR",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (isRegisterMode) "Create account" else "Sign in",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(24.dp))

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password (min 6 chars)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isLoading
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!errorMessage.isNullOrBlank()) {
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            Button(
                onClick = {
                    if (isRegisterMode) {
                        onRegister(email, password)
                    } else {
                        onLogin(email, password)
                    }
                },
                enabled = !isLoading && email.isNotBlank() && password.length >= 6,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    Text(if (isRegisterMode) "Create Account" else "Sign In")
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            TextButton(
                onClick = { isRegisterMode = !isRegisterMode },
                enabled = !isLoading,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    if (isRegisterMode) {
                        "Already have an account? Sign in"
                    } else {
                        "No account yet? Register"
                    }
                )
            }
        }
    }
}

private suspend fun loadBitmapFromAnySource(
    context: android.content.Context,
    source: String
): Bitmap? = withContext(Dispatchers.IO) {
    try {
        when {
            source.startsWith("http://") || source.startsWith("https://") -> {
                URL(source).openStream().use { BitmapFactory.decodeStream(it) }
            }
            else -> {
                context.contentResolver.openInputStream(Uri.parse(source))
                    ?.use { BitmapFactory.decodeStream(it) }
            }
        }
    } catch (_: Exception) {
        null
    }
}