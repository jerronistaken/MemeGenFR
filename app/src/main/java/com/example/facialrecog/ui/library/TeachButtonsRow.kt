package com.example.facialrecog.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TeachButtonsRow(
    libraryCount: Int,
    hasMatch: Boolean,
    matchScore: Float,
    onTeachClick: () -> Unit,
    onLibraryClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Button(
            onClick = onTeachClick,
            modifier = Modifier.height(36.dp)
        ) { Text("+ Teach") }

        OutlinedButton(
            onClick = onLibraryClick,
            modifier = Modifier.height(36.dp)
        ) { Text("Library ($libraryCount)") }

        Spacer(Modifier.weight(1f))

        if (hasMatch) {
            Text(
                text = "Match: ${(matchScore * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}