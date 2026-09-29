package com.stash.feature.settings.navidrome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
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
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.stash.data.download.export.NavidromeEndpoint
import com.stash.feature.settings.components.SettingsToggleRow

/**
 * "Navidrome account": the user's own login on their Navidrome server,
 * used to report what they listen to (Navidrome forwards it to Last.fm /
 * ListenBrainz). Separate from the stash-ingest upload connection above it.
 */
@Composable
fun NavidromeAccountSection(
    modifier: Modifier = Modifier,
    viewModel: NavidromeAccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }

    state.message?.let { message ->
        LaunchedEffect(message) {
            kotlinx.coroutines.delay(5_000)
            viewModel.clearMessage()
        }
    }

    Column(
        modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Navidrome account", style = MaterialTheme.typography.titleMedium)
        Text(
            "Your login on the Navidrome server itself. Stash reports what you listen to, and Navidrome " +
                "forwards it to the Last.fm or ListenBrainz accounts you linked there.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = when {
                state.passwordError -> "Encrypted password unavailable — enter it again"
                state.configured -> "${state.username} @ ${state.serverUrl.removePrefix("https://")}"
                else -> "Not connected"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = when {
                state.passwordError -> MaterialTheme.colorScheme.error
                state.configured -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        )

        SettingsToggleRow(
            title = "Report plays to my server",
            subtitle = when {
                !state.configured -> "Connect your account first."
                state.pendingScrobbles > 0 ->
                    "${state.pendingScrobbles} waiting — sent when the server is reachable and has the song."
                else -> "Now playing and finished listens go to Navidrome."
            },
            checked = state.scrobbleEnabled,
            onCheckedChange = viewModel::setScrobbleEnabled,
            enabled = state.configured,
            modifier = Modifier.padding(horizontal = 0.dp),
        )

        state.message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showDialog = true }, modifier = Modifier.weight(1f)) {
                Text(if (state.configured) "Edit account" else "Connect")
            }
            OutlinedButton(
                onClick = viewModel::test,
                enabled = state.configured && !state.checking,
                modifier = Modifier.weight(1f),
            ) {
                Text(if (state.checking) "Checking…" else "Test")
            }
        }
        if (state.configured) {
            TextButton(onClick = { showRemove = true }) { Text("Remove Navidrome account") }
        }
    }

    if (showDialog) {
        NavidromeAccountDialog(
            initialUrl = state.serverUrl,
            initialUsername = state.username,
            passwordConfigured = state.passwordConfigured,
            onSave = { url, user, password ->
                viewModel.save(url, user, password)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
    if (showRemove) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("Remove Navidrome account?") },
            text = { Text("Stash stops reporting plays. Nothing on the server is changed.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clear()
                    showRemove = false
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NavidromeAccountDialog(
    initialUrl: String,
    initialUsername: String,
    passwordConfigured: Boolean,
    onSave: (String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var user by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    val urlValid = NavidromeEndpoint.normalize(url) != null
    // The saved password only ever goes to the server it was entered for.
    val canKeepPassword = passwordConfigured &&
        NavidromeEndpoint.normalize(url) == NavidromeEndpoint.normalize(initialUrl)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Navidrome account") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Use the HTTPS address you open Navidrome with. The password is encrypted with " +
                        "Android Keystore and never shown again. A separate non-admin user is recommended.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Navidrome URL") },
                    placeholder = { Text("https://music.example.com") },
                    isError = url.isNotBlank() && !urlValid,
                    supportingText = if (url.isNotBlank() && !urlValid) {
                        { Text("Use an HTTPS URL without credentials, query, or fragment.") }
                    } else {
                        null
                    },
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Username") },
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(if (canKeepPassword) "New password (optional)" else "Password") },
                    supportingText = when {
                        canKeepPassword -> { { Text("Leave blank to keep the saved password.") } }
                        passwordConfigured -> { { Text("New server: enter the password again.") } }
                        else -> null
                    },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(url, user, password) },
                enabled = urlValid && user.isNotBlank() && (canKeepPassword || password.isNotBlank()),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
