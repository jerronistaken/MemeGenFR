package com.example.facialrecog

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Displays the meme/sticker overlay image that corresponds to the active keywords,
 * or a short text summary if no overlay is mapped to the current gesture.
 */
@Composable
fun OverlayResultSection(keywords: List<String>) {
    val context = LocalContext.current

    val resolvedRes = remember(keywords) { OverlayAssetResolver.resolve(keywords) }
    val overlayBitmap = remember(resolvedRes) {
        resolvedRes?.let { OverlayAssetResolver.loadBitmap(context, it) }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (overlayBitmap != null) {
            Image(
                bitmap = overlayBitmap.asImageBitmap(),
                contentDescription = "Pose overlay",
                modifier = Modifier
                    .size(160.dp)
                    .align(Alignment.CenterHorizontally)
            )
        } else {
            val activeKeywords = keywords
                .filter { it != "hand_detected" && it != "neutral" }
                .joinToString(", ")
                .ifEmpty { "none" }

            Text(
                text = "Active: $activeKeywords",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}