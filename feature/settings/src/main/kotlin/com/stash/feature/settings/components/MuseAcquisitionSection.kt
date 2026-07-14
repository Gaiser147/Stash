package com.stash.feature.settings.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.stash.data.download.acquisition.MuseAcquisitionEndpoint
import com.stash.feature.settings.SettingsUiState
import java.text.DateFormat
import java.util.Date

private data class MuseAcquisitionStatus(val message: String, val isError: Boolean = false)

private fun status(result: String): MuseAcquisitionStatus? = when (result) {
    "configuration_saved" -> MuseAcquisitionStatus("Connection settings saved")
    "configuration_cleared" -> MuseAcquisitionStatus("Connection removed")
    "poll_complete" -> MuseAcquisitionStatus("Inbox check completed")
    "track_downloaded" -> MuseAcquisitionStatus("Latest requested track downloaded")
    "no_confident_match" -> MuseAcquisitionStatus("Request rejected: no unique safe match", isError = true)
    "waiting_for_lossless" -> MuseAcquisitionStatus("Request is waiting for a lossless source")
    "download_failed" -> MuseAcquisitionStatus("Latest requested download will retry", isError = true)
    "connection_failed" -> MuseAcquisitionStatus("Latest inbox check failed", isError = true)
    else -> null
}

@Composable
fun MuseAcquisitionSection(
    state: SettingsUiState,
    onSaveConnection: (String, String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onChargingOnlyChanged: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onCheckNow: () -> Unit,
    onClearConnection: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfiguration by remember { mutableStateOf(false) }
    var showRemoveConfirmation by remember { mutableStateOf(false) }
    val configured = state.museAcquisitionUrl.isNotBlank() && state.museAcquisitionTokenConfigured

    state.museAcquisitionMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(4_000)
            onClearMessage()
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Muse request inbox", style = MaterialTheme.typography.titleMedium)
        Text(
            "Downloads only manually confirmed Discord searches from your private Muse endpoint. " +
                "Spotify identities, playlists, and account data are never included.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                state.museAcquisitionTokenError -> "Encrypted token unavailable — enter a new token"
                !configured -> "Not configured"
                state.museAcquisitionEnabled -> "Automatic checks are on"
                else -> "Configured, automatic checks are off"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                state.museAcquisitionTokenError -> MaterialTheme.colorScheme.error
                configured -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        SettingsToggleRow(
            title = "Automatic request checks",
            subtitle = "Checks at most every 15 minutes and claims up to three requests per run.",
            checked = state.museAcquisitionEnabled,
            onCheckedChange = { enabled ->
                if (enabled && !configured) showConfiguration = true else onEnabledChanged(enabled)
            },
            modifier = Modifier.padding(horizontal = 0.dp),
        )
        SettingsToggleRow(
            title = "Unmetered network only",
            subtitle = "Recommended for lossless downloads.",
            checked = state.museAcquisitionWifiOnly,
            onCheckedChange = onWifiOnlyChanged,
            enabled = configured,
            modifier = Modifier.padding(horizontal = 0.dp),
        )
        SettingsToggleRow(
            title = "Only while charging",
            subtitle = "Low battery and low storage always pause this work.",
            checked = state.museAcquisitionChargingOnly,
            onCheckedChange = onChargingOnlyChanged,
            enabled = configured,
            modifier = Modifier.padding(horizontal = 0.dp),
        )

        if (state.museAcquisitionPendingCount > 0) {
            Text(
                "Pending at last check: ${state.museAcquisitionPendingCount}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.museAcquisitionLastAttemptAt > 0L) {
            val formatted = remember(state.museAcquisitionLastAttemptAt) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(state.museAcquisitionLastAttemptAt))
            }
            Text(
                "Last inbox check: $formatted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        status(state.museAcquisitionLastResult)?.let { result ->
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.museAcquisitionMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        OutlinedButton(
            onClick = onTestConnection,
            enabled = configured && !state.museAcquisitionConnectionChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.museAcquisitionConnectionChecking) "Checking connection…" else "Test connection")
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showConfiguration = true }, modifier = Modifier.weight(1f)) {
                Text(if (configured) "Edit connection" else "Configure")
            }
            Button(
                onClick = onCheckNow,
                enabled = configured && state.museAcquisitionEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Check now")
            }
        }
        if (configured) {
            TextButton(onClick = { showRemoveConfirmation = true }) {
                Text("Remove Muse inbox connection")
            }
        }
    }

    if (showConfiguration) {
        MuseAcquisitionConnectionDialog(
            initialUrl = state.museAcquisitionUrl,
            tokenAlreadyConfigured = state.museAcquisitionTokenConfigured,
            onSave = { url, token ->
                onSaveConnection(url, token)
                showConfiguration = false
            },
            onDismiss = { showConfiguration = false },
        )
    }

    if (showRemoveConfirmation) {
        AlertDialog(
            onDismissRequest = { showRemoveConfirmation = false },
            title = { Text("Remove Muse inbox connection?") },
            text = { Text("Pending requests stay on Muse and no local music is deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    onClearConnection()
                    showRemoveConfirmation = false
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { showRemoveConfirmation = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MuseAcquisitionConnectionDialog(
    initialUrl: String,
    tokenAlreadyConfigured: Boolean,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    val endpointValid = MuseAcquisitionEndpoint.normalize(url) != null
    val tokenValid = tokenAlreadyConfigured || token.length >= 32

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Private Muse inbox") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the private HTTPS URL in front of Muse's acquisition inbox. The bearer token is " +
                        "encrypted with Android Keystore and never shown again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Private HTTPS URL") },
                    placeholder = { Text("https://muse.example.net/acquisition") },
                    isError = url.isNotBlank() && !endpointValid,
                    supportingText = if (url.isNotBlank() && !endpointValid) {
                        { Text("Use HTTPS without credentials, query, or fragment.") }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (tokenAlreadyConfigured) "New token (optional)" else "Bearer token") },
                    supportingText = if (tokenAlreadyConfigured) {
                        { Text("Leave blank to keep the encrypted token.") }
                    } else {
                        { Text("At least 32 characters.") }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url, token) },
                enabled = endpointValid && tokenValid,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
