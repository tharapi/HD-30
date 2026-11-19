package com.example.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.ui.components.ConsoleLcdHeader
import com.example.ui.components.HarmonyPanel
import com.example.ui.components.MetronomePanel
import com.example.ui.components.MidiConnectionDialog
import com.example.ui.components.PresetsPanel
import com.example.ui.theme.AnodizedSlate
import com.example.ui.theme.ElectricSky
import com.example.ui.theme.ElectricSkyContainer
import com.example.ui.theme.MutedSlateText
import com.example.ui.theme.StudioCharcoal

private enum class ConsoleTab {
    HARMONY,
    METRONOME,
    PRESETS
}

private enum class ExpandedSection {
    DUAL_CONSOLE,
    PRESETS
}

@Composable
fun HarmonyDirectorScreen(
    viewModel: ConsoleViewModel,
    onSignOut: () -> Unit
) {
    val harmonyState by viewModel.harmonyState.collectAsStateWithLifecycle()
    val metronomeState by viewModel.metronomeState.collectAsStateWithLifecycle()
    val audioOutputMode by viewModel.audioOutputMode.collectAsStateWithLifecycle()
    val presetsState by viewModel.presetsState.collectAsStateWithLifecycle()
    val activePresetId by viewModel.activePresetId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val midiState by viewModel.midiConnectionState.collectAsStateWithLifecycle()
    val sustainPedalDown by viewModel.sustainPedalDown.collectAsStateWithLifecycle()
    val midiMappings by viewModel.midiMappings.collectAsStateWithLifecycle()
    val learningTarget by viewModel.learningTarget.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    var selectedTab by rememberSaveable { mutableStateOf(ConsoleTab.HARMONY) }
    var expandedSection by rememberSaveable { mutableStateOf(ExpandedSection.DUAL_CONSOLE) }
    var showMidiDialog by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.snackbarEvents.collect { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isExpanded = maxWidth >= 840.dp

        Scaffold(
            containerColor = StudioCharcoal,
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!isExpanded) {
                    NavigationBar(
                        containerColor = AnodizedSlate,
                        windowInsets = WindowInsets.navigationBars
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == ConsoleTab.HARMONY,
                            onClick = { selectedTab = ConsoleTab.HARMONY },
                            modifier = Modifier.testTag("nav_tab_harmony"),
                            icon = { Icon(Icons.Filled.Piano, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_harmony)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricSky,
                                selectedTextColor = ElectricSky,
                                indicatorColor = ElectricSkyContainer,
                                unselectedIconColor = MutedSlateText,
                                unselectedTextColor = MutedSlateText
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == ConsoleTab.METRONOME,
                            onClick = { selectedTab = ConsoleTab.METRONOME },
                            modifier = Modifier.testTag("nav_tab_metronome"),
                            icon = { Icon(Icons.Filled.Speed, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_metronome)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricSky,
                                selectedTextColor = ElectricSky,
                                indicatorColor = ElectricSkyContainer,
                                unselectedIconColor = MutedSlateText,
                                unselectedTextColor = MutedSlateText
                            )
                        )
                        NavigationBarItem(
                            selected = selectedTab == ConsoleTab.PRESETS,
                            onClick = { selectedTab = ConsoleTab.PRESETS },
                            modifier = Modifier.testTag("nav_tab_presets"),
                            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_presets)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ElectricSky,
                                selectedTextColor = ElectricSky,
                                indicatorColor = ElectricSkyContainer,
                                unselectedIconColor = MutedSlateText,
                                unselectedTextColor = MutedSlateText
                            )
                        )
                    }
                }
            }
        ) { innerPadding ->
            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                ) {
                    NavigationRail(
                        containerColor = AnodizedSlate,
                        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
                    ) {
                        NavigationRailItem(
                            selected = expandedSection == ExpandedSection.DUAL_CONSOLE,
                            onClick = { expandedSection = ExpandedSection.DUAL_CONSOLE },
                            modifier = Modifier.testTag("rail_dual_console"),
                            icon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                            label = { Text("Console") },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ElectricSky,
                                selectedTextColor = ElectricSky,
                                indicatorColor = ElectricSkyContainer
                            )
                        )
                        NavigationRailItem(
                            selected = expandedSection == ExpandedSection.PRESETS,
                            onClick = { expandedSection = ExpandedSection.PRESETS },
                            modifier = Modifier.testTag("rail_presets"),
                            icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                            label = { Text(stringResource(R.string.tab_presets)) },
                            colors = NavigationRailItemDefaults.colors(
                                selectedIconColor = ElectricSky,
                                selectedTextColor = ElectricSky,
                                indicatorColor = ElectricSkyContainer
                            )
                        )
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        ConsoleLcdHeader(
                            harmonyState = harmonyState,
                            metronomeState = metronomeState,
                            beatSnapshotFlow = viewModel.beatSnapshot,
                            onStopAllSound = viewModel::stopAllSound,
                            onSignOut = onSignOut,
                            midiState = midiState,
                            onOpenMidiSettings = { showMidiDialog = true },
                            audioOutputMode = audioOutputMode,
                            onToggleOutputMode = viewModel::toggleAudioOutputMode
                        )

                        when (expandedSection) {
                            ExpandedSection.DUAL_CONSOLE -> {
                                Row(
                                    modifier = Modifier.fillMaxSize(),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    MetronomePanel(
                                        state = metronomeState,
                                        beatSnapshotFlow = viewModel.beatSnapshot,
                                        onTogglePlay = viewModel::toggleMetronomePlaying,
                                        onBpmChanged = viewModel::setBpm,
                                        onAdjustBpm = viewModel::adjustBpm,
                                        onTapTempo = viewModel::onTapTempo,
                                        onTimeSignatureSelected = viewModel::selectTimeSignature,
                                        onMetronomeSoundSelected = viewModel::selectMetronomeSound,
                                        onSubdivisionMixChanged = viewModel::updateSubdivisionMix,
                                        onMetronomeGainChanged = viewModel::setMetronomeGain,
                                        onToggleMuteBar = viewModel::toggleAlternateMuteBar,
                                        audioOutputMode = audioOutputMode,
                                        onAudioOutputModeSelected = viewModel::setAudioOutputMode,
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxHeight()
                                    )
                                    HarmonyPanel(
                                        state = harmonyState,
                                        onVoiceSelected = viewModel::selectVoice,
                                        onReferencePitchChanged = viewModel::setReferencePitchHz,
                                        onAdjustPitch = viewModel::adjustReferencePitch,
                                        onTemperamentSelected = viewModel::selectTemperament,
                                        onRootSelected = viewModel::selectRootKey,
                                        onToggleAutoRoot = viewModel::toggleAutoDetectRoot,
                                        onTranspositionSelected = viewModel::selectTransposition,
                                        onAdjustOctave = viewModel::adjustOctaveShift,
                                        onToggleChordHold = viewModel::toggleChordHold,
                                        onClearNotes = viewModel::clearHeldNotes,
                                        onToneGainChanged = viewModel::setToneGain,
                                        onKeyPressed = viewModel::onKeyPressed,
                                        onKeyReleased = viewModel::onKeyReleased,
                                        modifier = Modifier
                                            .weight(1.1f)
                                            .fillMaxHeight()
                                    )
                                }
                            }
                            ExpandedSection.PRESETS -> {
                                PresetsPanel(
                                    presetsState = presetsState,
                                    activePresetId = activePresetId,
                                    searchQuery = searchQuery,
                                    harmonyState = harmonyState,
                                    metronomeState = metronomeState,
                                    onSearchQueryChange = viewModel::setSearchQuery,
                                    onLoadPreset = {
                                        viewModel.loadPreset(it)
                                        expandedSection = ExpandedSection.DUAL_CONSOLE
                                    },
                                    onSaveNewPreset = viewModel::saveNewPreset,
                                    onUpdatePreset = viewModel::overwritePresetWithCurrentSettings,
                                    onDeletePreset = viewModel::deletePreset
                                )
                            }
                        }
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    ConsoleLcdHeader(
                        harmonyState = harmonyState,
                        metronomeState = metronomeState,
                        beatSnapshotFlow = viewModel.beatSnapshot,
                        onStopAllSound = viewModel::stopAllSound,
                        onSignOut = onSignOut,
                        midiState = midiState,
                        onOpenMidiSettings = { showMidiDialog = true },
                        audioOutputMode = audioOutputMode,
                        onToggleOutputMode = viewModel::toggleAudioOutputMode
                    )

                    Box(modifier = Modifier.weight(1f)) {
                        when (selectedTab) {
                            ConsoleTab.HARMONY -> HarmonyPanel(
                                state = harmonyState,
                                onVoiceSelected = viewModel::selectVoice,
                                onReferencePitchChanged = viewModel::setReferencePitchHz,
                                onAdjustPitch = viewModel::adjustReferencePitch,
                                onTemperamentSelected = viewModel::selectTemperament,
                                onRootSelected = viewModel::selectRootKey,
                                onToggleAutoRoot = viewModel::toggleAutoDetectRoot,
                                onTranspositionSelected = viewModel::selectTransposition,
                                onAdjustOctave = viewModel::adjustOctaveShift,
                                onToggleChordHold = viewModel::toggleChordHold,
                                onClearNotes = viewModel::clearHeldNotes,
                                onToneGainChanged = viewModel::setToneGain,
                                onKeyPressed = viewModel::onKeyPressed,
                                onKeyReleased = viewModel::onKeyReleased
                            )
                            ConsoleTab.METRONOME -> MetronomePanel(
                                state = metronomeState,
                                beatSnapshotFlow = viewModel.beatSnapshot,
                                onTogglePlay = viewModel::toggleMetronomePlaying,
                                onBpmChanged = viewModel::setBpm,
                                onAdjustBpm = viewModel::adjustBpm,
                                onTapTempo = viewModel::onTapTempo,
                                onTimeSignatureSelected = viewModel::selectTimeSignature,
                                onMetronomeSoundSelected = viewModel::selectMetronomeSound,
                                onSubdivisionMixChanged = viewModel::updateSubdivisionMix,
                                onMetronomeGainChanged = viewModel::setMetronomeGain,
                                onToggleMuteBar = viewModel::toggleAlternateMuteBar,
                                audioOutputMode = audioOutputMode,
                                onAudioOutputModeSelected = viewModel::setAudioOutputMode
                            )
                            ConsoleTab.PRESETS -> PresetsPanel(
                                presetsState = presetsState,
                                activePresetId = activePresetId,
                                searchQuery = searchQuery,
                                harmonyState = harmonyState,
                                metronomeState = metronomeState,
                                onSearchQueryChange = viewModel::setSearchQuery,
                                onLoadPreset = {
                                    viewModel.loadPreset(it)
                                    selectedTab = ConsoleTab.HARMONY
                                },
                                onSaveNewPreset = viewModel::saveNewPreset,
                                onUpdatePreset = viewModel::overwritePresetWithCurrentSettings,
                                onDeletePreset = viewModel::deletePreset
                            )
                        }
                    }
                }
            }
        }

        if (showMidiDialog) {
            MidiConnectionDialog(
                midiState = midiState,
                sustainPedalDown = sustainPedalDown,
                midiMappings = midiMappings,
                learningTarget = learningTarget,
                onDismiss = {
                    viewModel.cancelMidiLearn()
                    viewModel.clearMidiStatus()
                    showMidiDialog = false
                },
                onRefreshUsb = viewModel::refreshMidiDevices,
                onConnectUsbDevice = viewModel::connectMidiDevice,
                onDisconnectUsbDevice = viewModel::disconnectMidiDevice,
                onStartBleScan = viewModel::startBleMidiScan,
                onStopBleScan = viewModel::stopBleMidiScan,
                onConnectBleDevice = viewModel::connectBleMidiDevice,
                onStartMidiLearn = viewModel::startMidiLearn,
                onCancelMidiLearn = viewModel::cancelMidiLearn,
                onResetDefaultMappings = viewModel::resetDefaultMidiMappings
            )
        }
    }
}
