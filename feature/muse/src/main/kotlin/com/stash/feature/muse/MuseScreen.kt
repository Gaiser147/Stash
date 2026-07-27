package com.stash.feature.muse

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage

@Composable
fun MuseScreen(
    onOpenSync: () -> Unit,
) {
    val viewModel: MuseViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val importDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::onSpotifyDocumentSelected)
    }

    DisposableEffect(viewModel) {
        viewModel.onVisible()
        onDispose(viewModel::onHidden)
    }

    MuseScreenContent(
        state = state,
        onOpenSync = onOpenSync,
        onSelectSpotifyDocument = {
            importDocument.launch(
                arrayOf(
                    "application/json",
                    "text/csv",
                    "audio/x-mpegurl",
                    "application/vnd.apple.mpegurl",
                    "text/plain",
                ),
            )
        },
        viewModel = viewModel,
    )
}

@Composable
private fun MuseScreenContent(
    state: MuseUiState,
    onOpenSync: () -> Unit,
    onSelectSpotifyDocument: () -> Unit,
    viewModel: MuseViewModel,
) {
    var showDisconnectConfirmation by remember { mutableStateOf(false) }
    var showStopConfirmation by remember { mutableStateOf(false) }
    var showClearConfirmation by remember { mutableStateOf(false) }
    var showImportIdentityConfirmation by remember { mutableStateOf(false) }
    var showSpotifyDisconnectConfirmation by remember { mutableStateOf(false) }

    if (showDisconnectConfirmation) {
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmation = false },
            icon = { Icon(Icons.Default.WarningAmber, contentDescription = null) },
            title = { Text("Gerät widerrufen und trennen?") },
            text = {
                Text(
                    "Muse widerruft den Zugriff serverseitig. Erst nach der Bestätigung löscht Stash " +
                        "die lokalen Tokens und den nicht exportierbaren Geräteschlüssel.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDisconnectConfirmation = false
                    viewModel.disconnectLocally()
                }) { Text("Widerrufen") }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmation = false }) { Text("Abbrechen") }
            },
        )
    }

    if (showStopConfirmation) {
        ConfirmationDialog(
            title = "Discord-Wiedergabe stoppen?",
            body = "Muse stoppt die aktuelle Sprachsitzung. Die Queue bleibt gemäß Serverzustand erhalten.",
            confirmLabel = "Stoppen",
            onDismiss = { showStopConfirmation = false },
            onConfirm = {
                showStopConfirmation = false
                viewModel.stop()
            },
        )
    }

    if (showClearConfirmation) {
        ConfirmationDialog(
            title = "Queue leeren?",
            body = "Der laufende Titel bleibt erhalten. Muse bietet die serverseitige Undo-Funktion kurzzeitig an.",
            confirmLabel = "Queue leeren",
            onDismiss = { showClearConfirmation = false },
            onConfirm = {
                showClearConfirmation = false
                viewModel.clearQueue()
            },
        )
    }

    if (showImportIdentityConfirmation) {
        ConfirmationDialog(
            title = "Privates Importprofil anlegen?",
            body = "Stash erzeugt eine zufällige, verschlüsselt gespeicherte Profil-ID für diese Installation. " +
                "Sie enthält keinen Spotify-Login. Erst eine später ausdrücklich ausgewählte Datei wird lokal " +
                "bereinigt und nach einer zweiten Bestätigung als privates Manifest übertragen.",
            confirmLabel = "Profil anlegen",
            onDismiss = { showImportIdentityConfirmation = false },
            onConfirm = {
                showImportIdentityConfirmation = false
                viewModel.createSpotifyImportIdentity()
            },
        )
    }

    if (showSpotifyDisconnectConfirmation) {
        ConfirmationDialog(
            title = "Privates Playlistprofil löschen?",
            body = "Muse löscht die private Importgeneration atomar. Danach entfernt Stash auch die lokale " +
                "Profil-ID. Lokale Musikdateien und getrennte Navidrome-Playlists werden nicht gelöscht.",
            confirmLabel = "Profil löschen",
            onDismiss = { showSpotifyDisconnectConfirmation = false },
            onConfirm = {
                showSpotifyDisconnectConfirmation = false
                viewModel.disconnectSpotifyImport()
            },
        )
    }

    state.importPreview?.let { preview ->
        AlertDialog(
            onDismissRequest = viewModel::cancelSpotifyImportPreview,
            title = { Text("Private Generation ${preview.generation} übertragen?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Format: ${preview.format}")
                    Text("${preview.playlistCount} Playlists · ${preview.itemCount} Titel")
                    Text("Normalisierte Größe: ${formatByteSize(preview.byteSize)}")
                    Text(
                        "0 übersprungene Einträge. Muse übernimmt diese vollständige Generation atomar; " +
                            "bei einem Fehler bleibt die letzte gute Generation unverändert.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                Button(onClick = viewModel::confirmSpotifyImport, enabled = !state.importBusy) {
                    if (state.importBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text("Privat übertragen")
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelSpotifyImportPreview, enabled = !state.importBusy) {
                    Text("Abbrechen")
                }
            },
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Muse", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "Discord Player · Queue · Geräte",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedButton(onClick = onOpenSync) {
                    Icon(Icons.Default.Sync, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Stash Sync")
                }
            }
        }

        item { InvitationOnlyNotice() }

        state.message?.let { message ->
            item { NoticeCard(message, isError = false, onDismiss = viewModel::clearNotice) }
        }
        state.error?.let { error ->
            item { NoticeCard(error, isError = true, onDismiss = viewModel::clearNotice) }
        }

        when (state.connectionStage) {
            MuseConnectionStage.UNCONFIGURED,
            MuseConnectionStage.READY_TO_PAIR,
            -> item {
                PairingSetupCard(
                    state = state,
                    onEndpointChanged = viewModel::onEndpointChanged,
                    onSaveEndpoint = viewModel::saveEndpoint,
                    onStartPairing = viewModel::startPairing,
                )
            }
            MuseConnectionStage.PAIRING -> item {
                PairingCodeCard(state, viewModel::cancelPairing)
            }
            MuseConnectionStage.PAIRED -> {
                item {
                    PlaybackTargetSelector(
                        selected = state.playbackTarget,
                        onSelected = viewModel::setPlaybackTarget,
                    )
                }
                item {
                    MuseSectionSelector(state.section, viewModel::setSection)
                }
                if (state.playbackTarget == MusePlaybackTarget.DEVICE && state.section != MuseSection.DEVICES) {
                    item { LocalPlayerCard() }
                } else {
                    when (state.section) {
                        MuseSection.PLAYER -> item {
                            DiscordPlayerCard(
                                state = state,
                                onRefresh = viewModel::reconcileNow,
                                onPrevious = viewModel::previous,
                                onBack = { viewModel.seekRelative(-15) },
                                onTogglePause = viewModel::togglePause,
                                onForward = { viewModel.seekRelative(30) },
                                onNext = viewModel::next,
                                onSeek = viewModel::seekTo,
                                onVolume = viewModel::setVolume,
                                onRepeatSong = viewModel::toggleRepeatSong,
                                onRepeatQueue = viewModel::toggleRepeatQueue,
                                onAutoplay = viewModel::toggleAutoplay,
                                onStop = { showStopConfirmation = true },
                            )
                        }
                        MuseSection.QUEUE -> item {
                            DiscordQueueCard(
                                state = state,
                                onShuffle = viewModel::shuffle,
                                onUndo = viewModel::undoQueueChange,
                                onClear = { showClearConfirmation = true },
                                onRemove = viewModel::removeQueueEntry,
                                onMove = viewModel::moveQueueEntry,
                            )
                        }
                        MuseSection.DEVICES -> item {
                            DeviceCard(
                                state = state,
                                onDisconnect = { showDisconnectConfirmation = true },
                                onCreateImportIdentity = { showImportIdentityConfirmation = true },
                                onSelectImportDocument = onSelectSpotifyDocument,
                                onDisconnectSpotifyImport = { showSpotifyDisconnectConfirmation = true },
                            )
                        }
                    }
                }
            }
        }

        item {
            ContractRoadmapCard()
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun InvitationOnlyNotice() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.WarningAmber, contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Einladungs-/Testbetrieb", fontWeight = FontWeight.SemiBold)
                Text(
                    "Der aktuelle v1-Server bestätigt den Android-Schlüssel erst bei authentifizierten " +
                        "Folgeanfragen. Die zusätzliche signierte Pairing-Challenge ist vor Produktion erforderlich.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun PairingSetupCard(
    state: MuseUiState,
    onEndpointChanged: (String) -> Unit,
    onSaveEndpoint: () -> Unit,
    onStartPairing: () -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Muse verbinden", style = MaterialTheme.typography.titleLarge)
            Text(
                "Trage die HTTPS-Adresse deines Muse-Gateways ein. Stash enthält absichtlich keinen " +
                    "voreingestellten Produktionsserver.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = state.endpointDraft,
                onValueChange = onEndpointChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Muse HTTPS-Adresse") },
                placeholder = { Text("https://music.example.net") },
                singleLine = true,
                enabled = !state.busy,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSaveEndpoint,
                    enabled = !state.busy && state.endpointDraft.isNotBlank(),
                ) { Text("Adresse speichern") }
                Button(
                    onClick = onStartPairing,
                    enabled = !state.busy && state.endpoint != null,
                ) {
                    if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Link, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Koppeln")
                }
            }
        }
    }
}

