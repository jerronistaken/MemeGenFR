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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
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
import com.google.firebase.firestore.DocumentSnapshot
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ══════════════════════════════════════════════════════════════════════
//  Helper: is this URI pointing to a remote cloud resource?
// ══════════════════════════════════════════════════════════════════════
private fun String.isRemoteUrl() = startsWith("http://") || startsWith("https://")

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
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { GestureStore.load(context) }
        localGestures = GestureStore.all()
    }

    var communityGestures by remember { mutableStateOf<List<GestureFeatureVector>>(emptyList()) }
    var communitySyncing by remember { mutableStateOf(false) }
    // Cursor for the next community page; null = first page or no more pages available.
    var communityNextCursor by remember { mutableStateOf<DocumentSnapshot?>(null) }
    var communityHasMore by remember { mutableStateOf(false) }
    var localVersion by remember { mutableIntStateOf(0) }

    // Loads the next page of public gestures and appends to communityGestures.
    // Safe to call multiple times; guards against concurrent calls via communitySyncing.
    fun loadMoreCommunityGestures() {
        if (communitySyncing) return
        scope.launch {
            communitySyncing = true
            val result = cloudRepository.fetchPublicGesturesPaged(afterCursor = communityNextCursor)
            result.onSuccess { page ->
                // Deduplicate by label before appending: if the community already
                // has an entry with the same label, keep the one already in the
                // list (newer entries win — they arrived first due to DESC order).
                val existingLabels = communityGestures.map { it.label }.toHashSet()
                val fresh = page.gestures.filterNot { it.label in existingLabels }
                communityGestures = communityGestures + fresh
                communityNextCursor = page.nextCursor
                communityHasMore = page.nextCursor != null
            }
            result.exceptionOrNull()?.let {
                android.util.Log.e("CommunitySync", "Failed to load community memes", it)
                Toast.makeText(
                    context,
                    "Community sync failed: ${it.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
            communitySyncing = false
        }
    }

    LaunchedEffect(currentUser?.uid) {
        // Reset and load the first page whenever the signed-in user changes.
        communityGestures = emptyList()
        communityNextCursor = null
        communityHasMore = false
        loadMoreCommunityGestures()
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

    // ── Merge local + community into one candidate pool ───────────────────────
    // Rules:
    //   1. Local gestures always win over community ones with the same label
    //      (the user has already claimed/taught them — use the local copy).
    //   2. Within the community pool, deduplicate by label keeping the first
    //      (newest) entry so each distinct pose appears exactly once.
    //   3. GestureMatcher.syncCache is called whenever the pool changes so
    //      stale pre-computed weighted arrays are evicted.
    val allCandidates by remember(localGestures, communityGestures, localVersion) {
        derivedStateOf {
            val localLabels = localGestures.map { it.label }.toHashSet()
            val seenCommunityLabels = HashSet<String>()
            val dedupedCommunity = communityGestures.filter { cloud ->
                cloud.label !in localLabels && seenCommunityLabels.add(cloud.label)
            }
            val merged = localGestures + dedupedCommunity
            GestureMatcher.syncCache(merged)
            merged
        }
    }

    var matchedGesture by remember { mutableStateOf<GestureFeatureVector?>(null) }
    var matchScore by remember { mutableStateOf(0f) }

    // Dispatch to Default so cosine similarity over 200 candidates
    // doesn't compete with UI work on the main thread.
    LaunchedEffect(liveVector, allCandidates) {
        val vec = liveVector ?: return@LaunchedEffect
        val result = withContext(Dispatchers.Default) {
            GestureMatcher.findBestMatch(live = vec, threshold = 0.80f, candidates = allCandidates)
        }
        matchedGesture = result?.first
        matchScore = result?.second ?: 0f
    }

    // ── Async bitmap loading for the matched gesture ──────────────────────────
    // Previously this used remember{} which calls loadBitmap on the composition
    // thread, causing ANR for remote URLs. Now it uses LaunchedEffect + IO.
    var matchedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(matchedGesture?.imageUri) {
        val uri = matchedGesture?.imageUri
        if (uri == null) { matchedBitmap = null; return@LaunchedEffect }
        matchedBitmap = withContext(Dispatchers.IO) {
            LocalImageStore.loadBitmapCached(context, uri)
        }
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

    // ── Cloud-claim state ─────────────────────────────────────────────────────
    // When the user acts out a cloud meme above threshold, we show a prompt
    // offering to save it locally. This is keyed on the matched gesture so it
    // only fires once per new cloud match.
    var claimCandidate by remember { mutableStateOf<GestureFeatureVector?>(null) }
    var claimConfidence by remember { mutableStateOf(0f) }
    var claimBitmap by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(claimCandidate?.imageUri) {
        val uri = claimCandidate?.imageUri
        if (uri == null) {
            claimBitmap = null
            return@LaunchedEffect
        }

        claimBitmap = withContext(Dispatchers.IO) {
            LocalImageStore.loadBitmapCached(context, uri)
        }
    }

    var isClaiming by remember { mutableStateOf(false) }

    // Trigger the claim prompt whenever a high-confidence cloud match appears
    // that the user doesn't already own locally.

    // True when the matched gesture's label exists in the community pool — even
    // if the local copy won the vector match (because deduplication prefers the
    // local entry but the gesture still originated from the cloud).
    val hasCloudCounterpart = matchedGesture?.let { mg ->
        communityGestures.any { it.label == mg.label }
    } ?: false

    // A "cloud match" is any match whose gesture has a cloud counterpart,
    // regardless of whether the winning candidate's imageUri is remote or local.
    val isCloudMatch = hasCloudCounterpart

    // The user already owns it locally only if they have a non-remote URI for
    // this label (i.e. they've previously claimed or self-taught it).
    val alreadyOwned = matchedGesture?.let { mg ->
        localGestures.any { it.label == mg.label && !it.imageUri.isRemoteUrl() }
    } ?: false

    // For the claim dialog we need the cloud vector (with the remote imageUri)
    // so we can download it, not the local deduped copy.
    val cloudCounterpart = matchedGesture?.let { mg ->
        communityGestures.firstOrNull { it.label == mg.label }
    }

    LaunchedEffect(matchedGesture?.label, matchScore) {
        if (
            isCloudMatch &&
            !alreadyOwned &&
            matchScore >= 0.85f
        ) {

            // If new match OR higher score → update
            if (claimCandidate == null || matchScore > claimConfidence) {
                claimCandidate = cloudCounterpart ?: matchedGesture
                claimConfidence = matchScore
            }
        }
    }

    // ── Cloud-unlock progress (live confidence toward the 0.85 threshold) ───────
    // Shows a progress bar + blurred teaser for unowned cloud gestures that are
    // partially matched — letting the user know they're getting close before the
    // full claim dialog fires at 85%.
    var cloudMatchProgress by remember { mutableStateOf(0f) }
    var cloudMatchCandidate by remember { mutableStateOf<GestureFeatureVector?>(null) }

    LaunchedEffect(liveVector, communityGestures) {
        val vec = liveVector ?: return@LaunchedEffect
        if (communityGestures.isEmpty()) return@LaunchedEffect
        val unowned = communityGestures.filter { cloud ->
            localGestures.none { it.label == cloud.label && !it.imageUri.isRemoteUrl() }
        }
        val best = withContext(Dispatchers.Default) {
            GestureMatcher.findBestMatch(live = vec, threshold = 0.40f, candidates = unowned)
        }
        cloudMatchProgress = best?.second ?: 0f
        cloudMatchCandidate = best?.first
    }

    // Async load of the teaser bitmap for the progress bar.
    var teaserBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(cloudMatchCandidate?.imageUri) {
        val uri = cloudMatchCandidate?.imageUri
        if (uri == null) { teaserBitmap = null; return@LaunchedEffect }
        teaserBitmap = withContext(Dispatchers.IO) {
            LocalImageStore.loadBitmapCached(context, uri)
        }
    }

    // ── Teach / upload state ──────────────────────────────────────────────────
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

    // ══════════════════════════════════════════════════════════════════════════
    //  DIALOG: Claim a matched cloud meme
    // ══════════════════════════════════════════════════════════════════════════
    val candidate = claimCandidate
    if (candidate != null && !isClaiming) {
        AlertDialog(
            onDismissRequest = { claimCandidate = null
                claimBitmap = null},
            title = { Text("☁ Unlock Cloud Meme") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "You matched \"${candidate.label}\" from the community pool " +
                                "(${(claimConfidence * 100).toInt()}% confidence)"
                    )
                    if (claimBitmap != null) {
                        Image(
                            bitmap = claimBitmap!!.asImageBitmap(),
                            contentDescription = candidate.label,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        }
                    }
                    Text(
                        text = "Save this meme to your local library?",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        isClaiming = true
                        scope.launch {
                            // 1. Download the remote image and persist it locally.
                            val localPath = withContext(Dispatchers.IO) {
                                LocalImageStore.downloadAndSave(
                                    context = context,
                                    remoteUrl = candidate.imageUri,
                                    prefix = "cloud_${candidate.label}"
                                )
                            }

                            if (localPath == null) {
                                Toast.makeText(
                                    context,
                                    "Failed to download image — check your connection",
                                    Toast.LENGTH_SHORT
                                ).show()
                                isClaiming = false
                                claimCandidate = null
                                claimBitmap = null
                                claimConfidence = 0f
                                return@launch
                            }

                            // 2. Re-point the feature vector at the local path and save.
                            val localVector = candidate.copy(imageUri = localPath)
                            withContext(Dispatchers.IO) {
                                GestureStore.add(context, localVector)
                            }
                            localGestures = GestureStore.all()
                            localVersion++

                            isClaiming = false
                            claimCandidate = null
                            claimConfidence = 0f
                            claimBitmap = null

                            Toast.makeText(
                                context,
                                "\"${candidate.label}\" saved to your library!",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                ) {
                    Text("Save to Library")
                }
            },
            dismissButton = {
                TextButton(onClick = { claimCandidate = null
                    claimBitmap = null
                    claimConfidence = 0f}) {
                    Text("Not now")
                }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════════════
    //  DIALOG: Teach a new gesture
    // ══════════════════════════════════════════════════════════════════════════
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
                            val localImagePath = withContext(Dispatchers.IO) {
                                LocalImageStore.saveBitmapToInternalStorage(
                                    context = context,
                                    bitmap = bmp
                                )
                            }

                            val vector = withContext(Dispatchers.IO) {
                                StaticImageExtractor.extract(
                                    context = context,
                                    bitmap = bmp,
                                    handLandmarker = handLandmarkerRef,
                                    imageUri = localImagePath,
                                    label = labelInput.trim()
                                )
                            }

                            GestureStore.add(context, vector)
                            localGestures = GestureStore.all()
                            localVersion++

                            val uploadResult = cloudRepository.uploadMeme(
                                localImageUri = localImagePath,
                                vector = vector.copy(imageUri = localImagePath),
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

                                communityGestures = emptyList()
                                communityNextCursor = null
                                communityHasMore = false
                                loadMoreCommunityGestures()
                            } else {
                                val e = uploadResult.exceptionOrNull()
                                android.util.Log.e("FirebaseUpload", "Upload failed", e)
                                Toast.makeText(
                                    context,
                                    "Saved locally, but Firebase upload failed: ${e?.message}",
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

    // ══════════════════════════════════════════════════════════════════════════
    //  DIALOG: Local library browser
    // ══════════════════════════════════════════════════════════════════════════
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
                                // ── Async bitmap load per library card ────────
                                var cardBitmap by remember(gesture.imageUri) {
                                    mutableStateOf<Bitmap?>(null)
                                }
                                LaunchedEffect(gesture.imageUri) {
                                    cardBitmap = withContext(Dispatchers.IO) {
                                        LocalImageStore.loadBitmapCached(context, gesture.imageUri)
                                    }
                                }

                                if (cardBitmap != null) {
                                    Image(
                                        bitmap = cardBitmap!!.asImageBitmap(),
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
                                        BitmapCache.evict(gesture.imageUri)
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

    // ══════════════════════════════════════════════════════════════════════════
    //  MAIN SCREEN
    // ══════════════════════════════════════════════════════════════════════════
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
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Image(
                        painter = androidx.compose.ui.res.painterResource(id = R.drawable.icon),
                        contentDescription = "App Icon",
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                    )
                }

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
                        BitmapCache.clear()
                        authRepository.signOut()
                    }
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

                when {
                    communitySyncing -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp)
                            Text(
                                text = "Syncing…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    communityHasMore -> {
                        TextButton(
                            onClick = { loadMoreCommunityGestures() },
                            modifier = Modifier.height(36.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Text(
                                text = "☁ ${communityGestures.size}  Load more",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                    else -> {
                        Text(
                            text = "☁ ${communityGestures.size}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── Cloud-unlock progress panel ───────────────────────────────────
            // Shows when a community gesture is 40–84% matched but not yet owned.
            // Blurred teaser reveals itself as confidence rises; claim dialog
            // fires automatically at 85%.
            val showUnlockPanel = cloudMatchProgress >= 0.40f
                    && cloudMatchProgress < 0.85f
                    && (matchedGesture == null || (isCloudMatch && !alreadyOwned))
                    && cloudMatchCandidate != null

            if (showUnlockPanel) {
                val unlockCandidate = cloudMatchCandidate!!
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.92f),
                            RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Blurred teaser thumbnail — clears as confidence rises
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (teaserBitmap != null) {
                                val blurDp = ((1f - (cloudMatchProgress - 0.40f) / 0.45f)
                                    .coerceIn(0f, 1f) * 16f).dp
                                Image(
                                    bitmap = teaserBitmap!!.asImageBitmap(),
                                    contentDescription = "Locked cloud image",
                                    modifier = Modifier.fillMaxSize().blur(blurDp),
                                    contentScale = ContentScale.Crop
                                )
                                val lockAlpha = (1f - (cloudMatchProgress - 0.40f) / 0.45f)
                                    .coerceIn(0f, 1f)
                                Text("🔒", fontSize = 20.sp,
                                    modifier = Modifier.alpha(lockAlpha))
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant,
                                            RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) { Text("🔒", fontSize = 20.sp) }
                            }
                        }

                        // Label + progress bar
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "☁ Unlock \"${unlockCandidate.label}\"",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { cloudMatchProgress / 0.85f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = when {
                                    cloudMatchProgress >= 0.75f -> Color(0xFF4CAF50)
                                    cloudMatchProgress >= 0.60f -> Color(0xFFFFC107)
                                    else -> MaterialTheme.colorScheme.primary
                                }
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Reenact the pose  •  " +
                                        "${(cloudMatchProgress * 100).toInt()}% / 85%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                                    .copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ── Matched overlay ───────────────────────────────────────────────
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
                            hasCloudCounterpart && !alreadyOwned -> "☁ Cloud match — act it out to unlock!"
                            hasCloudCounterpart && alreadyOwned  -> "☁ Cloud match (owned locally)"
                            matchedBitmap != null                -> "📸 Learned local match"
                            fallbackBitmap != null               -> "🔑 Keyword match"
                            else                                 -> ""
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
                                if (saved) "Saved to Pictures/WhatsThatMeme!" else "Save failed",
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

// ══════════════════════════════════════════════════════════════════════════════
//  AUTH GATE (unchanged)
// ══════════════════════════════════════════════════════════════════════════════
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
                text = "Whats That Meme",
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
                    if (isRegisterMode) onRegister(email, password)
                    else onLogin(email, password)
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