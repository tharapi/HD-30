package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.audio.MetronomeBeatSnapshot
import com.example.midi.MidiConnectionUiState
import com.example.model.AudioOutputMode
import com.example.model.NotePitchInfo
import com.example.model.PitchTheory
import com.example.model.TemperamentMode
import com.example.ui.HarmonyUiState
import com.example.ui.MetronomeUiState
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.ConsoleSurface
import com.example.ui.theme.ConsoleSurfaceBright
import com.example.ui.theme.CrispOffWhite
import com.example.ui.theme.DownbeatCrimson
import com.example.ui.theme.ElectricSky
import com.example.ui.theme.ElectricSkyContainer
import com.example.ui.theme.HardwareBorder
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.LcdBorder
import com.example.ui.theme.LcdMidnight
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.PitchEmerald
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.StudioCharcoal
import com.example.ui.theme.WarmAmber
import com.example.ui.theme.WarmAmberContainer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Dedicated full-screen, zero-scroll, touch-locked MIDI Keyboard Monitor Mode.
 * Displays all Harmony, Metronome, Held Keys, Audio Output, and Mixer levels at a glance
 * without any interactive controls or virtual keyboard. Exits only via press-and-hold unlock.
 */
@Composable
fun MidiMonitorScreen(
    harmonyState: HarmonyUiState,
    metronomeState: MetronomeUiState,
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>,
    midiState: MidiConnectionUiState,
    sustainPedalDown: Boolean,
    audioOutputMode: AudioOutputMode,
    onExitMonitor: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Keep screen awake automatically while monitoring on a music stand
    val view = LocalView.current
    DisposableEffect(view) {
        val previousKeepScreenOn = view.keepScreenOn
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = previousKeepScreenOn
        }
    }

    // Ignore accidental hardware/gesture Back navigation while locked in Monitor Mode
    BackHandler(enabled = true) {}

    Surface(
        modifier = modifier
            .fillMaxSize()
            .testTag("midi_monitor_screen"),
        color = StudioCharcoal
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            val isLandscape = maxWidth > maxHeight || maxWidth >= 700.dp
            val isCompactHeight = maxHeight < 460.dp
            val isCompactWidth = maxWidth < 400.dp
            val sectionGap = if (isCompactHeight) 6.dp else 8.dp

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(sectionGap)
            ) {
                MonitorTopBar(
                    midiState = midiState,
                    audioOutputMode = audioOutputMode,
                    showOutputBadge = isLandscape,
                    isCompactHeight = isCompactHeight,
                    isCompactWidth = isCompactWidth,
                    onExitMonitor = onExitMonitor
                )

                if (isLandscape) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(sectionGap)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.1f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(sectionGap)
                        ) {
                            HarmonyMonitorCard(
                                harmonyState = harmonyState,
                                sustainPedalDown = sustainPedalDown,
                                isCompactHeight = isCompactHeight,
                                isLandscape = true,
                                modifier = Modifier
                                    .weight(1.1f)
                                    .fillMaxWidth()
                            )
                            HeldKeysMonitorCard(
                                harmonyState = harmonyState,
                                isLandscape = true,
                                isCompactHeight = isCompactHeight,
                                modifier = Modifier
                                    .weight(1.0f)
                                    .fillMaxWidth()
                            )
                        }

                        Column(
                            modifier = Modifier
                                .weight(1.05f)
                                .fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(sectionGap)
                        ) {
                            MetronomeMonitorCard(
                                metronomeState = metronomeState,
                                beatSnapshotFlow = beatSnapshotFlow,
                                isCompactHeight = isCompactHeight,
                                isLandscape = true,
                                modifier = Modifier
                                    .weight(1.02f)
                                    .fillMaxWidth()
                            )
                            LevelsMonitorCard(
                                harmonyState = harmonyState,
                                metronomeState = metronomeState,
                                audioOutputMode = audioOutputMode,
                                showOutputBadge = false,
                                isCompactHeight = isCompactHeight,
                                modifier = Modifier
                                    .weight(1.08f)
                                    .fillMaxWidth()
                            )
                        }
                    }
                } else {
                    // Portrait zero-scroll layout:
                    // Row 1: Harmony + Tempo hero split
                    Row(
                        modifier = Modifier
                            .weight(1.32f)
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(sectionGap)
                    ) {
                        HarmonyMonitorCard(
                            harmonyState = harmonyState,
                            sustainPedalDown = sustainPedalDown,
                            isCompactHeight = false,
                            isLandscape = false,
                            modifier = Modifier
                                .weight(1.12f)
                                .fillMaxHeight()
                        )
                        MetronomeMonitorCard(
                            metronomeState = metronomeState,
                            beatSnapshotFlow = beatSnapshotFlow,
                            isCompactHeight = false,
                            isLandscape = false,
                            modifier = Modifier
                                .weight(1.0f)
                                .fillMaxHeight()
                        )
                    }

                    // Row 2: Active Held Keys (no virtual keyboard)
                    HeldKeysMonitorCard(
                        harmonyState = harmonyState,
                        isLandscape = false,
                        isCompactHeight = false,
                        modifier = Modifier
                            .weight(1.0f)
                            .fillMaxWidth()
                    )

                    // Row 3: All 7 Volume / Subdivision Level Meters
                    LevelsMonitorCard(
                        harmonyState = harmonyState,
                        metronomeState = metronomeState,
                        audioOutputMode = audioOutputMode,
                        showOutputBadge = true,
                        isCompactHeight = false,
                        modifier = Modifier
                            .weight(1.18f)
                            .fillMaxWidth()
                    )
                }

                MonitorMidiFooterStrip(
                    midiState = midiState,
                    isCompactHeight = isCompactHeight
                )
            }
        }
    }
}

