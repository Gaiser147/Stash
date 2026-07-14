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
import com.stash.data.download.export.NavidromeEndpoint
import com.stash.feature.settings.SettingsUiState
import java.text.DateFormat
import java.util.Date

internal data class NavidromeExportStatus(
    val message: String,
    val isError: Boolean = false,
)

internal fun navidromeExportStatus(result: String): NavidromeExportStatus? = when (result) {
    "configuration_saved" -> NavidromeExportStatus("Connection settings saved")
    "configuration_cleared" -> NavidromeExportStatus("Connection removed")
    "full_export_queued" -> NavidromeExportStatus("Full library export is queued")
    "export_in_progress" -> NavidromeExportStatus("Export is running or waiting to retry")
    "track_uploaded" -> NavidromeExportStatus("Latest track export completed")
    "full_export_complete" -> NavidromeExportStatus("Full library export completed")
    "playlist_export_complete" -> NavidromeExportStatus("Playlist export completed")
    "export_incomplete" -> NavidromeExportStatus("Latest export completed with errors", isError = true)
    "track_upload_failed" -> NavidromeExportStatus("Latest audio upload failed", isError = true)
    "cover_upload_failed" -> NavidromeExportStatus("Latest artwork upload failed", isError = true)
    else -> null
}

@Composable
fun NavidromeExportSection(
    state: SettingsUiState,
    onSaveConnection: (String, String) -> Unit,
    onEnabledChanged: (Boolean) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onChargingOnlyChanged: (Boolean) -> Unit,
    onTestConnection: () -> Unit,
    onSyncNow: () -> Unit,
    onClearConnection: () -> Unit,
    onClearMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showConfiguration by remember { mutableStateOf(false) }
    var showRemoveConfirmation by remember { mutableStateOf(false) }
    val configured = state.navidromeExportUrl.isNotBlank() && state.navidromeExportTokenConfigured

    state.navidromeExportMessage?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(4_000)
            onClearMessage()
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Navidrome export",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Uploads only downloaded audio, album art, and playlist files to your authenticated stash-ingest endpoint. It never sends Spotify account data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                state.navidromeExportTokenError -> "Encrypted token unavailable — enter a new token"
                !configured -> "Not configured"
                state.navidromeExportEnabled -> "Automatic export is on"
                else -> "Configured, automatic export is off"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                state.navidromeExportTokenError -> MaterialTheme.colorScheme.error
                configured -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        SettingsToggleRow(
            title = "Automatic export",
            subtitle = "New downloads and changed playlist manifests are queued in the background.",
            checked = state.navidromeExportEnabled,
            onCheckedChange = { enabled ->
                if (enabled && !configured) showConfiguration = true else onEnabledChanged(enabled)
            },
            modifier = Modifier.padding(horizontal = 0.dp),
        )
        SettingsToggleRow(
            title = "Unmetered network only",
            subtitle = "Recommended for large lossless files.",
            checked = state.navidromeExportWifiOnly,
            onCheckedChange = onWifiOnlyChanged,
            enabled = configured,
            modifier = Modifier.padding(horizontal = 0.dp),
        )
        SettingsToggleRow(
            title = "Only while charging",
            subtitle = "WorkManager also requires a healthy battery.",
            checked = state.navidromeExportChargingOnly,
            onCheckedChange = onChargingOnlyChanged,
            enabled = configured,
            modifier = Modifier.padding(horizontal = 0.dp),
        )

        if (state.navidromeExportLastAttemptAt > 0L) {
            val formatted = remember(state.navidromeExportLastAttemptAt) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(state.navidromeExportLastAttemptAt))
            }
            Text(
                text = "Last export attempt: $formatted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        navidromeExportStatus(state.navidromeExportLastResult)?.let { status ->
            Text(
                text = status.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (status.isError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (state.navidromeExportLastSuccessAt > 0L &&
            state.navidromeExportLastSuccessAt != state.navidromeExportLastAttemptAt
        ) {
            val formatted = remember(state.navidromeExportLastSuccessAt) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(Date(state.navidromeExportLastSuccessAt))
            }
            Text(
                text = "Last successful export: $formatted",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        state.navidromeExportMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }

        OutlinedButton(
            onClick = onTestConnection,
            enabled = configured && !state.navidromeExportConnectionChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.navidromeExportConnectionChecking) "Checking connection…" else "Test connection")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { showConfiguration = true }, modifier = Modifier.weight(1f)) {
                Text(if (configured) "Edit connection" else "Configure")
            }
            Button(
                onClick = onSyncNow,
                enabled = configured && state.navidromeExportEnabled,
                modifier = Modifier.weight(1f),
            ) {
                Text("Sync all now")
            }
        }
        if (configured) {
            TextButton(onClick = { showRemoveConfirmation = true }) {
                Text("Remove Navidrome connection")
            }
        }
    }

    if (showConfiguration) {
        NavidromeConnectionDialog(
            initialUrl = state.navidromeExportUrl,
            tokenAlreadyConfigured = state.navidromeExportTokenConfigured,
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
            title = { Text("Remove Navidrome connection?") },
            text = { Text("Pending export work will become inert. Local music and remote Navidrome files are not deleted.") },
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
private fun NavidromeConnectionDialog(
    initialUrl: String,
    tokenAlreadyConfigured: Boolean,
    onSave: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var token by remember { mutableStateOf("") }
    val endpointValid = NavidromeEndpoint.normalize(url) != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Navidrome ingest connection") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use the HTTPS base URL of your stash-ingest service. The bearer token is encrypted with Android Keystore and is never shown again.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("HTTPS ingest URL") },
                    placeholder = { Text("https://music.example.com/stash-ingest") },
                    isError = url.isNotBlank() && !endpointValid,
                    supportingText = if (url.isNotBlank() && !endpointValid) {
                        { Text("Use an HTTPS URL without credentials, query, or fragment.") }
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
                        null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url, token) },
                enabled = endpointValid && (tokenAlreadyConfigured || token.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
