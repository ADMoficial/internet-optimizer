package com.internetoptimizer.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.internetoptimizer.app.AppConfig
import com.internetoptimizer.app.ConfigManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the main screen.
 *
 * Holds UI state:
 * - Whether the tunnel is active
 * - Current tunnel status message
 * - Speed test results (before/after comparison)
 * - Navigation events (settings, excluded apps picker)
 */
class MainViewModel(
    private val configManager: ConfigManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    fun loadConfig() {
        val config = configManager.loadConfig()
        _uiState.value = _uiState.value.copy(config = config)
    }

    fun refreshConfig() {
        viewModelScope.launch {
            loadConfig()
        }
    }

    fun toggleTunnel() {
        val current = _uiState.value.isTunnelActive
        _uiState.value = _uiState.value.copy(
            isTunnelActive = !current,
            tunnelStatus = if (!current) {
                "Conectando..."
            } else {
                "Desativado"
            },
        )
    }
}

data class MainUiState(
    val isTunnelActive: Boolean = false,
    val tunnelStatus: String = "Desligado",
    val config: AppConfig = AppConfig(),
    val latencyBeforeMs: Long = -1L,
    val latencyAfterMs: Long = -1L,
    val throughputBeforeMbps: Double = 0.0,
    val throughputAfterMbps: Double = 0.0,
    val showSettings: Boolean = false,
    val showExcludedApps: Boolean = false,
)