@Composable
private fun MonitorTopBar(
    midiState: MidiConnectionUiState,
    audioOutputMode: AudioOutputMode,
    showOutputBadge: Boolean,
    isCompactHeight: Boolean,
    isCompactWidth: Boolean,
    onExitMonitor: () -> Unit
) {
    val isMidiConnected = midiState.connectedDeviceName != null
    val barHeight = if (isCompactHeight) 36.dp else 40.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Lock status badge
        Surface(
            color = ElectricSky.copy(alpha = 0.14f),
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(1.dp, ElectricSky.copy(alpha = 0.55f)),
            modifier = Modifier.fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    tint = ElectricSky,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (isCompactWidth) "LOCKED" else stringResource(R.string.monitor_mode_badge),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = ElectricSky,
                    maxLines = 1
                )
            }
        }

        // Connected MIDI hardware device indicator (read-only)
        Surface(
            color = LcdMidnight,
            shape = MaterialTheme.shapes.small,
            border = BorderStroke(
                1.dp,
                if (isMidiConnected) PitchEmerald.copy(alpha = 0.65f) else WarmAmber.copy(alpha = 0.5f)
            ),
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(if (isMidiConnected) PitchEmerald else WarmAmber)
                )
                Text(
                    text = midiState.connectedDeviceName ?: "NO MIDI KEYBOARD",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isMidiConnected) PitchEmerald else WarmAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        // Audio output mode read-only badge (landscape top bar)
        if (showOutputBadge) {
            MonitorOutputModeBadge(
                audioOutputMode = audioOutputMode,
                modifier = Modifier.fillMaxHeight()
            )
        }

        // Only interactive control on the entire screen: Hold-to-Exit unlock button
        HoldToExitButton(
            onExitConfirmed = onExitMonitor,
            modifier = Modifier.fillMaxHeight()
        )
    }
}

