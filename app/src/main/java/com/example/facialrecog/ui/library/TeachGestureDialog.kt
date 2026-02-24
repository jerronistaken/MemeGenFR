package com.example.facialrecog.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp

@Composable
fun TeachGestureDialog(
    visible: Boolean,
    pendingBitmap: Bitmap?,
    labelInput: String,
    isExtracting: Boolean,
    onLabelChange: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    if (!visible || pendingBitmap == null) return

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Name this gesture") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Image(
                    bitmap = pendingBitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(160.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Fit
                )

                OutlinedTextField(
                    value = labelInput,
                    onValueChange = onLabelChange,
                    label = { Text("Label (e.g. 'thumbs up')") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (isExtracting) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text("Extracting features…", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = labelInput.isNotBlank() && !isExtracting
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}