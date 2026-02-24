package com.example.facialrecog

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.facialrecog.ui.theme.FacialRecogTheme

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