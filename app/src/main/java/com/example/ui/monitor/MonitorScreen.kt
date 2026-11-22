package com.example.ui.monitor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.data.CameraFacing
import com.example.data.CctvRoom
import com.example.data.RoomStatus
import com.example.data.VideoResolution
import com.example.ui.theme.AlertRed
import com.example.ui.theme.AmberTertiary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.webrtc.WebRtcEngine
import org.webrtc.PeerConnection
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import java.text.SimpleDateFormat
import java.util.Locale

@Composable
fun MonitorScreen(
    viewModel: MonitorViewModel,
    scannedCode: String?,
    onScannedCodeConsumed: () -> Unit,
    onOpenQrScanner: () -> Unit,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.ensureEngineAndJoinIfInitial(context)
    }

    LaunchedEffect(scannedCode) {
        if (!scannedCode.isNullOrBlank()) {
            viewModel.connectToRoom(scannedCode, context)
            onScannedCodeConsumed()
        }
    }

    BackHandler {
        if (uiState.room != null) {
            viewModel.disconnectRoom()
        } else {
            onNavigateBack()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .safeDrawingPadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        val room = uiState.room
        if (room == null) {
            PairToRoomView(
                initialInput = uiState.activeRoomId,
                isConnecting = uiState.isConnecting,
                errorMessage = uiState.errorMessage,
                onConnectClick = { code -> viewModel.connectToRoom(code, context) },
                onScanQrClick = onOpenQrScanner,
                onBackClick = onNavigateBack
            )
        } else {
            LiveMonitorDashboard(
                room = room,
                uiState = uiState,
                engine = viewModel.webRtcEngine,
                onBack = {
                    viewModel.disconnectRoom()
                    onNavigateBack()
                },
                onReconnect = { viewModel.requestReconnect() },
                onToggleSpeaker = { viewModel.toggleSpeakerMute(context) },
                onFlipCamera = { viewModel.flipRemoteCamera() },
                onToggleFlash = { viewModel.toggleRemoteFlash() },
                onToggleBlackScreen = { viewModel.toggleRemoteBlackScreen() },
                onToggleMotion = { viewModel.toggleRemoteMotionDetection() },
                onSelectQuality = { res, fps -> viewModel.setRemoteResolutionAndFps(res, fps) }
            )
        }
    }
}

