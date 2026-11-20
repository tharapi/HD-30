import AVFoundation
import Foundation
import os

struct MetronomeBeatSnapshot: Equatable {
    var beatIndex: Int = 0
    var totalBeats: Int = 4
    var isMutedBar: Bool = false
    var isPlaying: Bool = false
    var tickId: Int64 = 0
}

private enum HitKind {
    case accent
    case quarter
    case secondaryCompound
    case eighth
    case triplet
    case sixteenth
}

private struct ActiveVoice {
    let writtenMidi: Int
    var frequencyHz: Double
    var releasing: Bool = false
    var phase: Double = 0.0
    var env: Double = 0.0
    var ageSamples: Int64 = 0
    var sampleZoneIndex: Int = -1
    var sampleVoice: ToneVoice? = nil
    var samplePos: Double = 0.0
}

private struct PercussionVoice {
    var active: Bool = false
    var sampleSound: MetronomeSound? = nil
    var useBundledSample: Bool = false
    var samplePos: Double = 0.0
    var sampleStep: Double = 1.0
    var ageSamples: Int = 0
    var totalSamples: Int = 0
    var attackSamples: Int = 12
    var phase: Double = 0.0
    var phaseOver: Double = 0.0
    var prevNoise: Double = 0.0
    var hpPrevIn: Double = 0.0
    var hpPrevOut: Double = 0.0
    var hpAlpha: Double = 0.89
    var freqStart: Double = 1480.0
    var freqEnd: Double = 1180.0
    var gain: Double = 0.0
    var sound: MetronomeSound = .woodblock
    var isAccent: Bool = false
}

/// Real-time low-latency Harmony Director audio synthesizer and sample-accurate metronome
/// built on Apple's AVAudioEngine + AVAudioSourceNode.
final class HarmonyAudioEngine {
    static let defaultSampleRate: Double = 48000.0
    private static let twoPi: Double = 2.0 * Double.pi
    private static let ln2: Double = 0.6931471805599453
    private static let spectralTargetRms: Double = 0.48
    private static let harmonicCount: Int = 9

    // Dedicated per-bus headroom budgets matching Android HarmonyAudioEngine:
    // |toneLimited| <= 0.27 (-11.4 dBFS) and |metroLimited| <= 0.50 (-6.0 dBFS)
    // => worst-case mixed peak <= 0.77 (-2.27 dBFS)
    private static let baseVoiceNorm: Double = 0.22
    private static let toneBusKnee: Double = 0.23
    private static let toneBusCeiling: Double = 0.27
    private static let metroBusKnee: Double = 0.40
    private static let metroBusCeiling: Double = 0.50

    private static let gainSmoothCoeff: Double = 0.005
    private static let polyNormSmoothCoeff: Double = 0.0015
    private static let splitMixSmoothCoeff: Double = 0.003

    // Phase-locked harmonic spectra (H1..H9) measured from acoustic recordings
    private static let organLow: [Double] = [0.88, 0.14, 0.36, 0.09, 0.06, 0.025, 0.015, 0.012, 0.005]
    private static let organMid: [Double] = [0.94, 0.06, 0.30, 0.035, 0.018, 0.010, 0.005, 0.004, 0.002]
    private static let organHigh: [Double] = [0.99, 0.05, 0.04, 0.015, 0.005, 0.002, 0.0, 0.0, 0.0]

    private static let brassLow: [Double] = [0.52, 0.58, 0.48, 0.34, 0.20, 0.12, 0.06, 0.03, 0.015]
    private static let brassMid: [Double] = [0.64, 0.68, 0.28, 0.14, 0.07, 0.03, 0.015, 0.008, 0.004]
    private static let brassHigh: [Double] = [0.80, 0.52, 0.22, 0.10, 0.05, 0.02, 0.008, 0.004, 0.0]

    private static let woodwindLow: [Double] = [0.64, 0.30, 0.48, 0.24, 0.22, 0.08, 0.11, 0.03, 0.035]
    private static let woodwindMid: [Double] = [0.88, 0.04, 0.40, 0.04, 0.20, 0.025, 0.075, 0.012, 0.025]
    private static let woodwindHigh: [Double] = [0.98, 0.12, 0.05, 0.015, 0.005, 0.0, 0.0, 0.0, 0.0]

    private static let stringsLow: [Double] = [0.54, 0.62, 0.42, 0.28, 0.16, 0.10, 0.065, 0.04, 0.025]
    private static let stringsMid: [Double] = [0.68, 0.46, 0.44, 0.24, 0.14, 0.085, 0.05, 0.03, 0.018]
    private static let stringsHigh: [Double] = [0.84, 0.44, 0.24, 0.11, 0.05, 0.025, 0.012, 0.006, 0.0]

    static func softLimitBus(_ sample: Double, knee: Double, ceiling: Double) -> Double {
        let absVal = abs(sample)
        if absVal <= knee { return sample }
        let headroom = ceiling - knee
        let excess = (absVal - knee) / headroom
        let shaped = knee + headroom * tanh(excess)
        return sample >= 0.0 ? shaped : -shaped
    }

