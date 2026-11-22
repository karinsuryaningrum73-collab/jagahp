package com.example.ui.camera

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.RoomStatus
import com.example.data.VideoResolution
import com.example.ui.security.PinVerifyDialog
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.JetBrainsMonoFontFamily
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer

@Composable
fun CameraHostScreen(
    viewModel: CameraHostViewModel,
    requirePinForBlackScreen: Boolean,
    onVerifyPin: suspend (String) -> Boolean,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showQrDialog by remember { mutableStateOf(false) }
    var showBlackScreenPinPrompt by remember { mutableStateOf(false) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    var hasCameraAndMic by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val camOk = results[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val micOk = results[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        hasCameraAndMic = camOk && micOk
    }

    LaunchedEffect(hasCameraAndMic) {
        if (hasCameraAndMic) {
            viewModel.startCameraSession(context, lifecycleOwner)
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    // Window brightness & keep-screen-on control for extreme OLED/LCD power saving
    DisposableEffect(uiState.blackScreenEnabled, context) {
        val window = (context as? Activity)?.window
        val originalBrightness = window?.attributes?.screenBrightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (window != null) {
            if (uiState.blackScreenEnabled) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = window.attributes
                lp.screenBrightness = 0.01f
                window.attributes = lp
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = window.attributes
                lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                window.attributes = lp
            }
        }
        onDispose {
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                val lp = window.attributes
                lp.screenBrightness = originalBrightness
                window.attributes = lp
            }
        }
    }

    BackHandler {
        if (uiState.blackScreenEnabled) {
            if (requirePinForBlackScreen) {
                showBlackScreenPinPrompt = true
            } else {
                viewModel.toggleBlackScreen(false)
            }
        } else {
            viewModel.stopSession(context)
            onNavigateBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        if (!hasCameraAndMic) {
            PermissionPromptView(
                title = stringResource(R.string.permission_required_title),
                description = stringResource(R.string.permission_camera_desc),
                onGrantClick = { permissionLauncher.launch(requiredPermissions) },
                onBackClick = onNavigateBack
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .widthIn(max = 640.dp)
                    .align(Alignment.TopCenter)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Top Bar
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                viewModel.stopSession(context)
                                onNavigateBack()
                            },
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("camera_back_button")
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Kembali",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                        Column {
                            Text(
                                text = "Mode Kamera CCTV",
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = statusLabel(uiState.status),
                                style = MaterialTheme.typography.labelMedium,
                                color = statusColor(uiState.status)
                            )
                        }
                    }

                    // Battery & Temp chip
                    Surface(
                        color = if (uiState.battery.percent < 15) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(
                            1.dp,
                            if (uiState.battery.percent < 15) AlertRed else MaterialTheme.colorScheme.outline
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = when {
                                    uiState.battery.charging -> Icons.Default.BatteryChargingFull
                                    uiState.battery.percent < 15 -> Icons.Default.BatteryAlert
                                    else -> Icons.Default.BatteryStd
                                },
                                contentDescription = "Baterai",
                                tint = if (uiState.battery.percent < 15) AlertRed else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "${uiState.battery.percent}% • ${uiState.battery.temperatureCelsius}°C",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }

                if (uiState.errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = uiState.errorMessage.orEmpty(),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                // Pairing Code + QR Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.pairing_code_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = uiState.roomId.ifBlank { "--------" },
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.testTag("camera_room_code")
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Kode Pairing", uiState.roomId))
                                        Toast.makeText(context, "Kode disalin: ${uiState.roomId}", Toast.LENGTH_SHORT).show()
                                    },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("copy_room_code_button")
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("Salin")
                                }
                                OutlinedButton(
                                    onClick = { showQrDialog = true },
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("show_qr_button")
                                ) {
                                    Icon(Icons.Default.QrCode2, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.size(6.dp))
                                    Text("QR Code")
                                }
                            }
                        }

                        if (uiState.qrBitmap != null) {
                            Surface(
                                onClick = { showQrDialog = true },
                                color = Color.White,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier
                                    .size(92.dp)
                                    .testTag("qr_thumbnail")
                            ) {
                                Image(
                                    bitmap = uiState.qrBitmap!!.asImageBitmap(),
                                    contentDescription = "QR Code Pairing JagaCam",
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(6.dp)
                                )
                            }
                        }
                    }
                }

                // Local Camera Preview (hidden/detached when Black Screen is ON to save GPU power)
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.Black),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(210.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        val engine = viewModel.webRtcEngine
                        if (engine != null && !uiState.isStarting && !uiState.blackScreenEnabled) {
                            LocalWebRtcPreview(
                                engine = engine,
                                mirror = uiState.cameraFacing == com.example.data.CameraFacing.FRONT,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }

                        // Live badge
                        Surface(
                            color = Color.Black.copy(alpha = 0.72f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(statusColor(uiState.status), CircleShape)
                                )
                                Text(
                                    text = "${uiState.resolution.label} • ${uiState.targetFps} FPS",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // HUGE Black Screen Power-Saver Trigger Button
                Button(
                    onClick = { viewModel.toggleBlackScreen(true) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(68.dp)
                        .testTag("activate_black_screen_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.DarkMode,
                        contentDescription = null,
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.size(10.dp))
                    Text(
                        text = stringResource(R.string.black_screen_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Large Touch-Friendly Hardware Controls
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LargeControlButton(
                        icon = Icons.Default.Cameraswitch,
                        label = if (uiState.cameraFacing == com.example.data.CameraFacing.BACK) "Kamera Belakang" else "Kamera Depan",
                        active = false,
                        testTag = "camera_flip_button",
                        onClick = { viewModel.toggleCameraFacing() },
                        modifier = Modifier.weight(1f)
                    )
                    LargeControlButton(
                        icon = if (uiState.flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        label = if (uiState.flashEnabled) "Senter Nyala" else "Senter Mati",
                        active = uiState.flashEnabled,
                        enabled = uiState.hasFlashUnit && uiState.cameraFacing == com.example.data.CameraFacing.BACK,
                        testTag = "camera_flash_button",
                        onClick = { viewModel.toggleFlash() },
                        modifier = Modifier.weight(1f)
                    )
                    LargeControlButton(
                        icon = if (uiState.audioMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (uiState.audioMuted) "Mikrofon Bisu" else "Mikrofon Aktif",
                        active = !uiState.audioMuted,
                        testTag = "camera_mic_button",
                        onClick = { viewModel.toggleAudioMute() },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Power-Saving Quality & Motion Detection Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            text = "Penghemat Daya Ekstrem",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Resolution & FPS Presets
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PresetButton(
                                label = "480p\n15 FPS",
                                selected = uiState.resolution == VideoResolution.SD_480P && uiState.targetFps == 15,
                                testTag = "preset_480p_15",
                                onClick = { viewModel.setResolutionAndFps(VideoResolution.SD_480P, 15) },
                                modifier = Modifier.weight(1f)
                            )
                            PresetButton(
                                label = "720p\n15 FPS",
                                selected = uiState.resolution == VideoResolution.HD_720P && uiState.targetFps == 15,
                                testTag = "preset_720p_15",
                                onClick = { viewModel.setResolutionAndFps(VideoResolution.HD_720P, 15) },
                                modifier = Modifier.weight(1f)
                            )
                            PresetButton(
                                label = "720p\n30 FPS",
                                selected = uiState.resolution == VideoResolution.HD_720P && uiState.targetFps == 30,
                                testTag = "preset_720p_30",
                                onClick = { viewModel.setResolutionAndFps(VideoResolution.HD_720P, 30) },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Motion Detection Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsRun,
                                    contentDescription = null,
                                    tint = if (uiState.motionActive) AmberTertiary else MaterialTheme.colorScheme.primary
                                )
                                Column {
                                    Text(
                                        text = "Deteksi Gerakan (Hemat Data & CPU)",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = when {
                                            !uiState.motionDetectionEnabled -> "Streaming video terus-menerus"
                                            uiState.motionActive -> "Gerakan terdeteksi — mengirim penuh"
                                            else -> "Siaga hemat daya — menunggu gerakan"
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (uiState.motionActive) AmberTertiary else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Switch(
                                checked = uiState.motionDetectionEnabled,
                                onCheckedChange = { viewModel.toggleMotionDetection() },
                                modifier = Modifier.testTag("camera_motion_switch")
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Full OLED Black Screen Overlay
        AnimatedVisibility(
            visible = uiState.blackScreenEnabled,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            BlackScreenOverlay(
                roomCode = uiState.roomId,
                batteryPercent = uiState.battery.percent,
                motionActive = uiState.motionActive,
                onUnlock = {
                    if (requirePinForBlackScreen) {
                        showBlackScreenPinPrompt = true
                    } else {
                        viewModel.toggleBlackScreen(false)
                    }
                }
            )
        }
    }

    if (showBlackScreenPinPrompt && uiState.blackScreenEnabled) {
        PinVerifyDialog(
            title = "Layar Hitam Terkunci",
            subtitle = "Masukkan PIN rahasia untuk membuka kontrol kamera.",
            onDismiss = { showBlackScreenPinPrompt = false },
            onVerifyPin = onVerifyPin,
            onVerified = {
                showBlackScreenPinPrompt = false
                viewModel.toggleBlackScreen(false)
            }
        )
    }

    if (showQrDialog && uiState.qrBitmap != null) {
        Dialog(onDismissRequest = { showQrDialog = false }) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "Pindai QR dari HP Pemantau",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(color = Color.White, shape = RoundedCornerShape(16.dp)) {
                        Image(
                            bitmap = uiState.qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR Code Ruangan ${uiState.roomId}",
                            modifier = Modifier
                                .size(240.dp)
                                .padding(12.dp)
                        )
                    }
                    Text(
                        text = uiState.roomId,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Button(
                        onClick = { showQrDialog = false },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp)
                    ) {
                        Text("Tutup")
                    }
                }
            }
        }
    }
}

@Composable
private fun BlackScreenOverlay(
    roomCode: String,
    batteryPercent: Int,
    motionActive: Boolean,
    onUnlock: () -> Unit
) {
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(isHolding) {
        if (isHolding) {
            val steps = 30
            for (i in 1..steps) {
                delay(50L)
                holdProgress = i / steps.toFloat()
            }
            onUnlock()
            isHolding = false
            holdProgress = 0f
        } else {
            holdProgress = 0f
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        isHolding = true
                        tryAwaitRelease()
                        isHolding = false
                    }
                )
            }
            .testTag("black_screen_overlay"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.LockOpen,
                contentDescription = "Layar Hitam Aktif",
                tint = Color(0xFF2B3544),
                modifier = Modifier.size(36.dp)
            )
            Text(
                text = "JagaCam Berjalan • $roomCode • $batteryPercent%",
                style = MaterialTheme.typography.labelMedium,
                color = Color(0xFF334155)
            )
            if (motionActive) {
                Text(
                    text = "• GERAKAN TERDETEKSI •",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF10B981).copy(alpha = 0.55f)
                )
            }
            Text(
                text = stringResource(R.string.black_screen_unlock_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF475569),
                textAlign = TextAlign.Center
            )
            if (isHolding) {
                LinearProgressIndicator(
                    progress = { holdProgress },
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color(0xFF1E293B),
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

@Composable
private fun LocalWebRtcPreview(
    engine: com.example.webrtc.WebRtcEngine,
    mirror: Boolean,
    modifier: Modifier = Modifier
) {
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(engine) {
        onDispose {
            rendererRef?.let {
                engine.removeLocalPreviewSink(it)
                it.release()
            }
            rendererRef = null
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(engine.rootEglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
                setMirror(mirror)
                setEnableHardwareScaler(true)
                engine.addLocalPreviewSink(this)
                rendererRef = this
            }
        },
        update = { view ->
            view.setMirror(mirror)
        },
        modifier = modifier
    )
}

@Composable
fun PermissionPromptView(
    title: String,
    description: String,
    onGrantClick: () -> Unit,
    onBackClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 480.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp)
                )
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onGrantClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("grant_permission_button")
                ) {
                    Text(stringResource(R.string.grant_permission_button))
                }
                OutlinedButton(
                    onClick = onBackClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Text("Kembali")
                }
            }
        }
    }
}

@Composable
private fun LargeControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = when {
            !enabled -> MaterialTheme.colorScheme.surface.copy(alpha = 0.5f)
            active -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        border = BorderStroke(
            1.dp,
            if (active && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .heightIn(min = 84.dp)
            .testTag(testTag)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(26.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun PresetButton(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
            .heightIn(min = 60.dp)
            .testTag(testTag)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun statusLabel(status: RoomStatus): String = when (status) {
    RoomStatus.WAITING -> "Menunggu HP Pemantau terhubung"
    RoomStatus.STREAMING -> "Siaran Langsung Aktif"
    RoomStatus.PAUSED_MOTION -> "Hemat Daya • Menunggu Gerakan"
    RoomStatus.OFFLINE -> "Offline"
}

@Composable
private fun statusColor(status: RoomStatus): Color = when (status) {
    RoomStatus.WAITING -> MaterialTheme.colorScheme.secondary
    RoomStatus.STREAMING -> MaterialTheme.colorScheme.primary
    RoomStatus.PAUSED_MOTION -> AmberTertiary
    RoomStatus.OFFLINE -> AlertRed
}