@Composable
private fun HoldToExitButton(
    onExitConfirmed: () -> Unit,
    modifier: Modifier = Modifier,
    holdDurationMs: Long = 800L
) {
    var isHolding by remember { mutableStateOf(false) }
    var holdProgress by remember { mutableFloatStateOf(0f) }
    val currentOnExit by rememberUpdatedState(onExitConfirmed)
    val haptic = LocalHapticFeedback.current
    val cdText = stringResource(R.string.cd_hold_exit_monitor)

    LaunchedEffect(isHolding) {
        if (!isHolding) {
            holdProgress = 0f
            return@LaunchedEffect
        }
        val startNanos = System.nanoTime()
        val durationNanos = holdDurationMs * 1_000_000L
        while (isHolding) {
            val elapsed = System.nanoTime() - startNanos
            val fraction = (elapsed.toFloat() / durationNanos.toFloat()).coerceIn(0f, 1f)
            holdProgress = fraction
            if (fraction >= 1f) {
                isHolding = false
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                currentOnExit()
                break
            }
            delay(16L)
        }
    }

    val borderColor by animateColorAsState(
        targetValue = if (isHolding) WarmAmber else HardwareBorder,
        animationSpec = tween(120),
        label = "hold_exit_border"
    )

    Surface(
        shape = MaterialTheme.shapes.small,
        color = ConsoleSurface,
        border = BorderStroke(1.2.dp, borderColor),
        modifier = modifier
            .width(106.dp)
            .semantics { contentDescription = cdText }
            .testTag("monitor_hold_to_exit_button")
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    isHolding = true
                    try {
                        waitForUpOrCancellation()
                    } finally {
                        isHolding = false
                    }
                }
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            // Animated hold progress fill
            if (holdProgress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .fillMaxHeight()
                        .fillMaxWidth(holdProgress)
                        .background(WarmAmber.copy(alpha = 0.42f))
                )
            }

            Row(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = if (isHolding) Icons.Filled.LockOpen else Icons.Filled.Lock,
                    contentDescription = null,
                    tint = if (isHolding) CrispOffWhite else WarmAmber,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = if (isHolding) {
                        stringResource(R.string.monitor_holding_exit)
                    } else {
                        stringResource(R.string.monitor_hold_to_exit)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isHolding) CrispOffWhite else WarmAmber,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun HarmonyMonitorCard(
    harmonyState: HarmonyUiState,
    sustainPedalDown: Boolean,
    isCompactHeight: Boolean,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val isPure = harmonyState.temperament == TemperamentMode.PURE
    val rootName = PitchTheory.pitchClassName(harmonyState.effectiveRootIndex)
    val tempLabel = if (isPure) {
        val autoTag = if (harmonyState.autoDetectRoot) "AUTO" else "LOCK"
        "PURE · ROOT $rootName ($autoTag)"
    } else {
        "EQUAL TEMPERAMENT"
    }
    val hasActiveNotes = harmonyState.activeWrittenMidis.isNotEmpty()
    val cardPadding = if (isCompactHeight) 8.dp else 10.dp

    Surface(
        modifier = modifier,
        color = LcdMidnight,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.2.dp,
            if (hasActiveNotes) ElectricSky.copy(alpha = 0.65f) else LcdBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(cardPadding),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Section label + Voice badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "HARMONY",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MutedSlateText,
                    maxLines = 1
                )
                Surface(
                    color = ElectricSkyContainer,
                    shape = MaterialTheme.shapes.extraSmall,
                    border = BorderStroke(1.dp, ElectricSky.copy(alpha = 0.65f))
                ) {
                    Text(
                        text = harmonyState.voice.label.uppercase(Locale.US),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = ElectricSky,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                    )
                }
            }

            if (isLandscape && isCompactHeight) {
                // Compact landscape phone: side-by-side chord + temperament/badges
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        ChordHeroText(
                            chordLabel = harmonyState.chordLabel,
                            isActive = hasActiveNotes,
                            compact = true
                        )
                    }
                    Column(
                        modifier = Modifier.weight(1.15f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        TemperamentPill(
                            text = tempLabel,
                            isPure = isPure,
                            compact = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        HarmonyStatusFlagsRow(
                            referencePitchHz = harmonyState.referencePitchHz,
                            transpositionShort = harmonyState.transposition.shortLabel,
                            isTransposed = harmonyState.transposition.semitoneOffset != 0,
                            chordHoldEnabled = harmonyState.chordHoldEnabled,
                            sustainPedalDown = sustainPedalDown,
                            compact = true
                        )
                    }
                }
            } else {
                // Standard portrait & tablet landscape layout
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart
                ) {
                    ChordHeroText(
                        chordLabel = harmonyState.chordLabel,
                        isActive = hasActiveNotes,
                        compact = !isLandscape
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    TemperamentPill(
                        text = tempLabel,
                        isPure = isPure,
                        compact = false,
                        modifier = Modifier.fillMaxWidth()
                    )
                    HarmonyStatusFlagsRow(
                        referencePitchHz = harmonyState.referencePitchHz,
                        transpositionShort = harmonyState.transposition.shortLabel,
                        isTransposed = harmonyState.transposition.semitoneOffset != 0,
                        chordHoldEnabled = harmonyState.chordHoldEnabled,
                        sustainPedalDown = sustainPedalDown,
                        compact = !isLandscape
                    )
                }
            }
        }
    }
}

