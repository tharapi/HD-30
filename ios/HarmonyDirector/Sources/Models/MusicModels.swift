import Foundation

enum TemperamentMode: String, CaseIterable, Codable, Identifiable {
    case equal = "EQUAL"
    case pure = "PURE"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .equal: return "Equal"
        case .pure: return "Pure (Just)"
        }
    }
}

enum TranspositionKey: String, CaseIterable, Codable, Identifiable {
    case concertC = "CONCERT_C"
    case bb = "BB"
    case eb = "EB"
    case f = "F"

    var id: String { rawValue }

    var shortLabel: String {
        switch self {
        case .concertC: return "C"
        case .bb: return "Bb"
        case .eb: return "Eb"
        case .f: return "F"
        }
    }

    var fullLabel: String {
        switch self {
        case .concertC: return "Concert C"
        case .bb: return "Bb (Clarinet / Trumpet)"
        case .eb: return "Eb (Alto Sax / Eb Horn)"
        case .f: return "F (French Horn / English Horn)"
        }
    }

    var semitoneOffset: Int {
        switch self {
        case .concertC: return 0
        case .bb: return -2
        case .eb: return 3
        case .f: return -7
        }
    }
}

enum ToneVoice: String, CaseIterable, Codable, Identifiable {
    case pureSine = "PURE_SINE"
    case organ = "ORGAN"
    case brass = "BRASS"
    case woodwind = "WOODWIND"
    case strings = "STRINGS"
    case piano = "PIANO"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .pureSine: return "Pure Sine"
        case .organ: return "Organ"
        case .brass: return "Brass"
        case .woodwind: return "Woodwind"
        case .strings: return "Strings"
        case .piano: return "Piano"
        }
    }

    var category: String {
        switch self {
        case .pureSine: return "Pitch"
        case .organ: return "Harmony"
        case .brass: return "Orchestra"
        case .woodwind: return "Orchestra"
        case .strings: return "Orchestra"
        case .piano: return "Keyboard"
        }
    }
}

struct TimeSignature: Equatable, Hashable, Codable {
    static let minNumerator = 1
    static let maxNumerator = 16
    static let allowedDenominators = [1, 2, 4, 8, 16]
    static let fourFour = TimeSignature(numerator: 4, denominator: 4)

    let numerator: Int
    let denominator: Int

    init(numerator: Int = 4, denominator: Int = 4) {
        self.numerator = min(max(numerator, Self.minNumerator), Self.maxNumerator)
        self.denominator = Self.allowedDenominators.contains(denominator) ? denominator : 4
    }

    var label: String { "\(numerator)/\(denominator)" }
    var beatsPerMeasure: Int { min(max(numerator, Self.minNumerator), Self.maxNumerator) }
    var isCompound: Bool {
        (denominator == 8 || denominator == 16) && numerator >= 6 && numerator % 3 == 0
    }

    func toStorageString() -> String {
        "\(beatsPerMeasure)/\(denominator)"
    }

    static func create(numerator: Int, denominator: Int) -> TimeSignature {
        TimeSignature(numerator: numerator, denominator: denominator)
    }

    static func fromStorageString(_ raw: String) -> TimeSignature {
        let normalized = raw.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
        switch normalized {
        case "TWO_FOUR": return TimeSignature(numerator: 2, denominator: 4)
        case "THREE_FOUR": return TimeSignature(numerator: 3, denominator: 4)
        case "FOUR_FOUR": return TimeSignature(numerator: 4, denominator: 4)
        case "FIVE_FOUR": return TimeSignature(numerator: 5, denominator: 4)
        case "SIX_EIGHT": return TimeSignature(numerator: 6, denominator: 8)
        case "NINE_EIGHT": return TimeSignature(numerator: 9, denominator: 8)
        case "TWELVE_EIGHT": return TimeSignature(numerator: 12, denominator: 8)
        default: break
        }
        let parts = normalized.split(separator: "/")
        if parts.count == 2,
           let num = Int(parts[0]),
           let den = Int(parts[1]),
           (minNumerator...maxNumerator).contains(num),
           allowedDenominators.contains(den) {
            return TimeSignature(numerator: num, denominator: den)
        }
        return .fourFour
    }
}

