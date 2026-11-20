import Combine
import Foundation

struct HarmonyUiState: Equatable {
    var voice: ToneVoice = .organ
    var referencePitchHz: Double = 440.0
    var temperament: TemperamentMode = .pure
    var rootKeyIndex: Int = 0
    var autoDetectRoot: Bool = true
    var transposition: TranspositionKey = .concertC
    var octaveShift: Int = 0
    var chordHoldEnabled: Bool = true
    var activeWrittenMidis: Set<Int> = []
    var effectiveRootIndex: Int = 0
    var activePitchInfos: [NotePitchInfo] = []
    var chordLabel: String = "--"
    var toneGain: Float = 0.80
}

struct MetronomeUiState: Equatable {
    var isPlaying: Bool = false
    var bpm: Int = 120
    var timeSignature: TimeSignature = .fourFour
    var metronomeSound: MetronomeSound = .woodblock
    var subdivisionMix: SubdivisionMix = SubdivisionMix()
    var alternateMuteBar: Bool = false
    var metronomeGain: Float = 0.75
}

@MainActor
final class ConsoleViewModel: ObservableObject {
    @Published private(set) var harmony = HarmonyUiState()
    @Published private(set) var metronome = MetronomeUiState()
    @Published private(set) var audioOutputMode: AudioOutputMode = .mixed
    @Published private(set) var beatSnapshot = MetronomeBeatSnapshot()
    @Published private(set) var presets: [RehearsalPreset] = []
    @Published private(set) var activePresetId: String? = nil
    @Published var searchQuery: String = ""
    @Published private(set) var toastMessage: String? = nil

    @Published private(set) var midiState = MidiConnectionUiState()
    @Published private(set) var sustainPedalDown: Bool = false
    @Published private(set) var midiMappings: [MidiControlTarget: Int] = Dictionary(
        uniqueKeysWithValues: MidiControlTarget.allCases.map { ($0, $0.defaultCc) }
    )
    @Published private(set) var learningTarget: MidiControlTarget? = nil

    private let audioEngine: HarmonyAudioEngine
    private let midiController: MidiInputController
    private let presetStore: LocalPresetStore
    private let noteTracker = MidiNoteStateTracker()
    private var tapTimestampsMs: [Int64] = []
    private var lastCcValues: [Int: Int] = [:]
    private var toastDismissWorkItem: DispatchWorkItem?

    init(
        audioEngine: HarmonyAudioEngine = HarmonyAudioEngine(),
        midiController: MidiInputController = MidiInputController(),
        presetStore: LocalPresetStore = LocalPresetStore()
    ) {
        self.audioEngine = audioEngine
        self.midiController = midiController
        self.presetStore = presetStore
        self.presets = presetStore.loadPresets()

        audioEngine.onBeatSnapshot = { [weak self] snapshot in
            Task { @MainActor [weak self] in
                self?.beatSnapshot = snapshot
            }
        }

        midiController.onStateChanged = { [weak self] newState in
            Task { @MainActor [weak self] in
                self?.midiState = newState
            }
        }

        midiController.onEvent = { [weak self] event in
            Task { @MainActor [weak self] in
                self?.handleMidiHardwareEvent(event)
            }
        }

        audioEngine.startEngine()
        pushAudioConfig()
        midiController.start()
    }

    deinit {
        midiController.release()
        audioEngine.stopEngine()
    }

