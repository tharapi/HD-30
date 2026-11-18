package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.model.PitchTheory
import com.example.model.TemperamentMode
import com.example.model.ToneVoice
import com.example.model.TranspositionKey
import com.example.ui.HarmonyUiState
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceBright
import com.example.ui.theme.EbonyKey
import com.example.ui.theme.EbonyKeyPressed
import com.example.ui.theme.ElectricSky
import com.example.ui.theme.ElectricSkyContainer
import com.example.ui.theme.HardwareBorder
import com.example.ui.theme.IvoryKey
import com.example.ui.theme.IvoryKeyPressed
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.PitchEmerald
import com.example.ui.theme.StudioCharcoal
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.WarmAmberContainer
import java.util.Locale

@Composable
fun HarmonyPanel(
    state: HarmonyUiState,
    onVoiceSelected: (ToneVoice) -> Unit,
    onReferencePitchChanged: (Double) -> Unit,
    onAdjustPitch: (Double) -> Unit,
    onTemperamentSelected: (TemperamentMode) -> Unit,
    onRootSelected: (Int) -> Unit,
    onToggleAutoRoot: () -> Unit,
    onTranspositionSelected: (TranspositionKey) -> Unit,
    onAdjustOctave: (Int) -> Unit,
    onToggleChordHold: () -> Unit,
    onClearNotes: () -> Unit,
    onToneGainChanged: (Float) -> Unit,
    onKeyPressed: (Int) -> Unit,
    onKeyReleased: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. Voice Timbre Rack
        ConsoleSectionCard(title = "VOICE TIMBRE") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToneVoice.entries.forEach { voice ->
                    val selected = state.voice == voice
                    FilterChip(
                        selected = selected,
                        onClick = { onVoiceSelected(voice) },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("voice_chip_${voice.name.lowercase()}"),
                        label = {
                            Column {
                                Text(
                                    text = voice.label,
                                    style = MaterialTheme.typography.labelLarge
                                )
                                Text(
                                    text = voice.category,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (selected) ElectricSky else MutedSlateText
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            selectedContainerColor = ElectricSkyContainer,
                            labelColor = MaterialTheme.colorScheme.onSurface,
                            selectedLabelColor = ElectricSky
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = HardwareBorder,
                            selectedBorderColor = ElectricSky
                        )
                    )
                }
            }
        }

        // 2. Pitch Calibration & Instrument Transposition
        ConsoleSectionCard(title = "CALIBRATION & TRANSPOSITION") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Tune,
                            contentDescription = null,
                            tint = WarmAmber,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = String.format(Locale.US, "A4 = %.1f Hz", state.referencePitchHz),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontFamily = MaterialTheme.typography.labelMedium.fontFamily
                            ),
                            color = WarmAmber
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(440.0, 442.0).forEach { presetHz ->
                            val isExact = kotlin.math.abs(state.referencePitchHz - presetHz) < 0.05
                            FilterChip(
                                selected = isExact,
                                onClick = { onReferencePitchChanged(presetHz) },
                                modifier = Modifier
                                    .height(40.dp)
                                    .testTag("pitch_preset_${presetHz.toInt()}"),
                                label = {
                                    Text(
                                        text = "${presetHz.toInt()}",
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = ConsoleSurface,
                                    selectedContainerColor = WarmAmberContainer,
                                    selectedLabelColor = WarmAmber
                                )
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SmallStepButton(
                        label = "-0.5",
                        onClick = { onAdjustPitch(-0.5) },
                        testTag = "pitch_minus_btn"
                    )
                    Slider(
                        value = state.referencePitchHz.toFloat(),
                        onValueChange = { onReferencePitchChanged(it.toDouble()) },
                        valueRange = 430f..450f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("pitch_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = WarmAmber,
                            activeTrackColor = WarmAmber,
                            inactiveTrackColor = ConsoleSurfaceBright
                        )
                    )
                    SmallStepButton(
                        label = "+0.5",
                        onClick = { onAdjustPitch(0.5) },
                        testTag = "pitch_plus_btn"
                    )
                }

                Text(
                    text = "Instrument Transposition (${state.transposition.fullLabel})",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MutedSlateText
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TranspositionKey.entries.forEach { key ->
                        val selected = state.transposition == key
                        FilterChip(
                            selected = selected,
                            onClick = { onTranspositionSelected(key) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("transpose_${key.name.lowercase()}"),
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = key.shortLabel,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ConsoleSurface,
                                selectedContainerColor = ElectricSkyContainer,
                                selectedLabelColor = ElectricSky
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = HardwareBorder,
                                selectedBorderColor = ElectricSky
                            )
                        )
                    }
                }
            }
        }

        // 3. Temperament (Equal vs Pure Just Intonation) & Root Key
        ConsoleSectionCard(title = "TEMPERAMENT & HARMONIC ROOT") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TemperamentMode.entries.forEach { mode ->
                        val selected = state.temperament == mode
                        FilterChip(
                            selected = selected,
                            onClick = { onTemperamentSelected(mode) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("temperament_${mode.name.lowercase()}"),
                            label = {
                                Box(
                                    modifier = Modifier.fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = mode.label,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                }
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = ConsoleSurface,
                                selectedContainerColor = if (mode == TemperamentMode.PURE) WarmAmberContainer else ElectricSkyContainer,
                                selectedLabelColor = if (mode == TemperamentMode.PURE) WarmAmber else ElectricSky
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = HardwareBorder,
                                selectedBorderColor = if (mode == TemperamentMode.PURE) WarmAmber else ElectricSky
                            )
                        )
                    }
                }

                if (state.temperament == TemperamentMode.PURE) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Auto-Detect Root from Lowest Note",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Active Root: ${PitchTheory.pitchClassName(state.effectiveRootIndex)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = PitchEmerald
                            )
                        }
                        Switch(
                            checked = state.autoDetectRoot,
                            onCheckedChange = { onToggleAutoRoot() },
                            modifier = Modifier.testTag("auto_root_switch"),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = StudioCharcoal,
                                checkedTrackColor = PitchEmerald
                            )
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        PitchTheory.NOTE_NAMES.forEachIndexed { idx, name ->
                            val isSelected = state.effectiveRootIndex == idx && !state.autoDetectRoot
                            val isAutoActive = state.effectiveRootIndex == idx && state.autoDetectRoot
                            FilterChip(
                                selected = isSelected || isAutoActive,
                                onClick = { onRootSelected(idx) },
                                modifier = Modifier
                                    .height(42.dp)
                                    .testTag("root_key_$idx"),
                                label = {
                                    Text(
                                        text = name,
                                        style = MaterialTheme.typography.labelMedium
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = ConsoleSurface,
                                    selectedContainerColor = if (isAutoActive) PitchEmerald.copy(alpha = 0.2f) else WarmAmberContainer,
                                    selectedLabelColor = if (isAutoActive) PitchEmerald else WarmAmber
                                )
                            )
                        }
                    }
                }
            }
        }

        // 4. Interactive Playable Keyboard & Chord Hold
        ConsoleSectionCard(title = "HARMONY KEYBOARD") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    FilterChip(
                        selected = state.chordHoldEnabled,
                        onClick = onToggleChordHold,
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("chord_hold_toggle"),
                        leadingIcon = {
                            Icon(
                                imageVector = if (state.chordHoldEnabled) Icons.Filled.Lock else Icons.Filled.LockOpen,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        label = {
                            Text(
                                text = stringResource(R.string.chord_hold),
                                style = MaterialTheme.typography.labelLarge
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = ConsoleSurface,
                            selectedContainerColor = ElectricSkyContainer,
                            selectedLabelColor = ElectricSky,
                            selectedLeadingIconColor = ElectricSky
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = state.chordHoldEnabled,
                            borderColor = HardwareBorder,
                            selectedBorderColor = ElectricSky
                        )
                    )

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallStepButton(
                            label = "OCT-",
                            onClick = { onAdjustOctave(-1) },
                            enabled = state.octaveShift > -1,
                            testTag = "octave_down_btn"
                        )
                        Text(
                            text = if (state.octaveShift >= 0) "+${state.octaveShift}" else "${state.octaveShift}",
                            style = MaterialTheme.typography.labelMedium,
                            color = ElectricSky
                        )
                        SmallStepButton(
                            label = "OCT+",
                            onClick = { onAdjustOctave(1) },
                            enabled = state.octaveShift < 1,
                            testTag = "octave_up_btn"
                        )
                    }

                    OutlinedButton(
                        onClick = onClearNotes,
                        enabled = state.activeWrittenMidis.isNotEmpty(),
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("clear_notes_button"),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        border = BorderStroke(1.dp, HardwareBorder),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = WarmAmber)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.ClearAll,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(R.string.clear_held),
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // Master Tone Volume
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "TONE VOL",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlateText
                    )
                    Slider(
                        value = state.toneGain,
                        onValueChange = onToneGainChanged,
                        valueRange = 0f..1f,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("tone_volume_slider"),
                        colors = SliderDefaults.colors(
                            thumbColor = ElectricSky,
                            activeTrackColor = ElectricSky,
                            inactiveTrackColor = ConsoleSurfaceBright
                        )
                    )
                    Text(
                        text = "${(state.toneGain * 100).toInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = ElectricSky
                    )
                }

                // 2-Octave Playable Keyboard (C3..B4 shifted by octaveShift)
                val baseMidi = 48 + (state.octaveShift * 12)
                PlayablePianoKeyboard(
                    startMidiC = baseMidi,
                    octaves = 2,
                    activeWrittenMidis = state.activeWrittenMidis,
                    chordHoldEnabled = state.chordHoldEnabled,
                    onKeyPressed = onKeyPressed,
                    onKeyReleased = onKeyReleased,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(176.dp)
                )
            }
        }
    }
}