@Composable
private fun PairingCodeCard(state: MuseUiState, onCancel: () -> Unit) {
    val pairing = state.pairing ?: return
    Card {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("In Discord bestätigen", style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(
                    pairing.code.chunked(4).joinToString(" "),
                    style = MaterialTheme.typography.displaySmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text("/music → Geräte · gültig bis ${pairing.expiresAt}")
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Warte auf Freigabe …")
            }
            TextButton(onClick = onCancel, enabled = !state.busy) { Text("Abbrechen") }
        }
    }
}

@Composable
private fun PlaybackTargetSelector(
    selected: MusePlaybackTarget,
    onSelected: (MusePlaybackTarget) -> Unit,
) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Wiedergabeziel", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selected == MusePlaybackTarget.DEVICE,
                    onClick = { onSelected(MusePlaybackTarget.DEVICE) },
                    label = { Text("Dieses Gerät") },
                    leadingIcon = { Icon(Icons.Default.Devices, contentDescription = null) },
                )
                FilterChip(
                    selected = selected == MusePlaybackTarget.DISCORD,
                    onClick = { onSelected(MusePlaybackTarget.DISCORD) },
                    label = { Text("Discord") },
                    leadingIcon = { Icon(Icons.Default.QueueMusic, contentDescription = null) },
                )
            }
        }
    }
}