enum MetronomeSound: String, CaseIterable, Codable, Identifiable {
    case woodblock = "WOODBLOCK"
    case click = "CLICK"
    case bell = "BELL"
    case rimshot = "RIMSHOT"

    var id: String { rawValue }

    var label: String {
        switch self {
        case .woodblock: return "Woodblock"
        case .click: return "Digital Click"
        case .bell: return "Bell / Chime"
        case .rimshot: return "Rimshot"
        }
    }
}

struct SubdivisionMix: Equatable, Codable {
    var accent: Float = 1.0
    var quarter: Float = 0.85
    var eighth: Float = 0.0
    var triplet: Float = 0.0
    var sixteenth: Float = 0.0

    func clamped() -> SubdivisionMix {
        SubdivisionMix(
            accent: min(max(accent, 0), 1),
            quarter: min(max(quarter, 0), 1),
            eighth: min(max(eighth, 0), 1),
            triplet: min(max(triplet, 0), 1),
            sixteenth: min(max(sixteenth, 0), 1)
        )
    }
}

enum AudioOutputMode: String, CaseIterable, Codable, Identifiable {
    case mixed = "MIXED"
    case splitLR = "SPLIT_LR"

    var id: String { rawValue }

    var shortLabel: String {
        switch self {
        case .mixed: return "MIXED"
        case .splitLR: return "L/R SPLIT"
        }
    }

    var label: String {
        switch self {
        case .mixed: return "Mixed (L+R)"
        case .splitLR: return "Split L/R"
        }
    }

    var description: String {
        switch self {
        case .mixed:
            return "Harmony & Metronome blended cleanly on both channels"
        case .splitLR:
            return "Left = Harmony only · Right = Metronome only (3.5mm Y-cable / PA mixer)"
        }
    }
}

struct NotePitchInfo: Equatable, Identifiable {
    var id: Int { writtenMidi }
    let writtenMidi: Int
    let soundingMidi: Int
    let writtenName: String
    let soundingName: String
    let frequencyHz: Double
    let centsOffsetFromEt: Double
    let intervalName: String
}

enum PitchTheory {
    static let noteNames = [
        "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"
    ]

    // Standard HD-300 Pure (Just Intonation) harmonic ratios relative to root
    private static let justRatios: [Double] = [
        1.0,        // 0: P1 (0.0 cents)
        16.0 / 15.0,// 1: m2 (+11.7 cents)
        9.0 / 8.0,  // 2: M2 (+3.9 cents)
        6.0 / 5.0,  // 3: m3 (+15.6 cents)
        5.0 / 4.0,  // 4: M3 (-13.7 cents)
        4.0 / 3.0,  // 5: P4 (-2.0 cents)
        45.0 / 32.0,// 6: TT (-9.8 cents)
        3.0 / 2.0,  // 7: P5 (+2.0 cents)
        8.0 / 5.0,  // 8: m6 (+13.7 cents)
        5.0 / 3.0,  // 9: M6 (-15.6 cents)
        9.0 / 5.0,  // 10: m7 (+17.6 cents)
        15.0 / 8.0  // 11: M7 (-11.7 cents)
    ]

    private static let justCentsFromEt: [Double] = [
        0.0, 11.7, 3.9, 15.6, -13.7, -2.0, -9.8, 2.0, 13.7, -15.6, 17.6, -11.7
    ]

    private static let intervalLabels = [
        "Root", "m2", "M2", "m3", "M3", "P4", "TT", "P5", "m6", "M6", "m7", "M7"
    ]