@Composable
private fun PairToRoomView(
    initialInput: String,
    isConnecting: Boolean,
    errorMessage: String?,
    onConnectClick: (String) -> Unit,
    onScanQrClick: () -> Unit,
    onBackClick: () -> Unit
) {
    var codeInput by remember(initialInput) { mutableStateOf(initialInput) }

    Column(
        modifier = Modifier
            .widthIn(max = 560.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBackClick,
                modifier = Modifier
                    .minimumInteractiveComponentSize()
                    .testTag("monitor_pair_back_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Kembali",
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text = "Mode Pemantau CCTV",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        // Giant QR Scanner CTA
        Card(
            onClick = onScanQrClick,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("open_qr_scanner_button")
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(22.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier
                            .padding(14.dp)
                            .size(34.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Scan QR Code Kamera",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Hubungkan instan dengan memindai QR Code di layar HP Kamera",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.85f)
                    )
                }
            }
        }

        // Manual Room Code Input Card
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "Atau Masukkan Kode Pairing",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                OutlinedTextField(
                    value = codeInput,
                    onValueChange = { raw ->
                        codeInput = raw.uppercase().filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(16)
                    },
                    label = { Text("Kode Ruangan (mis. JG4K8M2P)") },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.headlineSmall.copy(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("room_code_input")
                )

                if (errorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }

                Button(
                    onClick = { onConnectClick(codeInput) },
                    enabled = !isConnecting && codeInput.length >= 6,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .testTag("connect_room_button")
                ) {
                    if (isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Icon(Icons.Default.Link, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "Pantau Sekarang",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveMonitorDashboard(
    room: CctvRoom,
    uiState: MonitorUiState,
    engine: WebRtcEngine?,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onToggleSpeaker: () -> Unit,
    onFlipCamera: () -> Unit,
    onToggleFlash: () -> Unit,
    onToggleBlackScreen: () -> Unit,
    onToggleMotion: () -> Unit,
    onSelectQuality: (VideoResolution, Int) -> Unit
) {
    Column(
        modifier = Modifier
            .widthIn(max = 720.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("monitor_live_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Kembali",
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Column {
                    Text(
                        text = room.cameraName,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Text(
                        text = "Kode: ${room.id} • ${connectionLabel(uiState.peerState, room.parsedStatus)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.peerState == PeerConnection.PeerConnectionState.CONNECTED) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondary
                        }
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    IconButton(
                        onClick = onToggleSpeaker,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("monitor_speaker_button")
                    ) {
                        Icon(
                            imageVector = if (uiState.speakerMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "Audio Pemantau",
                            tint = if (uiState.speakerMuted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    IconButton(
                        onClick = onReconnect,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("monitor_reconnect_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Sambungkan Ulang",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        // CRITICAL <15% BATTERY ALERT BANNER
        if (room.isLowBattery) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                border = BorderStroke(1.5.dp, AlertRed),
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("low_battery_warning_banner")
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Peringatan Baterai Lemah",
                        tint = AlertRed,
                        modifier = Modifier.size(28.dp)
                    )
                    Text(
                        text = "PERINGATAN: Baterai HP Kamera tinggal ${room.batteryPercent}% (<15%)! Segera isi daya agar CCTV tidak mati.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        // Real-Time Battery & Thermal Telemetry Strip
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TelemetryCard(
                icon = when {
                    room.charging -> Icons.Default.BatteryChargingFull
                    room.isLowBattery -> Icons.Default.BatteryAlert
                    else -> Icons.Default.BatteryStd
                },
                label = if (room.charging) "Mengisi Daya" else "Baterai Kamera",
                value = "${room.batteryPercent}%",
                accentColor = when {
                    room.isLowBattery -> AlertRed
                    room.charging -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.secondary
                },
                modifier = Modifier.weight(1f)
            )
            val tempColor = when {
                room.batteryTempC >= 43.0 -> AlertRed
                room.batteryTempC >= 38.0 -> AmberTertiary
                else -> MaterialTheme.colorScheme.primary
            }
            TelemetryCard(
                icon = Icons.Default.Thermostat,
                label = "Suhu HP Kamera",
                value = "${room.batteryTempC}°C",
                accentColor = tempColor,
                modifier = Modifier.weight(1f)
            )
        }

        // Live WebRTC Video Feed
        Card(
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            border = BorderStroke(
                1.5.dp,
                if (room.motionDetected) AmberTertiary else MaterialTheme.colorScheme.outline
            ),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier
                .fillMaxWidth()
                .height(270.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (engine != null) {
                    RemoteWebRtcVideo(
                        engine = engine,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                if (uiState.peerState != PeerConnection.PeerConnectionState.CONNECTED) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.primary,
                                strokeWidth = 2.5.dp
                            )
                            Text(
                                text = "Menghubungkan video real-time...",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                // Top status overlays
                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${room.resolution} • ${room.targetFps} FPS",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                        )
                    }
                    if (room.motionDetected) {
                        Surface(
                            color = AmberTertiary,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "GERAKAN TERDETEKSI",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.Black,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    } else if (room.parsedStatus == RoomStatus.PAUSED_MOTION) {
                        Surface(
                            color = Color(0xFF1E293B).copy(alpha = 0.9f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "JEDA HEMAT DAYA",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }
            }
        }

        // Large Remote Control Deck
        Text(
            text = "Kendali Jarak Jauh Kamera",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onBackground
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RemoteControlTile(
                icon = Icons.Default.Cameraswitch,
                title = "Ganti Kamera",
                subtitle = if (room.parsedFacing == CameraFacing.BACK) "Belakang" else "Depan",
                active = false,
                testTag = "remote_flip_camera_button",
                onClick = onFlipCamera,
                modifier = Modifier.weight(1f)
            )
            RemoteControlTile(
                icon = if (room.flashEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                title = "Lampu Senter",
                subtitle = if (room.flashEnabled) "Menyala" else "Mati",
                active = room.flashEnabled,
                enabled = room.parsedFacing == CameraFacing.BACK,
                testTag = "remote_flash_button",
                onClick = onToggleFlash,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            RemoteControlTile(
                icon = Icons.Default.DarkMode,
                title = "Layar Hitam",
                subtitle = if (room.blackScreenEnabled) "Layar Gelap Total" else "Layar Normal",
                active = room.blackScreenEnabled,
                testTag = "remote_black_screen_button",
                onClick = onToggleBlackScreen,
                modifier = Modifier.weight(1f)
            )
            RemoteControlTile(
                icon = Icons.Default.DirectionsRun,
                title = "Deteksi Gerakan",
                subtitle = if (room.motionDetectionEnabled) "Hemat Kuota Aktif" else "Siaran Terus",
                active = room.motionDetectionEnabled,
                testTag = "remote_motion_button",
                onClick = onToggleMotion,
                modifier = Modifier.weight(1f)
            )
        }

        // Resolution & FPS Remote Presets
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Kualitas & FPS Kamera (Atur Jarak Jauh)",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    QualityChip(
                        label = "480p • 15 FPS",
                        selected = room.parsedResolution == VideoResolution.SD_480P && room.targetFps == 15L,
                        onClick = { onSelectQuality(VideoResolution.SD_480P, 15) },
                        modifier = Modifier.weight(1f)
                    )
                    QualityChip(
                        label = "720p • 15 FPS",
                        selected = room.parsedResolution == VideoResolution.HD_720P && room.targetFps == 15L,
                        onClick = { onSelectQuality(VideoResolution.HD_720P, 15) },
                        modifier = Modifier.weight(1f)
                    )
                    QualityChip(
                        label = "720p • 30 FPS",
                        selected = room.parsedResolution == VideoResolution.HD_720P && room.targetFps == 30L,
                        onClick = { onSelectQuality(VideoResolution.HD_720P, 30) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Motion Event History
        if (uiState.motionEvents.isNotEmpty()) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Catatan Deteksi Gerakan Terbaru",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val formatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    uiState.motionEvents.take(5).forEach { evt ->
                        val timeText = evt.createdAt?.toDate()?.let { formatter.format(it) } ?: "Baru saja"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Gerakan terdeteksi (${String.format(Locale.US, "%.1f", evt.intensity)}%)",
                                style = MaterialTheme.typography.bodyMedium,
                                color = AmberTertiary
                            )
                            Text(
                                text = timeText,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun RemoteWebRtcVideo(
    engine: WebRtcEngine,
    modifier: Modifier = Modifier
) {
    var rendererRef by remember { mutableStateOf<SurfaceViewRenderer?>(null) }

    DisposableEffect(engine) {
        onDispose {
            rendererRef?.let {
                engine.removeRemoteSink(it)
                it.release()
            }
            rendererRef = null
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(engine.rootEglBase.eglBaseContext, null)
                setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                setEnableHardwareScaler(true)
                engine.addRemoteSink(this)
                rendererRef = this
            }
        },
        modifier = modifier
    )
}

@Composable
private fun TelemetryCard(
    icon: ImageVector,
    label: String,
    value: String,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.45f)),
        shape = MaterialTheme.shapes.medium,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = accentColor,
                modifier = Modifier.size(26.dp)
            )
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = value,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge,
                    color = accentColor
                )
            }
        }
    }
}

@Composable
private fun RemoteControlTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
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
            1.5.dp,
            if (active && enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        shape = MaterialTheme.shapes.large,
        modifier = modifier
            .heightIn(min = 84.dp)
            .testTag(testTag)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    active -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
                modifier = Modifier.size(28.dp)
            )
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QualityChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        border = BorderStroke(
            1.5.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            contentColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        ),
        shape = MaterialTheme.shapes.small,
        modifier = modifier.heightIn(min = 48.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center
        )
    }
}

private fun connectionLabel(peerState: PeerConnection.PeerConnectionState, roomStatus: RoomStatus): String =
    when (peerState) {
        PeerConnection.PeerConnectionState.CONNECTED -> "Siaran Aktif"
        PeerConnection.PeerConnectionState.CONNECTING -> "Menyambungkan..."
        PeerConnection.PeerConnectionState.FAILED,
        PeerConnection.PeerConnectionState.DISCONNECTED -> "Terputus — Ketuk Refresh"
        else -> if (roomStatus == RoomStatus.OFFLINE) "Kamera Offline" else "Menunggu Kamera..."
    }
