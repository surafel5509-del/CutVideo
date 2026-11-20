package com.cutvideo.editor

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.cutvideo.editor.di.AppContainer
import com.cutvideo.editor.ui.CutVideoRoot
import com.cutvideo.editor.ui.theme.CutVideoTheme

class MainActivity : ComponentActivity() {

    private val container: AppContainer
        get() = (application as CutVideoApp).container

    private var incomingUri by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        incomingUri = extractUri(intent)

        setContent {
            CutVideoTheme {
                Surface(modifier = Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFF101014))) {
                    CutVideoRoot(
                        container = container,
                        incomingMediaUri = incomingUri,
                        onIncomingMediaConsumed = { incomingUri = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        incomingUri = extractUri(intent)
    }

    private fun extractUri(intent: Intent?): Uri? {
        intent ?: return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
            else -> null
        }
    }
}
