package io.github.andru1989.xmaudiostudio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import io.github.andru1989.xmaudiostudio.ui.AppScaffold
import io.github.andru1989.xmaudiostudio.ui.MainViewModel
import io.github.andru1989.xmaudiostudio.ui.theme.XmAudioStudioTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            XmAudioStudioTheme {
                AppScaffold(viewModel)
            }
        }
    }

    // The activity being on screen is the "control surface": the session acquires the MDR
    // channel while visible and releases it 20 s after the app leaves the screen.
    override fun onStart() {
        super.onStart()
        viewModel.refreshDevices()
        viewModel.setSurfaceVisible(true)
    }

    override fun onStop() {
        viewModel.setSurfaceVisible(false)
        super.onStop()
    }
}