@Composable
private fun MuseSectionSelector(selected: MuseSection, onSelected: (MuseSection) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MuseSection.entries.forEach { section ->
            FilterChip(
                selected = selected == section,
                onClick = { onSelected(section) },
                label = {
                    Text(
                        when (section) {
                            MuseSection.PLAYER -> "Player"
                            MuseSection.QUEUE -> "Queue"
                            MuseSection.DEVICES -> "Gerät & Sync"
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun LocalPlayerCard() {
    Card {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Lokaler Stash-Player", style = MaterialTheme.typography.titleLarge)
            Text(
                "Der bestehende Media3-Player bleibt unverändert aktiv. Nutze den Mini-Player unten, " +
                    "um lokale Titel zu steuern. Queue-Handoff zu Discord folgt in einer späteren Welle.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun DiscordPlayerCard(
    state: MuseUiState,
    onRefresh: () -> Unit,
    onPrevious: () -> Unit,
    onBack: () -> Unit,
    onTogglePause: () -> Unit,
    onForward: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Int) -> Unit,
    onVolume: (Int) -> Unit,
    onRepeatSong: () -> Unit,
    onRepeatQueue: () -> Unit,
    onAutoplay: () -> Unit,
    onStop: () -> Unit,
) {
    val snapshot = state.snapshot
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(snapshot?.guild?.name ?: "Discord", style = MaterialTheme.typography.titleLarge)
                    Text(
                        snapshot?.guild?.voiceChannel?.name ?: "Kein aktiver Sprachkanal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (state.eventStreamConnected) Icons.Default.CheckCircle else Icons.Default.WarningAmber,
                        contentDescription = null,
                        tint = if (state.eventStreamConnected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Neu laden")
                    }
                }
            }

            if (snapshot == null) {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            val current = snapshot.player.current
            if (current == null) {
                Text("Muse wartet auf Musik.", style = MaterialTheme.typography.titleMedium)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(
                        model = current.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.size(88.dp).clip(MaterialTheme.shapes.medium),
                        contentScale = ContentScale.Crop,
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(current.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(current.artist, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            listOfNotNull(current.source.sourceLabel(), current.qualityLabel).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(snapshot.player.phase.phaseLabel(), style = MaterialTheme.typography.bodySmall)
                    }
                }

                var seekPosition by remember(
                    snapshot.player.playerRevision,
                    snapshot.player.positionSeconds,
                ) { mutableFloatStateOf(snapshot.player.positionSeconds.toFloat()) }
                val duration = current.durationSeconds.coerceAtLeast(1)
                Slider(
                    value = seekPosition.coerceIn(0f, duration.toFloat()),
                    onValueChange = { seekPosition = it },
                    onValueChangeFinished = { onSeek(seekPosition.toInt()) },
                    valueRange = 0f..duration.toFloat(),
                    enabled = snapshot.permissions.canControl && !state.actionBusy,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(seekPosition.toInt()), style = MaterialTheme.typography.labelSmall)
                    Text(formatDuration(current.durationSeconds), style = MaterialTheme.typography.labelSmall)
                }
            }

            val canControl = snapshot.permissions.canControl && !state.actionBusy
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrevious, enabled = canControl) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "Zurück")
                }
                TextButton(onClick = onBack, enabled = canControl) { Text("−15 s") }
                FilledIconButton(onClick = onTogglePause, enabled = canControl) {
                    val paused = snapshot.player.phase == "paused" || snapshot.player.status == "paused"
                    Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, contentDescription = null)
                }
                TextButton(onClick = onForward, enabled = canControl) { Text("+30 s") }
                IconButton(onClick = onNext, enabled = canControl) {
                    Icon(Icons.Default.SkipNext, contentDescription = "Weiter")
                }
            }

            var volume by remember(snapshot.player.playerRevision, snapshot.player.volume) {
                mutableFloatStateOf(snapshot.player.volume.toFloat())
            }
            Text("Lautstärke ${volume.toInt()} %", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = volume,
                onValueChange = { volume = it },
                onValueChangeFinished = { onVolume(volume.toInt()) },
                valueRange = 0f..100f,
                steps = 9,
                enabled = canControl,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = snapshot.player.repeatSong,
                    onClick = onRepeatSong,
                    enabled = canControl,
                    label = { Text("Song") },
                    leadingIcon = { Icon(Icons.Default.RepeatOne, contentDescription = null) },
                )
                FilterChip(
                    selected = snapshot.player.repeatQueue,
                    onClick = onRepeatQueue,
                    enabled = canControl,
                    label = { Text("Queue") },
                    leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null) },
                )
                // Only offered while the server reports autoplay as available;
                // it is a per-voice-session feature behind its own gate.
                FilterChip(
                    selected = snapshot.player.autoplay.active,
                    onClick = onAutoplay,
                    enabled = canControl && snapshot.player.autoplay.available,
                    label = { Text("Auto") },
                    leadingIcon = { Icon(Icons.Default.AllInclusive, contentDescription = null) },
                )
                OutlinedButton(onClick = onStop, enabled = canControl) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Text("Stop")
                }
            }

            PermissionHint(snapshot)
        }
    }
}

