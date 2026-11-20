import XCTest
@testable import HarmonyDirector

final class HarmonyDirectorTests: XCTestCase {
    func testEqualTemperamentA4AndOctaves() {
        let a4 = PitchTheory.equalTemperamentFrequency(midiNote: 69, referenceA4Hz: 440.0)
        XCTAssertEqual(a4, 440.0, accuracy: 1e-6)

        let a5 = PitchTheory.equalTemperamentFrequency(midiNote: 81, referenceA4Hz: 442.0)
        XCTAssertEqual(a5, 884.0, accuracy: 1e-6)
    }

    func testPureJustIntonationMajorTriadRatios() {
        // C4 (60), E4 (64), G4 (67) with root C (0) at A4 = 440 Hz
        let c4 = PitchTheory.calculatePitchInfo(
            writtenMidi: 60,
            transposition: .concertC,
            referenceA4Hz: 440.0,
            temperament: .pure,
            rootPitchClass: 0
        )
        let e4 = PitchTheory.calculatePitchInfo(
            writtenMidi: 64,
            transposition: .concertC,
            referenceA4Hz: 440.0,
            temperament: .pure,
            rootPitchClass: 0
        )
        let g4 = PitchTheory.calculatePitchInfo(
            writtenMidi: 67,
            transposition: .concertC,
            referenceA4Hz: 440.0,
            temperament: .pure,
            rootPitchClass: 0
        )

        XCTAssertEqual(e4.frequencyHz / c4.frequencyHz, 5.0 / 4.0, accuracy: 1e-6)
        XCTAssertEqual(g4.frequencyHz / c4.frequencyHz, 3.0 / 2.0, accuracy: 1e-6)
        XCTAssertEqual(e4.centsOffsetFromEt, -13.7, accuracy: 1e-6)
        XCTAssertEqual(g4.centsOffsetFromEt, 2.0, accuracy: 1e-6)
        XCTAssertEqual(PitchTheory.detectChordOrNoteLabel([60, 64, 67]), "C Maj")
    }

    func testTranspositionBbClarinet() {
        // Written C4 on Bb instrument sounds Bb3 (MIDI 58)
        let info = PitchTheory.calculatePitchInfo(
            writtenMidi: 60,
            transposition: .bb,
            referenceA4Hz: 440.0,
            temperament: .equal,
            rootPitchClass: 10
        )
        XCTAssertEqual(info.writtenName, "C4")
        XCTAssertEqual(info.soundingMidi, 58)
        XCTAssertEqual(info.soundingName, "Bb3")
    }

    func testTimeSignatureParsingAndCompoundDetection() {
        XCTAssertEqual(TimeSignature.fromStorageString("SIX_EIGHT"), TimeSignature(numerator: 6, denominator: 8))
        XCTAssertTrue(TimeSignature(numerator: 6, denominator: 8).isCompound)
        XCTAssertFalse(TimeSignature(numerator: 4, denominator: 4).isCompound)
        XCTAssertEqual(TimeSignature.fromStorageString("7/8"), TimeSignature(numerator: 7, denominator: 8))
        XCTAssertEqual(TimeSignature.fromStorageString("invalid"), TimeSignature.fourFour)
    }

    func testMidiNoteStateTrackerChordHoldToggleCancel() {
        var now: Int64 = 100
        let tracker = MidiNoteStateTracker(clockMs: { now })

        // First press latches note 60
        let active1 = tracker.onExternalNoteOn(midiNote: 60, chordHoldEnabled: true)
        XCTAssertEqual(active1, [60])
        _ = tracker.onExternalNoteOff(midiNote: 60, chordHoldEnabled: true, sustainPedalDown: false)
        XCTAssertEqual(tracker.activeMidis(), [60])

        // Second press after bounce window cancels note 60
        now += 50
        let active2 = tracker.onExternalNoteOn(midiNote: 60, chordHoldEnabled: true)
        XCTAssertEqual(active2, [])
        let activeAfterOff = tracker.onExternalNoteOff(midiNote: 60, chordHoldEnabled: true, sustainPedalDown: false)
        XCTAssertEqual(activeAfterOff, [])
    }