    static func synthesizeSpectralVoice(
        timbre: ToneVoice,
        freqHz: Double,
        phase: Double,
        ageSec: Double,
        sr: Double
    ) -> Double {
        let low: [Double]
        let mid: [Double]
        let high: [Double]
        switch timbre {
        case .organ:
            low = organLow; mid = organMid; high = organHigh
        case .brass:
            low = brassLow; mid = brassMid; high = brassHigh
        case .woodwind:
            low = woodwindLow; mid = woodwindMid; high = woodwindHigh
        case .strings:
            low = stringsLow; mid = stringsMid; high = stringsHigh
        default:
            return sin(phase)
        }

        let safeFreq = max(freqHz, 20.0)
        let octFromC3 = log(safeFreq / 130.8128) / ln2
        let profileA: [Double]
        let profileB: [Double]
        let blend: Double
        if octFromC3 <= 1.0 {
            profileA = low
            profileB = mid
            blend = min(max((octFromC3 + 0.5) / 1.5, 0.0), 1.0)
        } else {
            profileA = mid
            profileB = high
            blend = min(max((octFromC3 - 1.0) / 2.0, 0.0), 1.0)
        }

        let isBrass = (timbre == .brass)
        let bloom = isBrass ? min(max(ageSec / 0.095, 0.35), 1.0) : 1.0
        let nyqSoft = sr * 0.36
        let nyqCut = sr * 0.45

        var sPrev = 0.0
        var sCurr = sin(phase)
        let twoCos = 2.0 * cos(phase)
        var acc = 0.0
        var sumSq = 0.0

        for h in 0..<harmonicCount {
            let fh = safeFreq * Double(h + 1)
            if fh >= nyqCut { break }
            var w = profileA[h] + (profileB[h] - profileA[h]) * blend
            if isBrass && h >= 2 {
                w *= bloom
            }
            if fh > nyqSoft {
                w *= (nyqCut - fh) / (nyqCut - nyqSoft)
            }
            acc += w * sCurr
            sumSq += w * w

            let sNext = twoCos * sCurr - sPrev
            sPrev = sCurr
            sCurr = sNext
        }

        if sumSq <= 1e-12 { return 0.0 }
        return acc * (spectralTargetRms / sqrt(0.5 * sumSq))
    }

    // Lock protecting all DSP state shared between main thread and real-time audio callback
    private let lockPtr: UnsafeMutablePointer<os_unfair_lock_s>

    private var avEngine: AVAudioEngine?
    private var sourceNode: AVAudioSourceNode?
    private var engineStarted: Bool = false
    private(set) var activeSampleRate: Double = HarmonyAudioEngine.defaultSampleRate

    // Bundled sample banks
    private var metronomeSamples: [MetronomeSound: DecodedWavSample] = [:]
    private var pianoZones: [InstrumentSampleZone] = []

    // Polyphonic synth state
    private var voices: [ActiveVoice] = []
    private var currentToneVoice: ToneVoice = .organ
    private var masterToneGain: Double = 0.80
    private var targetPolyNorm: Double = HarmonyAudioEngine.baseVoiceNorm
    private var smoothedPolyNorm: Double = HarmonyAudioEngine.baseVoiceNorm
    private var smoothedToneGain: Double = 0.80

    // Metronome state
    private var metronomePlaying: Bool = false
    private var bpm: Int = 120
    private var timeSignature: TimeSignature = .fourFour
    private var metronomeSound: MetronomeSound = .woodblock
    private var subdivisionMix: SubdivisionMix = SubdivisionMix()
    private var alternateMuteBar: Bool = false
    private var masterMetronomeGain: Double = 0.75
    private var smoothedMetronomeGain: Double = 0.75

    // Stereo output routing state (0.0 = MIXED L+R, 1.0 = SPLIT L: Harmony / R: Metronome)
    private var outputMode: AudioOutputMode = .mixed
    private var smoothedSplitMix: Double = 0.0

    // Beat clock state inside audio render thread
    private var samplePosInBeat: Double = 0.0
    private var currentBeatInBar: Int = 0
    private var barCounter: Int = 0
    private var firedEighth: Bool = false
    private var firedTrip1: Bool = false
    private var firedTrip2: Bool = false
    private var firedSixteenth1: Bool = false
    private var firedSixteenth3: Bool = false
    private var tickSeq: Int64 = 0

    // Simple fast xorshift64 PRNG for zero-allocation rimshot noise on the audio thread
    private var noiseState: UInt64 = 0x9E3779B97F4A7C15

    private var percPool: [PercussionVoice] = Array(repeating: PercussionVoice(), count: 12)
    private var percPoolCursor: Int = 0

    var onBeatSnapshot: ((MetronomeBeatSnapshot) -> Void)?

    private var interruptionObserver: NSObjectProtocol?
    private var routeChangeObserver: NSObjectProtocol?