@Composable
private fun ChordHeroText(
    chordLabel: String,
    isActive: Boolean,
    compact: Boolean
) {
    val fontSize = when {
        chordLabel.length <= 4 -> if (compact) 34.sp else 46.sp
        chordLabel.length <= 7 -> if (compact) 26.sp else 36.sp
        else -> if (compact) 20.sp else 26.sp
    }
    val lineHeight = (fontSize.value * 1.08f).sp
    Text(
        text = chordLabel,
        style = MaterialTheme.typography.displaySmall.copy(
            fontSize = fontSize,
            lineHeight = lineHeight
        ),
        color = if (isActive) ElectricSky else MutedSlateText.copy(alpha = 0.4f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.testTag("monitor_chord_label")
    )
}

@Composable
private fun TemperamentPill(
    text: String,
    isPure: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    val accent = if (isPure) PitchEmerald else WarmAmber
    val bg = if (isPure) PitchEmerald.copy(alpha = 0.16f) else WarmAmberContainer
    Surface(
        modifier = modifier,
        color = bg,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.8f))
    ) {
        Text(
            text = text,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 2.dp else 4.dp
            )
        )
    }
}

@Composable
private fun HarmonyStatusFlagsRow(
    referencePitchHz: Double,
    transpositionShort: String,
    isTransposed: Boolean,
    chordHoldEnabled: Boolean,
    sustainPedalDown: Boolean,
    compact: Boolean
) {
    val pitchText = String.format(Locale.US, "A4 %.1f", referencePitchHz)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ReadOnlyTinyBadge(
            text = pitchText,
            color = WarmAmber,
            highlighted = false,
            compact = compact,
            modifier = Modifier.weight(1.65f)
        )
        ReadOnlyTinyBadge(
            text = transpositionShort,
            color = if (isTransposed) WarmAmber else CrispOffWhite,
            highlighted = isTransposed,
            compact = compact,
            modifier = Modifier.weight(0.60f)
        )
        ReadOnlyTinyBadge(
            text = "HOLD",
            color = if (chordHoldEnabled) ElectricSky else MutedSlateText,
            highlighted = chordHoldEnabled,
            compact = compact,
            modifier = Modifier.weight(0.90f)
        )
        ReadOnlyTinyBadge(
            text = "PED",
            color = if (sustainPedalDown) PitchEmerald else MutedSlateText,
            highlighted = sustainPedalDown,
            compact = compact,
            modifier = Modifier.weight(0.75f)
        )
    }
}

