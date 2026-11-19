package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.HarmonyAudioEngine
import com.example.audio.MetronomeBeatSnapshot
import com.example.data.RehearsalPresetRepository
import com.example.midi.MidiConnectionUiState
import com.example.midi.MidiHardwareEvent
import com.example.midi.MidiInputController
import com.example.midi.MidiNoteStateTracker
import com.example.model.MetronomeSound
import com.example.model.NotePitchInfo
import com.example.model.PitchTheory
import com.example.model.RehearsalPreset
import com.example.model.SubdivisionMix
import com.example.model.TemperamentMode
import com.example.model.TimeSignature
import com.example.model.ToneVoice
import com.example.model.TranspositionKey
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Success<T>(val data: T) : UiState<T>
    data class Error(val message: String) : UiState<Nothing>
}

data class HarmonyUiState(
    val voice: ToneVoice = ToneVoice.ORGAN,
    val referencePitchHz: Double = 440.0,
    val temperament: TemperamentMode = TemperamentMode.PURE,
    val rootKeyIndex: Int = 0,
    val autoDetectRoot: Boolean = true,
    val transposition: TranspositionKey = TranspositionKey.CONCERT_C,
    val octaveShift: Int = 0,
    val chordHoldEnabled: Boolean = true,
    val activeWrittenMidis: Set<Int> = emptySet(),
    val effectiveRootIndex: Int = 0,
    val activePitchInfos: List<NotePitchInfo> = emptyList(),
    val chordLabel: String = "--",
    val toneGain: Float = 0.80f
)

data class MetronomeUiState(
    val isPlaying: Boolean = false,
    val bpm: Int = 120,
    val timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    val metronomeSound: MetronomeSound = MetronomeSound.WOODBLOCK,
    val subdivisionMix: SubdivisionMix = SubdivisionMix(),
    val alternateMuteBar: Boolean = false,
    val metronomeGain: Float = 0.75f
)

enum class MidiControlTarget(
    val label: String,
    val defaultCc: Int,
    val isTrigger: Boolean
) {
    TONE_VOLUME("Tone Vol", 49, false),
    BPM("Tempo BPM", 48, false),
    ACCENT_LEVEL("Accent", 1, false),
    QUARTER_LEVEL("1/4 Beat", 64, false),
    EIGHTH_LEVEL("1/8 Beat", 65, false),
    TRIPLET_LEVEL("Triplet", 66, false),
    SIXTEENTH_LEVEL("1/16 Beat", 67, false),
    TAP_TEMPO("Tap Tempo", 36, true),
    CLEAR_HELD("Clear Held", 37, true),
    TOGGLE_METRONOME("Play/Stop", 40, true),
    TOGGLE_CHORD_HOLD("Chord Hold", 41, true),
    TOGGLE_TEMPERAMENT("Equal / Pure", 42, true),
    MUTE_ALL("All Sound Off", 47, true),
    METER_TOP_VALUE("Meter Top (1-16)", -1, false),
    METER_BOTTOM_VALUE("Meter Bottom", -1, false),
    METER_TOP_STEP("Meter Top +1", -1, true),
    METER_BOTTOM_STEP("Meter Bottom Step", -1, true)
}

