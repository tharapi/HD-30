import Foundation

/// Pure state machine managing active MIDI note sets across on-screen touches,
/// external MIDI hardware keys, Chord Hold latch, and Sustain Pedal (CC #64).
final class MidiNoteStateTracker {
    static let keyBounceMs: Int64 = 20

    private let clockMs: () -> Int64
    private var chordHeldMidis: Set<Int> = []
    private var onScreenMidis: Set<Int> = []
    private var externalPressedMidis: Set<Int> = []
    private var externalSustainedMidis: Set<Int> = []
    private var externalCancelledWhileHeldMidis: Set<Int> = []
    private var lastExternalNoteOnMs: [Int: Int64] = [:]

    init(clockMs: @escaping () -> Int64 = { Int64(ProcessInfo.processInfo.systemUptime * 1000.0) }) {
        self.clockMs = clockMs
    }

    func activeMidis() -> Set<Int> {
        chordHeldMidis
            .union(onScreenMidis)
            .union(externalPressedMidis)
            .union(externalSustainedMidis)
    }

    func onScreenKeyPressed(writtenMidi: Int, chordHoldEnabled: Bool) -> Set<Int> {
        if chordHoldEnabled {
            if activeMidis().contains(writtenMidi) {
                chordHeldMidis.remove(writtenMidi)
                onScreenMidis.remove(writtenMidi)
                externalPressedMidis.remove(writtenMidi)
                externalSustainedMidis.remove(writtenMidi)
            } else {
                chordHeldMidis.insert(writtenMidi)
            }
        } else {
            onScreenMidis.insert(writtenMidi)
        }
        return activeMidis()
    }

    func onScreenKeyReleased(writtenMidi: Int) -> Set<Int> {
        onScreenMidis.remove(writtenMidi)
        return activeMidis()
    }

    func onChordHoldChanged(nextHoldEnabled: Bool, sustainPedalDown: Bool) -> Set<Int> {
        if nextHoldEnabled {
            chordHeldMidis = activeMidis()
        } else {
            chordHeldMidis.removeAll()
            onScreenMidis.removeAll()
            externalCancelledWhileHeldMidis.removeAll()
            if !sustainPedalDown {
                externalSustainedMidis.removeAll()
            }
        }
        return activeMidis()
    }

    func onOctaveShift() -> Set<Int> {
        onScreenMidis.removeAll()
        return activeMidis()
    }

    func onExternalNoteOn(midiNote: Int, chordHoldEnabled: Bool) -> Set<Int>? {
        let clamped = min(max(midiNote, 21), 108)
        if externalPressedMidis.contains(clamped) || externalCancelledWhileHeldMidis.contains(clamped) {
            return nil
        }
        let nowMs = clockMs()
        if let prevMs = lastExternalNoteOnMs[clamped],
           (nowMs - prevMs) >= 0 && (nowMs - prevMs) < Self.keyBounceMs {
            return nil
        }
        lastExternalNoteOnMs[clamped] = nowMs

        if chordHoldEnabled {
            if activeMidis().contains(clamped) {
                chordHeldMidis.remove(clamped)
                onScreenMidis.remove(clamped)
                externalPressedMidis.remove(clamped)
                externalSustainedMidis.remove(clamped)
                externalCancelledWhileHeldMidis.insert(clamped)
            } else {
                chordHeldMidis.insert(clamped)
                externalPressedMidis.insert(clamped)
                externalSustainedMidis.remove(clamped)
            }
        } else {
            externalSustainedMidis.remove(clamped)
            externalPressedMidis.insert(clamped)
        }
        return activeMidis()
    }

    func onExternalNoteOff(
        midiNote: Int,
        chordHoldEnabled: Bool,
        sustainPedalDown: Bool
    ) -> Set<Int> {
        let clamped = min(max(midiNote, 21), 108)
        let wasPressed = externalPressedMidis.remove(clamped) != nil
        let wasCancelled = externalCancelledWhileHeldMidis.remove(clamped) != nil

        if wasPressed && !wasCancelled && !chordHoldEnabled && sustainPedalDown {
            externalSustainedMidis.insert(clamped)
        }
        return activeMidis()
    }

    func onSustainPedalReleased(chordHoldEnabled: Bool) -> Set<Int> {
        if !chordHoldEnabled {
            externalSustainedMidis.removeAll()
        }
        return activeMidis()
    }

    func onExternalAllNotesOff() -> Set<Int> {
        externalPressedMidis.removeAll()
        externalSustainedMidis.removeAll()
        externalCancelledWhileHeldMidis.removeAll()
        return activeMidis()
    }

    func clearAll() -> Set<Int> {
        chordHeldMidis.removeAll()
        onScreenMidis.removeAll()
        externalPressedMidis.removeAll()
        externalSustainedMidis.removeAll()
        externalCancelledWhileHeldMidis.removeAll()
        lastExternalNoteOnMs.removeAll()
        return []
    }
}