    var filteredPresets: [RehearsalPreset] {
        let q = searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return presets }
        return presets.filter {
            $0.title.localizedCaseInsensitiveContains(q) ||
            $0.notes.localizedCaseInsensitiveContains(q)
        }
    }

    // MARK: - Harmony / Keyboard Actions

    func onKeyPressed(_ writtenMidi: Int) {
        let active = noteTracker.onScreenKeyPressed(
            writtenMidi: writtenMidi,
            chordHoldEnabled: harmony.chordHoldEnabled
        )
        syncHarmonyActiveMidis(active)
    }

    func onKeyReleased(_ writtenMidi: Int) {
        let active = noteTracker.onScreenKeyReleased(writtenMidi: writtenMidi)
        syncHarmonyActiveMidis(active)
    }

    func toggleChordHold() {
        let nextHold = !harmony.chordHoldEnabled
        let active = noteTracker.onChordHoldChanged(
            nextHoldEnabled: nextHold,
            sustainPedalDown: sustainPedalDown
        )
        updateHarmony { state in
            state.chordHoldEnabled = nextHold
            state.activeWrittenMidis = active
        }
    }

    func clearHeldNotes() {
        syncHarmonyActiveMidis(noteTracker.clearAll())
    }

    func selectVoice(_ voice: ToneVoice) {
        harmony.voice = voice
        audioEngine.setToneVoice(voice)
    }

    func setToneGain(_ gain: Float) {
        let clamped = min(max(gain, 0), 1)
        harmony.toneGain = clamped
        audioEngine.setToneGain(clamped)
    }

    func setReferencePitchHz(_ hz: Double) {
        let clamped = min(max(hz, 430.0), 450.0)
        let rounded = round(clamped * 10.0) / 10.0
        updateHarmony { $0.referencePitchHz = rounded }
    }

    func adjustReferencePitch(_ deltaHz: Double) {
        setReferencePitchHz(harmony.referencePitchHz + deltaHz)
    }

    func selectTemperament(_ mode: TemperamentMode) {
        updateHarmony { $0.temperament = mode }
    }

    func toggleTemperament() {
        updateHarmony { state in
            state.temperament = (state.temperament == .pure) ? .equal : .pure
        }
    }

    func selectRootKey(_ index: Int) {
        let normalized = ((index % 12) + 12) % 12
        updateHarmony { state in
            state.rootKeyIndex = normalized
            state.autoDetectRoot = false
        }
    }

    func toggleAutoDetectRoot() {
        updateHarmony { $0.autoDetectRoot.toggle() }
    }

    func selectTransposition(_ key: TranspositionKey) {
        updateHarmony { $0.transposition = key }
    }

    func adjustOctaveShift(_ delta: Int) {
        let active = noteTracker.onOctaveShift()
        updateHarmony { state in
            state.octaveShift = min(max(state.octaveShift + delta, -1), 1)
            state.activeWrittenMidis = active
        }
    }

    // MARK: - Metronome Actions

    func toggleMetronomePlaying() {
        let next = !metronome.isPlaying
        metronome.isPlaying = next
        audioEngine.setMetronomePlaying(next)
    }

    func setBpm(_ newBpm: Int) {
        let clamped = min(max(newBpm, 32), 280)
        metronome.bpm = clamped
        pushMetronomeAudioConfig()
    }

    func adjustBpm(_ delta: Int) {
        setBpm(metronome.bpm + delta)
    }

    func onTapTempo() {
        let now = Int64(Date().timeIntervalSince1970 * 1000.0)
        if let last = tapTimestampsMs.last, now - last > 2200 {
            tapTimestampsMs.removeAll()
        }
        tapTimestampsMs.append(now)
        if tapTimestampsMs.count > 5 {
            tapTimestampsMs.removeFirst(tapTimestampsMs.count - 5)
        }
        if tapTimestampsMs.count >= 2 {
            var intervals: [Double] = []
            for i in 1..<tapTimestampsMs.count {
                let diff = tapTimestampsMs[i] - tapTimestampsMs[i - 1]
                if diff > 150 {
                    intervals.append(Double(diff))
                }
            }
            if !intervals.isEmpty {
                let avgMs = intervals.reduce(0, +) / Double(intervals.count)
                let tappedBpm = min(max(Int(60000.0 / avgMs), 32), 280)
                setBpm(tappedBpm)
            }
        }
    }

    func selectTimeSignature(_ ts: TimeSignature) {
        metronome.timeSignature = TimeSignature.create(numerator: ts.numerator, denominator: ts.denominator)
        pushMetronomeAudioConfig()
    }

    func setTimeSignatureNumerator(_ numerator: Int) {
        selectTimeSignature(TimeSignature.create(numerator: numerator, denominator: metronome.timeSignature.denominator))
    }

    func setTimeSignatureDenominator(_ denominator: Int) {
        selectTimeSignature(TimeSignature.create(numerator: metronome.timeSignature.numerator, denominator: denominator))
    }

    func cycleTimeSignatureNumerator() {
        let current = metronome.timeSignature
        let nextNum = current.numerator >= TimeSignature.maxNumerator ? TimeSignature.minNumerator : current.numerator + 1
        selectTimeSignature(TimeSignature.create(numerator: nextNum, denominator: current.denominator))
    }

    func cycleTimeSignatureDenominator() {
        let allowed = TimeSignature.allowedDenominators
        let idx = allowed.firstIndex(of: metronome.timeSignature.denominator) ?? 0
        let nextDen = allowed[(idx + 1) % allowed.count]
        selectTimeSignature(TimeSignature.create(numerator: metronome.timeSignature.numerator, denominator: nextDen))
    }

    func selectMetronomeSound(_ sound: MetronomeSound) {
        metronome.metronomeSound = sound
        pushMetronomeAudioConfig()
    }

    func updateSubdivisionMix(_ mutate: (inout SubdivisionMix) -> Void) {
        var next = metronome.subdivisionMix
        mutate(&next)
        metronome.subdivisionMix = next.clamped()
        pushMetronomeAudioConfig()
    }

    func toggleAlternateMuteBar() {
        metronome.alternateMuteBar.toggle()
        pushMetronomeAudioConfig()
    }

    func setMetronomeGain(_ gain: Float) {
        let clamped = min(max(gain, 0), 1)
        metronome.metronomeGain = clamped
        audioEngine.setMetronomeGain(clamped)
    }

    func setAudioOutputMode(_ mode: AudioOutputMode) {
        guard audioOutputMode != mode else { return }
        audioOutputMode = mode
        audioEngine.setOutputMode(mode)
        switch mode {
        case .mixed:
            showToast("Audio Output: Mixed (L+R)")
        case .splitLR:
            showToast("Audio Output: Split L/R (L: Harmony · R: Metronome)")
        }
    }

    func toggleAudioOutputMode() {
        setAudioOutputMode(audioOutputMode == .mixed ? .splitLR : .mixed)
    }

    func stopAllSound() {
        _ = noteTracker.clearAll()
        harmony.activeWrittenMidis = []
        harmony.activePitchInfos = []
        harmony.chordLabel = "--"
        metronome.isPlaying = false
        audioEngine.stopAllSound()
    }

    // MARK: - MIDI Actions

    func refreshMidiDevices() {
        midiController.refreshMidiDevices(autoConnect: true)
    }

    func connectMidiDevice(_ entry: MidiDeviceEntry) {
        midiController.connectDevice(entry)
    }

    func disconnectMidiDevice(_ entry: MidiDeviceEntry) {
        midiController.disconnectDevice(entry)
    }

    func startMidiLearn(_ target: MidiControlTarget) {
        learningTarget = target
    }

    func cancelMidiLearn() {
        learningTarget = nil
    }

    func resetDefaultMidiMappings() {
        learningTarget = nil
        midiMappings = Dictionary(uniqueKeysWithValues: MidiControlTarget.allCases.map { ($0, $0.defaultCc) })
        showToast("Reset MIDI CC mappings to SMK-37 PRO defaults")
    }

    func handleMidiHardwareEvent(_ event: MidiHardwareEvent) {
        switch event {
        case .noteOn(let midiNote, _, _):
            if let updated = noteTracker.onExternalNoteOn(midiNote: midiNote, chordHoldEnabled: harmony.chordHoldEnabled) {
                syncHarmonyActiveMidis(updated)
            }
        case .noteOff(let midiNote, _):
            let updated = noteTracker.onExternalNoteOff(
                midiNote: midiNote,
                chordHoldEnabled: harmony.chordHoldEnabled,
                sustainPedalDown: sustainPedalDown
            )
            syncHarmonyActiveMidis(updated)
        case .controlChange(let cc, let value, _):
            onExternalControlChange(cc: cc, value: value)
        case .allNotesOff:
            syncHarmonyActiveMidis(noteTracker.onExternalAllNotesOff())
        }
    }

    private func onExternalControlChange(cc: Int, value: Int) {
        if let pending = learningTarget {
            for target in MidiControlTarget.allCases {
                if target == pending {
                    midiMappings[target] = cc
                } else if midiMappings[target] == cc {
                    midiMappings[target] = -1
                }
            }
            learningTarget = nil
            lastCcValues[cc] = value
            showToast("Mapped \(pending.label) to CC #\(cc)")
            return
        }

        let prev = lastCcValues[cc] ?? 0
        lastCcValues[cc] = value
        let matchedTarget = midiMappings.first { $0.value >= 0 && $0.value == cc }?.key

        if let target = matchedTarget {
            if target.isTrigger {
                if value >= 64 && prev < 64 {
                    switch target {
                    case .toggleMetronome: toggleMetronomePlaying()
                    case .tapTempo: onTapTempo()
                    case .clearHeld: clearHeldNotes()
                    case .toggleChordHold: toggleChordHold()
                    case .toggleTemperament: toggleTemperament()
                    case .muteAll: stopAllSound()
                    case .meterTopStep: cycleTimeSignatureNumerator()
                    case .meterBottomStep: cycleTimeSignatureDenominator()
                    default: break
                    }
                }
            } else {
                let normalized = min(max(Float(value) / 127.0, 0), 1)
                switch target {
                case .toneVolume: setToneGain(normalized)
                case .bpm:
                    let targetBpm = 32 + Int(round(Double(normalized) * Double(280 - 32)))
                    setBpm(targetBpm)
                case .accentLevel: updateSubdivisionMix { $0.accent = normalized }
                case .quarterLevel: updateSubdivisionMix { $0.quarter = normalized }
                case .eighthLevel: updateSubdivisionMix { $0.eighth = normalized }
                case .tripletLevel: updateSubdivisionMix { $0.triplet = normalized }
                case .sixteenthLevel: updateSubdivisionMix { $0.sixteenth = normalized }
                case .meterTopValue:
                    let num = TimeSignature.minNumerator + Int(round(Double(normalized) * Double(TimeSignature.maxNumerator - TimeSignature.minNumerator)))
                    setTimeSignatureNumerator(num)
                case .meterBottomValue:
                    let allowed = TimeSignature.allowedDenominators
                    let idx = min(max(Int(round(Double(normalized) * Double(allowed.count - 1))), 0), allowed.count - 1)
                    setTimeSignatureDenominator(allowed[idx])
                default: break
                }
            }
            return
        }

        if cc == 64 {
            let down = value >= 64
            sustainPedalDown = down
            if !down {
                let active = noteTracker.onSustainPedalReleased(chordHoldEnabled: harmony.chordHoldEnabled)
                syncHarmonyActiveMidis(active)
            }
        }
    }

    // MARK: - Preset Actions

    func loadPreset(_ preset: RehearsalPreset) {
        activePresetId = preset.id
        metronome.bpm = min(max(preset.bpm, 32), 280)
        metronome.timeSignature = preset.safeTimeSignature()
        metronome.metronomeSound = preset.safeMetronomeSound()
        metronome.subdivisionMix = SubdivisionMix(
            accent: Float(preset.accentLevel),
            quarter: Float(preset.quarterLevel),
            eighth: Float(preset.eighthLevel),
            triplet: Float(preset.tripletLevel),
            sixteenth: Float(preset.sixteenthLevel)
        ).clamped()
        metronome.alternateMuteBar = preset.alternateMuteBar

        updateHarmony { state in
            state.referencePitchHz = min(max(preset.referencePitchHz, 430.0), 450.0)
            state.temperament = preset.safeTemperament()
            state.rootKeyIndex = min(max(preset.rootKeyIndex, 0), 11)
            state.autoDetectRoot = preset.autoDetectRoot
            state.transposition = preset.safeTransposition()
            state.voice = preset.safeVoice()
        }
        audioEngine.setToneVoice(preset.safeVoice())
        pushMetronomeAudioConfig()
        showToast("Loaded \"\(preset.title)\"")
    }

    func saveNewPreset(title: String, notes: String) {
        let trimmedTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedTitle.isEmpty, trimmedTitle.count <= 80 else {
            showToast("Preset title must be 1–80 characters.")
            return
        }
        let trimmedNotes = String(notes.trimmingCharacters(in: .whitespacesAndNewlines).prefix(300))
        let now = Date()
        let item = RehearsalPreset(
            id: UUID().uuidString,
            title: trimmedTitle,
            notes: trimmedNotes,
            bpm: metronome.bpm,
            timeSignature: metronome.timeSignature.toStorageString(),
            metronomeSound: metronome.metronomeSound.rawValue,
            accentLevel: Double(metronome.subdivisionMix.accent),
            quarterLevel: Double(metronome.subdivisionMix.quarter),
            eighthLevel: Double(metronome.subdivisionMix.eighth),
            tripletLevel: Double(metronome.subdivisionMix.triplet),
            sixteenthLevel: Double(metronome.subdivisionMix.sixteenth),
            alternateMuteBar: metronome.alternateMuteBar,
            referencePitchHz: harmony.referencePitchHz,
            temperament: harmony.temperament.rawValue,
            rootKeyIndex: harmony.rootKeyIndex,
            autoDetectRoot: harmony.autoDetectRoot,
            transposition: harmony.transposition.rawValue,
            voice: harmony.voice.rawValue,
            createdAt: now,
            updatedAt: now
        )
        presets = presetStore.savePresets([item] + presets)
        activePresetId = item.id
        showToast("Saved preset \"\(trimmedTitle)\"")
    }

    func overwritePreset(_ existing: RehearsalPreset) {
        let now = Date()
        let updated = presets.map { item -> RehearsalPreset in
            guard item.id == existing.id else { return item }
            var copy = item
            copy.bpm = metronome.bpm
            copy.timeSignature = metronome.timeSignature.toStorageString()
            copy.metronomeSound = metronome.metronomeSound.rawValue
            copy.accentLevel = Double(metronome.subdivisionMix.accent)
            copy.quarterLevel = Double(metronome.subdivisionMix.quarter)
            copy.eighthLevel = Double(metronome.subdivisionMix.eighth)
            copy.tripletLevel = Double(metronome.subdivisionMix.triplet)
            copy.sixteenthLevel = Double(metronome.subdivisionMix.sixteenth)
            copy.alternateMuteBar = metronome.alternateMuteBar
            copy.referencePitchHz = harmony.referencePitchHz
            copy.temperament = harmony.temperament.rawValue
            copy.rootKeyIndex = harmony.rootKeyIndex
            copy.autoDetectRoot = harmony.autoDetectRoot
            copy.transposition = harmony.transposition.rawValue
            copy.voice = harmony.voice.rawValue
            copy.updatedAt = now
            return copy
        }
        presets = presetStore.savePresets(updated)
        showToast("Updated \"\(existing.title)\"")
    }

    func deletePreset(_ preset: RehearsalPreset) {
        presets = presetStore.savePresets(presets.filter { $0.id != preset.id })
        if activePresetId == preset.id {
            activePresetId = nil
        }
        showToast("Deleted \"\(preset.title)\"")
    }

    // MARK: - Internal Helpers

    private func syncHarmonyActiveMidis(_ active: Set<Int>) {
        updateHarmony { $0.activeWrittenMidis = active }
    }

    private func updateHarmony(_ mutate: (inout HarmonyUiState) -> Void) {
        var next = harmony
        mutate(&next)
        let effectiveRoot: Int
        if next.autoDetectRoot && !next.activeWrittenMidis.isEmpty {
            effectiveRoot = PitchTheory.inferRootPitchClass(
                writtenMidis: next.activeWrittenMidis,
                transposition: next.transposition,
                fallbackRoot: next.rootKeyIndex
            )
        } else {
            effectiveRoot = next.rootKeyIndex
        }

        let infos = next.activeWrittenMidis.sorted().map { midi in
            PitchTheory.calculatePitchInfo(
                writtenMidi: midi,
                transposition: next.transposition,
                referenceA4Hz: next.referencePitchHz,
                temperament: next.temperament,
                rootPitchClass: effectiveRoot
            )
        }
        let chord = PitchTheory.detectChordOrNoteLabel(infos.map { $0.soundingMidi })
        let freqMap = Dictionary(uniqueKeysWithValues: infos.map { ($0.writtenMidi, $0.frequencyHz) })
        audioEngine.updateActiveNotes(freqMap)

        next.effectiveRootIndex = effectiveRoot
        next.activePitchInfos = infos
        next.chordLabel = chord
        harmony = next
    }

    private func pushAudioConfig() {
        audioEngine.setToneVoice(harmony.voice)
        audioEngine.setToneGain(harmony.toneGain)
        audioEngine.setMetronomeGain(metronome.metronomeGain)
        audioEngine.setOutputMode(audioOutputMode)
        pushMetronomeAudioConfig()
    }

    private func pushMetronomeAudioConfig() {
        audioEngine.setMetronomeConfig(
            bpm: metronome.bpm,
            timeSignature: metronome.timeSignature,
            sound: metronome.metronomeSound,
            mix: metronome.subdivisionMix,
            alternateMuteBar: metronome.alternateMuteBar
        )
    }

    private func showToast(_ message: String) {
        toastDismissWorkItem?.cancel()
        toastMessage = message
        let item = DispatchWorkItem { [weak self] in
            self?.toastMessage = nil
        }
        toastDismissWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.4, execute: item)
    }
}