@Composable
private fun DiscordQueueCard(
    state: MuseUiState,
    onShuffle: () -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onRemove: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    val snapshot = state.snapshot
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Discord Queue", style = MaterialTheme.typography.titleLarge)
            if (snapshot == null) {
                CircularProgressIndicator()
                return@Column
            }
            val canWrite = snapshot.permissions.canWriteQueue && !state.actionBusy
            Text(
                "${snapshot.player.queueLength} Titel · ${formatDuration(snapshot.player.queueRemainingSeconds)} verbleibend",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = onShuffle,
                    enabled = canWrite && snapshot.player.queue.size > 1,
                    label = { Text("Shuffle") },
                    leadingIcon = { Icon(Icons.Default.Shuffle, contentDescription = null) },
                )
                AssistChip(
                    onClick = onUndo,
                    enabled = canWrite,
                    label = { Text("Undo") },
                    leadingIcon = { Icon(Icons.Default.Undo, contentDescription = null) },
                )
                AssistChip(
                    onClick = onClear,
                    enabled = canWrite && snapshot.player.queue.isNotEmpty(),
                    label = { Text("Leeren") },
                    leadingIcon = { Icon(Icons.Default.DeleteOutline, contentDescription = null) },
                )
            }
            if (snapshot.player.queue.isEmpty()) {
                Text("Die Queue ist leer.")
            } else {
                snapshot.player.queue.forEachIndexed { index, song ->
                    if (index > 0) HorizontalDivider()
                    QueueRow(
                        song = song,
                        index = index,
                        count = snapshot.player.queue.size,
                        enabled = canWrite,
                        onRemove = { onRemove(song.entryId) },
                        onMove = { destination -> onMove(song.entryId, destination) },
                    )
                }
            }
            PermissionHint(snapshot)
        }
    }
}

