package com.example.data

import android.content.Context
import com.example.R
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import org.webrtc.IceCandidate

class CctvRepository(
    private val db: FirebaseFirestore,
    private val preferences: DevicePreferences
) {
    constructor(context: Context) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        DevicePreferences(context.applicationContext)
    )

    fun observeRoom(roomId: String): Flow<CctvRoom?> = flow {
        val sanitizedId = roomId.trim().uppercase()
        require(ROOM_CODE_REGEX.matches(sanitizedId)) { "Kode pairing tidak valid." }
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        emitAll(
            db.document(path)
                .snapshots()
                .map { snapshot ->
                    if (!snapshot.exists()) null
                    else snapshot.toObject(
                        CctvRoom::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path, deviceId)
                    }
                    throw error
                }
        )
    }

    suspend fun createOrResumeRoom(
        roomId: String,
        cameraName: String,
        batteryPercent: Int,
        batteryTempC: Double,
        charging: Boolean,
        resolution: VideoResolution,
        targetFps: Int,
        motionDetectionEnabled: Boolean
    ): Result<CctvRoom> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            require(ROOM_CODE_REGEX.matches(sanitizedId)) { "Kode ruangan harus 6-32 karakter huruf/angka." }
            val safeName = cameraName.trim().ifBlank { "Kamera JagaCam" }.take(50)
            val safeBattery = batteryPercent.coerceIn(0, 100).toLong()
            val safeTemp = batteryTempC.coerceIn(-20.0, 100.0)
            val safeFps = targetFps.coerceIn(10, 30).toLong()

            val docRef = db.collection("rooms").document(sanitizedId)
            val existing = docRef.get().await()
            if (existing.exists()) {
                val existingRoom = existing.toObject(
                    CctvRoom::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
                if (existingRoom != null && existingRoom.ownerDeviceId == deviceId) {
                    docRef.update(
                        mapOf(
                            "cameraName" to safeName,
                            "status" to RoomStatus.WAITING.name,
                            "batteryPercent" to safeBattery,
                            "batteryTempC" to safeTemp,
                            "charging" to charging,
                            "resolution" to resolution.label,
                            "targetFps" to safeFps,
                            "motionDetectionEnabled" to motionDetectionEnabled,
                            "motionDetected" to false,
                            "blackScreenEnabled" to false,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                    val refreshed = docRef.get().await().toObject(
                        CctvRoom::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ) ?: existingRoom
                    return Result.success(refreshed)
                }
            }

            val payload = mapOf(
                "ownerDeviceId" to deviceId,
                "viewerDeviceIds" to listOf(deviceId),
                "cameraName" to safeName,
                "status" to RoomStatus.WAITING.name,
                "cameraFacing" to CameraFacing.BACK.name,
                "flashEnabled" to false,
                "resolution" to resolution.label,
                "targetFps" to safeFps,
                "motionDetectionEnabled" to motionDetectionEnabled,
                "motionDetected" to false,
                "blackScreenEnabled" to false,
                "batteryPercent" to safeBattery,
                "batteryTempC" to safeTemp,
                "charging" to charging,
                "viewerRequestSeq" to 0L,
                "offerSeq" to 0L,
                "answerSeq" to 0L,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            val created = docRef.get().await().toObject(
                CctvRoom::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: CctvRoom(
                id = sanitizedId,
                ownerDeviceId = deviceId,
                viewerDeviceIds = listOf(deviceId),
                cameraName = safeName,
                batteryPercent = safeBattery,
                batteryTempC = safeTemp,
                charging = charging,
                resolution = resolution.label,
                targetFps = safeFps,
                motionDetectionEnabled = motionDetectionEnabled
            )
            Result.success(created)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, path, deviceId)
            Result.failure(e)
        }
    }

    suspend fun joinRoomByCode(roomId: String): Result<CctvRoom> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            require(ROOM_CODE_REGEX.matches(sanitizedId)) { "Format kode pairing tidak valid (6-32 huruf/angka)." }
            val docRef = db.collection("rooms").document(sanitizedId)
            val snap = docRef.get().await()
            if (!snap.exists()) {
                return Result.failure(IllegalStateException("Ruangan CCTV dengan kode $sanitizedId tidak ditemukan."))
            }
            val room = snap.toObject(
                CctvRoom::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: return Result.failure(IllegalStateException("Data ruangan tidak dapat dibaca."))

            val updatedViewers = (room.viewerDeviceIds + deviceId).distinct().take(10)
            docRef.update(
                mapOf(
                    "viewerDeviceIds" to updatedViewers,
                    "viewerRequestSeq" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val updatedRoom = docRef.get().await().toObject(
                CctvRoom::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ) ?: room.copy(viewerDeviceIds = updatedViewers)

            preferences.savePairedRoomHistory(updatedRoom.id, updatedRoom.cameraName)
            Result.success(updatedRoom)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path, deviceId)
            Result.failure(e)
        }
    }

    suspend fun requestFreshOffer(roomId: String): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            db.document(path).update(
                mapOf(
                    "viewerRequestSeq" to FieldValue.increment(1L),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path, deviceId)
            Result.failure(e)
        }
    }

    suspend fun updateRoomTelemetry(
        roomId: String,
        status: RoomStatus,
        batteryPercent: Int,
        batteryTempC: Double,
        charging: Boolean,
        motionDetected: Boolean
    ): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            db.document(path).update(
                mapOf(
                    "status" to status.name,
                    "batteryPercent" to batteryPercent.coerceIn(0, 100).toLong(),
                    "batteryTempC" to batteryTempC.coerceIn(-20.0, 100.0),
                    "charging" to charging,
                    "motionDetected" to motionDetected,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path, deviceId)
            Result.failure(e)
        }
    }

    suspend fun updateRemoteControls(
        roomId: String,
        cameraFacing: CameraFacing? = null,
        flashEnabled: Boolean? = null,
        resolution: VideoResolution? = null,
        targetFps: Int? = null,
        motionDetectionEnabled: Boolean? = null,
        blackScreenEnabled: Boolean? = null
    ): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            val payload = mutableMapOf<String, Any>(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            cameraFacing?.let { payload["cameraFacing"] = it.name }
            flashEnabled?.let { payload["flashEnabled"] = it }
            resolution?.let { payload["resolution"] = it.label }
            targetFps?.let { payload["targetFps"] = it.coerceIn(10, 30).toLong() }
            motionDetectionEnabled?.let { payload["motionDetectionEnabled"] = it }
            blackScreenEnabled?.let { payload["blackScreenEnabled"] = it }

            db.document(path).update(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path, deviceId)
            Result.failure(e)
        }
    }

    suspend fun publishOffer(roomId: String, offerSdp: String, offerSeq: Long): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            db.document(path).update(
                mapOf(
                    "offerSdp" to offerSdp.take(40000),
                    "offerSeq" to offerSeq,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path, deviceId)
            Result.failure(e)
        }
    }

    suspend fun publishAnswer(roomId: String, answerSdp: String, answerSeq: Long): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            db.document(path).update(
                mapOf(
                    "answerSdp" to answerSdp.take(40000),
                    "answerSeq" to answerSeq,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path, deviceId)
            Result.failure(e)
        }
    }

    suspend fun addIceCandidate(
        roomId: String,
        isCaller: Boolean,
        ownerDeviceId: String,
        candidate: IceCandidate,
        sessionSeq: Long
    ): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val subCollection = if (isCaller) "callerCandidates" else "calleeCandidates"
        val docRef = db.collection("rooms").document(sanitizedId).collection(subCollection).document()
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            val payload = mapOf(
                "ownerDeviceId" to ownerDeviceId,
                "senderDeviceId" to deviceId,
                "sdpMid" to (candidate.sdpMid ?: "").take(64),
                "sdpMLineIndex" to candidate.sdpMLineIndex.coerceIn(0, 20).toLong(),
                "candidate" to candidate.sdp.take(2048),
                "sessionSeq" to sessionSeq.coerceAtLeast(0L),
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, deviceId)
            Result.failure(e)
        }
    }

    fun observeIceCandidates(
        roomId: String,
        fromCaller: Boolean
    ): Flow<List<IceCandidateDoc>> = flow {
        val sanitizedId = roomId.trim().uppercase()
        val subCollection = if (fromCaller) "callerCandidates" else "calleeCandidates"
        val path = "rooms/$sanitizedId/$subCollection"
        val deviceId = preferences.getOrCreateDeviceId()
        emitAll(
            db.collection("rooms").document(sanitizedId).collection(subCollection)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        IceCandidateDoc::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    )
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, deviceId)
                    }
                    throw error
                }
        )
    }

    suspend fun logMotionEvent(
        roomId: String,
        intensity: Double,
        batteryPercent: Int
    ): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val docRef = db.collection("rooms").document(sanitizedId).collection("motionEvents").document()
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            val payload = mapOf(
                "ownerDeviceId" to deviceId,
                "intensity" to intensity.coerceIn(0.0, 100.0),
                "batteryPercent" to batteryPercent.coerceIn(0, 100).toLong(),
                "createdAt" to FieldValue.serverTimestamp()
            )
            docRef.set(payload).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path, deviceId)
            Result.failure(e)
        }
    }

    fun observeMotionEvents(roomId: String): Flow<List<MotionEvent>> = flow {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId/motionEvents"
        val deviceId = preferences.getOrCreateDeviceId()
        emitAll(
            db.collection("rooms").document(sanitizedId).collection("motionEvents")
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(
                        MotionEvent::class.java,
                        DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                    ).sortedByDescending { it.createdAt ?: Timestamp(0, 0) }
                        .take(20)
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path, deviceId)
                    }
                    throw error
                }
        )
    }

    suspend fun markRoomOffline(roomId: String): Result<Unit> {
        val sanitizedId = roomId.trim().uppercase()
        val path = "rooms/$sanitizedId"
        val deviceId = preferences.getOrCreateDeviceId()
        return try {
            db.document(path).update(
                mapOf(
                    "status" to RoomStatus.OFFLINE.name,
                    "flashEnabled" to false,
                    "motionDetected" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, path, deviceId)
            Result.failure(e)
        }
    }

    companion object {
        val ROOM_CODE_REGEX = Regex("^[A-Z0-9_\\-]{6,32}$")
    }
}
