package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.DevicePreferences
import com.example.data.PairedRoomHistoryItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RoleSelectionUiState(
    val savedCameraCode: String? = null,
    val pairedHistory: List<PairedRoomHistoryItem> = emptyList()
)

class RoleSelectionViewModel(
    private val preferences: DevicePreferences
) : ViewModel() {

    val uiState: StateFlow<RoleSelectionUiState> = combine(
        preferences.cameraRoomCodeFlow,
        preferences.pairedHistoryFlow
    ) { cameraCode, history ->
        RoleSelectionUiState(
            savedCameraCode = cameraCode,
            pairedHistory = history
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000L),
        initialValue = RoleSelectionUiState()
    )

    fun removeHistoryItem(roomCode: String) {
        viewModelScope.launch {
            preferences.removePairedRoomHistory(roomCode)
        }
    }
}
