package com.example.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.camera.BatteryTelemetry
import com.example.camera.BatteryTelemetryMonitor
import com.example.data.CameraFacing
import com.example.data.CctvRepository
import com.example.data.CctvRoom
import com.example.data.DevicePreferences
import com.example.data.RoomStatus
import com.example.data.VideoResolution
import com.example.service.CctvForegroundService
import com.example.util.QrCodeUtils
import com.example.webrtc.WebRtcEngine
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection

data class CameraHostUiState(
    val roomId: String = "",
    val cameraName: String = "Kamera ${Build.MODEL.take(24)}",
    val qrBitmap: Bitmap? = null,
    val status: RoomStatus = RoomStatus.WAITING,
    val cameraFacing: CameraFacing = CameraFacing.BACK,
    val flashEnabled: Boolean = false,
    val hasFlashUnit: Boolean = false,
    val resolution: VideoResolution = VideoResolution.SD_480P,
    val targetFps: Int = 15,
    val motionDetectionEnabled: Boolean = true,
    val motionActive: Boolean = false,
    val blackScreenEnabled: Boolean = false,
    val audioMuted: Boolean = false,
    val battery: BatteryTelemetry = BatteryTelemetry(),
    val peerState: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW,
    val isStarting: Boolean = true,
    val errorMessage: String? = null
)