    func testMidiStreamParserRunningStatus() {
        var received: [(Int, Int, Int)] = []
        let parser = MidiStreamParser(onChannelMessage: { s, d1, d2 in
            received.append((s, d1, d2))
        })

        // NoteOn ch1 C4 vel 100, followed by running-status E4 vel 90 with a clock byte 0xF8 interleaved
        parser.parse([0x90, 60, 100, 0xF8, 64, 90])
        XCTAssertEqual(received.count, 2)
        XCTAssertEqual(received[0].0, 0x90)
        XCTAssertEqual(received[0].1, 60)
        XCTAssertEqual(received[1].0, 0x90)
        XCTAssertEqual(received[1].1, 64)
    }

    func testAudioEngineStereoSplitIsolationAndHeadroom() {
        let engine = HarmonyAudioEngine(loadBundledAssets: false)
        engine.setOutputMode(.splitLR)
        engine.setToneVoice(.organ)
        engine.setToneGain(1.0)
        engine.setMetronomeGain(1.0)
        engine.updateActiveNotes([60: 261.6256, 64: 327.032, 67: 392.4384])

        // Render tone only (metronome off) in Split L/R -> Right channel must be silent
        var left = [Float](repeating: 0, count: 1024)
        var right = [Float](repeating: 0, count: 1024)
        engine.renderStereoFrame(left: &left, right: &right, frameCount: 1024)

        let peakL = left.map { abs($0) }.max() ?? 0
        let peakR = right.map { abs($0) }.max() ?? 0
        XCTAssertGreaterThan(peakL, 0.01)
        XCTAssertLessThan(peakR, 0.0001)
        XCTAssertLessThanOrEqual(peakL, 0.28)
    }

    func testWavSampleDecoderParsesBundledAudioOrSyntheticWav() {
        // Synthesize a minimal valid 16-bit mono 48 kHz RIFF/WAVE file in memory
        var wav = Data()
        func appendASCII(_ s: String) { wav.append(contentsOf: s.utf8) }
        func appendU32(_ v: UInt32) {
            var le = v.littleEndian
            withUnsafeBytes(of: &le) { wav.append(contentsOf: $0) }
        }
        func appendU16(_ v: UInt16) {
            var le = v.littleEndian
            withUnsafeBytes(of: &le) { wav.append(contentsOf: $0) }
        }
        let samples: [Int16] = [0, 16384, -16384, 8192]
        let dataBytes = UInt32(samples.count * 2)
        appendASCII("RIFF")
        appendU32(36 + dataBytes)
        appendASCII("WAVE")
        appendASCII("fmt ")
        appendU32(16)
        appendU16(1) // PCM
        appendU16(1) // Mono
        appendU32(48000)
        appendU32(48000 * 2)
        appendU16(2)
        appendU16(16)
        appendASCII("data")
        appendU32(dataBytes)
        for s in samples {
            var le = s.littleEndian
            withUnsafeBytes(of: &le) { wav.append(contentsOf: $0) }
        }

        let decoded = WavSampleDecoder.decode(wav)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(decoded?.sampleRate, 48000)
        XCTAssertEqual(decoded?.pcm.count, 4)
        XCTAssertEqual(decoded?.pcm[1] ?? 0, 0.5, accuracy: 0.001)
    }

    func testBundledAudioResourcesDecodeCleanly() throws {
        let woodblockURL = Bundle.main.url(forResource: "metro_woodblock", withExtension: "wav", subdirectory: "Audio/Metronome")
            ?? Bundle.main.url(forResource: "metro_woodblock", withExtension: "wav")
        let resolvedWoodblockURL = try XCTUnwrap(woodblockURL, "Expected bundled metro_woodblock.wav in app bundle")
        let woodblockData = try Data(contentsOf: resolvedWoodblockURL)
        let woodblock = try XCTUnwrap(WavSampleDecoder.decode(woodblockData))
        XCTAssertGreaterThan(woodblock.pcm.count, 100)

        let pianoURL = Bundle.main.url(forResource: "m060", withExtension: "wav", subdirectory: "Audio/Piano")
            ?? Bundle.main.url(forResource: "m060", withExtension: "wav")
        let resolvedPianoURL = try XCTUnwrap(pianoURL, "Expected bundled piano m060.wav in app bundle")
        let pianoData = try Data(contentsOf: resolvedPianoURL)
        let zone = try XCTUnwrap(WavSampleDecoder.decodeInstrumentZone(pianoData, fallbackMidi: 60))
        XCTAssertGreaterThan(zone.pcm.count, 1000)
        XCTAssertEqual(zone.rootFreqHz, 261.6256, accuracy: 2.0)
    }
}
