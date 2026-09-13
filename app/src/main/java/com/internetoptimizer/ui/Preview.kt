package com.internetoptimizer.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

/**
 * Preview composables for the MainScreen and SettingsScreen.
 *
 * These allow design-time preview in Android Studio without requiring
 * a running device.
 */
@Preview(showBackground = true, widthDp = 1080, heightDp = 2340)
@Composable
fun MainScreenPreview() {
    MainScreen(
        uiState = MainUiState(
            isTunnelActive = false,
            tunnelStatus = "Toque para ativar",
            config = com.internetoptimizer.app.AppConfig(),
        ),
        onToggleTunnel = {},
        onSettingsClick = {},
        onSpeedTestClick = {},
        onExcludedAppsClick = {},
    )
}

@Preview(showBackground = true, widthDp = 1080, heightDp = 2340)
@Composable
fun MainScreenActivePreview() {
    MainScreen(
        uiState = MainUiState(
            isTunnelActive = true,
            tunnelStatus = "Otimizando conexão...",
            config = com.internetoptimizer.app.AppConfig(),
            latencyBeforeMs = 85L,
            latencyAfterMs = 22L,
        ),
        onToggleTunnel = {},
        onSettingsClick = {},
        onSpeedTestClick = {},
        onExcludedAppsClick = {},
    )
}
