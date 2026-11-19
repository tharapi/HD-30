package com.example.midi

/**
 * Pure state machine managing active MIDI note sets across on-screen touches,
 * external MIDI hardware keys, Chord Hold latch, and Sustain Pedal (CC #64).
 *
 * Guarantees:
 * - Duplicate NoteOn while a physical key is already down (or within bounce window) is ignored.
 * - While Chord Hold is active, pressing an already-held key again cancels that exact note
 *   immediately on NoteOn, and subsequent NoteOff(s) never resurrect it.
 * - Stray NoteOff events for keys that were not pressed never activate or stick a note.
 */
internal class MidiNoteStateTracker(
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    companion object {
        const val KEY_BOUNCE_MS = 20L
    }

    private var chordHeldMidis: Set<Int> = emptySet()
    private var onScreenMidis: Set<Int> = emptySet()
    private var externalPressedMidis: Set<Int> = emptySet()
    private var externalSustainedMidis: Set<Int> = emptySet()
    private var externalCancelledWhileHeldMidis: Set<Int> = emptySet()
    private val lastExternalNoteOnMs = mutableMapOf<Int, Long>()

    fun activeMidis(): Set<Int> =
        chordHeldMidis + onScreenMidis + externalPressedMidis + externalSustainedMidis

    fun onScreenKeyPressed(writtenMidi: Int, chordHoldEnabled: Boolean): Set<Int> {
        if (chordHoldEnabled) {
            if (writtenMidi in activeMidis()) {
                chordHeldMidis = chordHeldMidis - writtenMidi
                onScreenMidis = onScreenMidis - writtenMidi
                externalPressedMidis = externalPressedMidis - writtenMidi
                externalSustainedMidis = externalSustainedMidis - writtenMidi
            } else {
                chordHeldMidis = chordHeldMidis + writtenMidi
            }
        } else {
            onScreenMidis = onScreenMidis + writtenMidi
        }
        return activeMidis()
    }

    fun onScreenKeyReleased(writtenMidi: Int): Set<Int> {
        onScreenMidis = onScreenMidis - writtenMidi
        return activeMidis()
    }

    fun onChordHoldChanged(nextHoldEnabled: Boolean, sustainPedalDown: Boolean): Set<Int> {
        if (nextHoldEnabled) {
            chordHeldMidis = activeMidis()
        } else {
            chordHeldMidis = emptySet()
            onScreenMidis = emptySet()
            externalCancelledWhileHeldMidis = emptySet()
            if (!sustainPedalDown) {
                externalSustainedMidis = emptySet()
            }
        }
        return activeMidis()
    }

    fun onOctaveShift(): Set<Int> {
        onScreenMidis = emptySet()
        return activeMidis()
    }

    fun onExternalNoteOn(midiNote: Int, chordHoldEnabled: Boolean): Set<Int>? {
        val clamped = midiNote.coerceIn(21, 108)
        // Ignore duplicate NoteOn while the same physical key is already down or just cancelled
        if (clamped in externalPressedMidis || clamped in externalCancelledWhileHeldMidis) {
            return null
        }
        val nowMs = clockMs()
        val prevMs = lastExternalNoteOnMs[clamped]
        if (prevMs != null && (nowMs - prevMs) in 0L until KEY_BOUNCE_MS) {
            return null
        }
        lastExternalNoteOnMs[clamped] = nowMs

        if (chordHoldEnabled) {
            if (clamped in activeMidis()) {
                // Pressing the same key again while Hold is active cancels that held note immediately
                chordHeldMidis = chordHeldMidis - clamped
                onScreenMidis = onScreenMidis - clamped
                externalPressedMidis = externalPressedMidis - clamped
                externalSustainedMidis = externalSustainedMidis - clamped
                externalCancelledWhileHeldMidis = externalCancelledWhileHeldMidis + clamped
            } else {
                chordHeldMidis = chordHeldMidis + clamped
                externalPressedMidis = externalPressedMidis + clamped
                externalSustainedMidis = externalSustainedMidis - clamped
            }
        } else {
            externalSustainedMidis = externalSustainedMidis - clamped
            externalPressedMidis = externalPressedMidis + clamped
        }
        return activeMidis()
    }

    fun onExternalNoteOff(
        midiNote: Int,
        chordHoldEnabled: Boolean,
        sustainPedalDown: Boolean
    ): Set<Int> {
        val clamped = midiNote.coerceIn(21, 108)
        val wasPressed = clamped in externalPressedMidis
        val wasCancelled = clamped in externalCancelledWhileHeldMidis
        externalPressedMidis = externalPressedMidis - clamped
        externalCancelledWhileHeldMidis = externalCancelledWhileHeldMidis - clamped

        if (wasPressed && !wasCancelled && !chordHoldEnabled && sustainPedalDown) {
            externalSustainedMidis = externalSustainedMidis + clamped
        }
        return activeMidis()
    }

    fun onSustainPedalReleased(chordHoldEnabled: Boolean): Set<Int> {
        if (!chordHoldEnabled) {
            externalSustainedMidis = emptySet()
        }
        return activeMidis()
    }

    fun onExternalAllNotesOff(): Set<Int> {
        externalPressedMidis = emptySet()
        externalSustainedMidis = emptySet()
        externalCancelledWhileHeldMidis = emptySet()
        return activeMidis()
    }

    fun clearAll(): Set<Int> {
        chordHeldMidis = emptySet()
        onScreenMidis = emptySet()
        externalPressedMidis = emptySet()
        externalSustainedMidis = emptySet()
        externalCancelledWhileHeldMidis = emptySet()
        lastExternalNoteOnMs.clear()
        return emptySet()
    }
}
