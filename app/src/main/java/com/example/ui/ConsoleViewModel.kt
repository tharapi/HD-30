package com.example.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.HarmonyAudioEngine
import com.example.audio.MetronomeBeatSnapshot
import com.example.data.RehearsalPresetRepository
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

class ConsoleViewModel(
    private val repository: RehearsalPresetRepository,
    private val audioEngine: HarmonyAudioEngine,
    private val currentUserId: String
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

    private val tapTimestampsMs = ArrayDeque<Long>()

    init {
        audioEngine.startEngine()
        pushAudioConfig()
    }

    override fun onCleared() {
        super.onCleared()
        audioEngine.release()
    }

    // --- Harmony / Keyboard Actions ---

    fun onKeyPressed(writtenMidi: Int) {
        updateHarmony { state ->
            val updated = if (state.chordHoldEnabled) {
                if (writtenMidi in state.activeWrittenMidis) {
                    state.activeWrittenMidis - writtenMidi
                } else {
                    state.activeWrittenMidis + writtenMidi
                }
            } else {
                state.activeWrittenMidis + writtenMidi
            }
            state.copy(activeWrittenMidis = updated)
        }
    }

    fun onKeyReleased(writtenMidi: Int) {
        val state = _harmonyState.value
        if (!state.chordHoldEnabled && writtenMidi in state.activeWrittenMidis) {
            updateHarmony { it.copy(activeWrittenMidis = it.activeWrittenMidis - writtenMidi) }
        }
    }

    fun toggleChordHold() {
        updateHarmony { state ->
            val nextHold = !state.chordHoldEnabled
            state.copy(
                chordHoldEnabled = nextHold,
                activeWrittenMidis = if (!nextHold) emptySet() else state.activeWrittenMidis
            )
        }
    }

    fun clearHeldNotes() {
        updateHarmony { it.copy(activeWrittenMidis = emptySet()) }
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
        updateHarmony { state ->
            val nextShift = (state.octaveShift + delta).coerceIn(-1, 1)
            state.copy(
                octaveShift = nextShift,
                activeWrittenMidis = emptySet()
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
        _metronomeConfig.update { it.copy(timeSignature = ts) }
        pushMetronomeAudioConfig()
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
            timeSignature = m.timeSignature.name,
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
            timeSignature = m.timeSignature.name,
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