class ConsoleViewModel(
    private val repository: RehearsalPresetRepository,
    private val audioEngine: HarmonyAudioEngine,
    private val currentUserId: String,
    private val midiController: MidiInputController? = null,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) : ViewModel() {

    private val _harmonyState = MutableStateFlow(HarmonyUiState())
    val harmonyState: StateFlow<HarmonyUiState> = _harmonyState.asStateFlow()

    private val _metronomeConfig = MutableStateFlow(MetronomeUiState())
    val metronomeState: StateFlow<MetronomeUiState> = _metronomeConfig.asStateFlow()

    val beatSnapshot: StateFlow<MetronomeBeatSnapshot> = audioEngine.beatSnapshot

    val presetsState: StateFlow<UiState<List<RehearsalPreset>>> =
        repository.observePresets(currentUserId)
            .map<List<RehearsalPreset>, UiState<List<RehearsalPreset>>> { UiState.Success(it) }
            .catch { error ->
                val firestoreError = generateSequence(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .firstOrNull()
                if (firestoreError == null && error is CancellationException) throw error
                val rootError = firestoreError ?: error
                emit(UiState.Error(rootError.message ?: "Failed to load rehearsal presets."))
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = UiState.Loading
            )

    private val _activePresetId = MutableStateFlow<String?>(null)
    val activePresetId: StateFlow<String?> = _activePresetId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _snackbarEvents = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarEvents: SharedFlow<String> = _snackbarEvents.asSharedFlow()

    val midiConnectionState: StateFlow<MidiConnectionUiState> =
        midiController?.state ?: MutableStateFlow(MidiConnectionUiState()).asStateFlow()

    private val _sustainPedalDown = MutableStateFlow(false)
    val sustainPedalDown: StateFlow<Boolean> = _sustainPedalDown.asStateFlow()

    private val _midiMappings = MutableStateFlow(
        MidiControlTarget.entries.associateWith { it.defaultCc }
    )
    val midiMappings: StateFlow<Map<MidiControlTarget, Int>> = _midiMappings.asStateFlow()

    private val _learningTarget = MutableStateFlow<MidiControlTarget?>(null)
    val learningTarget: StateFlow<MidiControlTarget?> = _learningTarget.asStateFlow()

    private val tapTimestampsMs = ArrayDeque<Long>()
    private val noteTracker = MidiNoteStateTracker(clockMs = clockMs)
    private val lastCcValues = mutableMapOf<Int, Int>()

    init {
        audioEngine.startEngine()
        pushAudioConfig()
        midiController?.let { controller ->
            controller.start()
            viewModelScope.launch {
                controller.events.collect { event ->
                    handleMidiHardwareEvent(event)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        midiController?.release()
        audioEngine.release()
    }

    // --- Harmony / Keyboard Actions ---

    fun onKeyPressed(writtenMidi: Int) {
        val active = noteTracker.onScreenKeyPressed(
            writtenMidi = writtenMidi,
            chordHoldEnabled = _harmonyState.value.chordHoldEnabled
        )
        syncHarmonyActiveMidis(active)
    }

    fun onKeyReleased(writtenMidi: Int) {
        val active = noteTracker.onScreenKeyReleased(writtenMidi)
        syncHarmonyActiveMidis(active)
    }

    fun toggleChordHold() {
        val nextHold = !_harmonyState.value.chordHoldEnabled
        val active = noteTracker.onChordHoldChanged(
            nextHoldEnabled = nextHold,
            sustainPedalDown = _sustainPedalDown.value
        )
        updateHarmony { state ->
            state.copy(
                chordHoldEnabled = nextHold,
                activeWrittenMidis = active
            )
        }
    }

    fun clearHeldNotes() {
        syncHarmonyActiveMidis(noteTracker.clearAll())
    }

    fun selectVoice(voice: ToneVoice) {
        _harmonyState.update { it.copy(voice = voice) }
        audioEngine.setToneVoice(voice)
    }

    fun setToneGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        _harmonyState.update { it.copy(toneGain = clamped) }
        audioEngine.setToneGain(clamped)
    }

    fun setReferencePitchHz(hz: Double) {
        val rounded = (kotlin.math.round(hz.coerceIn(430.0, 450.0) * 10.0) / 10.0)
        updateHarmony { it.copy(referencePitchHz = rounded) }
    }

    fun adjustReferencePitch(deltaHz: Double) {
        setReferencePitchHz(_harmonyState.value.referencePitchHz + deltaHz)
    }

    fun selectTemperament(mode: TemperamentMode) {
        updateHarmony { it.copy(temperament = mode) }
    }

    fun toggleTemperament() {
        updateHarmony { state ->
            val next = if (state.temperament == TemperamentMode.PURE) {
                TemperamentMode.EQUAL
            } else {
                TemperamentMode.PURE
            }
            state.copy(temperament = next)
        }
    }

    fun selectRootKey(index: Int) {
        val normalized = ((index % 12) + 12) % 12
        updateHarmony { it.copy(rootKeyIndex = normalized, autoDetectRoot = false) }
    }

    fun toggleAutoDetectRoot() {
        updateHarmony { it.copy(autoDetectRoot = !it.autoDetectRoot) }
    }

    fun selectTransposition(key: TranspositionKey) {
        updateHarmony { it.copy(transposition = key) }
    }

    fun adjustOctaveShift(delta: Int) {
        val active = noteTracker.onOctaveShift()
        updateHarmony { state ->
            val nextShift = (state.octaveShift + delta).coerceIn(-1, 1)
            state.copy(
                octaveShift = nextShift,
                activeWrittenMidis = active
            )
        }
    }

    // --- Metronome Actions ---

    fun toggleMetronomePlaying() {
        val next = !_metronomeConfig.value.isPlaying
        _metronomeConfig.update { it.copy(isPlaying = next) }
        audioEngine.setMetronomePlaying(next)
    }

    fun setBpm(newBpm: Int) {
        val clamped = newBpm.coerceIn(32, 280)
        _metronomeConfig.update { it.copy(bpm = clamped) }
        pushMetronomeAudioConfig()
    }

    fun adjustBpm(delta: Int) {
        setBpm(_metronomeConfig.value.bpm + delta)
    }

    fun onTapTempo() {
        val now = System.currentTimeMillis()
        val last = tapTimestampsMs.lastOrNull()
        if (last != null && now - last > 2200L) {
            tapTimestampsMs.clear()
        }
        tapTimestampsMs.addLast(now)
        while (tapTimestampsMs.size > 5) {
            tapTimestampsMs.removeFirst()
        }
        if (tapTimestampsMs.size >= 2) {
            val intervals = tapTimestampsMs.zipWithNext { a, b -> b - a }.filter { it > 150L }
            if (intervals.isNotEmpty()) {
                val avgMs = intervals.average()
                val tappedBpm = (60000.0 / avgMs).toInt().coerceIn(32, 280)
                setBpm(tappedBpm)
            }
        }
    }

    fun selectTimeSignature(ts: TimeSignature) {
        val safe = TimeSignature.create(ts.numerator, ts.denominator)
        _metronomeConfig.update { it.copy(timeSignature = safe) }
        pushMetronomeAudioConfig()
    }

    fun setTimeSignatureNumerator(numerator: Int) {
        val current = _metronomeConfig.value.timeSignature
        selectTimeSignature(TimeSignature.create(numerator, current.denominator))
    }

    fun setTimeSignatureDenominator(denominator: Int) {
        val current = _metronomeConfig.value.timeSignature
        selectTimeSignature(TimeSignature.create(current.numerator, denominator))
    }

    fun cycleTimeSignatureNumerator() {
        val current = _metronomeConfig.value.timeSignature
        val nextNum = if (current.numerator >= TimeSignature.MAX_NUMERATOR) {
            TimeSignature.MIN_NUMERATOR
        } else {
            current.numerator + 1
        }
        selectTimeSignature(TimeSignature.create(nextNum, current.denominator))
    }

    fun cycleTimeSignatureDenominator() {
        val current = _metronomeConfig.value.timeSignature
        val allowed = TimeSignature.ALLOWED_DENOMINATORS
        val idx = allowed.indexOf(current.denominator).coerceAtLeast(0)
        val nextDen = allowed[(idx + 1) % allowed.size]
        selectTimeSignature(TimeSignature.create(current.numerator, nextDen))
    }

    fun selectMetronomeSound(sound: MetronomeSound) {
        _metronomeConfig.update { it.copy(metronomeSound = sound) }
        pushMetronomeAudioConfig()
    }

    fun updateSubdivisionMix(transform: (SubdivisionMix) -> SubdivisionMix) {
        _metronomeConfig.update { state ->
            val nextMix = transform(state.subdivisionMix)
            state.copy(
                subdivisionMix = SubdivisionMix(
                    accent = nextMix.accent.coerceIn(0f, 1f),
                    quarter = nextMix.quarter.coerceIn(0f, 1f),
                    eighth = nextMix.eighth.coerceIn(0f, 1f),
                    triplet = nextMix.triplet.coerceIn(0f, 1f),
                    sixteenth = nextMix.sixteenth.coerceIn(0f, 1f)
                )
            )
        }
        pushMetronomeAudioConfig()
    }

    fun toggleAlternateMuteBar() {
        _metronomeConfig.update { it.copy(alternateMuteBar = !it.alternateMuteBar) }
        pushMetronomeAudioConfig()
    }

    fun setMetronomeGain(gain: Float) {
        val clamped = gain.coerceIn(0f, 1f)
        _metronomeConfig.update { it.copy(metronomeGain = clamped) }
        audioEngine.setMetronomeGain(clamped)
    }

    fun stopAllSound() {
        noteTracker.clearAll()
        _harmonyState.update {
            it.copy(
                activeWrittenMidis = emptySet(),
                activePitchInfos = emptyList(),
                chordLabel = "--"
            )
        }
        _metronomeConfig.update { it.copy(isPlaying = false) }
        audioEngine.stopAllSound()
    }

    // --- Hardware MIDI (M-VAVE SMK-37 PRO) Actions ---

    fun refreshMidiDevices() {
        midiController?.refreshMidiDevices()
    }

    fun connectMidiDevice(deviceId: Int) {
        midiController?.connectMidiDevice(deviceId)
    }

    fun disconnectMidiDevice(deviceId: Int) {
        midiController?.disconnectMidiDevice(deviceId)
    }

    fun startBleMidiScan() {
        midiController?.startBleScan()
    }

    fun stopBleMidiScan() {
        midiController?.stopBleScan()
    }

    fun connectBleMidiDevice(address: String) {
        midiController?.connectBleDevice(address)
    }

    fun clearMidiStatus() {
        midiController?.clearStatusMessage()
    }

    fun startMidiLearn(target: MidiControlTarget) {
        _learningTarget.value = target
    }

    fun cancelMidiLearn() {
        _learningTarget.value = null
    }

    fun resetDefaultMidiMappings() {
        _learningTarget.value = null
        _midiMappings.value = MidiControlTarget.entries.associateWith { it.defaultCc }
        _snackbarEvents.tryEmit("Reset MIDI CC mappings to SMK-37 PRO defaults")
    }

    internal fun handleMidiHardwareEvent(event: MidiHardwareEvent) {
        when (event) {
            is MidiHardwareEvent.NoteOn -> onExternalNoteOn(event.midiNote)
            is MidiHardwareEvent.NoteOff -> onExternalNoteOff(event.midiNote)
            is MidiHardwareEvent.ControlChange -> onExternalControlChange(event.cc, event.value)
            MidiHardwareEvent.AllNotesOff -> onExternalAllNotesOff()
        }
    }

    private fun onExternalAllNotesOff() {
        syncHarmonyActiveMidis(noteTracker.onExternalAllNotesOff())
    }

    private fun onExternalNoteOn(midiNote: Int) {
        val updated = noteTracker.onExternalNoteOn(
            midiNote = midiNote,
            chordHoldEnabled = _harmonyState.value.chordHoldEnabled
        ) ?: return
        syncHarmonyActiveMidis(updated)
    }

    private fun onExternalNoteOff(midiNote: Int) {
        val updated = noteTracker.onExternalNoteOff(
            midiNote = midiNote,
            chordHoldEnabled = _harmonyState.value.chordHoldEnabled,
            sustainPedalDown = _sustainPedalDown.value
        )
        syncHarmonyActiveMidis(updated)
    }

    private fun onExternalControlChange(cc: Int, value: Int) {
        val pendingLearn = _learningTarget.value
        if (pendingLearn != null) {
            _midiMappings.update { current ->
                current.mapValues { (target, mappedCc) ->
                    when {
                        target == pendingLearn -> cc
                        mappedCc == cc -> -1
                        else -> mappedCc
                    }
                }
            }
            _learningTarget.value = null
            lastCcValues[cc] = value
            _snackbarEvents.tryEmit("Mapped ${pendingLearn.label} to CC #$cc")
            return
        }

        val prev = lastCcValues[cc] ?: 0
        lastCcValues[cc] = value
        val matchedTarget = _midiMappings.value.entries.firstOrNull { it.value >= 0 && it.value == cc }?.key

        if (matchedTarget != null) {
            if (matchedTarget.isTrigger) {
                if (value >= 64 && prev < 64) {
                    when (matchedTarget) {
                        MidiControlTarget.TOGGLE_METRONOME -> toggleMetronomePlaying()
                        MidiControlTarget.TAP_TEMPO -> onTapTempo()
                        MidiControlTarget.CLEAR_HELD -> clearHeldNotes()
                        MidiControlTarget.TOGGLE_CHORD_HOLD -> toggleChordHold()
                        MidiControlTarget.TOGGLE_TEMPERAMENT -> toggleTemperament()
                        MidiControlTarget.MUTE_ALL -> stopAllSound()
                        MidiControlTarget.METER_TOP_STEP -> cycleTimeSignatureNumerator()
                        MidiControlTarget.METER_BOTTOM_STEP -> cycleTimeSignatureDenominator()
                        else -> Unit
                    }
                }
            } else {
                val normalized = (value / 127f).coerceIn(0f, 1f)
                when (matchedTarget) {
                    MidiControlTarget.TONE_VOLUME -> setToneGain(normalized)
                    MidiControlTarget.BPM -> {
                        val bpm = (32 + kotlin.math.round(normalized * (280 - 32))).toInt()
                        setBpm(bpm)
                    }
                    MidiControlTarget.ACCENT_LEVEL -> updateSubdivisionMix { it.copy(accent = normalized) }
                    MidiControlTarget.QUARTER_LEVEL -> updateSubdivisionMix { it.copy(quarter = normalized) }
                    MidiControlTarget.EIGHTH_LEVEL -> updateSubdivisionMix { it.copy(eighth = normalized) }
                    MidiControlTarget.TRIPLET_LEVEL -> updateSubdivisionMix { it.copy(triplet = normalized) }
                    MidiControlTarget.SIXTEENTH_LEVEL -> updateSubdivisionMix { it.copy(sixteenth = normalized) }
                    MidiControlTarget.METER_TOP_VALUE -> {
                        val num = (TimeSignature.MIN_NUMERATOR +
                            kotlin.math.round(normalized * (TimeSignature.MAX_NUMERATOR - TimeSignature.MIN_NUMERATOR))).toInt()
                        setTimeSignatureNumerator(num)
                    }
                    MidiControlTarget.METER_BOTTOM_VALUE -> {
                        val allowed = TimeSignature.ALLOWED_DENOMINATORS
                        val idx = kotlin.math.round(normalized * (allowed.size - 1)).toInt()
                            .coerceIn(0, allowed.lastIndex)
                        setTimeSignatureDenominator(allowed[idx])
                    }
                    else -> Unit
                }
            }
            return
        }

        // Fallback: CC 64 acts as Sustain Pedal when not assigned to a console parameter
        if (cc == 64) {
            val down = value >= 64
            _sustainPedalDown.value = down
            if (!down) {
                val active = noteTracker.onSustainPedalReleased(_harmonyState.value.chordHoldEnabled)
                syncHarmonyActiveMidis(active)
            }
        }
    }

    private fun syncHarmonyActiveMidis(active: Set<Int>) {
        updateHarmony { it.copy(activeWrittenMidis = active) }
    }

    // --- Preset Actions ---

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun loadPreset(preset: RehearsalPreset) {
        _activePresetId.value = preset.id
        _metronomeConfig.update { state ->
            state.copy(
                bpm = preset.bpm.toInt().coerceIn(32, 280),
                timeSignature = preset.safeTimeSignature(),
                metronomeSound = preset.safeMetronomeSound(),
                subdivisionMix = SubdivisionMix(
                    accent = preset.accentLevel.toFloat().coerceIn(0f, 1f),
                    quarter = preset.quarterLevel.toFloat().coerceIn(0f, 1f),
                    eighth = preset.eighthLevel.toFloat().coerceIn(0f, 1f),
                    triplet = preset.tripletLevel.toFloat().coerceIn(0f, 1f),
                    sixteenth = preset.sixteenthLevel.toFloat().coerceIn(0f, 1f)
                ),
                alternateMuteBar = preset.alternateMuteBar
            )
        }
        updateHarmony { state ->
            state.copy(
                referencePitchHz = preset.referencePitchHz.coerceIn(430.0, 450.0),
                temperament = preset.safeTemperament(),
                rootKeyIndex = preset.rootKeyIndex.toInt().coerceIn(0, 11),
                autoDetectRoot = preset.autoDetectRoot,
                transposition = preset.safeTransposition(),
                voice = preset.safeVoice()
            )
        }
        audioEngine.setToneVoice(preset.safeVoice())
        pushMetronomeAudioConfig()
        _snackbarEvents.tryEmit("Loaded \"${preset.title}\"")
    }

    fun saveNewPreset(title: String, notes: String) {
        val h = _harmonyState.value
        val m = _metronomeConfig.value
        val newPreset = RehearsalPreset(
            userId = currentUserId,
            title = title,
            notes = notes,
            bpm = m.bpm.toLong(),
            timeSignature = m.timeSignature.toStorageString(),
            metronomeSound = m.metronomeSound.name,
            accentLevel = m.subdivisionMix.accent.toDouble(),
            quarterLevel = m.subdivisionMix.quarter.toDouble(),
            eighthLevel = m.subdivisionMix.eighth.toDouble(),
            tripletLevel = m.subdivisionMix.triplet.toDouble(),
            sixteenthLevel = m.subdivisionMix.sixteenth.toDouble(),
            alternateMuteBar = m.alternateMuteBar,
            referencePitchHz = h.referencePitchHz,
            temperament = h.temperament.name,
            rootKeyIndex = h.rootKeyIndex.toLong(),
            autoDetectRoot = h.autoDetectRoot,
            transposition = h.transposition.name,
            voice = h.voice.name
        )
        viewModelScope.launch {
            val result = repository.createPreset(currentUserId, newPreset)
            if (result.isSuccess) {
                _snackbarEvents.tryEmit("Saved preset \"${title.trim()}\"")
            } else {
                _snackbarEvents.tryEmit(
                    result.exceptionOrNull()?.localizedMessage ?: "Failed to save preset"
                )
            }
        }
    }

    fun overwritePresetWithCurrentSettings(existing: RehearsalPreset) {
        val h = _harmonyState.value
        val m = _metronomeConfig.value
        val updated = existing.copy(
            bpm = m.bpm.toLong(),
            timeSignature = m.timeSignature.toStorageString(),
            metronomeSound = m.metronomeSound.name,
            accentLevel = m.subdivisionMix.accent.toDouble(),
            quarterLevel = m.subdivisionMix.quarter.toDouble(),
            eighthLevel = m.subdivisionMix.eighth.toDouble(),
            tripletLevel = m.subdivisionMix.triplet.toDouble(),
            sixteenthLevel = m.subdivisionMix.sixteenth.toDouble(),
            alternateMuteBar = m.alternateMuteBar,
            referencePitchHz = h.referencePitchHz,
            temperament = h.temperament.name,
            rootKeyIndex = h.rootKeyIndex.toLong(),
            autoDetectRoot = h.autoDetectRoot,
            transposition = h.transposition.name,
            voice = h.voice.name
        )
        viewModelScope.launch {
            val result = repository.updatePreset(currentUserId, updated)
            if (result.isSuccess) {
                _snackbarEvents.tryEmit("Updated \"${existing.title}\"")
            } else {
                _snackbarEvents.tryEmit(
                    result.exceptionOrNull()?.localizedMessage ?: "Failed to update preset"
                )
            }
        }
    }

    fun deletePreset(preset: RehearsalPreset) {
        viewModelScope.launch {
            val result = repository.deletePreset(currentUserId, preset.id)
            if (result.isSuccess) {
                if (_activePresetId.value == preset.id) {
                    _activePresetId.value = null
                }
                _snackbarEvents.tryEmit("Deleted \"${preset.title}\"")
            } else {
                _snackbarEvents.tryEmit(
                    result.exceptionOrNull()?.localizedMessage ?: "Failed to delete preset"
                )
            }
        }
    }

    // --- Internal Sync Helpers ---

    private fun updateHarmony(transform: (HarmonyUiState) -> HarmonyUiState) {
        _harmonyState.update { prev ->
            val next = transform(prev)
            val effectiveRoot = if (next.autoDetectRoot && next.activeWrittenMidis.isNotEmpty()) {
                PitchTheory.inferRootPitchClass(
                    writtenMidis = next.activeWrittenMidis,
                    transposition = next.transposition,
                    fallbackRoot = next.rootKeyIndex
                )
            } else {
                next.rootKeyIndex
            }
            val infos = next.activeWrittenMidis.sorted().map { midi ->
                PitchTheory.calculatePitchInfo(
                    writtenMidi = midi,
                    transposition = next.transposition,
                    referenceA4Hz = next.referencePitchHz,
                    temperament = next.temperament,
                    rootPitchClass = effectiveRoot
                )
            }
            val chord = PitchTheory.detectChordOrNoteLabel(infos.map { it.soundingMidi })
            val freqMap = infos.associate { it.writtenMidi to it.frequencyHz }
            audioEngine.updateActiveNotes(freqMap)
            next.copy(
                effectiveRootIndex = effectiveRoot,
                activePitchInfos = infos,
                chordLabel = chord
            )
        }
    }

    private fun pushAudioConfig() {
        val h = _harmonyState.value
        val m = _metronomeConfig.value
        audioEngine.setToneVoice(h.voice)
        audioEngine.setToneGain(h.toneGain)
        audioEngine.setMetronomeGain(m.metronomeGain)
        pushMetronomeAudioConfig()
    }

    private fun pushMetronomeAudioConfig() {
        val m = _metronomeConfig.value
        audioEngine.setMetronomeConfig(
            bpm = m.bpm,
            timeSignature = m.timeSignature,
            sound = m.metronomeSound,
            mix = m.subdivisionMix,
            alternateMuteBar = m.alternateMuteBar
        )
    }
}
