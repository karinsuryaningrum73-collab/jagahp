package com.example.security

import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DevicePreferences
import com.example.data.SecurityConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SecurityUiState(
    val isInitialized: Boolean = false,
    val isUnlocked: Boolean = false,
    val config: SecurityConfig = SecurityConfig(),
    val failedAttempts: Int = 0,
    val lockoutRemainingSeconds: Int = 0,
    val pinError: String? = null
)

class SecurityViewModel(
    private val preferences: DevicePreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(SecurityUiState())
    val uiState: StateFlow<SecurityUiState> = _uiState.asStateFlow()

    private var lastStoppedElapsedMs: Long = 0L

    init {
        viewModelScope.launch {
            val initialConfig = preferences.securityConfigFlow.first()
            _uiState.update {
                it.copy(
                    isInitialized = true,
                    isUnlocked = !initialConfig.isProtectionActive,
                    config = initialConfig
                )
            }
            preferences.securityConfigFlow.collect { config ->
                _uiState.update { current ->
                    current.copy(
                        config = config,
                        isUnlocked = if (!config.isProtectionActive) true else current.isUnlocked
                    )
                }
            }
        }
    }

    suspend fun verifyPinAndUnlock(pin: String, showErrorOnMismatch: Boolean): Boolean {
        if (_uiState.value.lockoutRemainingSeconds > 0) return false
        val matched = preferences.verifyPin(pin)
        if (matched) {
            _uiState.update {
                it.copy(
                    isUnlocked = true,
                    failedAttempts = 0,
                    lockoutRemainingSeconds = 0,
                    pinError = null
                )
            }
            return true
        }

        if (showErrorOnMismatch) {
            val attempts = _uiState.value.failedAttempts + 1
            if (attempts >= 5) {
                _uiState.update {
                    it.copy(
                        failedAttempts = 0,
                        lockoutRemainingSeconds = 15,
                        pinError = "Terlalu banyak percobaan salah. Tunggu 15 detik."
                    )
                }
                startLockoutCountdown()
            } else {
                _uiState.update {
                    it.copy(
                        failedAttempts = attempts,
                        pinError = "PIN salah ($attempts/5)"
                    )
                }
            }
        }
        return false
    }

    suspend fun verifyPinDirect(pin: String): Boolean = preferences.verifyPin(pin)

    fun clearPinError() {
        _uiState.update { it.copy(pinError = null) }
    }

    fun lockNow() {
        if (_uiState.value.config.isProtectionActive) {
            _uiState.update {
                it.copy(
                    isUnlocked = false,
                    pinError = null
                )
            }
        }
    }

    fun onAppBackgrounded() {
        lastStoppedElapsedMs = SystemClock.elapsedRealtime()
    }

    fun onAppForegrounded(isHostingCamera: Boolean) {
        val stoppedAt = lastStoppedElapsedMs
        lastStoppedElapsedMs = 0L
        if (stoppedAt <= 0L) return
        val elapsedInBackground = SystemClock.elapsedRealtime() - stoppedAt
        // Auto-lock if left in background for more than 8 seconds (avoids locking during brief permission dialogs)
        if (!isHostingCamera && elapsedInBackground > 8_000L && _uiState.value.config.isProtectionActive) {
            lockNow()
        } else if (isHostingCamera && elapsedInBackground > 20_000L && _uiState.value.config.disguiseAsCalculator) {
            lockNow()
        }
    }

    fun savePin(pin: String, onDone: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = preferences.savePin(pin)
            onDone(ok)
        }
    }

    fun setPinLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setPinLockEnabled(enabled)
        }
    }

    fun setDisguiseAsCalculator(context: Context, enabled: Boolean, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val applied = StealthManager.applyCalculatorDisguise(context, enabled)
            if (applied) {
                preferences.setDisguiseAsCalculator(enabled)
            }
            onResult(applied)
        }
    }

    fun setLockBlackScreenUnlock(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setLockBlackScreenUnlock(enabled)
        }
    }

    fun disableAllSecurity(context: Context) {
        viewModelScope.launch {
            StealthManager.applyCalculatorDisguise(context, false)
            preferences.disableAllSecurity()
            _uiState.update {
                it.copy(
                    isUnlocked = true,
                    failedAttempts = 0,
                    lockoutRemainingSeconds = 0,
                    pinError = null
                )
            }
        }
    }

    private fun startLockoutCountdown() {
        viewModelScope.launch {
            while (_uiState.value.lockoutRemainingSeconds > 0) {
                delay(1000L)
                _uiState.update { state ->
                    val next = (state.lockoutRemainingSeconds - 1).coerceAtLeast(0)
                    state.copy(
                        lockoutRemainingSeconds = next,
                        pinError = if (next > 0) "Tunggu $next detik sebelum mencoba lagi." else null
                    )
                }
            }
        }
    }
}
