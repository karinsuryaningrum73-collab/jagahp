package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.security.PinHasher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

private val Context.jagaCamDataStore: DataStore<Preferences> by preferencesDataStore(name = "jagacam_prefs")

data class PairedRoomHistoryItem(
    val roomCode: String,
    val cameraName: String,
    val lastConnectedAtMs: Long
)

data class SecurityConfig(
    val hasPin: Boolean = false,
    val pinLockEnabled: Boolean = false,
    val disguiseAsCalculator: Boolean = false,
    val lockBlackScreenUnlock: Boolean = true
) {
    val isProtectionActive: Boolean
        get() = hasPin && (pinLockEnabled || disguiseAsCalculator)
}

class DevicePreferences(context: Context) {

    private val dataStore = context.applicationContext.jagaCamDataStore

    val cameraRoomCodeFlow: Flow<String?> = dataStore.data.map { prefs ->
        prefs[KEY_CAMERA_ROOM_CODE]
    }

    val pairedHistoryFlow: Flow<List<PairedRoomHistoryItem>> = dataStore.data.map { prefs ->
        parseHistory(prefs[KEY_PAIRED_HISTORY_JSON].orEmpty())
    }

    val securityConfigFlow: Flow<SecurityConfig> = dataStore.data.map { prefs ->
        SecurityConfig(
            hasPin = !prefs[KEY_PIN_HASH].isNullOrBlank(),
            pinLockEnabled = prefs[KEY_PIN_LOCK_ENABLED] ?: false,
            disguiseAsCalculator = prefs[KEY_DISGUISE_CALCULATOR_ENABLED] ?: false,
            lockBlackScreenUnlock = prefs[KEY_LOCK_BLACK_SCREEN_UNLOCK] ?: true
        )
    }

    suspend fun getOrCreateDeviceId(): String {
        val current = dataStore.data.first()[KEY_DEVICE_ID]
        if (!current.isNullOrBlank()) return current

        val generated = generateRandomId(24)
        var resolved = generated
        dataStore.edit { prefs ->
            val existing = prefs[KEY_DEVICE_ID]
            if (existing.isNullOrBlank()) {
                prefs[KEY_DEVICE_ID] = generated
            } else {
                resolved = existing
            }
        }
        return resolved
    }

    suspend fun getOrCreateCameraRoomCode(): String {
        val current = dataStore.data.first()[KEY_CAMERA_ROOM_CODE]
        if (!current.isNullOrBlank()) return current

        val generated = generateRoomCode()
        var resolved = generated
        dataStore.edit { prefs ->
            val existing = prefs[KEY_CAMERA_ROOM_CODE]
            if (existing.isNullOrBlank()) {
                prefs[KEY_CAMERA_ROOM_CODE] = generated
            } else {
                resolved = existing
            }
        }
        return resolved
    }

    suspend fun regenerateCameraRoomCode(): String {
        val newCode = generateRoomCode()
        dataStore.edit { prefs ->
            prefs[KEY_CAMERA_ROOM_CODE] = newCode
        }
        return newCode
    }

    suspend fun savePairedRoomHistory(roomCode: String, cameraName: String) {
        val normalizedCode = roomCode.trim().uppercase()
        if (normalizedCode.length < 6) return
        val safeName = cameraName.trim().ifBlank { "Kamera $normalizedCode" }.take(50)
        val now = System.currentTimeMillis()

        dataStore.edit { prefs ->
            val currentList = parseHistory(prefs[KEY_PAIRED_HISTORY_JSON].orEmpty())
                .filterNot { it.roomCode == normalizedCode }
            val updated = (listOf(
                PairedRoomHistoryItem(
                    roomCode = normalizedCode,
                    cameraName = safeName,
                    lastConnectedAtMs = now
                )
            ) + currentList).take(12)
            prefs[KEY_PAIRED_HISTORY_JSON] = serializeHistory(updated)
        }
    }

    suspend fun removePairedRoomHistory(roomCode: String) {
        val normalizedCode = roomCode.trim().uppercase()
        dataStore.edit { prefs ->
            val updated = parseHistory(prefs[KEY_PAIRED_HISTORY_JSON].orEmpty())
                .filterNot { it.roomCode == normalizedCode }
            prefs[KEY_PAIRED_HISTORY_JSON] = serializeHistory(updated)
        }
    }

    suspend fun savePin(pin: String): Boolean {
        if (!PinHasher.isValidPin(pin)) return false
        val hashed = PinHasher.hashPin(pin)
        dataStore.edit { prefs ->
            prefs[KEY_PIN_HASH] = hashed
            if (prefs[KEY_PIN_LOCK_ENABLED] != true && prefs[KEY_DISGUISE_CALCULATOR_ENABLED] != true) {
                prefs[KEY_PIN_LOCK_ENABLED] = true
            }
        }
        return true
    }

    suspend fun verifyPin(pin: String): Boolean {
        val stored = dataStore.data.first()[KEY_PIN_HASH]
        return PinHasher.verifyPin(pin, stored)
    }

    suspend fun setPinLockEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_PIN_LOCK_ENABLED] = enabled
        }
    }

    suspend fun setDisguiseAsCalculator(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_DISGUISE_CALCULATOR_ENABLED] = enabled
            if (enabled) {
                prefs[KEY_PIN_LOCK_ENABLED] = true
            }
        }
    }

    suspend fun setLockBlackScreenUnlock(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[KEY_LOCK_BLACK_SCREEN_UNLOCK] = enabled
        }
    }

    suspend fun disableAllSecurity() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_PIN_HASH)
            prefs[KEY_PIN_LOCK_ENABLED] = false
            prefs[KEY_DISGUISE_CALCULATOR_ENABLED] = false
        }
    }

    private fun generateRoomCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return (1..8).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun generateRandomId(length: Int): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val random = SecureRandom()
        return (1..length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun parseHistory(rawJson: String): List<PairedRoomHistoryItem> {
        if (rawJson.isBlank()) return emptyList()
        return try {
            val array = JSONArray(rawJson)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val code = obj.optString("roomCode").trim().uppercase()
                    if (code.length >= 6) {
                        add(
                            PairedRoomHistoryItem(
                                roomCode = code,
                                cameraName = obj.optString("cameraName", "Kamera $code"),
                                lastConnectedAtMs = obj.optLong("lastConnectedAtMs", 0L)
                            )
                        )
                    }
                }
            }.sortedByDescending { it.lastConnectedAtMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serializeHistory(items: List<PairedRoomHistoryItem>): String {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("roomCode", item.roomCode)
                    put("cameraName", item.cameraName)
                    put("lastConnectedAtMs", item.lastConnectedAtMs)
                }
            )
        }
        return array.toString()
    }

    private companion object {
        val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        val KEY_CAMERA_ROOM_CODE = stringPreferencesKey("camera_room_code")
        val KEY_PAIRED_HISTORY_JSON = stringPreferencesKey("paired_history_json")
        val KEY_PIN_HASH = stringPreferencesKey("pin_hash")
        val KEY_PIN_LOCK_ENABLED = booleanPreferencesKey("pin_lock_enabled")
        val KEY_DISGUISE_CALCULATOR_ENABLED = booleanPreferencesKey("disguise_calculator_enabled")
        val KEY_LOCK_BLACK_SCREEN_UNLOCK = booleanPreferencesKey("lock_black_screen_unlock")
    }
}
