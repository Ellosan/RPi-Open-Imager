package dev.openimager

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.openimager.ui.ImagerViewModel
import dev.openimager.ui.screens.ImagerApp
import dev.openimager.ui.theme.OpenImagerTheme

class MainActivity : ComponentActivity() {

    private val viewModel: ImagerViewModel by viewModels()

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* progress only */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            OpenImagerTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                ImagerApp(state = state, viewModel = viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStorage()
    }
}
