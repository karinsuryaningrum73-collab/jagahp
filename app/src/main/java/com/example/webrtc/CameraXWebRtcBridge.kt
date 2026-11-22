package com.example.webrtc

import android.content.Context
import android.hardware.camera2.CaptureRequest
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.camera.MotionDetectionResult
import com.example.camera.MotionDetector
import com.example.data.CameraFacing
import com.example.data.VideoResolution
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraXWebRtcBridge(
    private val context: Context,
    private val surfaceTextureHelper: SurfaceTextureHelper,
    private val capturerObserver: CapturerObserver,
    private val onMotionResult: (MotionDetectionResult) -> Unit,
    private val onTorchAvailabilityChanged: (Boolean) -> Unit
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val motionDetector = MotionDetector()

    private var cameraProvider: ProcessCameraProvider? = null
    private var boundCamera: Camera? = null
    private var activeLifecycleOwner: LifecycleOwner? = null
    private var activeSurface: Surface? = null

    @Volatile
    private var motionDetectionEnabled: Boolean = true

    @Volatile
    private var motionCurrentlyActive: Boolean = false

    @Volatile
    private var lastKeepaliveFrameMs: Long = 0L

    private var currentFacing: CameraFacing = CameraFacing.BACK
    private var currentResolution: VideoResolution = VideoResolution.SD_480P
    private var currentFps: Int = 15
    private var currentFlash: Boolean = false
    private var listeningStarted: Boolean = false

    fun start(
        lifecycleOwner: LifecycleOwner,
        facing: CameraFacing,
        resolution: VideoResolution,
        targetFps: Int,
        flashEnabled: Boolean,
        motionEnabled: Boolean
    ) {
        activeLifecycleOwner = lifecycleOwner
        currentFacing = facing
        currentResolution = resolution
        currentFps = targetFps.coerceIn(10, 30)
        currentFlash = flashEnabled
        motionDetectionEnabled = motionEnabled

        if (!listeningStarted) {
            listeningStarted = true
            capturerObserver.onCapturerStarted(true)
            surfaceTextureHelper.startListening { videoFrame ->
                val now = System.currentTimeMillis()
                val allowFrame = if (!motionDetectionEnabled || motionCurrentlyActive) {
                    true
                } else {
                    // Send a single keepalive frame every 4s to keep WebRTC stream alive at near-zero CPU/data
                    if (now - lastKeepaliveFrameMs >= 4000L) {
                        lastKeepaliveFrameMs = now
                        true
                    } else {
                        false
                    }
                }
                if (allowFrame) {
                    capturerObserver.onFrameCaptured(videoFrame)
                }
            }
        }

        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            try {
                cameraProvider = providerFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to obtain ProcessCameraProvider", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun updateConfig(
        facing: CameraFacing,
        resolution: VideoResolution,
        targetFps: Int,
        flashEnabled: Boolean,
        motionEnabled: Boolean
    ) {
        val safeFps = targetFps.coerceIn(10, 30)
        val needsRebind = facing != currentFacing ||
            resolution != currentResolution ||
            safeFps != currentFps

        currentFacing = facing
        currentResolution = resolution
        currentFps = safeFps
        currentFlash = flashEnabled
        motionDetectionEnabled = motionEnabled
        if (!motionEnabled) {
            motionCurrentlyActive = false
            motionDetector.reset()
        }

        if (needsRebind) {
            bindCameraUseCases()
        } else {
            applyTorchState()
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: return
        val owner = activeLifecycleOwner ?: return

        val mainExecutor = ContextCompat.getMainExecutor(context)
        mainExecutor.execute {
            try {
                provider.unbindAll()

                val targetSize = Size(currentResolution.width, currentResolution.height)
                val resolutionSelector = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            targetSize,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val previewBuilder = Preview.Builder()
                    .setResolutionSelector(resolutionSelector)

                // Lock AE FPS range to save battery and prevent thermal throttling on old devices
                Camera2Interop.Extender(previewBuilder).setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(currentFps, currentFps)
                )

                val preview = previewBuilder.build()
                surfaceTextureHelper.setTextureSize(currentResolution.width, currentResolution.height)
                surfaceTextureHelper.surfaceTexture.setDefaultBufferSize(
                    currentResolution.width,
                    currentResolution.height
                )

                preview.setSurfaceProvider { surfaceRequest ->
                    val res = surfaceRequest.resolution
                    surfaceTextureHelper.setTextureSize(res.width, res.height)
                    surfaceTextureHelper.surfaceTexture.setDefaultBufferSize(res.width, res.height)
                    surfaceRequest.setTransformationInfoListener(mainExecutor) { info ->
                        surfaceTextureHelper.setFrameRotation(info.rotationDegrees)
                    }
                    activeSurface?.release()
                    val surface = Surface(surfaceTextureHelper.surfaceTexture)
                    activeSurface = surface
                    surfaceRequest.provideSurface(
                        surface,
                        java.util.concurrent.Executor { surfaceTextureHelper.handler.post(it) }
                    ) {
                        surface.release()
                        if (activeSurface === surface) {
                            activeSurface = null
                        }
                    }
                }

                val analysisResolution = ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(320, 240),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER
                        )
                    )
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    .setResolutionSelector(analysisResolution)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    try {
                        if (motionDetectionEnabled) {
                            val result = motionDetector.analyze(imageProxy)
                            motionCurrentlyActive = result.motionActive
                            onMotionResult(result)
                        }
                    } finally {
                        imageProxy.close()
                    }
                }

                val selector = if (currentFacing == CameraFacing.FRONT) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val camera = provider.bindToLifecycle(owner, selector, preview, imageAnalysis)
                boundCamera = camera
                val hasFlash = camera.cameraInfo.hasFlashUnit()
                onTorchAvailabilityChanged(hasFlash)
                applyTorchState()
            } catch (e: Exception) {
                Log.e(TAG, "CameraX bindToLifecycle failed", e)
            }
        }
    }

    private fun applyTorchState() {
        val camera = boundCamera ?: return
        val hasFlash = camera.cameraInfo.hasFlashUnit()
        val enable = hasFlash && currentFlash && currentFacing == CameraFacing.BACK
        camera.cameraControl.enableTorch(enable)
    }

    fun dispose() {
        try {
            if (listeningStarted) {
                surfaceTextureHelper.stopListening()
                capturerObserver.onCapturerStopped()
                listeningStarted = false
            }
            ContextCompat.getMainExecutor(context).execute {
                cameraProvider?.unbindAll()
                activeSurface?.release()
                activeSurface = null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing CameraXWebRtcBridge", e)
        } finally {
            analysisExecutor.shutdownNow()
        }
    }

    private companion object {
        const val TAG = "CameraXWebRtcBridge"
    }
}