@Composable
private fun ReadOnlyTinyBadge(
    text: String,
    color: Color,
    highlighted: Boolean,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = if (highlighted) color.copy(alpha = 0.18f) else ConsoleSurface.copy(alpha = 0.75f),
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(
            1.dp,
            if (highlighted) color.copy(alpha = 0.8f) else HardwareBorder.copy(alpha = 0.7f)
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontSize = if (compact) 9.sp else 10.sp,
            fontWeight = if (highlighted) FontWeight.Bold else FontWeight.Medium,
            color = if (highlighted) color else color.copy(alpha = 0.78f),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 3.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun MetronomeMonitorCard(
    metronomeState: MetronomeUiState,
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>,
    isCompactHeight: Boolean,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val isPlaying = metronomeState.isPlaying
    val cardPadding = if (isCompactHeight) 8.dp else 10.dp

    Surface(
        modifier = modifier,
        color = LcdMidnight,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(
            1.2.dp,
            if (isPlaying) PitchEmerald.copy(alpha = 0.7f) else LcdBorder
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(cardPadding),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Header: Metronome label + Playing/Stopped state
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "METRONOME",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MutedSlateText,
                    maxLines = 1
                )
                Surface(
                    color = if (isPlaying) PitchEmerald.copy(alpha = 0.18f) else ConsoleSurface,
                    shape = MaterialTheme.shapes.extraSmall,
                    border = BorderStroke(
                        1.dp,
                        if (isPlaying) PitchEmerald else HardwareBorder
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(if (isPlaying) PitchEmerald else MutedSlateText)
                        )
                        Text(
                            text = if (isPlaying) "PLAYING" else "STOPPED",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isPlaying) PitchEmerald else MutedSlateText,
                            maxLines = 1
                        )
                    }
                }
            }

            // Center hero: Giant BPM + Time Signature + Metronome Sound
            if (isLandscape && isCompactHeight) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "${metronomeState.bpm}",
                            style = MaterialTheme.typography.displaySmall.copy(
                                fontSize = 36.sp,
                                lineHeight = 38.sp
                            ),
                            color = if (isPlaying) PitchEmerald else ElectricSky,
                            maxLines = 1
                        )
                        Text(
                            text = "BPM",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedSlateText,
                            modifier = Modifier.padding(bottom = 5.dp)
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    TimeSignatureBadge(
                        label = metronomeState.timeSignature.label,
                        compact = true
                    )
                    MetronomeSoundBadge(
                        label = metronomeState.metronomeSound.label,
                        compact = true
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.Center
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "${metronomeState.bpm}",
                                style = MaterialTheme.typography.displaySmall.copy(
                                    fontSize = if (isLandscape) 46.sp else 32.sp,
                                    lineHeight = if (isLandscape) 48.sp else 34.sp
                                ),
                                color = if (isPlaying) PitchEmerald else ElectricSky,
                                maxLines = 1
                            )
                            Text(
                                text = "BPM",
                                style = MaterialTheme.typography.labelSmall,
                                color = MutedSlateText,
                                modifier = Modifier.padding(bottom = 5.dp)
                            )
                        }

                        TimeSignatureBadge(
                            label = metronomeState.timeSignature.label,
                            compact = !isLandscape
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    MetronomeSoundBadge(
                        label = metronomeState.metronomeSound.label,
                        compact = !isLandscape,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // Bottom isolated Beat LED Bar + Mute Drill indicator
            MonitorBeatLedBar(
                beatSnapshotFlow = beatSnapshotFlow,
                fallbackTotalBeats = metronomeState.timeSignature.beatsPerMeasure,
                alternateMuteBar = metronomeState.alternateMuteBar,
                isCompactHeight = isCompactHeight
            )
        }
    }
}

@Composable
private fun TimeSignatureBadge(
    label: String,
    compact: Boolean
) {
    Surface(
        color = ConsoleSurface,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, ElectricSky.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = if (compact) 14.sp else 20.sp,
            color = CrispOffWhite,
            maxLines = 1,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 10.dp,
                vertical = if (compact) 3.dp else 4.dp
            )
        )
    }
}

@Composable
private fun MetronomeSoundBadge(
    label: String,
    compact: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = WarmAmber.copy(alpha = 0.14f),
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, WarmAmber.copy(alpha = 0.55f))
    ) {
        Text(
            text = label.uppercase(Locale.US),
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = WarmAmber,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(
                horizontal = if (compact) 6.dp else 8.dp,
                vertical = if (compact) 3.dp else 4.dp
            )
        )
    }
}

/**
 * Isolated Beat LED Bar that collects [beatSnapshotFlow] without recomposing parent cards.
 */
