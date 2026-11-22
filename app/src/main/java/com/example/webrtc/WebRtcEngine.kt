package com.example.webrtc

import android.content.Context
import android.util.Log
import androidx.lifecycle.LifecycleOwner
import com.example.camera.MotionDetectionResult
import com.example.data.CameraFacing
import com.example.data.VideoResolution
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpSender
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

class WebRtcEngine(
    private val context: Context
) {
    val rootEglBase: EglBase = EglBase.create()

    private val peerConnectionFactory: PeerConnectionFactory
    private var peerConnection: PeerConnection? = null

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoRtpSender: RtpSender? = null
    private var cameraBridge: CameraXWebRtcBridge? = null

    private var remoteVideoTrack: VideoTrack? = null
    private val remoteSinks = mutableSetOf<VideoSink>()
    private val localSinks = mutableSetOf<VideoSink>()

    private var isRemoteDescriptionSet = false
    private val pendingIceCandidates = mutableListOf<IceCandidate>()

    private var onIceCandidateGenerated: ((IceCandidate) -> Unit)? = null
    private var onConnectionStateChanged: ((PeerConnection.PeerConnectionState) -> Unit)? = null

    init {
        ensureFactoryInitialized(context.applicationContext)
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(rootEglBase.eglBaseContext))
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(rootEglBase.eglBaseContext, true, true))
            .createPeerConnectionFactory()
    }

    fun startLocalCameraCapture(
        lifecycleOwner: LifecycleOwner,
        facing: CameraFacing,
        resolution: VideoResolution,
        targetFps: Int,
        flashEnabled: Boolean,
        motionEnabled: Boolean,
        includeAudio: Boolean,
        onMotionResult: (MotionDetectionResult) -> Unit,
        onTorchAvailabilityChanged: (Boolean) -> Unit
    ) {
        if (cameraBridge != null) {
            cameraBridge?.updateConfig(facing, resolution, targetFps, flashEnabled, motionEnabled)
            applyBitrateCap(resolution, targetFps)
            return
        }

        val helper = SurfaceTextureHelper.create("JagaCamCaptureThread", rootEglBase.eglBaseContext)
        surfaceTextureHelper = helper

        val vSource = peerConnectionFactory.createVideoSource(false)
        videoSource = vSource

        val vTrack = peerConnectionFactory.createVideoTrack("jagacam_video_0", vSource).apply {
            setEnabled(true)
        }
        localVideoTrack = vTrack
        localSinks.forEach { vTrack.addSink(it) }

        if (includeAudio) {
            val audioConstraints = MediaConstraints().apply {
                mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
                mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            }
            val aSource = peerConnectionFactory.createAudioSource(audioConstraints)
            audioSource = aSource
            localAudioTrack = peerConnectionFactory.createAudioTrack("jagacam_audio_0", aSource).apply {
                setEnabled(true)
            }
        }

        val bridge = CameraXWebRtcBridge(
            context = context,
            surfaceTextureHelper = helper,
            capturerObserver = vSource.capturerObserver,
            onMotionResult = onMotionResult,
            onTorchAvailabilityChanged = onTorchAvailabilityChanged
        )
        cameraBridge = bridge
        bridge.start(lifecycleOwner, facing, resolution, targetFps, flashEnabled, motionEnabled)
    }

    fun updateCameraConfig(
        facing: CameraFacing,
        resolution: VideoResolution,
        targetFps: Int,
        flashEnabled: Boolean,
        motionEnabled: Boolean
    ) {
        cameraBridge?.updateConfig(facing, resolution, targetFps, flashEnabled, motionEnabled)
        applyBitrateCap(resolution, targetFps)
    }

    fun setLocalAudioMuted(muted: Boolean) {
        localAudioTrack?.setEnabled(!muted)
    }

    fun addLocalPreviewSink(sink: VideoSink) {
        if (localSinks.add(sink)) {
            localVideoTrack?.addSink(sink)
        }
    }

    fun removeLocalPreviewSink(sink: VideoSink) {
        if (localSinks.remove(sink)) {
            localVideoTrack?.removeSink(sink)
        }
    }

    fun addRemoteSink(sink: VideoSink) {
        if (remoteSinks.add(sink)) {
            remoteVideoTrack?.addSink(sink)
        }
    }

    fun removeRemoteSink(sink: VideoSink) {
        if (remoteSinks.remove(sink)) {
            remoteVideoTrack?.removeSink(sink)
        }
    }

    fun createPeerConnection(
        isCameraHost: Boolean,
        resolution: VideoResolution,
        targetFps: Int,
        onIceCandidate: (IceCandidate) -> Unit,
        onStateChanged: (PeerConnection.PeerConnectionState) -> Unit
    ) {
        closePeerConnection()
        onIceCandidateGenerated = onIceCandidate
        onConnectionStateChanged = onStateChanged

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun2.l.google.com:19302").createIceServer()
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        val pc = peerConnectionFactory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { onIceCandidateGenerated?.invoke(it) }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                newState?.let { onConnectionStateChanged?.invoke(it) }
            }

            override fun onTrack(transceiver: RtpTransceiver?) {
                val track = transceiver?.receiver?.track()
                if (track is VideoTrack) {
                    remoteVideoTrack = track
                    track.setEnabled(true)
                    remoteSinks.forEach { track.addSink(it) }
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {}
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {}
            override fun onRenegotiationNeeded() {}
        })

        peerConnection = pc
        if (pc != null && isCameraHost) {
            val streamIds = listOf("jagacam_stream")
            localVideoTrack?.let { vTrack ->
                videoRtpSender = pc.addTrack(vTrack, streamIds)
                applyBitrateCap(resolution, targetFps)
            }
            localAudioTrack?.let { aTrack ->
                pc.addTrack(aTrack, streamIds)
            }
        } else if (pc != null && !isCameraHost) {
            pc.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
            pc.addTransceiver(
                org.webrtc.MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
            )
        }
    }

    private fun applyBitrateCap(resolution: VideoResolution, targetFps: Int) {
        val sender = videoRtpSender ?: return
        try {
            val params = sender.parameters
            params.encodings.forEach { encoding ->
                encoding.maxBitrateBps = resolution.maxBitrateKbps * 1000
                encoding.maxFramerate = targetFps.coerceIn(10, 30)
            }
            sender.parameters = params
        } catch (e: Exception) {
            Log.w(TAG, "Unable to set RTP bitrate cap", e)
        }
    }

    fun createOffer(onOfferCreated: (String) -> Unit, onError: (String) -> Unit) {
        val pc = peerConnection ?: return onError("PeerConnection belum siap.")
        val constraints = MediaConstraints()
        pc.createOffer(object : SimpleSdpObserver() {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                if (sdp == null) {
                    onError("Offer SDP kosong")
                    return
                }
                pc.setLocalDescription(object : SimpleSdpObserver() {
                    override fun onSetSuccess() {
                        onOfferCreated(sdp.description)
                    }

                    override fun onSetFailure(error: String?) {
                        onError(error ?: "Gagal mengatur local offer")
                    }
                }, sdp)
            }

            override fun onCreateFailure(error: String?) {
                onError(error ?: "Gagal membuat offer")
            }
        }, constraints)
    }

    fun handleRemoteOfferAndCreateAnswer(
        offerSdp: String,
        onAnswerCreated: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val pc = peerConnection ?: return onError("PeerConnection belum siap.")
        val remoteDesc = SessionDescription(SessionDescription.Type.OFFER, offerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                flushPendingIceCandidates()
                pc.createAnswer(object : SimpleSdpObserver() {
                    override fun onCreateSuccess(answerSdp: SessionDescription?) {
                        if (answerSdp == null) {
                            onError("Answer SDP kosong")
                            return
                        }
                        pc.setLocalDescription(object : SimpleSdpObserver() {
                            override fun onSetSuccess() {
                                onAnswerCreated(answerSdp.description)
                            }

                            override fun onSetFailure(error: String?) {
                                onError(error ?: "Gagal mengatur local answer")
                            }
                        }, answerSdp)
                    }

                    override fun onCreateFailure(error: String?) {
                        onError(error ?: "Gagal membuat answer")
                    }
                }, MediaConstraints())
            }

            override fun onSetFailure(error: String?) {
                onError(error ?: "Gagal memproses offer dari kamera")
            }
        }, remoteDesc)
    }

    fun handleRemoteAnswer(answerSdp: String, onError: (String) -> Unit) {
        val pc = peerConnection ?: return
        val remoteDesc = SessionDescription(SessionDescription.Type.ANSWER, answerSdp)
        pc.setRemoteDescription(object : SimpleSdpObserver() {
            override fun onSetSuccess() {
                isRemoteDescriptionSet = true
                flushPendingIceCandidates()
            }

            override fun onSetFailure(error: String?) {
                onError(error ?: "Gagal memproses answer dari pemantau")
            }
        }, remoteDesc)
    }

    fun addRemoteIceCandidate(candidate: IceCandidate) {
        val pc = peerConnection ?: return
        if (isRemoteDescriptionSet) {
            pc.addIceCandidate(candidate)
        } else {
            pendingIceCandidates.add(candidate)
        }
    }

    private fun flushPendingIceCandidates() {
        val pc = peerConnection ?: return
        pendingIceCandidates.forEach { pc.addIceCandidate(it) }
        pendingIceCandidates.clear()
    }

    fun closePeerConnection() {
        isRemoteDescriptionSet = false
        pendingIceCandidates.clear()
        videoRtpSender = null
        remoteVideoTrack = null
        try {
            peerConnection?.close()
            peerConnection?.dispose()
        } catch (_: Exception) {
        }
        peerConnection = null
    }

    fun dispose() {
        closePeerConnection()
        localSinks.clear()
        remoteSinks.clear()
        cameraBridge?.dispose()
        cameraBridge = null
        localVideoTrack?.dispose()
        localVideoTrack = null
        videoSource?.dispose()
        videoSource = null
        localAudioTrack?.dispose()
        localAudioTrack = null
        audioSource?.dispose()
        audioSource = null
        surfaceTextureHelper?.dispose()
        surfaceTextureHelper = null
        peerConnectionFactory.dispose()
        rootEglBase.release()
    }

    private open class SimpleSdpObserver : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) {}
        override fun onSetFailure(error: String?) {}
    }

    companion object {
        private const val TAG = "WebRtcEngine"
        @Volatile
        private var initialized = false

        @Synchronized
        private fun ensureFactoryInitialized(appContext: Context) {
            if (!initialized) {
                PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(appContext)
                        .setEnableInternalTracer(false)
                        .createInitializationOptions()
                )
                initialized = true
            }
        }
    }
}