class CameraHostViewModel(
    private val repository: CctvRepository,
    private val preferences: DevicePreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraHostUiState())
    val uiState: StateFlow<CameraHostUiState> = _uiState.asStateFlow()

    var webRtcEngine: WebRtcEngine? = null
        private set

    private var currentRoom: CctvRoom? = null
    private var myDeviceId: String = ""
    private var lastHandledViewerSeq: Long = 0L
    private var lastHandledAnswerSeq: Long = 0L
    private val processedCalleeCandidates = mutableSetOf<String>()

    private var telemetryJob: Job? = null
    private var roomObserverJob: Job? = null
    private var calleeCandidatesJob: Job? = null
    private var lastTelemetrySyncMs: Long = 0L
    private var lastBatterySyncedPercent: Int = -1
    private var lastMotionEventLogMs: Long = 0L
    private var started = false

    fun startCameraSession(context: Context, lifecycleOwner: LifecycleOwner) {
        if (started) return
        started = true
        val appContext = context.applicationContext

        val initialBattery = BatteryTelemetryMonitor.readSnapshot(appContext)
        _uiState.update {
            it.copy(
                battery = initialBattery,
                isStarting = true,
                errorMessage = null
            )
        }

        val engine = WebRtcEngine(appContext)
        webRtcEngine = engine

        viewModelScope.launch {
            myDeviceId = preferences.getOrCreateDeviceId()
            val roomId = preferences.getOrCreateCameraRoomCode()
            val qrBitmap = QrCodeUtils.generateQrBitmap(QrCodeUtils.buildPairPayload(roomId), 512)

            _uiState.update {
                it.copy(
                    roomId = roomId,
                    qrBitmap = qrBitmap
                )
            }

            val createResult = repository.createOrResumeRoom(
                roomId = roomId,
                cameraName = _uiState.value.cameraName,
                batteryPercent = initialBattery.percent,
                batteryTempC = initialBattery.temperatureCelsius,
                charging = initialBattery.charging,
                resolution = _uiState.value.resolution,
                targetFps = _uiState.value.targetFps,
                motionDetectionEnabled = _uiState.value.motionDetectionEnabled
            )
            createResult.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isStarting = false,
                        errorMessage = err.localizedMessage ?: "Gagal membuat ruangan CCTV."
                    )
                }
                return@launch
            }

            val created = createResult.getOrNull()
            currentRoom = created
            lastHandledViewerSeq = created?.viewerRequestSeq ?: 0L
            CctvForegroundService.start(appContext, roomId)

            engine.startLocalCameraCapture(
                lifecycleOwner = lifecycleOwner,
                facing = _uiState.value.cameraFacing,
                resolution = _uiState.value.resolution,
                targetFps = _uiState.value.targetFps,
                flashEnabled = _uiState.value.flashEnabled,
                motionEnabled = _uiState.value.motionDetectionEnabled,
                includeAudio = true,
                onMotionResult = { motion ->
                    val activeRoomId = _uiState.value.roomId
                    val prevMotion = _uiState.value.motionActive
                    if (prevMotion != motion.motionActive) {
                        _uiState.update { it.copy(motionActive = motion.motionActive) }
                        if (activeRoomId.isNotBlank()) syncTelemetryNow(activeRoomId)
                    }
                    val now = System.currentTimeMillis()
                    if (motion.triggeredNewEvent && activeRoomId.isNotBlank() && now - lastMotionEventLogMs > 12_000L) {
                        lastMotionEventLogMs = now
                        viewModelScope.launch {
                            repository.logMotionEvent(
                                roomId = activeRoomId,
                                intensity = motion.intensityPercent,
                                batteryPercent = _uiState.value.battery.percent
                            )
                        }
                    }
                },
                onTorchAvailabilityChanged = { hasFlash ->
                    _uiState.update { it.copy(hasFlashUnit = hasFlash) }
                }
            )

            _uiState.update { it.copy(isStarting = false) }
            observeBattery(appContext)
            observeRoomCommands(roomId)
            observeCalleeCandidates(roomId)
        }
    }

    fun regenerateRoomCode(context: Context) {
        val appContext = context.applicationContext
        val oldRoomId = _uiState.value.roomId
        viewModelScope.launch {
            if (oldRoomId.isNotBlank()) {
                repository.markRoomOffline(oldRoomId)
            }
            val newRoomId = preferences.regenerateCameraRoomCode()
            val qrBitmap = QrCodeUtils.generateQrBitmap(QrCodeUtils.buildPairPayload(newRoomId), 512)
            val battery = _uiState.value.battery

            webRtcEngine?.closePeerConnection()
            lastHandledViewerSeq = 0L
            lastHandledAnswerSeq = 0L
            processedCalleeCandidates.clear()

            _uiState.update {
                it.copy(
                    roomId = newRoomId,
                    qrBitmap = qrBitmap,
                    status = RoomStatus.WAITING,
                    peerState = PeerConnection.PeerConnectionState.NEW,
                    errorMessage = null
                )
            }

            repository.createOrResumeRoom(
                roomId = newRoomId,
                cameraName = _uiState.value.cameraName,
                batteryPercent = battery.percent,
                batteryTempC = battery.temperatureCelsius,
                charging = battery.charging,
                resolution = _uiState.value.resolution,
                targetFps = _uiState.value.targetFps,
                motionDetectionEnabled = _uiState.value.motionDetectionEnabled
            ).onSuccess { created ->
                currentRoom = created
                CctvForegroundService.start(appContext, newRoomId)
                observeRoomCommands(newRoomId)
                observeCalleeCandidates(newRoomId)
            }.onFailure { err ->
                _uiState.update { it.copy(errorMessage = err.localizedMessage) }
            }
        }
    }

    private fun observeBattery(appContext: Context) {
        telemetryJob?.cancel()
        telemetryJob = viewModelScope.launch {
            BatteryTelemetryMonitor.observe(appContext).collect { battery ->
                _uiState.update { it.copy(battery = battery) }
                val activeRoomId = _uiState.value.roomId
                if (activeRoomId.isNotBlank()) {
                    val now = System.currentTimeMillis()
                    val percentChanged = battery.percent != lastBatterySyncedPercent
                    if (percentChanged || now - lastTelemetrySyncMs >= 15_000L) {
                        syncTelemetryNow(activeRoomId)
                    }
                }
            }
        }
    }

    private fun syncTelemetryNow(roomId: String) {
        val state = _uiState.value
        val computedStatus = computeRoomStatus(state)
        lastTelemetrySyncMs = System.currentTimeMillis()
        lastBatterySyncedPercent = state.battery.percent
        _uiState.update { it.copy(status = computedStatus) }
        viewModelScope.launch {
            repository.updateRoomTelemetry(
                roomId = roomId,
                status = computedStatus,
                batteryPercent = state.battery.percent,
                batteryTempC = state.battery.temperatureCelsius,
                charging = state.battery.charging,
                motionDetected = state.motionActive
            )
        }
    }

    private fun computeRoomStatus(state: CameraHostUiState): RoomStatus {
        return when {
            state.peerState == PeerConnection.PeerConnectionState.CONNECTED &&
                state.motionDetectionEnabled && !state.motionActive -> RoomStatus.PAUSED_MOTION
            state.peerState == PeerConnection.PeerConnectionState.CONNECTED -> RoomStatus.STREAMING
            else -> RoomStatus.WAITING
        }
    }

    private fun observeRoomCommands(roomId: String) {
        roomObserverJob?.cancel()
        roomObserverJob = viewModelScope.launch {
            repository.observeRoom(roomId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    _uiState.update {
                        it.copy(errorMessage = (firestoreError ?: error).localizedMessage)
                    }
                }
                .collect { room ->
                    if (room == null) return@collect
                    currentRoom = room

                    val facing = room.parsedFacing
                    val resolution = room.parsedResolution
                    val fps = room.targetFps.toInt().coerceIn(10, 30)
                    val flash = room.flashEnabled && facing == CameraFacing.BACK
                    val motionEnabled = room.motionDetectionEnabled
                    val blackScreen = room.blackScreenEnabled

                    _uiState.update {
                        it.copy(
                            cameraFacing = facing,
                            resolution = resolution,
                            targetFps = fps,
                            flashEnabled = flash,
                            motionDetectionEnabled = motionEnabled,
                            blackScreenEnabled = blackScreen
                        )
                    }

                    webRtcEngine?.updateCameraConfig(
                        facing = facing,
                        resolution = resolution,
                        targetFps = fps,
                        flashEnabled = flash,
                        motionEnabled = motionEnabled
                    )

                    if (room.viewerRequestSeq > lastHandledViewerSeq) {
                        lastHandledViewerSeq = room.viewerRequestSeq
                        createAndPublishOffer(room, room.viewerRequestSeq)
                    }

                    val answerSdp = room.answerSdp
                    if (!answerSdp.isNullOrBlank() && room.answerSeq == lastHandledViewerSeq && room.answerSeq > lastHandledAnswerSeq) {
                        lastHandledAnswerSeq = room.answerSeq
                        webRtcEngine?.handleRemoteAnswer(answerSdp) { errMsg ->
                            Log.w(TAG, "Remote answer error: $errMsg")
                        }
                    }
                }
        }
    }

    private fun createAndPublishOffer(room: CctvRoom, seq: Long) {
        val engine = webRtcEngine ?: return
        processedCalleeCandidates.clear()

        engine.createPeerConnection(
            isCameraHost = true,
            resolution = room.parsedResolution,
            targetFps = room.targetFps.toInt(),
            onIceCandidate = { candidate ->
                viewModelScope.launch {
                    repository.addIceCandidate(
                        roomId = room.id,
                        isCaller = true,
                        ownerDeviceId = myDeviceId,
                        candidate = candidate,
                        sessionSeq = seq
                    )
                }
            },
            onStateChanged = { peerState ->
                _uiState.update { it.copy(peerState = peerState) }
                syncTelemetryNow(room.id)
            }
        )

        engine.createOffer(
            onOfferCreated = { offerSdp ->
                viewModelScope.launch {
                    repository.publishOffer(room.id, offerSdp, seq)
                }
            },
            onError = { err ->
                _uiState.update { it.copy(errorMessage = err) }
            }
        )
    }

    private fun observeCalleeCandidates(roomId: String) {
        calleeCandidatesJob?.cancel()
        calleeCandidatesJob = viewModelScope.launch {
            repository.observeIceCandidates(
                roomId = roomId,
                fromCaller = false
            )
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Callee candidates stream error", firestoreError ?: error)
                }
                .collect { docs ->
                    docs.filter { it.sessionSeq == lastHandledViewerSeq && processedCalleeCandidates.add(it.id) }
                        .forEach { doc ->
                            webRtcEngine?.addRemoteIceCandidate(
                                IceCandidate(doc.sdpMid, doc.sdpMLineIndex.toInt(), doc.candidate)
                            )
                        }
                }
        }
    }

    fun toggleBlackScreen(enabled: Boolean) {
        val roomId = _uiState.value.roomId
        _uiState.update { it.copy(blackScreenEnabled = enabled) }
        if (roomId.isNotBlank()) {
            viewModelScope.launch {
                repository.updateRemoteControls(roomId, blackScreenEnabled = enabled)
            }
        }
    }

    fun toggleCameraFacing() {
        val next = if (_uiState.value.cameraFacing == CameraFacing.BACK) {
            CameraFacing.FRONT
        } else {
            CameraFacing.BACK
        }
        val nextFlash = if (next == CameraFacing.FRONT) false else _uiState.value.flashEnabled
        updateControls(cameraFacing = next, flashEnabled = nextFlash)
    }

    fun toggleFlash() {
        if (_uiState.value.cameraFacing != CameraFacing.BACK || !_uiState.value.hasFlashUnit) return
        updateControls(flashEnabled = !_uiState.value.flashEnabled)
    }

    fun setResolutionAndFps(resolution: VideoResolution, fps: Int) {
        updateControls(resolution = resolution, targetFps = fps)
    }

    fun toggleMotionDetection() {
        updateControls(motionDetectionEnabled = !_uiState.value.motionDetectionEnabled)
    }

    fun toggleAudioMute() {
        val next = !_uiState.value.audioMuted
        webRtcEngine?.setLocalAudioMuted(next)
        _uiState.update { it.copy(audioMuted = next) }
    }

    private fun updateControls(
        cameraFacing: CameraFacing? = null,
        flashEnabled: Boolean? = null,
        resolution: VideoResolution? = null,
        targetFps: Int? = null,
        motionDetectionEnabled: Boolean? = null
    ) {
        val roomId = _uiState.value.roomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            repository.updateRemoteControls(
                roomId = roomId,
                cameraFacing = cameraFacing,
                flashEnabled = flashEnabled,
                resolution = resolution,
                targetFps = targetFps,
                motionDetectionEnabled = motionDetectionEnabled
            )
        }
    }

    fun stopSession(context: Context) {
        val roomId = _uiState.value.roomId
        CctvForegroundService.stop(context.applicationContext)
        if (roomId.isNotBlank()) {
            viewModelScope.launch {
                repository.markRoomOffline(roomId)
            }
        }
    }

    override fun onCleared() {
        telemetryJob?.cancel()
        roomObserverJob?.cancel()
        calleeCandidatesJob?.cancel()
        webRtcEngine?.dispose()
        webRtcEngine = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "CameraHostVM"
    }
}
