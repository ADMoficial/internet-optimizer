package com.internetoptimizer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.internetoptimizer.app.AppConfig
import com.internetoptimizer.app.ProxyType

/**
 * Settings screen for configuring DNS, proxy, and excluded apps.
 *
 * Sections:
 * 1. DNS endpoint — editable DoH URL and upstream IP.
 * 2. Proxy — optional SOCKS5 or HTTP CONNECT proxy.
 * 3. Auto-start on boot switch.
 * 4. Telemetry toggle (opt-in).
 * 5. "Apps excluídos" — navigates to app picker.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
    onNavigateBack: () -> Unit,
    onExcludedAppsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Configurações") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Voltar",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            DnsSection(config, onConfigChange)
            ProxySection(config, onConfigChange)
            ToggleSection(config, onConfigChange)
            ExcludedAppsSection(config, onExcludedAppsClick)
        }
    }
}

@Composable
private fun DnsSection(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
) {
    var dnsEndpoint by rememberSaveable { mutableStateOf(config.dnsEndpoint) }
    var dnsUpstream by rememberSaveable { mutableStateOf(config.dnsUpstreamIp) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "DNS sobre HTTPS",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        OutlinedTextField(
            value = dnsEndpoint,
            onValueChange = { newVal ->
                dnsEndpoint = newVal
                onConfigChange(config.copy(dnsEndpoint = newVal))
            },
            label = { Text("Endpoint DoH") },
            placeholder = { Text("https://1.1.1.1/dns-query") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = dnsUpstream,
            onValueChange = { newVal ->
                dnsUpstream = newVal
                onConfigChange(config.copy(dnsUpstreamIp = newVal))
            },
            label = { Text("IP DNS") },
            placeholder = { Text("1.1.1.1") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun ProxySection(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
) {
    var isEnabled by rememberSaveable { mutableStateOf(config.isProxyEnabled) }
    var proxyType by rememberSaveable { mutableStateOf(config.proxyType) }
    var host by rememberSaveable { mutableStateOf(config.proxyHost) }
    var port by rememberSaveable { mutableStateOf(config.proxyPort.toString()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Proxy (opcional)",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Switch(
            checked = isEnabled,
            onCheckedChange = { enabled ->
                isEnabled = enabled
                onConfigChange(config.copy(isProxyEnabled = enabled))
            },
        )

        if (isEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    proxyType = ProxyType.SOCKS5
                    onConfigChange(config.copy(proxyType = ProxyType.SOCKS5))
                }) {
                    Text("SOCKS5")
                }
                OutlinedButton(onClick = {
                    proxyType = ProxyType.HTTP_CONNECT
                    onConfigChange(config.copy(proxyType = ProxyType.HTTP_CONNECT))
                }) {
                    Text("HTTP")
                }
            }

            OutlinedTextField(
                value = host,
                onValueChange = { newHost ->
                    host = newHost
                    onConfigChange(config.copy(proxyHost = newHost))
                },
                label = { Text("Host / IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = port,
                onValueChange = { newPort ->
                    if (newPort.all { it.isDigit() }) {
                        port = newPort
                        val portInt = newPort.toIntOrNull() ?: 0
                        onConfigChange(config.copy(proxyPort = portInt))
                    }
                },
                label = { Text("Porta") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun ToggleSection(
    config: AppConfig,
    onConfigChange: (AppConfig) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Preferências",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Iniciar automaticamente")
            Switch(
                checked = config.autoStart,
                onCheckedChange = { onConfigChange(config.copy(autoStart = it)) },
            )
        }

        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Telemetria (opcional)")
            Switch(
                checked = config.telemetryOptIn,
                onCheckedChange = { onConfigChange(config.copy(telemetryOptIn = it)) },
            )
        }
    }
}

@Composable
private fun ExcludedAppsSection(
    config: AppConfig,
    onExcludedAppsClick: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Apps excluídos",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        if (config.excludedApps.isNotEmpty()) {
            Text(
                text = "${config.excludedApps.size} app(s) excluído(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(0.7f),
            )
        }

        Button(
            onClick = onExcludedAppsClick,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Gerenciar apps excluídos")
        }
    }
}
