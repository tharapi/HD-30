package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.PitchTheory
import com.example.model.RehearsalPreset
import com.example.ui.HarmonyUiState
import com.example.ui.MetronomeUiState
import com.example.ui.UiState
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.DownbeatCrimson
import com.example.ui.theme.ElectricSky
import com.example.ui.theme.ElectricSkyContainer
import com.example.ui.theme.HardwareBorder
import com.example.ui.theme.LcdMidnight
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.PitchEmerald
import com.example.ui.theme.StudioCharcoal
import com.example.ui.theme.WarmAmber
import java.util.Locale

@Composable
fun PresetsPanel(
    presetsState: UiState<List<RehearsalPreset>>,
    activePresetId: String?,
    searchQuery: String,
    harmonyState: HarmonyUiState,
    metronomeState: MetronomeUiState,
    onSearchQueryChange: (String) -> Unit,
    onLoadPreset: (RehearsalPreset) -> Unit,
    onSaveNewPreset: (title: String, notes: String) -> Unit,
    onUpdatePreset: (RehearsalPreset) -> Unit,
    onDeletePreset: (RehearsalPreset) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSaveDialog by remember { mutableStateOf(false) }
    var presetToDelete by remember { mutableStateOf<RehearsalPreset?>(null) }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Action & Search Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                placeholder = { Text("Search pieces or notes...") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null,
                        tint = MutedSlateText
                    )
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("preset_search_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ElectricSky,
                    unfocusedBorderColor = HardwareBorder,
                    focusedContainerColor = ConsoleSurface,
                    unfocusedContainerColor = ConsoleSurface
                ),
                shape = MaterialTheme.shapes.medium
            )

            Button(
                onClick = { showSaveDialog = true },
                modifier = Modifier
                    .height(54.dp)
                    .testTag("open_save_preset_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricSky,
                    contentColor = StudioCharcoal
                ),
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.save_preset),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        when (presetsState) {
            is UiState.Loading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = ElectricSky)
                }
            }
            is UiState.Error -> {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = DownbeatCrimson.copy(alpha = 0.14f),
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, DownbeatCrimson)
                ) {
                    Text(
                        text = presetsState.message,
                        color = DownbeatCrimson,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            is UiState.Success -> {
                val filtered = remember(presetsState.data, searchQuery) {
                    val q = searchQuery.trim().lowercase()
                    if (q.isEmpty()) presetsState.data
                    else presetsState.data.filter {
                        it.title.lowercase().contains(q) || it.notes.lowercase().contains(q)
                    }
                }

                if (filtered.isEmpty()) {
                    EmptyPresetsView(
                        hasSearchQuery = searchQuery.isNotBlank(),
                        onSaveClick = { showSaveDialog = true }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(filtered, key = { it.id }) { preset ->
                            PresetCard(
                                preset = preset,
                                isActive = preset.id == activePresetId,
                                onLoad = { onLoadPreset(preset) },
                                onOverwriteWithCurrent = { onUpdatePreset(preset) },
                                onDelete = { presetToDelete = preset }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        SavePresetDialog(
            harmonyState = harmonyState,
            metronomeState = metronomeState,
            onDismiss = { showSaveDialog = false },
            onSave = { title, notes ->
                showSaveDialog = false
                onSaveNewPreset(title, notes)
            }
        )
    }

    presetToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { presetToDelete = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text("Delete Preset?", style = MaterialTheme.typography.titleLarge) },
            text = {
                Text(
                    "Remove \"${target.title}\" from your rehearsal library?",
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        presetToDelete = null
                        onDeletePreset(target)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DownbeatCrimson)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { presetToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PresetCard(
    preset: RehearsalPreset,
    isActive: Boolean,
    onLoad: () -> Unit,
    onOverwriteWithCurrent: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad)
            .testTag("preset_card_${preset.id}"),
        color = if (isActive) ElectricSkyContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(
            width = if (isActive) 1.5.dp else 1.dp,
            color = if (isActive) ElectricSky else HardwareBorder
        ),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = preset.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = if (isActive) ElectricSky else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (preset.notes.isNotBlank()) {
                        Text(
                            text = preset.notes,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MutedSlateText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onLoad,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("load_preset_${preset.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PlayArrow,
                            contentDescription = "Load preset ${preset.title}",
                            tint = PitchEmerald
                        )
                    }
                    IconButton(
                        onClick = onOverwriteWithCurrent,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("update_preset_${preset.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Save,
                            contentDescription = "Update ${preset.title} with current settings",
                            tint = ElectricSky
                        )
                    }
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("delete_preset_${preset.id}")
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DeleteOutline,
                            contentDescription = "Delete ${preset.title}",
                            tint = DownbeatCrimson
                        )
                    }
                }
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PresetBadge("${preset.bpm} BPM", PitchEmerald)
                PresetBadge(preset.safeTimeSignature().label, ElectricSky)
                PresetBadge(
                    String.format(Locale.US, "A4 %.1fHz", preset.referencePitchHz),
                    WarmAmber
                )
                PresetBadge(
                    "${preset.safeTemperament().label} (${PitchTheory.pitchClassName(preset.rootKeyIndex.toInt())})",
                    WarmAmber
                )
                PresetBadge("Key ${preset.safeTransposition().shortLabel}", ElectricSky)
                PresetBadge(preset.safeVoice().label, MutedSlateText)
            }
        }
    }
}

@Composable
private fun PresetBadge(
    text: String,
    color: androidx.compose.ui.graphics.Color
) {
    Surface(
        color = LcdMidnight,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun EmptyPresetsView(
    hasSearchQuery: Boolean,
    onSaveClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, HardwareBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.LibraryMusic,
                contentDescription = null,
                tint = ElectricSky,
                modifier = Modifier.size(44.dp)
            )
            Text(
                text = if (hasSearchQuery) "No Matching Presets" else "No Rehearsal Presets Yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = if (hasSearchQuery) {
                    "Try a different piece title or clear the search filter."
                } else {
                    "Dial in your orchestra tempo, 5-channel subdivision mix, A4 pitch, and Pure Just Intonation settings, then save them for instant rehearsal recall."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MutedSlateText,
                textAlign = TextAlign.Center
            )
            if (!hasSearchQuery) {
                OutlinedButton(
                    onClick = onSaveClick,
                    modifier = Modifier.height(48.dp),
                    border = BorderStroke(1.dp, ElectricSky)
                ) {
                    Text("Save Current Console Settings", color = ElectricSky)
                }
            }
        }
    }
}

@Composable
private fun SavePresetDialog(
    harmonyState: HarmonyUiState,
    metronomeState: MetronomeUiState,
    onDismiss: () -> Unit,
    onSave: (title: String, notes: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val canSave = title.trim().isNotEmpty()

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = {
            Text("Save Rehearsal Preset", style = MaterialTheme.typography.titleLarge)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { if (it.length <= 80) title = it },
                    label = { Text("Piece / Exercise Title") },
                    placeholder = { Text("e.g., Holst Suite No. 1 - March") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_preset_title_input")
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { if (it.length <= 300) notes = it },
                    label = { Text("Conductor Notes (Optional)") },
                    placeholder = { Text("e.g., Tune Eb chord in Pure mode") },
                    maxLines = 3,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("save_preset_notes_input")
                )
                Surface(
                    color = LcdMidnight,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, HardwareBorder)
                ) {
                    Text(
                        text = String.format(
                            Locale.US,
                            "Captures: %d BPM · %s · A4 %.1f Hz · %s · Key %s · %s",
                            metronomeState.bpm,
                            metronomeState.timeSignature.label,
                            harmonyState.referencePitchHz,
                            harmonyState.temperament.label,
                            harmonyState.transposition.shortLabel,
                            harmonyState.voice.label
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = ElectricSky,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(title.trim(), notes.trim()) },
                enabled = canSave,
                modifier = Modifier.testTag("confirm_save_preset_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ElectricSky,
                    contentColor = StudioCharcoal
                )
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