@Composable
private fun PlayablePianoKeyboard(
    startMidiC: Int,
    octaves: Int,
    activeWrittenMidis: Set<Int>,
    chordHoldEnabled: Boolean,
    onKeyPressed: (Int) -> Unit,
    onKeyReleased: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val whiteOffsets = listOf(0, 2, 4, 5, 7, 9, 11)
    // Black key offset in semitones -> white key index gap position (e.g. C# sits between white 0 and 1 -> 0.65)
    val blackKeySpecs = listOf(
        1 to 0.64f,
        3 to 1.64f,
        6 to 3.64f,
        8 to 4.64f,
        10 to 5.64f
    )
    val totalWhiteKeys = octaves * 7

    BoxWithConstraints(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(StudioCharcoal)
            .border(1.dp, HardwareBorder, MaterialTheme.shapes.medium)
            .padding(4.dp)
    ) {
        val whiteKeyWidth = maxWidth / totalWhiteKeys
        val blackKeyWidth = whiteKeyWidth * 0.68f
        val blackKeyHeight = maxHeight * 0.58f

        // White keys layer
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (oct in 0 until octaves) {
                whiteOffsets.forEach { semitone ->
                    val midi = startMidiC + oct * 12 + semitone
                    val isActive = midi in activeWrittenMidis
                    val keyShape = RoundedCornerShape(
                        topStart = 4.dp,
                        topEnd = 4.dp,
                        bottomStart = 8.dp,
                        bottomEnd = 8.dp
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(keyShape)
                            .background(if (isActive) IvoryKeyPressed else IvoryKey)
                            .border(
                                width = if (isActive) 2.dp else 1.dp,
                                color = if (isActive) ElectricSky else HardwareBorder,
                                shape = keyShape
                            )
                            .testTag("piano_key_$midi")
                            .pointerInput(midi, chordHoldEnabled) {
                                awaitEachGesture {
                                    awaitFirstDown(requireUnconsumed = false)
                                    onKeyPressed(midi)
                                    waitForUpOrCancellation()
                                    if (!chordHoldEnabled) {
                                        onKeyReleased(midi)
                                    }
                                }
                            },
                        contentAlignment = Alignment.BottomCenter
                    ) {
                        Text(
                            text = PitchTheory.noteNameWithOctave(midi),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isActive) ElectricSkyContainer else StudioCharcoal,
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }
            }
        }

        // Black keys overlay
        for (oct in 0 until octaves) {
            blackKeySpecs.forEach { (semitone, whitePos) ->
                val midi = startMidiC + oct * 12 + semitone
                val isActive = midi in activeWrittenMidis
                val xOffset = whiteKeyWidth * (oct * 7 + whitePos)
                val blackShape = RoundedCornerShape(
                    topStart = 3.dp,
                    topEnd = 3.dp,
                    bottomStart = 6.dp,
                    bottomEnd = 6.dp
                )
                Box(
                    modifier = Modifier
                        .offset(x = xOffset)
                        .width(blackKeyWidth)
                        .height(blackKeyHeight)
                        .clip(blackShape)
                        .background(if (isActive) EbonyKeyPressed else EbonyKey)
                        .border(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = if (isActive) ElectricSky else StudioCharcoal,
                            shape = blackShape
                        )
                        .testTag("piano_key_$midi")
                        .pointerInput(midi, chordHoldEnabled) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                onKeyPressed(midi)
                                waitForUpOrCancellation()
                                if (!chordHoldEnabled) {
                                    onKeyReleased(midi)
                                }
                            }
                        },
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 6.dp)
                            .size(6.dp)
                            .clip(RoundedCornerShape(50))
                            .background(if (isActive) IvoryKey else MutedSlateText.copy(alpha = 0.35f))
                    )
                }
            }
        }
    }
}

@Composable
fun ConsoleSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, HardwareBorder),
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MutedSlateText
            )
            content()
        }
    }
}

@Composable
fun SmallStepButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    testTag: String = ""
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .height(48.dp)
            .then(if (testTag.isNotEmpty()) Modifier.testTag(testTag) else Modifier),
        contentPadding = PaddingValues(horizontal = 10.dp),
        border = BorderStroke(1.dp, HardwareBorder),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = ConsoleSurface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium
        )
    }
}
