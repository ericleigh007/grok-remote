package com.xai.grokremote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.xai.grokremote.ui.GrokRemoteRoot
import com.xai.grokremote.ui.theme.GrokRemoteTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val pairUri = intent?.data
        setContent {
            GrokRemoteTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    GrokRemoteRoot(initialPairUri = pairUri?.toString())
                }
            }
        }
    }
}
