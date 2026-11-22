package com.example.ui.monitor

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CameraFacing
import com.example.data.CctvRepository
import com.example.data.CctvRoom
import com.example.data.MotionEvent
import com.example.data.VideoResolution
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

data class MonitorUiState(
    val activeRoomId: String = "",
    val room: CctvRoom? = null,
    val motionEvents: List<MotionEvent> = emptyList(),
    val peerState: PeerConnection.PeerConnectionState = PeerConnection.PeerConnectionState.NEW,
    val isConnecting: Boolean = false,
    val speakerMuted: Boolean = false,
    val errorMessage: String? = null
)

class MonitorViewModel(
    private val repository: CctvRepository,
    initialRoomId: String?
) : ViewModel() {

    private val _uiState = MutableStateFlow(MonitorUiState())
    val uiState: StateFlow<MonitorUiState> = _uiState.asStateFlow()

    var webRtcEngine: WebRtcEngine? = null
        private set

    private var roomJob: Job? = null
    private var callerCandidatesJob: Job? = null
    private var motionEventsJob: Job? = null

    private var lastHandledOfferSeq: Long = 0L
    private val processedCallerCandidates = mutableSetOf<String>()

    init {
        if (!initialRoomId.isNullOrBlank()) {
            _uiState.update { it.copy(activeRoomId = initialRoomId.trim().uppercase()) }
        }
    }

    fun ensureEngineAndJoinIfInitial(context: Context) {
        val appContext = context.applicationContext
        if (webRtcEngine == null) {
            webRtcEngine = WebRtcEngine(appContext)
        }
        val initial = _uiState.value.activeRoomId
        if (initial.isNotBlank() && roomJob == null) {
            connectToRoom(initial, appContext)
        }
    }

    fun connectToRoom(roomCode: String, context: Context) {
        val appContext = context.applicationContext
        if (webRtcEngine == null) {
            webRtcEngine = WebRtcEngine(appContext)
        }
        val normalized = roomCode.trim().uppercase()
        if (normalized.length < 6) {
            _uiState.update { it.copy(errorMessage = "Masukkan kode pairing minimal 6 karakter.") }
            return
        }

        _uiState.update {
            it.copy(
                activeRoomId = normalized,
                isConnecting = true,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            val result = repository.joinRoomByCode(normalized)
            result.onFailure { err ->
                _uiState.update {
                    it.copy(
                        isConnecting = false,
                        errorMessage = err.localizedMessage ?: "Gagal terhubung ke ruangan $normalized."
                    )
                }
                return@launch
            }
            val joinedRoom = result.getOrNull()
            _uiState.update { it.copy(room = joinedRoom, isConnecting = false) }
            startObservingRoom(normalized)
        }
    }

    fun disconnectRoom() {
        roomJob?.cancel()
        roomJob = null
        callerCandidatesJob?.cancel()
        callerCandidatesJob = null
        motionEventsJob?.cancel()
        motionEventsJob = null
        lastHandledOfferSeq = 0L
        processedCallerCandidates.clear()
        webRtcEngine?.closePeerConnection()
        _uiState.update {
            MonitorUiState(speakerMuted = it.speakerMuted)
        }
    }

    private fun startObservingRoom(roomId: String) {
        lastHandledOfferSeq = 0L
        processedCallerCandidates.clear()

        roomJob?.cancel()
        roomJob = viewModelScope.launch {
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
                    _uiState.update { it.copy(room = room) }
                    if (room == null) return@collect

                    val offerSdp = room.offerSdp
                    if (!offerSdp.isNullOrBlank() && room.offerSeq > lastHandledOfferSeq) {
                        lastHandledOfferSeq = room.offerSeq
                        acceptOfferAndSendAnswer(room, offerSdp, room.offerSeq)
                    }
                }
        }

        callerCandidatesJob?.cancel()
        callerCandidatesJob = viewModelScope.launch {
            repository.observeIceCandidates(
                roomId = roomId,
                fromCaller = true
            )
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Caller candidate stream error", firestoreError ?: error)
                }
                .collect { docs ->
                    docs.filter { it.sessionSeq == lastHandledOfferSeq && processedCallerCandidates.add(it.id) }
                        .forEach { doc ->
                            webRtcEngine?.addRemoteIceCandidate(
                                IceCandidate(doc.sdpMid, doc.sdpMLineIndex.toInt(), doc.candidate)
                            )
                        }
                }
        }

        motionEventsJob?.cancel()
        motionEventsJob = viewModelScope.launch {
            repository.observeMotionEvents(roomId)
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    Log.w(TAG, "Motion events stream error", firestoreError ?: error)
                }
                .collect { events ->
                    _uiState.update { it.copy(motionEvents = events) }
                }
        }
    }

    private fun acceptOfferAndSendAnswer(room: CctvRoom, offerSdp: String, seq: Long) {
        val engine = webRtcEngine ?: return
        processedCallerCandidates.clear()

        engine.createPeerConnection(
            isCameraHost = false,
            resolution = room.parsedResolution,
            targetFps = room.targetFps.toInt(),
            onIceCandidate = { candidate ->
                viewModelScope.launch {
                    repository.addIceCandidate(
                        roomId = room.id,
                        isCaller = false,
                        ownerDeviceId = room.ownerDeviceId,
                        candidate = candidate,
                        sessionSeq = seq
                    )
                }
            },
            onStateChanged = { state ->
                _uiState.update { it.copy(peerState = state) }
            }
        )

        engine.handleRemoteOfferAndCreateAnswer(
            offerSdp = offerSdp,
            onAnswerCreated = { answerSdp ->
                viewModelScope.launch {
                    repository.publishAnswer(room.id, answerSdp, seq)
                }
            },
            onError = { err ->
                _uiState.update { it.copy(errorMessage = err) }
            }
        )
    }

    fun flipRemoteCamera() {
        val room = _uiState.value.room ?: return
        val nextFacing = if (room.parsedFacing == CameraFacing.BACK) CameraFacing.FRONT else CameraFacing.BACK
        val nextFlash = if (nextFacing == CameraFacing.FRONT) false else room.flashEnabled
        viewModelScope.launch {
            repository.updateRemoteControls(
                roomId = room.id,
                cameraFacing = nextFacing,
                flashEnabled = nextFlash
            )
        }
    }

    fun toggleRemoteFlash() {
        val room = _uiState.value.room ?: return
        if (room.parsedFacing != CameraFacing.BACK) return
        viewModelScope.launch {
            repository.updateRemoteControls(
                roomId = room.id,
                flashEnabled = !room.flashEnabled
            )
        }
    }

    fun toggleRemoteBlackScreen() {
        val room = _uiState.value.room ?: return
        viewModelScope.launch {
            repository.updateRemoteControls(
                roomId = room.id,
                blackScreenEnabled = !room.blackScreenEnabled
            )
        }
    }

    fun toggleRemoteMotionDetection() {
        val room = _uiState.value.room ?: return
        viewModelScope.launch {
            repository.updateRemoteControls(
                roomId = room.id,
                motionDetectionEnabled = !room.motionDetectionEnabled
            )
        }
    }

    fun setRemoteResolutionAndFps(resolution: VideoResolution, fps: Int) {
        val room = _uiState.value.room ?: return
        viewModelScope.launch {
            repository.updateRemoteControls(
                roomId = room.id,
                resolution = resolution,
                targetFps = fps
            )
        }
    }

    fun requestReconnect() {
        val roomId = _uiState.value.activeRoomId
        if (roomId.isBlank()) return
        viewModelScope.launch {
            repository.requestFreshOffer(roomId)
        }
    }

    fun toggleSpeakerMute(context: Context) {
        val next = !_uiState.value.speakerMuted
        _uiState.update { it.copy(speakerMuted = next) }
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        audioManager?.isSpeakerphoneOn = !next
        audioManager?.adjustStreamVolume(
            AudioManager.STREAM_VOICE_CALL,
            if (next) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    override fun onCleared() {
        roomJob?.cancel()
        callerCandidatesJob?.cancel()
        motionEventsJob?.cancel()
        webRtcEngine?.dispose()
        webRtcEngine = null
        super.onCleared()
    }

    private companion object {
        const val TAG = "MonitorVM"
    }
}
