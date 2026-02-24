package com.example.facialrecog

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

@Composable
fun PoseExpressionApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var showDebug by remember { mutableStateOf(true) }

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

        CameraScreen(
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

        OverlayResultSection(keywords = keywords)
    }
}