@Composable
private fun QueueRow(
    song: MuseSong,
    index: Int,
    count: Int,
    enabled: Boolean,
    onRemove: () -> Unit,
    onMove: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("${index + 1}", style = MaterialTheme.typography.labelLarge)
        Column(Modifier.weight(1f)) {
            Text(song.title, maxLines = 1, fontWeight = FontWeight.Medium)
            Text(
                "${song.artist} · ${formatDuration(song.durationSeconds)}" +
                    (song.requestedBy?.let { " · @$it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        IconButton(onClick = { onMove(index) }, enabled = enabled && index > 0) {
            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Nach oben")
        }
        IconButton(onClick = { onMove(index + 2) }, enabled = enabled && index < count - 1) {
            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Nach unten")
        }
        IconButton(onClick = onRemove, enabled = enabled) {
            Icon(Icons.Default.DeleteOutline, contentDescription = "Entfernen")
        }
    }
}

@Composable
private fun DeviceCard(
    state: MuseUiState,
    onDisconnect: () -> Unit,
    onCreateImportIdentity: () -> Unit,
    onSelectImportDocument: () -> Unit,
    onDisconnectSpotifyImport: () -> Unit,
) {
    val device = state.device ?: return
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Gekoppeltes Gerät", style = MaterialTheme.typography.titleLarge)
            Text(device.name, fontWeight = FontWeight.SemiBold)
            Text("Profil: ${device.role.roleLabel()}")
            Text("Guild: ${device.guildId}")
            SelectionContainer {
                Text(
                    "Fingerprint: ${device.fingerprint.chunked(8).joinToString(" ")}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Text("Scopes", style = MaterialTheme.typography.labelLarge)
            Text(device.scopes.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            if ("worker:claim" in device.scopes) {
                Text(
                    "Download-Node ist freigegeben. Opportunistische Jobs laufen weiterhin über WorkManager; " +
                        "die Fernsteuerung erhält keine Quell-Cookies.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            Text("Private Playlistmanifeste", style = MaterialTheme.typography.titleMedium)
            Text(
                "Dateien werden nur lokal geparst. Muse erhält weder Rohdatei noch Cookie/Token und dieser " +
                    "Bereich kann keine Downloads oder Akquiseaufträge erzeugen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when {
                "spotify:manifest" !in device.scopes -> Text(
                    "Dieses Geräteprofil hat keinen spotify:manifest-Scope.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                state.spotifyImportEnabledOnServer == false -> Text(
                    "Privater Manifestimport ist auf Muse noch deaktiviert.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                !state.spotifyImportIdentityCreated -> {
                    Button(
                        onClick = onCreateImportIdentity,
                        enabled = !state.busy,
                    ) { Text("Privates Importprofil anlegen …") }
                    if (state.spotifyImportProfile != null) {
                        TextButton(
                            onClick = onDisconnectSpotifyImport,
                            enabled = !state.importBusy,
                        ) { Text("Vorhandenes Muse-Profil trennen …") }
                    }
                }
                else -> {
                    val profile = state.spotifyImportProfile
                    Text(
                        if (profile == null) {
                            "Noch keine serverseitige Generation · lokal bestätigt: " +
                                state.spotifyAcknowledgedGeneration
                        } else {
                            "Muse Generation ${profile.generation} · ${profile.playlistCount} Playlists · " +
                                "${profile.itemCount} Titel"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(
                        onClick = onSelectImportDocument,
                        enabled = !state.importBusy && state.spotifyImportEnabledOnServer != false,
                    ) {
                        if (state.importBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("JSON/CSV/M3U auswählen …")
                    }
                    if (profile != null) {
                        TextButton(onClick = onDisconnectSpotifyImport, enabled = !state.importBusy) {
                            Text("Privates Importprofil trennen …")
                        }
                    }
                }
            }
            HorizontalDivider()
            OutlinedButton(onClick = onDisconnect, enabled = !state.busy) {
                Text("Lokal trennen …")
            }
        }
    }
}

@Composable
private fun PermissionHint(snapshot: MusePlayerResponse) {
    if (!snapshot.permissions.canControl || !snapshot.permissions.canWriteQueue) {
        Text(
            when {
                snapshot.djMode.active -> "DJ-Modus aktiv: Muse prüft jede Aktion erneut serverseitig."
                snapshot.guild.voiceChannel == null -> "Tritt einem Discord-Sprachkanal bei, um Aktionen freizuschalten."
                else -> "Diese Ansicht ist aufgrund der aktuellen Discord-/Geräterechte teilweise schreibgeschützt."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )
    }
}

@Composable
private fun ContractRoadmapCard() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Noch nicht Teil des stabilen Serververtrags", fontWeight = FontWeight.SemiBold)
            Text(
                "Muse-Suche/Browse, Queue-Hinzufügen, Autoplay-Anzeige und Queue-Handoff " +
                    "werden erst aktiviert, wenn die entsprechenden Serverrouten verfügbar sind.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun NoticeCard(message: String, isError: Boolean, onDismiss: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
            else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onDismiss) { Text("OK") }
        }
    }
}

@Composable
private fun ConfirmationDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = { Button(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

private fun formatDuration(seconds: Int): String {
    val safe = seconds.coerceAtLeast(0)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val remainingSeconds = safe % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, remainingSeconds)
    else "%d:%02d".format(minutes, remainingSeconds)
}

private fun formatByteSize(bytes: Int): String = "%.2f MiB".format(bytes / 1024.0 / 1024.0)

private fun String.sourceLabel(): String = when (this) {
    "navidrome" -> "Navidrome"
    "youtube" -> "YouTube"
    "stream" -> "Stream"
    else -> "Unbekannt"
}

private fun String.phaseLabel(): String = when (this) {
    "idle" -> "Bereit"
    "preparing" -> "Wird vorbereitet"
    "playing" -> "Wiedergabe"
    "paused" -> "Pausiert"
    "reconnecting" -> "Verbindung wird aufgebaut"
    "retrying" -> "Quelle wird erneut aufgebaut"
    "waitingForAcquisition" -> "FLAC wird beschafft"
    "failed" -> "Wiedergabe fehlgeschlagen"
    else -> this
}

private fun String.roleLabel(): String = when (this) {
    "remote_only" -> "Nur Fernbedienung"
    "sync_device" -> "Eigenes Sync-Gerät"
    "admin_download_node" -> "Admin-Download-Node"
    "trust_pool" -> "Vertrauenspool"
    else -> this
}