    init(loadBundledAssets: Bool = true) {
        lockPtr = UnsafeMutablePointer<os_unfair_lock_s>.allocate(capacity: 1)
        lockPtr.initialize(to: os_unfair_lock_s())
        voices.reserveCapacity(16)

        if loadBundledAssets {
            let loadedMetro = Self.loadBundledMetronomeSamples()
            withLock {
                self.metronomeSamples = loadedMetro
            }
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                let zones = Self.loadBundledPianoZones()
                guard let self, !zones.isEmpty else { return }
                self.withLock {
                    self.pianoZones = zones
                }
            }
        }
    }

    deinit {
        stopEngine()
        lockPtr.deinitialize(count: 1)
        lockPtr.deallocate()
    }

    @inline(__always)
    private func withLock<T>(_ body: () -> T) -> T {
        os_unfair_lock_lock(lockPtr)
        defer { os_unfair_lock_unlock(lockPtr) }
        return body()
    }

    private static func loadBundledMetronomeSamples() -> [MetronomeSound: DecodedWavSample] {
        let names: [(MetronomeSound, String)] = [
            (.woodblock, "metro_woodblock"),
            (.click, "metro_click"),
            (.bell, "metro_bell"),
            (.rimshot, "metro_rimshot")
        ]
        var result: [MetronomeSound: DecodedWavSample] = [:]
        for (sound, name) in names {
            let url = Bundle.main.url(forResource: name, withExtension: "wav", subdirectory: "Audio/Metronome")
                ?? Bundle.main.url(forResource: name, withExtension: "wav")
            guard let url, let data = try? Data(contentsOf: url),
                  let decoded = WavSampleDecoder.decode(data),
                  !decoded.pcm.isEmpty else { continue }
            result[sound] = decoded
        }
        return result
    }

    private static func loadBundledPianoZones() -> [InstrumentSampleZone] {
        var urls = Bundle.main.urls(forResourcesWithExtension: "wav", subdirectory: "Audio/Piano") ?? []
        if urls.isEmpty {
            let allWavs = Bundle.main.urls(forResourcesWithExtension: "wav", subdirectory: nil) ?? []
            urls = allWavs.filter { $0.deletingPathExtension().lastPathComponent.hasPrefix("m0") || $0.deletingPathExtension().lastPathComponent.hasPrefix("m1") }
        }
        var zones: [InstrumentSampleZone] = []
        for url in urls.sorted(by: { $0.lastPathComponent < $1.lastPathComponent }) {
            let stem = url.deletingPathExtension().lastPathComponent
            let fallbackMidi = Int(stem.dropFirst()) ?? 60
            guard let data = try? Data(contentsOf: url),
                  let zone = WavSampleDecoder.decodeInstrumentZone(data, fallbackMidi: fallbackMidi),
                  !zone.pcm.isEmpty else { continue }
            zones.append(zone)
        }
        zones.sort { $0.rootFreqHz < $1.rootFreqHz }
        return zones
    }

    func setMetronomeSamplesForTesting(_ samples: [MetronomeSound: DecodedWavSample]) {
        withLock { metronomeSamples = samples }
    }

    func setPianoZonesForTesting(_ zones: [InstrumentSampleZone]) {
        withLock { pianoZones = zones.sorted { $0.rootFreqHz < $1.rootFreqHz } }
    }

    func startEngine() {
        guard !engineStarted else { return }

        #if os(iOS)
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [.mixWithOthers])
            try session.setPreferredSampleRate(Self.defaultSampleRate)
            try session.setPreferredIOBufferDuration(0.0053)
            try session.setActive(true)
        } catch {
            // Continue gracefully (e.g. headless CI / Simulator without audio hardware)
        }
        let hwRate = session.sampleRate
        let resolvedRate = (44100.0...96000.0).contains(hwRate) ? hwRate : Self.defaultSampleRate
        #else
        let resolvedRate = Self.defaultSampleRate
        #endif

        withLock {
            activeSampleRate = resolvedRate
        }

        let engine = AVAudioEngine()
        guard let format = AVAudioFormat(
            commonFormat: .pcmFormatFloat32,
            sampleRate: resolvedRate,
            channels: 2,
            interleaved: false
        ) else { return }

        let node = AVAudioSourceNode(format: format) { [weak self] _, _, frameCount, audioBufferList -> OSStatus in
            guard let self else { return noErr }
            let abl = UnsafeMutableAudioBufferListPointer(audioBufferList)
            let count = Int(frameCount)
            if abl.count >= 2,
               let leftPtr = abl[0].mData?.assumingMemoryBound(to: Float.self),
               let rightPtr = abl[1].mData?.assumingMemoryBound(to: Float.self) {
                self.renderStereoBuffers(left: leftPtr, right: rightPtr, frameCount: count)
            } else if abl.count == 1,
                      let monoPtr = abl[0].mData?.assumingMemoryBound(to: Float.self) {
                self.renderMonoBuffer(out: monoPtr, frameCount: count)
            }
            return noErr
        }

        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: format)

        do {
            try engine.start()
            avEngine = engine
            sourceNode = node
            engineStarted = true
            registerAudioSessionNotifications()
        } catch {
            engine.stop()
            avEngine = nil
            sourceNode = nil
            engineStarted = false
        }
    }

    func stopEngine() {
        #if os(iOS)
        if let obs = interruptionObserver {
            NotificationCenter.default.removeObserver(obs)
            interruptionObserver = nil
        }
        if let obs = routeChangeObserver {
            NotificationCenter.default.removeObserver(obs)
            routeChangeObserver = nil
        }
        #endif
        avEngine?.stop()
        avEngine = nil
        sourceNode = nil
        engineStarted = false
    }

    private func registerAudioSessionNotifications() {
        #if os(iOS)
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: .main
        ) { [weak self] notification in
            guard let self,
                  let info = notification.userInfo,
                  let typeValue = info[AVAudioSessionInterruptionTypeKey] as? UInt,
                  let type = AVAudioSession.InterruptionType(rawValue: typeValue) else { return }
            if type == .ended {
                try? AVAudioSession.sharedInstance().setActive(true)
                try? self.avEngine?.start()
            }
        }

        routeChangeObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.routeChangeNotification,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            guard let self, self.engineStarted, let engine = self.avEngine, !engine.isRunning else { return }
            try? engine.start()
        }
        #endif
    }

    // MARK: - Public Controls

    func updateActiveNotes(_ noteFrequencies: [Int: Double]) {
        withLock {
            let wasSilent = voices.isEmpty

            for idx in voices.indices {
                let midi = voices[idx].writtenMidi
                if let updatedFreq = noteFrequencies[midi] {
                    let wasReleasing = voices[idx].releasing
                    voices[idx].frequencyHz = updatedFreq
                    voices[idx].releasing = false
                    if wasReleasing {
                        voices[idx].ageSamples = 0
                        voices[idx].sampleZoneIndex = -1
                        voices[idx].sampleVoice = nil
                        voices[idx].samplePos = 0.0
                    }
                } else {
                    voices[idx].releasing = true
                }
            }

            for (midi, freq) in noteFrequencies {
                let hasActive = voices.contains { $0.writtenMidi == midi && !$0.releasing }
                if !hasActive {
                    if let existingIdx = voices.firstIndex(where: { $0.writtenMidi == midi }) {
                        voices[existingIdx].frequencyHz = freq
                        voices[existingIdx].releasing = false
                        voices[existingIdx].ageSamples = 0
                        voices[existingIdx].sampleZoneIndex = -1
                        voices[existingIdx].sampleVoice = nil
                        voices[existingIdx].samplePos = 0.0
                    } else {
                        if voices.count >= 16 {
                            if let quietestIdx = voices.enumerated()
                                .filter({ $0.element.releasing })
                                .min(by: { $0.element.env < $1.element.env })?.offset {
                                voices.remove(at: quietestIdx)
                            }
                        }
                        if voices.count < 16 {
                            voices.append(ActiveVoice(writtenMidi: midi, frequencyHz: freq))
                        }
                    }
                }
            }

            let heldCount = voices.reduce(0) { $0 + ($1.releasing ? 0 : 1) }
            if heldCount > 0 {
                let nextNorm = Self.baseVoiceNorm / sqrt(Double(heldCount))
                targetPolyNorm = nextNorm
                if wasSilent {
                    smoothedPolyNorm = nextNorm
                    smoothedToneGain = masterToneGain
                }
            }
        }
    }

    func setToneVoice(_ voice: ToneVoice) {
        withLock { currentToneVoice = voice }
    }

    func setToneGain(_ gain: Float) {
        let clamped = min(max(Double(gain), 0.0), 1.0)
        withLock {
            masterToneGain = clamped
            if voices.isEmpty {
                smoothedToneGain = clamped
            }
        }
    }

    func setMetronomeGain(_ gain: Float) {
        let clamped = min(max(Double(gain), 0.0), 1.0)
        withLock {
            masterMetronomeGain = clamped
            if !metronomePlaying {
                smoothedMetronomeGain = clamped
            }
        }
    }

    func setOutputMode(_ mode: AudioOutputMode) {
        let target = (mode == .splitLR) ? 1.0 : 0.0
        withLock {
            outputMode = mode
            if voices.isEmpty && !metronomePlaying {
                smoothedSplitMix = target
            }
        }
    }

    func setMetronomeConfig(
        bpm: Int,
        timeSignature: TimeSignature,
        sound: MetronomeSound,
        mix: SubdivisionMix,
        alternateMuteBar: Bool
    ) {
        let idleSnapshot: MetronomeBeatSnapshot? = withLock {
            let tsChanged = self.timeSignature != timeSignature
            self.bpm = min(max(bpm, 32), 280)
            self.timeSignature = timeSignature
            self.metronomeSound = sound
            self.subdivisionMix = mix
            self.alternateMuteBar = alternateMuteBar
            if tsChanged {
                currentBeatInBar = min(currentBeatInBar, timeSignature.beatsPerMeasure - 1)
                if !metronomePlaying {
                    tickSeq += 1
                    return MetronomeBeatSnapshot(
                        beatIndex: 0,
                        totalBeats: timeSignature.beatsPerMeasure,
                        isMutedBar: false,
                        isPlaying: false,
                        tickId: tickSeq
                    )
                }
            }
            return nil
        }
        if let snap = idleSnapshot {
            dispatchBeatSnapshot(snap)
        }
    }

    func setMetronomePlaying(_ playing: Bool) {
        let pendingSnapshot: MetronomeBeatSnapshot? = withLock {
            var snapshot: MetronomeBeatSnapshot? = nil
            if playing && !metronomePlaying {
                samplePosInBeat = 0.0
                currentBeatInBar = 0
                barCounter = 0
                firedEighth = false
                firedTrip1 = false
                firedTrip2 = false
                firedSixteenth1 = false
                firedSixteenth3 = false
                smoothedMetronomeGain = masterMetronomeGain
                snapshot = triggerBeatBoundaryLocked()
            } else if !playing {
                tickSeq += 1
                snapshot = MetronomeBeatSnapshot(
                    beatIndex: 0,
                    totalBeats: timeSignature.beatsPerMeasure,
                    isMutedBar: false,
                    isPlaying: false,
                    tickId: tickSeq
                )
            }
            metronomePlaying = playing
            return snapshot
        }
        if let snap = pendingSnapshot {
            dispatchBeatSnapshot(snap)
        }
    }

    func stopAllSound() {
        let stoppedSnapshot: MetronomeBeatSnapshot = withLock {
            for idx in voices.indices {
                voices[idx].releasing = true
            }
            metronomePlaying = false
            tickSeq += 1
            return MetronomeBeatSnapshot(
                beatIndex: 0,
                totalBeats: timeSignature.beatsPerMeasure,
                isMutedBar: false,
                isPlaying: false,
                tickId: tickSeq
            )
        }
        dispatchBeatSnapshot(stoppedSnapshot)
    }

    private func dispatchBeatSnapshot(_ snapshot: MetronomeBeatSnapshot) {
        guard let callback = onBeatSnapshot else { return }
        DispatchQueue.main.async {
            callback(snapshot)
        }
    }

    // MARK: - DSP Rendering (Real-time Safe & Unit-Testable)

    func renderStereoFrame(left: inout [Float], right: inout [Float], frameCount: Int) {
        guard left.count >= frameCount, right.count >= frameCount else { return }
        left.withUnsafeMutableBufferPointer { lBuf in
            right.withUnsafeMutableBufferPointer { rBuf in
                guard let lPtr = lBuf.baseAddress, let rPtr = rBuf.baseAddress else { return }
                renderStereoBuffers(left: lPtr, right: rPtr, frameCount: frameCount)
            }
        }
    }

    private func renderMonoBuffer(out: UnsafeMutablePointer<Float>, frameCount: Int) {
        let snapshot = withLock { () -> MetronomeBeatSnapshot? in
            var pending: MetronomeBeatSnapshot? = nil
            for i in 0..<frameCount {
                let (l, r, beat) = renderSingleStereoSampleLocked()
                if let beat { pending = beat }
                out[i] = Float((l + r) * 0.5)
            }
            return pending
        }
        if let snapshot {
            dispatchBeatSnapshot(snapshot)
        }
    }

    private func renderStereoBuffers(
        left: UnsafeMutablePointer<Float>,
        right: UnsafeMutablePointer<Float>,
        frameCount: Int
    ) {
        let snapshot = withLock { () -> MetronomeBeatSnapshot? in
            var pending: MetronomeBeatSnapshot? = nil
            let heldCount = voices.reduce(0) { $0 + ($1.releasing ? 0 : 1) }
            if heldCount > 0 {
                targetPolyNorm = Self.baseVoiceNorm / sqrt(Double(heldCount))
            }
            for i in 0..<frameCount {
                let (l, r, beat) = renderSingleStereoSampleLocked()
                if let beat { pending = beat }
                left[i] = Float(min(max(l, -1.0), 1.0))
                right[i] = Float(min(max(r, -1.0), 1.0))
            }
            return pending
        }
        if let snapshot {
            dispatchBeatSnapshot(snapshot)
        }
    }

    private func renderSingleStereoSampleLocked() -> (Double, Double, MetronomeBeatSnapshot?) {
        let sr = activeSampleRate
        let toneVoice = currentToneVoice
        let targetToneGain = masterToneGain
        let targetMetroGain = masterMetronomeGain
        let targetSplit = (outputMode == .splitLR) ? 1.0 : 0.0
        let isMetroRunning = metronomePlaying
        let samplesPerBeat = (sr * 60.0) / Double(bpm)

        smoothedPolyNorm += (targetPolyNorm - smoothedPolyNorm) * Self.polyNormSmoothCoeff
        smoothedToneGain += (targetToneGain - smoothedToneGain) * Self.gainSmoothCoeff
        smoothedMetronomeGain += (targetMetroGain - smoothedMetronomeGain) * Self.gainSmoothCoeff

        let splitDiff = targetSplit - smoothedSplitMix
        if abs(splitDiff) < 1e-5 {
            smoothedSplitMix = targetSplit
        } else {
            smoothedSplitMix += splitDiff * Self.splitMixSmoothCoeff
        }

        // 1. Isolated Tone Bus
        var toneSample = 0.0
        var idx = voices.count - 1
        while idx >= 0 {
            let sampleVal = synthesizeVoiceSampleLocked(at: idx, timbre: toneVoice, sr: sr)
            toneSample += sampleVal * voices[idx].env * smoothedPolyNorm * smoothedToneGain
            if voices[idx].releasing && voices[idx].env <= 0.0005 {
                voices.remove(at: idx)
            }
            idx -= 1
        }

        // 2. Sample-Accurate Metronome Clock
        var snapshot: MetronomeBeatSnapshot? = nil
        if isMetroRunning {
            samplePosInBeat += 1.0
            let progress = samplePosInBeat / samplesPerBeat

            if progress >= 1.0 {
                samplePosInBeat -= samplesPerBeat
                firedEighth = false
                firedTrip1 = false
                firedTrip2 = false
                firedSixteenth1 = false
                firedSixteenth3 = false

                currentBeatInBar += 1
                if currentBeatInBar >= timeSignature.beatsPerMeasure {
                    currentBeatInBar = 0
                    barCounter += 1
                }
                snapshot = triggerBeatBoundaryLocked()
            } else {
                let isMuted = alternateMuteBar && (barCounter % 2 == 1)
                if !isMuted {
                    let mix = subdivisionMix
                    if !firedSixteenth1 && progress >= 0.25 {
                        firedSixteenth1 = true
                        if mix.sixteenth > 0.01 {
                            spawnPercussionLocked(.sixteenth, level: Double(mix.sixteenth))
                        }
                    }
                    if !firedTrip1 && progress >= (1.0 / 3.0) {
                        firedTrip1 = true
                        if mix.triplet > 0.01 {
                            spawnPercussionLocked(.triplet, level: Double(mix.triplet))
                        }
                    }
                    if !firedEighth && progress >= 0.5 {
                        firedEighth = true
                        if mix.eighth > 0.01 {
                            spawnPercussionLocked(.eighth, level: Double(max(mix.eighth, mix.sixteenth)))
                        } else if mix.sixteenth > 0.01 {
                            spawnPercussionLocked(.sixteenth, level: Double(mix.sixteenth))
                        }
                    }
                    if !firedTrip2 && progress >= (2.0 / 3.0) {
                        firedTrip2 = true
                        if mix.triplet > 0.01 {
                            spawnPercussionLocked(.triplet, level: Double(mix.triplet))
                        }
                    }
                    if !firedSixteenth3 && progress >= 0.75 {
                        firedSixteenth3 = true
                        if mix.sixteenth > 0.01 {
                            spawnPercussionLocked(.sixteenth, level: Double(mix.sixteenth))
                        }
                    }
                }
            }
        }

        // 3. Isolated Metronome Bus
        var metroSample = 0.0
        for pIdx in percPool.indices {
            if percPool[pIdx].active {
                metroSample += synthesizePercussionSampleLocked(at: pIdx, sr: sr) * smoothedMetronomeGain
            }
        }

        // 4. Independent Bus Soft-Knee Limiting + Linear Summing / Stereo Split
        let toneLimited = Self.softLimitBus(toneSample, knee: Self.toneBusKnee, ceiling: Self.toneBusCeiling)
        let metroLimited = Self.softLimitBus(metroSample, knee: Self.metroBusKnee, ceiling: Self.metroBusCeiling)

        let crossFeed = 1.0 - smoothedSplitMix
        let left = toneLimited + metroLimited * crossFeed
        let right = metroLimited + toneLimited * crossFeed
        return (left, right, snapshot)
    }

    private func triggerBeatBoundaryLocked() -> MetronomeBeatSnapshot {
        let ts = timeSignature
        let mix = subdivisionMix
        let isMuted = alternateMuteBar && (barCounter % 2 == 1)

        if !isMuted {
            let maxSub = max(mix.eighth, max(mix.triplet, mix.sixteenth))
            if currentBeatInBar == 0 && mix.accent > 0.01 {
                spawnPercussionLocked(.accent, level: Double(mix.accent))
            } else if ts.isCompound && currentBeatInBar % 3 != 0 && mix.quarter > 0.01 {
                spawnPercussionLocked(.secondaryCompound, level: Double(mix.quarter) * 0.65)
            } else if mix.quarter > 0.01 {
                spawnPercussionLocked(.quarter, level: Double(max(mix.quarter, maxSub)))
            } else if mix.eighth > 0.01 {
                spawnPercussionLocked(.eighth, level: Double(max(mix.eighth, mix.sixteenth)))
            } else if mix.triplet > 0.01 {
                spawnPercussionLocked(.triplet, level: Double(mix.triplet))
            } else if mix.sixteenth > 0.01 {
                spawnPercussionLocked(.sixteenth, level: Double(mix.sixteenth))
            }
        }

        tickSeq += 1
        return MetronomeBeatSnapshot(
            beatIndex: currentBeatInBar,
            totalBeats: ts.beatsPerMeasure,
            isMutedBar: isMuted,
            isPlaying: true,
            tickId: tickSeq
        )
    }

    private func spawnPercussionLocked(_ kind: HitKind, level: Double) {
        let slotIdx = percPoolCursor
        percPoolCursor = (percPoolCursor + 1) % percPool.count

        let sr = activeSampleRate
        let sound = metronomeSound
        let accentFlag = (kind == .accent)

        if let bundledSample = metronomeSamples[sound], !bundledSample.pcm.isEmpty {
            let pitchRatio: Double
            switch kind {
            case .accent: pitchRatio = 1.22
            case .quarter: pitchRatio = 1.00
            case .secondaryCompound: pitchRatio = 0.90
            case .eighth: pitchRatio = 1.12
            case .triplet: pitchRatio = 1.06
            case .sixteenth: pitchRatio = 1.18
            }
            percPool[slotIdx].active = true
            percPool[slotIdx].useBundledSample = true
            percPool[slotIdx].sampleSound = sound
            percPool[slotIdx].samplePos = 0.0
            percPool[slotIdx].sampleStep = (Double(bundledSample.sampleRate) / sr) * pitchRatio
            percPool[slotIdx].gain = min(max(level * (accentFlag ? 0.56 : 0.40), 0.0), 0.62)
            percPool[slotIdx].sound = sound
            percPool[slotIdx].isAccent = accentFlag
            return
        }

        var fStart = 1480.0
        var fEnd = 1180.0
        var durMs = 12.0
        var hpCutoffHz = 900.0

        switch sound {
        case .woodblock:
            hpCutoffHz = 900.0
            switch kind {
            case .accent: fStart = 1950.0; fEnd = 1520.0; durMs = 14.0
            case .quarter: fStart = 1480.0; fEnd = 1180.0; durMs = 12.0
            case .secondaryCompound: fStart = 1320.0; fEnd = 1060.0; durMs = 10.5
            case .eighth: fStart = 1680.0; fEnd = 1360.0; durMs = 9.5
            case .triplet: fStart = 1580.0; fEnd = 1280.0; durMs = 9.0
            case .sixteenth: fStart = 1820.0; fEnd = 1460.0; durMs = 8.0
            }
        case .click:
            hpCutoffHz = 1400.0
            switch kind {
            case .accent: fStart = 3300.0; fEnd = 2500.0; durMs = 7.0
            case .quarter: fStart = 2550.0; fEnd = 1950.0; durMs = 6.0
            case .secondaryCompound: fStart = 2250.0; fEnd = 1750.0; durMs = 5.5
            case .eighth: fStart = 2850.0; fEnd = 2200.0; durMs = 5.0
            case .triplet: fStart = 2700.0; fEnd = 2080.0; durMs = 4.8
            case .sixteenth: fStart = 3050.0; fEnd = 2350.0; durMs = 4.5
            }
        case .bell:
            hpCutoffHz = 1200.0
            switch kind {
            case .accent: fStart = 2637.0; fEnd = 2637.0; durMs = 45.0
            case .quarter: fStart = 2093.0; fEnd = 2093.0; durMs = 32.0
            case .secondaryCompound: fStart = 1760.0; fEnd = 1760.0; durMs = 26.0
            case .eighth: fStart = 2349.0; fEnd = 2349.0; durMs = 22.0
            case .triplet: fStart = 2217.0; fEnd = 2217.0; durMs = 20.0
            case .sixteenth: fStart = 2489.0; fEnd = 2489.0; durMs = 18.0
            }
        case .rimshot:
            hpCutoffHz = 800.0
            switch kind {
            case .accent: fStart = 1150.0; fEnd = 680.0; durMs = 13.5
            case .quarter: fStart = 960.0; fEnd = 580.0; durMs = 11.5
            case .secondaryCompound: fStart = 860.0; fEnd = 520.0; durMs = 10.0
            case .eighth: fStart = 1280.0; fEnd = 780.0; durMs = 9.0
            case .triplet: fStart = 1180.0; fEnd = 720.0; durMs = 8.5
            case .sixteenth: fStart = 1380.0; fEnd = 860.0; durMs = 7.5
            }
        }

        let attackSamples = max(Int(round(0.00025 * sr)), 1)
        let totalSamples = max(Int(round(durMs * 0.001 * sr)), attackSamples + 8)

        percPool[slotIdx].active = true
        percPool[slotIdx].useBundledSample = false
        percPool[slotIdx].sampleSound = nil
        percPool[slotIdx].ageSamples = 0
        percPool[slotIdx].totalSamples = totalSamples
        percPool[slotIdx].attackSamples = attackSamples
        percPool[slotIdx].phase = 0.0
        percPool[slotIdx].phaseOver = 0.0
        percPool[slotIdx].prevNoise = 0.0
        percPool[slotIdx].hpPrevIn = 0.0
        percPool[slotIdx].hpPrevOut = 0.0
        percPool[slotIdx].hpAlpha = 1.0 / (1.0 + Self.twoPi * hpCutoffHz / sr)
        percPool[slotIdx].freqStart = fStart
        percPool[slotIdx].freqEnd = fEnd
        percPool[slotIdx].gain = min(max(level * (accentFlag ? 0.85 : 0.625), 0.0), 0.95)
        percPool[slotIdx].sound = sound
        percPool[slotIdx].isAccent = accentFlag
    }

    private func findNearestPianoZoneIndex(_ freqHz: Double) -> Int {
        var bestIdx = 0
        let safeFreq = max(freqHz, 16.0)
        var bestDist = abs(log(safeFreq / pianoZones[0].rootFreqHz))
        for i in 1..<pianoZones.count {
            let dist = abs(log(safeFreq / pianoZones[i].rootFreqHz))
            if dist < bestDist {
                bestDist = dist
                bestIdx = i
            }
        }
        return bestIdx
    }

    private func synthesizeVoiceSampleLocked(at idx: Int, timbre: ToneVoice, sr: Double) -> Double {
        voices[idx].ageSamples += 1
        let srScale = 44100.0 / sr

        if timbre == .piano && !pianoZones.isEmpty {
            var zoneIdx = voices[idx].sampleZoneIndex
            let currentFreq = voices[idx].frequencyHz
            let outOfRange: Bool
            if zoneIdx >= 0 && zoneIdx < pianoZones.count {
                let ratio = currentFreq / pianoZones[zoneIdx].rootFreqHz
                outOfRange = !voices[idx].releasing && (ratio < 0.72 || ratio > 1.38)
            } else {
                outOfRange = true
            }

            if voices[idx].sampleVoice != timbre || outOfRange {
                zoneIdx = findNearestPianoZoneIndex(currentFreq)
                voices[idx].sampleZoneIndex = zoneIdx
                voices[idx].sampleVoice = timbre
                voices[idx].samplePos = 0.0
                if voices[idx].env > 0.0 {
                    voices[idx].env = 0.0
                }
            }

            let zone = pianoZones[zoneIdx]
            let attackRate = 0.015 * srScale
            let releaseRate = 0.0018 * srScale
            if voices[idx].releasing {
                voices[idx].env = max(voices[idx].env - releaseRate, 0.0)
            } else {
                voices[idx].env = min(voices[idx].env + attackRate, 1.0)
            }

            let pcmCount = zone.pcm.count
            let lastIdx = pcmCount - 1
            if lastIdx < 1 { return 0.0 }

            let step = (Double(zone.sampleRate) / sr) * (currentFreq / zone.rootFreqHz)
            var pos = voices[idx].samplePos
            if zone.hasLoop {
                let loopStart = Double(zone.loopStart)
                let loopLen = Double(zone.loopEnd - zone.loopStart)
                if pos >= Double(zone.loopEnd) {
                    pos = loopStart + (pos - loopStart).truncatingRemainder(dividingBy: loopLen)
                }
            } else if pos >= Double(lastIdx) {
                voices[idx].env = 0.0
                voices[idx].releasing = true
                return 0.0
            }

            let iPos = Int(pos)
            let frac = pos - Double(iPos)
            let invScale = 1.0 / 32768.0
            let s0 = Double(zone.pcm[min(max(iPos - 1, 0), lastIdx)]) * invScale
            let s1 = Double(zone.pcm[min(max(iPos, 0), lastIdx)]) * invScale
            let s2 = Double(zone.pcm[min(max(iPos + 1, 0), lastIdx)]) * invScale
            let s3 = Double(zone.pcm[min(max(iPos + 2, 0), lastIdx)]) * invScale

            let c0 = s1
            let c1 = 0.5 * (s2 - s0)
            let c2 = s0 - 2.5 * s1 + 2.0 * s2 - 0.5 * s3
            let c3 = 0.5 * (s3 - s0) + 1.5 * (s1 - s2)
            let sample = ((c3 * frac + c2) * frac + c1) * frac + c0

            pos += step
            if zone.hasLoop && pos >= Double(zone.loopEnd) {
                let loopStart = Double(zone.loopStart)
                let loopLen = Double(zone.loopEnd - zone.loopStart)
                pos = loopStart + (pos - loopStart).truncatingRemainder(dividingBy: loopLen)
            }
            voices[idx].samplePos = pos
            return sample
        }

        // Phase-locked synthesized path (PURE_SINE, ORGAN, BRASS, WOODWIND, STRINGS, and PIANO fallback)
        voices[idx].sampleZoneIndex = -1
        voices[idx].sampleVoice = timbre
        let inc = Self.twoPi * voices[idx].frequencyHz / sr
        voices[idx].phase += inc
        if voices[idx].phase >= Self.twoPi {
            voices[idx].phase -= Self.twoPi
        }

        let baseAttack: Double
        switch timbre {
        case .strings: baseAttack = 0.0009
        case .woodwind: baseAttack = 0.0022
        case .piano: baseAttack = 0.012
        default: baseAttack = 0.0035
        }
        let attackRate = baseAttack * srScale
        let releaseRate = 0.0020 * srScale

        if voices[idx].releasing {
            voices[idx].env = max(voices[idx].env - releaseRate, 0.0)
        } else if timbre == .piano {
            let pianoAttackSamples = max(Int64(round(0.0032 * sr)), 1)
            if voices[idx].ageSamples < pianoAttackSamples {
                voices[idx].env = min(voices[idx].env + attackRate, 1.0)
            } else {
                voices[idx].env *= exp(-3.53 / sr)
            }
        } else {
            voices[idx].env = min(voices[idx].env + attackRate, 1.0)
        }

        let p = voices[idx].phase
        switch timbre {
        case .pureSine:
            return sin(p)
        case .organ, .brass, .woodwind, .strings:
            return Self.synthesizeSpectralVoice(
                timbre: timbre,
                freqHz: voices[idx].frequencyHz,
                phase: p,
                ageSec: Double(voices[idx].ageSamples) / sr,
                sr: sr
            )
        case .piano:
            let ageSec = Double(voices[idx].ageSamples) / sr
            let overDecay = exp(-ageSec * 3.8)
            return 0.64 * sin(p) +
                0.28 * overDecay * sin(2.0 * p) +
                0.14 * overDecay * sin(3.0 * p) +
                0.07 * overDecay * sin(4.0 * p)
        }
    }

    private func nextNoiseSampleLocked() -> Double {
        var x = noiseState
        x ^= x << 13
        x ^= x >> 7
        x ^= x << 17
        noiseState = x
        let unit = Double(x >> 11) * (1.0 / 9007199254740992.0)
        return unit * 2.0 - 1.0
    }

    private func synthesizePercussionSampleLocked(at pIdx: Int, sr: Double) -> Double {
        if percPool[pIdx].useBundledSample,
           let soundKey = percPool[pIdx].sampleSound,
           let sample = metronomeSamples[soundKey] {
            let data = sample.pcm
            let idx = Int(percPool[pIdx].samplePos)
            if idx >= data.count {
                percPool[pIdx].active = false
                percPool[pIdx].useBundledSample = false
                return 0.0
            }
            let frac = percPool[pIdx].samplePos - Double(idx)
            let s0 = Double(data[idx])
            let s1 = (idx + 1 < data.count) ? Double(data[idx + 1]) : 0.0
            let interpolated = s0 + (s1 - s0) * frac
            percPool[pIdx].samplePos += percPool[pIdx].sampleStep
            return interpolated * percPool[pIdx].gain
        }

        if percPool[pIdx].ageSamples >= percPool[pIdx].totalSamples {
            percPool[pIdx].active = false
            return 0.0
        }

        let t = Double(percPool[pIdx].ageSamples) / Double(percPool[pIdx].totalSamples)
        let freq = percPool[pIdx].freqStart + (percPool[pIdx].freqEnd - percPool[pIdx].freqStart) * t
        percPool[pIdx].phase += Self.twoPi * freq / sr
        if percPool[pIdx].phase >= Self.twoPi {
            percPool[pIdx].phase -= Self.twoPi
        }

        let attack: Double
        if percPool[pIdx].ageSamples < percPool[pIdx].attackSamples {
            attack = Double(percPool[pIdx].ageSamples) / Double(percPool[pIdx].attackSamples)
        } else {
            attack = 1.0
        }
        let fadeOut = (t < 0.88) ? 1.0 : max((1.0 - t) / 0.12, 0.0)

        let decayRate: Double
        switch percPool[pIdx].sound {
        case .bell: decayRate = 6.5
        case .click: decayRate = 8.5
        case .rimshot: decayRate = 8.0
        case .woodblock: decayRate = 7.5
        }
        let env = exp(-t * decayRate) * attack * fadeOut

        let rawWave: Double
        switch percPool[pIdx].sound {
        case .woodblock:
            rawWave = 0.78 * sin(percPool[pIdx].phase) + 0.22 * sin(2.42 * percPool[pIdx].phase) * exp(-t * 10.0)
        case .click:
            rawWave = 0.80 * sin(percPool[pIdx].phase) + 0.20 * sin(2.0 * percPool[pIdx].phase)
        case .bell:
            percPool[pIdx].phaseOver += Self.twoPi * (freq * 2.756) / sr
            if percPool[pIdx].phaseOver >= Self.twoPi {
                percPool[pIdx].phaseOver -= Self.twoPi
            }
            rawWave = 0.72 * sin(percPool[pIdx].phase) + 0.28 * sin(percPool[pIdx].phaseOver) * exp(-t * 8.0)
        case .rimshot:
            let rawNoise = nextNoiseSampleLocked()
            let hpNoise = (rawNoise - percPool[pIdx].prevNoise) * 0.5 * exp(-t * 12.0)
            percPool[pIdx].prevNoise = rawNoise
            rawWave = 0.62 * sin(percPool[pIdx].phase) + 0.38 * hpNoise
        }

        let shaped = rawWave * env * percPool[pIdx].gain
        let filtered = percPool[pIdx].hpAlpha * (percPool[pIdx].hpPrevOut + shaped - percPool[pIdx].hpPrevIn)
        percPool[pIdx].hpPrevIn = shaped
        percPool[pIdx].hpPrevOut = filtered
        percPool[pIdx].ageSamples += 1
        return filtered
    }
}
