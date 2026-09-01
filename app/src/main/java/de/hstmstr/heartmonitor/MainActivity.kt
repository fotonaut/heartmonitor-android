package de.hstmstr.heartmonitor

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import de.hstmstr.heartmonitor.ui.HeartRateScreen
import de.hstmstr.heartmonitor.ui.HeartRateViewModel
import de.hstmstr.heartmonitor.ui.RecordingsScreen
import de.hstmstr.heartmonitor.ui.theme.HeartMonitorTheme
import java.io.File

private enum class Screen { MAIN, RECORDINGS }

class MainActivity : ComponentActivity() {

    private val viewModel: HeartRateViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HeartMonitorTheme {
                val state by viewModel.uiState.collectAsState()
                var screen by remember { mutableStateOf(Screen.MAIN) }
                var recordings by remember { mutableStateOf(emptyList<File>()) }

                // Keep the screen awake while a recording is running.
                DisposableEffect(state.isRecording) {
                    if (state.isRecording) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                    onDispose {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                val notificationPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { /* best effort – recording still works without the notification */ }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                val blePermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { result ->
                    viewModel.onPermissionsResult(result.values.all { it })
                }

                when (screen) {
                    Screen.MAIN -> Surface {
                        HeartRateScreen(
                            state = state,
                            onConnectClick = {
                                val needsPermission = !state.isConnected &&
                                    !state.isBusy &&
                                    !viewModel.hasAllPermissions()
                                if (needsPermission) {
                                    blePermissionLauncher.launch(viewModel.requiredPermissions())
                                } else {
                                    viewModel.onConnectButtonClicked()
                                }
                            },
                            onRecordClick = viewModel::onRecordButtonClicked,
                            onMessageShown = viewModel::consumeMessage,
                            onOpenRecordings = {
                                recordings = viewModel.listRecordings()
                                screen = Screen.RECORDINGS
                            },
                        )
                    }

                    Screen.RECORDINGS -> Surface {
                        RecordingsScreen(
                            recordings = recordings,
                            onShare = { file ->
                                startActivity(
                                    Intent.createChooser(viewModel.shareIntentFor(file), "CSV teilen"),
                                )
                            },
                            onDelete = { file ->
                                viewModel.deleteRecording(file)
                                recordings = viewModel.listRecordings()
                            },
                            onBack = { screen = Screen.MAIN },
                        )
                    }
                }
            }
        }
    }
}
