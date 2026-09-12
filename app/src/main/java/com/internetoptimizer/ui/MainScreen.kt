package com.internetoptimizer.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Main screen composable for the Internet Optimizer app.
 *
 * Layout:
 * - Top bar with title and settings icon.
 * - Center: large toggle switch (power button style) that starts/stops the tunnel.
 * - Status indicator below the toggle showing current state.
 * - "Run speed test" button that triggers a before/after benchmark.
 * - Speed test results displayed in cards.
 *
 * All UI text is in Portuguese (user-facing) with no technical jargon.
 * Terms like "VpnService" or "tun2socks" are only in the code, never shown.
 */
@Composable
fun MainScreen(
    uiState: MainUiState,
    onToggleTunnel: () -> Unit,
    onSettingsClick: () -> Unit,
    onSpeedTestClick: () -> Unit,
    onExcludedAppsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isOn = uiState.isTunnelActive

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // ---- Top bar ----
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Internet Optimizer",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                IconButton(onClick = onSettingsClick) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configurações",
                        tint = MaterialTheme.colorScheme.onBackground.copy(0.7f),
                    )
                }
            }

            // ---- Center: toggle + status ----
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.weight(1f),
            ) {
                // Big toggle switch
                Box {
                    val switchColors = if (isOn) {
                        SwitchDefaults.colors(
                            checkedThumbColor = MaterialTheme.colorScheme.primary,
                            checkedTrackColor = MaterialTheme.colorScheme.primary.copy(0.5f),
                            uncheckedThumbColor = MaterialTheme.colorScheme.surfaceVariant,
                            uncheckedTrackColor = MaterialTheme.colorScheme.outline,
                        )
                    } else {
                        SwitchDefaults.colors()
                    }

                    Switch(
                        checked = isOn,
                        onCheckedChange = { onToggleTunnel() },
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape),
                        colors = switchColors,
                    )

                    // Power icon centered over the switch
                    if (isOn) {
                        Icon(
                            imageVector = Icons.Default.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(48.dp),
                        )
                    }
                }

                Text(
                    text = uiState.tunnelStatus,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isOn) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onBackground.copy(0.6f)
                    },
                )

                AnimatedVisibility(visible = isOn) {
                    Text(
                        text = "Apps excluídos: ${uiState.config.excludedApps.size}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(0.5f),
                        modifier = Modifier.clickable { onExcludedAppsClick() },
                    )
                }
            }

            // ---- Speed test section ----
            SpeedTestCard(
                uiState = uiState,
                onSpeedTestClick = onSpeedTestClick,
                onExcludedAppsClick = onExcludedAppsClick,
            )

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SpeedTestCard(
    uiState: MainUiState,
    onSpeedTestClick: () -> Unit,
    onExcludedAppsClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(0.3f),
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Teste de Velocidade",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                TextButton(onClick = onSpeedTestClick) {
                    Text("Executar")
                }
            }

            AnimatedVisibility(visible = uiState.latencyBeforeMs > 0 || uiState.latencyAfterMs > 0) {
                Column(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (uiState.latencyBeforeMs > 0) {
                        MetricRow(
                            label = "Latência antes",
                            value = "${uiState.latencyBeforeMs} ms",
                        )
                    }
                    if (uiState.latencyAfterMs > 0) {
                        MetricRow(
                            label = "Latência depois",
                            value = "${uiState.latencyAfterMs} ms",
                            valueColor = if (uiState.latencyAfterMs < uiState.latencyBeforeMs) {
                                Color(0xFF4CAF50)
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                        )
                    }
                }
            }

            if (uiState.config.excludedApps.isNotEmpty()) {
                TextButton(
                    onClick = onExcludedAppsClick,
                    modifier = Modifier.padding(top = 8.dp),
                ) {
                    Text(
                        text = "Gerenciar apps excluídos (${uiState.config.excludedApps.size})",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricRow(
    label: String,
    value: String,
    valueColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(0.7f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor,
        )
    }
}