    static func noteNameWithOctave(_ midiNote: Int) -> String {
        let pc = ((midiNote % 12) + 12) % 12
        let octave = (midiNote / 12) - 1
        return "\(noteNames[pc])\(octave)"
    }

    static func pitchClassName(_ pitchClass: Int) -> String {
        let pc = ((pitchClass % 12) + 12) % 12
        return noteNames[pc]
    }

    static func equalTemperamentFrequency(midiNote: Int, referenceA4Hz: Double) -> Double {
        referenceA4Hz * pow(2.0, Double(midiNote - 69) / 12.0)
    }

    static func calculatePitchInfo(
        writtenMidi: Int,
        transposition: TranspositionKey,
        referenceA4Hz: Double,
        temperament: TemperamentMode,
        rootPitchClass: Int
    ) -> NotePitchInfo {
        let soundingMidi = min(max(writtenMidi + transposition.semitoneOffset, 21), 108)
        let soundingPc = ((soundingMidi % 12) + 12) % 12
        let interval = (((soundingPc - rootPitchClass) % 12) + 12) % 12

        let frequency: Double
        let centsOffset: Double
        if temperament == .equal {
            frequency = equalTemperamentFrequency(midiNote: soundingMidi, referenceA4Hz: referenceA4Hz)
            centsOffset = 0.0
        } else {
            let rootMidi = soundingMidi - interval
            let rootFreq = equalTemperamentFrequency(midiNote: rootMidi, referenceA4Hz: referenceA4Hz)
            frequency = rootFreq * justRatios[interval]
            centsOffset = justCentsFromEt[interval]
        }

        return NotePitchInfo(
            writtenMidi: writtenMidi,
            soundingMidi: soundingMidi,
            writtenName: noteNameWithOctave(writtenMidi),
            soundingName: noteNameWithOctave(soundingMidi),
            frequencyHz: frequency,
            centsOffsetFromEt: centsOffset,
            intervalName: intervalLabels[interval]
        )
    }

    static func detectChordOrNoteLabel(_ soundingMidis: [Int]) -> String {
        guard !soundingMidis.isEmpty else { return "--" }
        let sorted = soundingMidis.sorted()
        if sorted.count == 1 {
            return noteNameWithOctave(sorted[0])
        }

        let rootPc = ((sorted[0] % 12) + 12) % 12
        let intervals = Set(sorted.map { (((($0 % 12) - rootPc) % 12) + 12) % 12 })
        let rootName = noteNames[rootPc]

        if intervals.isSuperset(of: [0, 4, 7, 10]) { return "\(rootName) 7" }
        if intervals.isSuperset(of: [0, 4, 7, 11]) { return "\(rootName) Maj7" }
        if intervals.isSuperset(of: [0, 3, 7, 10]) { return "\(rootName) m7" }
        if intervals.isSuperset(of: [0, 4, 7]) { return "\(rootName) Maj" }
        if intervals.isSuperset(of: [0, 3, 7]) { return "\(rootName) Min" }
        if intervals.isSuperset(of: [0, 3, 6]) { return "\(rootName) Dim" }
        if intervals.isSuperset(of: [0, 4, 8]) { return "\(rootName) Aug" }
        if intervals.isSuperset(of: [0, 5, 7]) { return "\(rootName) Sus4" }
        if intervals.isSuperset(of: [0, 2, 7]) { return "\(rootName) Sus2" }
        if intervals == [0, 7] { return "\(rootName) P5" }
        return sorted.map { pitchClassName($0) }.joined(separator: " ")
    }

    static func inferRootPitchClass(
        writtenMidis: Set<Int>,
        transposition: TranspositionKey,
        fallbackRoot: Int
    ) -> Int {
        guard let lowest = writtenMidis.min() else { return fallbackRoot }
        let sounding = min(max(lowest + transposition.semitoneOffset, 21), 108)
        return ((sounding % 12) + 12) % 12
    }
}

