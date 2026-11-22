package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.example.data.CctvRepository
import com.example.data.DevicePreferences
import com.example.security.SecurityViewModel
import com.example.ui.RoleSelectionScreen
import com.example.ui.RoleSelectionViewModel
import com.example.ui.camera.CameraHostScreen
import com.example.ui.camera.CameraHostViewModel
import com.example.ui.monitor.MonitorScreen
import com.example.ui.monitor.MonitorViewModel
import com.example.ui.monitor.QrScannerScreen
import com.example.ui.security.CalculatorDisguiseScreen
import com.example.ui.security.PinLockScreen
import com.example.ui.theme.JagaCamTheme
import com.google.firebase.firestore.FirebaseFirestore

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            JagaCamTheme {
                JagaCamRoot()
            }
        }
    }
}

private object Routes {
    const val ROLE_SELECTION = "role_selection"
    const val CAMERA_HOST = "camera_host"
    const val MONITOR_VIEWER = "monitor_viewer"
    const val QR_SCANNER = "qr_scanner"
}

@Composable
fun JagaCamRoot() {
    val securityVm: SecurityViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY])
                SecurityViewModel(DevicePreferences(app))
            }
        }
    )
    val securityState by securityVm.uiState.collectAsStateWithLifecycle()
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var hasEverUnlocked by remember { mutableStateOf(false) }

    if (securityState.isUnlocked) {
        hasEverUnlocked = true
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, currentRoute) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> securityVm.onAppBackgrounded()
                Lifecycle.Event.ON_START -> securityVm.onAppForegrounded(
                    isHostingCamera = currentRoute == Routes.CAMERA_HOST
                )
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (!securityState.isInitialized) {
            // Brief dark surface until DataStore loads so JagaCam UI never flashes before Disguise/PIN lock
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            )
        } else {
            // Keep NavHost alive once unlocked so an active Camera Host stream stays running behind the disguise overlay
            if (hasEverUnlocked || securityState.isUnlocked) {
                JagaCamNavHost(
                    navController = navController,
                    securityViewModel = securityVm
                )
            }

            if (!securityState.isUnlocked && securityState.config.isProtectionActive) {
                if (securityState.config.disguiseAsCalculator) {
                    CalculatorDisguiseScreen(
                        onTryPinUnlock = { pinCandidate ->
                            securityVm.verifyPinAndUnlock(
                                pin = pinCandidate,
                                showErrorOnMismatch = false
                            )
                        }
                    )
                } else {
                    PinLockScreen(
                        errorMessage = securityState.pinError,
                        lockoutRemainingSeconds = securityState.lockoutRemainingSeconds,
                        onClearError = { securityVm.clearPinError() },
                        onVerifyPin = { pinAttempt ->
                            securityVm.verifyPinAndUnlock(
                                pin = pinAttempt,
                                showErrorOnMismatch = true
                            )
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun JagaCamNavHost(
    navController: androidx.navigation.NavHostController,
    securityViewModel: SecurityViewModel
) {
    val securityState by securityViewModel.uiState.collectAsStateWithLifecycle()
    var pendingScannedCode by remember { mutableStateOf<String?>(null) }

    NavHost(
        navController = navController,
        startDestination = Routes.ROLE_SELECTION
    ) {
        composable(Routes.ROLE_SELECTION) {
            val roleVm: RoleSelectionViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        RoleSelectionViewModel(DevicePreferences(app))
                    }
                }
            )
            RoleSelectionScreen(
                viewModel = roleVm,
                securityViewModel = securityViewModel,
                onSelectCameraMode = {
                    navController.navigate(Routes.CAMERA_HOST)
                },
                onSelectMonitorMode = { existingId ->
                    val route = if (existingId.isNullOrBlank()) {
                        Routes.MONITOR_VIEWER
                    } else {
                        "${Routes.MONITOR_VIEWER}?roomId=$existingId"
                    }
                    navController.navigate(route)
                }
            )
        }

        composable(Routes.CAMERA_HOST) {
            val cameraVm: CameraHostViewModel = viewModel(
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        val prefs = DevicePreferences(app)
                        val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                        CameraHostViewModel(CctvRepository(db, prefs), prefs)
                    }
                }
            )
            CameraHostScreen(
                viewModel = cameraVm,
                requirePinForBlackScreen = securityState.config.hasPin &&
                    securityState.config.isProtectionActive &&
                    securityState.config.lockBlackScreenUnlock,
                onVerifyPin = { pin -> securityViewModel.verifyPinDirect(pin) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(
            route = "${Routes.MONITOR_VIEWER}?roomId={roomId}",
            arguments = listOf(navArgument("roomId") {
                type = NavType.StringType
                nullable = true
                defaultValue = null
            })
        ) { backStackEntry ->
            val initialRoomId = backStackEntry.arguments?.getString("roomId")
            val monitorVm: MonitorViewModel = viewModel(
                key = "monitor_${initialRoomId ?: "pair"}",
                factory = viewModelFactory {
                    initializer {
                        val app = checkNotNull(this[APPLICATION_KEY])
                        val prefs = DevicePreferences(app)
                        val db = FirebaseFirestore.getInstance(app.getString(R.string.firestore_database_id))
                        MonitorViewModel(CctvRepository(db, prefs), initialRoomId)
                    }
                }
            )
            MonitorScreen(
                viewModel = monitorVm,
                scannedCode = pendingScannedCode,
                onScannedCodeConsumed = { pendingScannedCode = null },
                onOpenQrScanner = { navController.navigate(Routes.QR_SCANNER) },
                onNavigateBack = { navController.popBackStack() }
            )
        }

        composable(Routes.QR_SCANNER) {
            QrScannerScreen(
                onCodeScanned = { code ->
                    pendingScannedCode = code
                    navController.popBackStack()
                },
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
