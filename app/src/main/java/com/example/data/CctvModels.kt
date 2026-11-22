package com.example.data

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

enum class RoomStatus {
    WAITING,
    STREAMING,
    PAUSED_MOTION,
    OFFLINE;

    companion object {
        fun safeParse(raw: String?): RoomStatus =
            entries.firstOrNull { it.name == raw } ?: WAITING
    }
}

enum class CameraFacing {
    BACK,
    FRONT;

    companion object {
        fun safeParse(raw: String?): CameraFacing =
            entries.firstOrNull { it.name == raw } ?: BACK
    }
}

enum class VideoResolution(val label: String, val width: Int, val height: Int, val maxBitrateKbps: Int) {
    SD_480P("480p", 640, 480, 380),
    HD_720P("720p", 1280, 720, 850);

    companion object {
        fun safeParse(raw: String?): VideoResolution =
            entries.firstOrNull { it.label == raw } ?: SD_480P
    }
}

@Keep
@IgnoreExtraProperties
data class CctvRoom(
    @DocumentId val id: String = "",
    val ownerDeviceId: String = "",
    val viewerDeviceIds: List<String> = emptyList(),
    val cameraName: String = "Kamera JagaCam",
    val status: String = RoomStatus.WAITING.name,
    val cameraFacing: String = CameraFacing.BACK.name,
    val flashEnabled: Boolean = false,
    val resolution: String = VideoResolution.SD_480P.label,
    val targetFps: Long = 15L,
    val motionDetectionEnabled: Boolean = true,
    val motionDetected: Boolean = false,
    val blackScreenEnabled: Boolean = false,
    val batteryPercent: Long = 100L,
    val batteryTempC: Double = 30.0,
    val charging: Boolean = false,
    val viewerRequestSeq: Long = 0L,
    val offerSeq: Long = 0L,
    val answerSeq: Long = 0L,
    val offerSdp: String? = null,
    val answerSdp: String? = null,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    val parsedStatus: RoomStatus get() = RoomStatus.safeParse(status)
    val parsedFacing: CameraFacing get() = CameraFacing.safeParse(cameraFacing)
    val parsedResolution: VideoResolution get() = VideoResolution.safeParse(resolution)
    val isLowBattery: Boolean get() = batteryPercent in 0L..14L
}

@Keep
@IgnoreExtraProperties
data class IceCandidateDoc(
    @DocumentId val id: String = "",
    val ownerDeviceId: String = "",
    val senderDeviceId: String = "",
    val sdpMid: String = "",
    val sdpMLineIndex: Long = 0L,
    val candidate: String = "",
    val sessionSeq: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class MotionEvent(
    @DocumentId val id: String = "",
    val ownerDeviceId: String = "",
    val intensity: Double = 0.0,
    val batteryPercent: Long = 100L,
    @ServerTimestamp val createdAt: Timestamp? = null
)