struct RehearsalPreset: Identifiable, Equatable, Codable {
    var id: String = UUID().uuidString
    var title: String = ""
    var notes: String = ""
    var bpm: Int = 120
    var timeSignature: String = TimeSignature.fourFour.toStorageString()
    var metronomeSound: String = MetronomeSound.woodblock.rawValue
    var accentLevel: Double = 1.0
    var quarterLevel: Double = 0.85
    var eighthLevel: Double = 0.0
    var tripletLevel: Double = 0.0
    var sixteenthLevel: Double = 0.0
    var alternateMuteBar: Bool = false
    var referencePitchHz: Double = 440.0
    var temperament: String = TemperamentMode.pure.rawValue
    var rootKeyIndex: Int = 0
    var autoDetectRoot: Bool = true
    var transposition: String = TranspositionKey.concertC.rawValue
    var voice: String = ToneVoice.organ.rawValue
    var createdAt: Date = Date()
    var updatedAt: Date = Date()

    func safeTimeSignature() -> TimeSignature {
        TimeSignature.fromStorageString(timeSignature)
    }

    func safeMetronomeSound() -> MetronomeSound {
        MetronomeSound(rawValue: metronomeSound) ?? .woodblock
    }

    func safeTemperament() -> TemperamentMode {
        TemperamentMode(rawValue: temperament) ?? .pure
    }

    func safeTransposition() -> TranspositionKey {
        TranspositionKey(rawValue: transposition) ?? .concertC
    }

    func safeVoice() -> ToneVoice {
        ToneVoice(rawValue: voice) ?? .organ
    }
}

enum MidiControlTarget: String, CaseIterable, Identifiable, Hashable {
    case toneVolume
    case bpm
    case accentLevel
    case quarterLevel
    case eighthLevel
    case tripletLevel
    case sixteenthLevel
    case tapTempo
    case clearHeld
    case toggleMetronome
    case toggleChordHold
    case toggleTemperament
    case muteAll
    case meterTopValue
    case meterBottomValue
    case meterTopStep
    case meterBottomStep

    var id: String { rawValue }

    var label: String {
        switch self {
        case .toneVolume: return "Tone Vol"
        case .bpm: return "Tempo BPM"
        case .accentLevel: return "Accent"
        case .quarterLevel: return "1/4 Beat"
        case .eighthLevel: return "1/8 Beat"
        case .tripletLevel: return "Triplet"
        case .sixteenthLevel: return "1/16 Beat"
        case .tapTempo: return "Tap Tempo"
        case .clearHeld: return "Clear Held"
        case .toggleMetronome: return "Play/Stop"
        case .toggleChordHold: return "Chord Hold"
        case .toggleTemperament: return "Equal / Pure"
        case .muteAll: return "All Sound Off"
        case .meterTopValue: return "Meter Top (1-16)"
        case .meterBottomValue: return "Meter Bottom"
        case .meterTopStep: return "Meter Top +1"
        case .meterBottomStep: return "Meter Bottom Step"
        }
    }

    var defaultCc: Int {
        switch self {
        case .toneVolume: return 49
        case .bpm: return 48
        case .accentLevel: return 1
        case .quarterLevel: return 64
        case .eighthLevel: return 65
        case .tripletLevel: return 66
        case .sixteenthLevel: return 67
        case .tapTempo: return 36
        case .clearHeld: return 37
        case .toggleMetronome: return 40
        case .toggleChordHold: return 41
        case .toggleTemperament: return 42
        case .muteAll: return 47
        case .meterTopValue, .meterBottomValue, .meterTopStep, .meterBottomStep: return -1
        }
    }

    var isTrigger: Bool {
        switch self {
        case .tapTempo, .clearHeld, .toggleMetronome, .toggleChordHold,
             .toggleTemperament, .muteAll, .meterTopStep, .meterBottomStep:
            return true
        default:
            return false
        }
    }
}