@Composable
private fun MonitorBeatLedBar(
    beatSnapshotFlow: StateFlow<MetronomeBeatSnapshot>,
    fallbackTotalBeats: Int,
    alternateMuteBar: Boolean,
    isCompactHeight: Boolean
) {
    val beatSnap by beatSnapshotFlow.collectAsStateWithLifecycle()
    val ledDesc = stringResource(R.string.cd_beat_led)
    val total = (if (beatSnap.isPlaying) beatSnap.totalBeats else fallbackTotalBeats).coerceIn(1, 16)
    val barHeight = if (isCompactHeight) 14.dp else 18.dp
    val segmentSpacing = if (total > 10) 2.dp else 4.dp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = ledDesc },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .height(barHeight),
            horizontalArrangement = Arrangement.spacedBy(segmentSpacing),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (b in 0 until total) {
                val isCurrent = beatSnap.isPlaying && beatSnap.beatIndex == b
                val targetColor = when {
                    !isCurrent && b == 0 -> DownbeatCrimson.copy(alpha = 0.22f)
                    !isCurrent -> ConsoleSurface
                    beatSnap.isMutedBar -> WarmAmber
                    b == 0 -> DownbeatCrimson
                    else -> PitchEmerald
                }
                val ledColor by animateColorAsState(
                    targetValue = targetColor,
                    animationSpec = tween(durationMillis = 65),
                    label = "monitor_beat_led_$b"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(ledColor)
                )
            }
        }

        if (alternateMuteBar) {
            val isMutedNow = beatSnap.isPlaying && beatSnap.isMutedBar
            Surface(
                color = if (isMutedNow) WarmAmber else WarmAmberContainer,
                shape = MaterialTheme.shapes.extraSmall,
                border = BorderStroke(1.dp, WarmAmber)
            ) {
                Text(
                    text = if (isMutedNow) "MUTED" else "1/1 MUTE",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isMutedNow) StudioCharcoal else WarmAmber,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun HeldKeysMonitorCard(
    harmonyState: HarmonyUiState,
    isLandscape: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val notes = harmonyState.activePitchInfos
    val isPure = harmonyState.temperament == TemperamentMode.PURE
    val cardPadding = if (isCompactHeight) 8.dp else 10.dp

    Surface(
        modifier = modifier,
        color = AnodizedSlate,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.2.dp, HardwareBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 5.dp else 7.dp)
        ) {
            // Header strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Piano,
                        contentDescription = null,
                        tint = ElectricSky,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "HELD KEYS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CrispOffWhite,
                        maxLines = 1
                    )
                    Surface(
                        color = if (notes.isNotEmpty()) ElectricSkyContainer else ConsoleSurface,
                        shape = MaterialTheme.shapes.extraSmall,
                        border = BorderStroke(
                            1.dp,
                            if (notes.isNotEmpty()) ElectricSky.copy(alpha = 0.6f) else HardwareBorder
                        )
                    ) {
                        Text(
                            text = if (notes.isEmpty()) "0" else "${notes.size} HELD",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (notes.isNotEmpty()) ElectricSky else MutedSlateText,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                        )
                    }
                }

                Text(
                    text = if (isPure) "CENTS vs ET" else "FREQUENCY Hz",
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedSlateText,
                    maxLines = 1
                )
            }

            // Deterministic zero-overflow note tile grid (no virtual keyboard, no scroll)
            if (notes.isEmpty()) {
                Surface(
                    color = LcdMidnight,
                    shape = MaterialTheme.shapes.small,
                    border = BorderStroke(1.dp, LcdBorder),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "NO KEYS HELD · PLAY MIDI KEYBOARD",
                            style = MaterialTheme.typography.labelMedium,
                            color = MutedSlateText.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            } else {
                val maxVisibleTiles = 12
                val hasOverflow = notes.size > maxVisibleTiles
                val visibleNotes = if (hasOverflow) notes.take(maxVisibleTiles - 1) else notes
                val hiddenCount = notes.size - visibleNotes.size
                val totalSlots = visibleNotes.size + if (hasOverflow) 1 else 0

                val rowCount = when {
                    totalSlots <= 4 -> 1
                    totalSlots <= 8 -> 2
                    isLandscape -> 2
                    else -> 3
                }
                val colsPerRow = (totalSlots + rowCount - 1) / rowCount
                val isDense = rowCount >= 2 || colsPerRow >= 5 || isCompactHeight

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    for (r in 0 until rowCount) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            for (c in 0 until colsPerRow) {
                                val slotIdx = r * colsPerRow + c
                                when {
                                    slotIdx < visibleNotes.size -> {
                                        HeldKeyTile(
                                            info = visibleNotes[slotIdx],
                                            isPure = isPure,
                                            isDense = isDense,
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        )
                                    }
                                    slotIdx == visibleNotes.size && hasOverflow -> {
                                        Surface(
                                            color = ConsoleSurface,
                                            shape = MaterialTheme.shapes.small,
                                            border = BorderStroke(1.dp, HardwareBorder),
                                            modifier = Modifier
                                                .weight(1f)
                                                .fillMaxHeight()
                                        ) {
                                            Box(
                                                modifier = Modifier.fillMaxSize(),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "+$hiddenCount MORE",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = MutedSlateText,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                    else -> {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeldKeyTile(
    info: NotePitchInfo,
    isPure: Boolean,
    isDense: Boolean,
    modifier: Modifier = Modifier
) {
    val isRoot = isPure && info.intervalName == "Root"
    val borderColor = if (isRoot) PitchEmerald else ElectricSky.copy(alpha = 0.65f)
    val noteDisplay = if (info.writtenName == info.soundingName) {
        info.soundingName
    } else {
        "${info.writtenName}→${info.soundingName}"
    }
    val detailText = if (isPure) {
        val sign = if (info.centsOffsetFromEt >= 0.0) "+" else ""
        String.format(Locale.US, "%s %s%.1f¢", info.intervalName, sign, info.centsOffsetFromEt)
    } else {
        String.format(Locale.US, "%.1f Hz", info.frequencyHz)
    }
    val detailColor = when {
        !isPure -> MutedSlateText
        abs(info.centsOffsetFromEt) < 0.05 -> PitchEmerald
        info.centsOffsetFromEt < 0.0 -> ElectricSky
        else -> WarmAmber
    }

    Surface(
        modifier = modifier,
        color = LcdMidnight,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.2.dp, borderColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = if (isDense) 3.dp else 6.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = noteDisplay,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = if (isDense) 15.sp else 21.sp,
                color = if (isRoot) PitchEmerald else CrispOffWhite,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = detailText,
                style = MaterialTheme.typography.labelSmall,
                fontSize = if (isDense) 9.sp else 11.sp,
                fontWeight = FontWeight.Medium,
                color = detailColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LevelsMonitorCard(
    harmonyState: HarmonyUiState,
    metronomeState: MetronomeUiState,
    audioOutputMode: AudioOutputMode,
    showOutputBadge: Boolean,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val mix = metronomeState.subdivisionMix
    val cardPadding = if (isCompactHeight) 8.dp else 10.dp

    Surface(
        modifier = modifier,
        color = AnodizedSlate,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.2.dp, HardwareBorder)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(cardPadding),
            verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 5.dp else 7.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = ElectricSky,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "OUTPUT LEVELS",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = CrispOffWhite,
                        maxLines = 1
                    )
                }

                if (showOutputBadge) {
                    MonitorOutputModeBadge(audioOutputMode = audioOutputMode)
                } else {
                    Text(
                        text = "MASTER  |  SUBDIVISIONS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MutedSlateText,
                        maxLines = 1
                    )
                }
            }

            // 7 Vertical Level Meters: Masters (Tone, Metro) | Subdivisions (Acc, 1/4, 1/8, Trip, 1/16)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LevelMeterColumn(
                    label = "TONE",
                    level = harmonyState.toneGain,
                    accentColor = ElectricSky,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                LevelMeterColumn(
                    label = "METRO",
                    level = metronomeState.metronomeGain,
                    accentColor = PitchEmerald,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )

                // Visual separator between Master gains and Metronome Subdivisions
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight(0.84f)
                        .background(HardwareBorder)
                )

                LevelMeterColumn(
                    label = "ACC",
                    level = mix.accent,
                    accentColor = WarmAmber,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                LevelMeterColumn(
                    label = "1/4",
                    level = mix.quarter,
                    accentColor = ElectricSky,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                LevelMeterColumn(
                    label = "1/8",
                    level = mix.eighth,
                    accentColor = ElectricSky,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                LevelMeterColumn(
                    label = "TRIP",
                    level = mix.triplet,
                    accentColor = PitchEmerald,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                LevelMeterColumn(
                    label = "1/16",
                    level = mix.sixteenth,
                    accentColor = ElectricSky,
                    isCompactHeight = isCompactHeight,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
            }
        }
    }
}

@Composable
private fun LevelMeterColumn(
    label: String,
    level: Float,
    accentColor: Color,
    isCompactHeight: Boolean,
    modifier: Modifier = Modifier
) {
    val clamped = level.coerceIn(0f, 1f)
    val animatedLevel by animateFloatAsState(
        targetValue = clamped,
        animationSpec = tween(durationMillis = 80),
        label = "level_meter_$label"
    )
    val percent = (clamped * 100f).roundToInt()
    val isActive = clamped > 0.005f

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (isCompactHeight) 2.dp else 4.dp)
    ) {
        Text(
            text = "$percent%",
            fontFamily = JetBrainsMonoFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = if (isCompactHeight) 9.sp else 11.sp,
            color = if (isActive) accentColor else MutedSlateText.copy(alpha = 0.55f),
            maxLines = 1
        )

        // Recessed studio level bar with 25/50/75% scale reference lines
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            color = LcdMidnight,
            shape = RoundedCornerShape(6.dp),
            border = BorderStroke(
                1.dp,
                if (isActive) accentColor.copy(alpha = 0.45f) else HardwareBorder.copy(alpha = 0.7f)
            )
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(3.dp)
            ) {
                val w = size.width
                val h = size.height
                val tickColor = HardwareBorder.copy(alpha = 0.45f)
                for (fraction in floatArrayOf(0.25f, 0.50f, 0.75f)) {
                    val y = h * (1f - fraction)
                    drawLine(
                        color = tickColor,
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                if (animatedLevel > 0.005f) {
                    val fillH = (h * animatedLevel).coerceAtLeast(3.dp.toPx())
                    val topY = h - fillH
                    val radius = CornerRadius(3.dp.toPx(), 3.dp.toPx())
                    drawRoundRect(
                        color = accentColor.copy(alpha = 0.35f),
                        topLeft = Offset(0f, topY),
                        size = Size(w, fillH),
                        cornerRadius = radius
                    )
                    // Bright crisp cap bar at the active level height
                    val capH = 3.dp.toPx().coerceAtMost(fillH)
                    drawRoundRect(
                        color = accentColor,
                        topLeft = Offset(0f, topY),
                        size = Size(w, capH),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
            }
        }

        Text(
            text = label,
            fontFamily = SpaceGroteskFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = if (isCompactHeight) 9.sp else 11.sp,
            color = if (isActive) CrispOffWhite else MutedSlateText.copy(alpha = 0.7f),
            maxLines = 1
        )
    }
}

@Composable
private fun MonitorOutputModeBadge(
    audioOutputMode: AudioOutputMode,
    modifier: Modifier = Modifier
) {
    val isSplit = audioOutputMode == AudioOutputMode.SPLIT_LR
    val accent = if (isSplit) WarmAmber else ElectricSky
    Surface(
        modifier = modifier,
        color = if (isSplit) WarmAmberContainer else ConsoleSurfaceBright,
        shape = MaterialTheme.shapes.extraSmall,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.GraphicEq,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = if (isSplit) "L:TONE · R:CLK" else "OUT: MIX",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = accent,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun MonitorMidiFooterStrip(
    midiState: MidiConnectionUiState,
    isCompactHeight: Boolean
) {
    val isConnected = midiState.connectedDeviceName != null
    Surface(
        color = LcdMidnight,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, LcdBorder),
        modifier = Modifier
            .fillMaxWidth()
            .height(if (isCompactHeight) 24.dp else 28.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Sensors,
                contentDescription = null,
                tint = if (isConnected) PitchEmerald else MutedSlateText,
                modifier = Modifier.size(13.dp)
            )
            Text(
                text = "MIDI:",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MutedSlateText,
                maxLines = 1
            )
            Text(
                text = midiState.lastEventText,
                style = MaterialTheme.typography.labelSmall,
                color = ElectricSky,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "AWAKE",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = PitchEmerald.copy(alpha = 0.8f),
                maxLines = 1
            )
        }
    }
}
