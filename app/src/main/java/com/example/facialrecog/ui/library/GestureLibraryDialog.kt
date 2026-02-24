package com.example.facialrecog.ui.library
import com.example.facialrecog.GestureFeatureVector
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun GestureLibraryDialog(
    visible: Boolean,
    gestures: List<GestureFeatureVector>,
    onRemove: (GestureFeatureVector) -> Unit,
    onClose: () -> Unit
) {
    if (!visible) return
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Gesture Library (${gestures.size})") },
        text = {
            if (gestures.isEmpty()) {
                Text("No gestures saved yet.\nUse '+ Teach' to add one.")
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(gestures) { gesture ->
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
                                    context.contentResolver
                                        .openInputStream(Uri.parse(gesture.imageUri))
                                        ?.use { BitmapFactory.decodeStream(it) }
                                } catch (_: Exception) { null }
                            }

                            if (bmp != null) {
                                Image(
                                    bitmap = bmp.asImageBitmap(),
                                    contentDescription = gesture.label,
                                    modifier = Modifier
                                        .size(80.dp)
                                        .clip(RoundedCornerShape(6.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
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
                                ) { Text("?") }
                            }

                            Spacer(Modifier.height(4.dp))

                            Text(
                                text = gesture.label,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                overflow = TextOverflow.Ellipsis
                            )

                            Spacer(Modifier.height(4.dp))

                            TextButton(
                                onClick = { onRemove(gesture) },
                                contentPadding = PaddingValues(0.dp),
                                modifier = Modifier.height(24.dp)
                            ) {
                                Text(
                                    "Remove",
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
            TextButton(onClick = onClose) { Text("Close") }
        }
    )
}