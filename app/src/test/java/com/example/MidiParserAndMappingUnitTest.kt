package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.audio.HarmonyAudioEngine
import com.example.data.LocalFallbackPresetRepository
import com.example.midi.MidiHardwareEvent
import com.example.midi.MidiInputController
import com.example.midi.MidiNoteStateTracker
import com.example.midi.MidiStreamParser
import com.example.model.MetronomeSound
import com.example.model.TimeSignature
import com.example.model.ToneVoice
import com.example.ui.ConsoleViewModel
import com.example.ui.MidiControlTarget
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class MidiParserAndMappingUnitTest {

    private fun createController(): MidiInputController {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return MidiInputController(context)
    }

    @Test
    fun `parses note on, velocity-zero note off, explicit note off, and control change`() = runBlocking {
        val controller = createController()
        val collected = mutableListOf<MidiHardwareEvent>()
        val job = launch {
            controller.events.take(4).toList(collected)
        }
        kotlinx.coroutines.yield()

        val packet = byteArrayOf(
            0x90.toByte(), 60, 100, // Note On C4 vel 100
            0x90.toByte(), 60, 0,   // Note On vel 0 -> Note Off C4
            0x80.toByte(), 64, 64,  // Note Off E4
            0xB0.toByte(), 64, 127  // CC 64
        )
        controller.parseMidiPacket(packet, 0, packet.size)
        job.join()

        assertEquals(
            listOf(
                MidiHardwareEvent.NoteOn(60, 100, 0),
                MidiHardwareEvent.NoteOff(60, 0),
                MidiHardwareEvent.NoteOff(64, 0),
                MidiHardwareEvent.ControlChange(64, 127, 0)
            ),
            collected
        )
    }

    @Test
    fun `supports MIDI running status and ignores real-time clock bytes`() = runBlocking {
        val controller = createController()
        val collected = mutableListOf<MidiHardwareEvent>()
        val job = launch {
            controller.events.take(3).toList(collected)
        }
        kotlinx.coroutines.yield()

        val packet = byteArrayOf(
            0x92.toByte(), 48, 90,
            0xF8.toByte(),
            52, 85,
            0xFE.toByte(),
            55, 0
        )
        controller.parseMidiPacket(packet, 0, packet.size)
        job.join()

        assertEquals(
            listOf(
                MidiHardwareEvent.NoteOn(48, 90, 2),
                MidiHardwareEvent.NoteOn(52, 85, 2),
                MidiHardwareEvent.NoteOff(55, 2)
            ),
            collected
        )
    }

    @Test
    fun `handles split packets across multiple onSend callbacks`() = runBlocking {
        val controller = createController()
        val collected = mutableListOf<MidiHardwareEvent>()
        val job = launch {
            controller.events.take(2).toList(collected)
        }
        kotlinx.coroutines.yield()

        controller.parseMidiPacket(byteArrayOf(0xB1.toByte(), 74), 0, 2)
        controller.parseMidiPacket(byteArrayOf(88, 0x91.toByte()), 0, 2)
        controller.parseMidiPacket(byteArrayOf(67, 110), 0, 2)
        job.join()

        assertEquals(
            listOf(
                MidiHardwareEvent.ControlChange(74, 88, 1),
                MidiHardwareEvent.NoteOn(67, 110, 1)
            ),
            collected
        )
    }

    @Test
    fun `isolated per-port parsers do not corrupt interleaved streams into phantom notes`() {
        val port0Msgs = mutableListOf<Triple<Int, Int, Int>>()
        val port1Msgs = mutableListOf<Triple<Int, Int, Int>>()
        val parser0 = MidiStreamParser(onChannelMessage = { s, d1, d2 -> port0Msgs.add(Triple(s, d1, d2)) })
        val parser1 = MidiStreamParser(onChannelMessage = { s, d1, d2 -> port1Msgs.add(Triple(s, d1, d2)) })

        // Interleave split NoteOn on port 0 with NoteOff / CC on port 1
        parser0.parse(byteArrayOf(0x90.toByte(), 60), 0, 2)
        parser1.parse(byteArrayOf(0xB0.toByte(), 49, 100), 0, 3)
        parser0.parse(byteArrayOf(112, 64, 98), 0, 3) // completes NoteOn 60 vel 112, then running status NoteOn 64 vel 98
        parser1.parse(byteArrayOf(0x80.toByte(), 60, 0), 0, 3)

        assertEquals(
            listOf(Triple(0x90, 60, 112), Triple(0x90, 64, 98)),
            port0Msgs
        )
        assertEquals(
            listOf(Triple(0xB0, 49, 100), Triple(0x80, 60, 0)),
            port1Msgs
        )
    }

    @Test
    fun `parses CC 123 as AllNotesOff`() = runBlocking {
        val controller = createController()
        val collected = mutableListOf<MidiHardwareEvent>()
        val job = launch {
            controller.events.take(1).toList(collected)
        }
        kotlinx.coroutines.yield()

        controller.parseMidiPacket(byteArrayOf(0xB0.toByte(), 123, 0), 0, 3)
        job.join()

        assertEquals(listOf(MidiHardwareEvent.AllNotesOff), collected)
    }

    @Test
    fun `parses CC 42 through 47 without filtering`() = runBlocking {
        val controller = createController()
        val collected = mutableListOf<MidiHardwareEvent>()
        val job = launch {
            controller.events.take(6).toList(collected)
        }
        kotlinx.coroutines.yield()

        val packet = byteArrayOf(
            0xB0.toByte(), 42, 127,
            0xB0.toByte(), 43, 64,
            0xB0.toByte(), 44, 100,
            0xB0.toByte(), 45, 50,
            0xB0.toByte(), 46, 10,
            0xB0.toByte(), 47, 127
        )
        controller.parseMidiPacket(packet, 0, packet.size)
        job.join()

        assertEquals(
            listOf(42, 43, 44, 45, 46, 47),
            collected.map { (it as MidiHardwareEvent.ControlChange).cc }
        )
    }

    @Test
    fun `chord hold cancels held note immediately when pressing same MIDI key again`() {
        var nowMs = 1000L
        val tracker = MidiNoteStateTracker(clockMs = { nowMs })

        // Hold C4, E4, G4
        assertEquals(setOf(60), tracker.onExternalNoteOn(60, chordHoldEnabled = true))
        assertEquals(setOf(60, 64), tracker.onExternalNoteOn(64, chordHoldEnabled = true))
        assertEquals(setOf(60, 64, 67), tracker.onExternalNoteOn(67, chordHoldEnabled = true))

        // Release all three keys -> remain held
        assertEquals(setOf(60, 64, 67), tracker.onExternalNoteOff(60, chordHoldEnabled = true, sustainPedalDown = false))
        assertEquals(setOf(60, 64, 67), tracker.onExternalNoteOff(64, chordHoldEnabled = true, sustainPedalDown = false))
        assertEquals(setOf(60, 64, 67), tracker.onExternalNoteOff(67, chordHoldEnabled = true, sustainPedalDown = false))

        // Press E4 again -> cancels ONLY E4 immediately
        nowMs += 100L
        assertEquals(setOf(60, 67), tracker.onExternalNoteOn(64, chordHoldEnabled = true))

        // Duplicate NoteOn while finger is still down is ignored (does not re-add E4)
        nowMs += 5L
        assertNull(tracker.onExternalNoteOn(64, chordHoldEnabled = true))
        assertEquals(setOf(60, 67), tracker.activeMidis())

        // Releasing E4 (even twice from duplicate port) must NOT resurrect E4
        assertEquals(setOf(60, 67), tracker.onExternalNoteOff(64, chordHoldEnabled = true, sustainPedalDown = false))
        assertEquals(setOf(60, 67), tracker.onExternalNoteOff(64, chordHoldEnabled = true, sustainPedalDown = false))
    }

    @Test
    fun `stray NoteOff and rapid key bounce never leave stuck notes`() {
        var nowMs = 1000L
        val tracker = MidiNoteStateTracker(clockMs = { nowMs })

        // Stray NoteOff for unpressed key while Chord Hold is ON must NOT turn it on
        assertEquals(emptySet<Int>(), tracker.onExternalNoteOff(72, chordHoldEnabled = true, sustainPedalDown = true))

        // Rapid playing with Hold OFF
        assertEquals(setOf(60), tracker.onExternalNoteOn(60, chordHoldEnabled = false))
        nowMs += 3L
        // Duplicate NoteOn while still pressed is ignored
        assertNull(tracker.onExternalNoteOn(60, chordHoldEnabled = false))
        // Release + mechanical bounce 5ms later is ignored
        assertEquals(emptySet<Int>(), tracker.onExternalNoteOff(60, chordHoldEnabled = false, sustainPedalDown = false))
        nowMs += 5L
        assertNull(tracker.onExternalNoteOn(60, chordHoldEnabled = false))
        assertEquals(emptySet<Int>(), tracker.activeMidis())

        // Clearing while a key is held, then releasing it later must not resurrect it
        nowMs += 50L
        assertEquals(setOf(62), tracker.onExternalNoteOn(62, chordHoldEnabled = false))
        assertEquals(emptySet<Int>(), tracker.clearAll())
        assertEquals(emptySet<Int>(), tracker.onExternalNoteOff(62, chordHoldEnabled = false, sustainPedalDown = true))
    }

    @Test
    fun `default SMK-37 PRO CC mappings are unique and match requested layout`() {
        val assignedCcs = MidiControlTarget.entries.map { it.defaultCc }.filter { it >= 0 }
        assertEquals(assignedCcs.size, assignedCcs.toSet().size)
        assertEquals(49, MidiControlTarget.TONE_VOLUME.defaultCc)
        assertEquals(48, MidiControlTarget.BPM.defaultCc)
        assertEquals(1, MidiControlTarget.ACCENT_LEVEL.defaultCc)
        assertEquals(64, MidiControlTarget.QUARTER_LEVEL.defaultCc)
        assertEquals(65, MidiControlTarget.EIGHTH_LEVEL.defaultCc)
        assertEquals(66, MidiControlTarget.TRIPLET_LEVEL.defaultCc)
        assertEquals(67, MidiControlTarget.SIXTEENTH_LEVEL.defaultCc)
        assertEquals(36, MidiControlTarget.TAP_TEMPO.defaultCc)
        assertEquals(37, MidiControlTarget.CLEAR_HELD.defaultCc)
        assertEquals(40, MidiControlTarget.TOGGLE_METRONOME.defaultCc)
        assertEquals(41, MidiControlTarget.TOGGLE_CHORD_HOLD.defaultCc)
        assertEquals(42, MidiControlTarget.TOGGLE_TEMPERAMENT.defaultCc)
        assertEquals(47, MidiControlTarget.MUTE_ALL.defaultCc)
        // Meter, Metronome Vol, and Voice/Click cycle MIDI Learn bindings are unassigned by default
        assertEquals(-1, MidiControlTarget.METER_TOP_VALUE.defaultCc)
        assertEquals(-1, MidiControlTarget.METER_BOTTOM_VALUE.defaultCc)
        assertEquals(-1, MidiControlTarget.METER_TOP_STEP.defaultCc)
        assertEquals(-1, MidiControlTarget.METER_BOTTOM_STEP.defaultCc)
        assertEquals(-1, MidiControlTarget.METRONOME_VOLUME.defaultCc)
        assertEquals(-1, MidiControlTarget.NEXT_TONE_VOICE.defaultCc)
        assertEquals(-1, MidiControlTarget.NEXT_METRONOME_SOUND.defaultCc)
        assertTrue(assignedCcs.all { it in 0..127 })
    }

    @Test
    fun `unassigned metronome volume and voice-cycle MIDI targets work once learned`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = LocalFallbackPresetRepository(context)
        val engine = HarmonyAudioEngine()
        try {
            val vm = ConsoleViewModel(
                repository = repo,
                audioEngine = engine,
                currentUserId = "test-user"
            )

            // Unassigned CCs must not alter state before MIDI Learn
            val initialMetroGain = vm.metronomeState.value.metronomeGain
            val initialVoice = vm.harmonyState.value.voice
            val initialSound = vm.metronomeState.value.metronomeSound
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(70, 127, 0))
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(71, 127, 0))
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 127, 0))
            assertEquals(initialMetroGain, vm.metronomeState.value.metronomeGain, 1e-4f)
            assertEquals(initialVoice, vm.harmonyState.value.voice)
            assertEquals(initialSound, vm.metronomeState.value.metronomeSound)

            // Learn & control Metronome Master Volume on CC 70
            vm.startMidiLearn(MidiControlTarget.METRONOME_VOLUME)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(70, 64, 0))
            assertEquals(70, vm.midiMappings.value[MidiControlTarget.METRONOME_VOLUME])
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(70, 127, 0))
            assertEquals(1.0f, vm.metronomeState.value.metronomeGain, 1e-4f)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(70, 0, 0))
            assertEquals(0.0f, vm.metronomeState.value.metronomeGain, 1e-4f)

            // Learn & trigger Harmony Voice cycle on CC 71 (rising edge only)
            vm.startMidiLearn(MidiControlTarget.NEXT_TONE_VOICE)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(71, 0, 0))
            assertEquals(71, vm.midiMappings.value[MidiControlTarget.NEXT_TONE_VOICE])
            vm.selectVoice(ToneVoice.ORGAN)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(71, 127, 0))
            assertEquals(ToneVoice.BRASS, vm.harmonyState.value.voice)
            // Repeated high value without release must not double-trigger
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(71, 127, 0))
            assertEquals(ToneVoice.BRASS, vm.harmonyState.value.voice)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(71, 0, 0))
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(71, 127, 0))
            assertEquals(ToneVoice.WOODWIND, vm.harmonyState.value.voice)

            // Learn & trigger Metronome Sound cycle on CC 72 (wraps around all 4 sounds)
            vm.startMidiLearn(MidiControlTarget.NEXT_METRONOME_SOUND)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 0, 0))
            assertEquals(72, vm.midiMappings.value[MidiControlTarget.NEXT_METRONOME_SOUND])
            vm.selectMetronomeSound(MetronomeSound.WOODBLOCK)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 127, 0))
            assertEquals(MetronomeSound.CLICK, vm.metronomeState.value.metronomeSound)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 0, 0))
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 127, 0))
            assertEquals(MetronomeSound.BELL, vm.metronomeState.value.metronomeSound)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 0, 0))
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 127, 0))
            assertEquals(MetronomeSound.RIMSHOT, vm.metronomeState.value.metronomeSound)
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 0, 0))
            vm.handleMidiHardwareEvent(MidiHardwareEvent.ControlChange(72, 127, 0))
            assertEquals(MetronomeSound.WOODBLOCK, vm.metronomeState.value.metronomeSound)
        } finally {
            engine.release()
        }
    }

    @Test
    fun `custom TimeSignature enforces 1 to 16 numerator and locked 1 2 4 8 16 denominator`() {
        assertEquals(listOf(1, 2, 4, 8, 16), TimeSignature.ALLOWED_DENOMINATORS)

        // Clamping numerator and snapping invalid denominator
        assertEquals(TimeSignature(1, 4), TimeSignature.create(0, 3))
        assertEquals(TimeSignature(16, 16), TimeSignature.create(24, 16))
        assertEquals(TimeSignature(7, 8), TimeSignature.create(7, 8))

        // Storage round-trip & legacy compatibility
        assertEquals("7/8", TimeSignature.create(7, 8).toStorageString())
        assertEquals(TimeSignature(7, 8), TimeSignature.fromStorageString("7/8"))
        assertEquals(TimeSignature(16, 16), TimeSignature.fromStorageString("16/16"))
        assertEquals(TimeSignature(1, 1), TimeSignature.fromStorageString("1/1"))
        assertEquals(TimeSignature(6, 8), TimeSignature.fromStorageString("SIX_EIGHT"))
        assertEquals(TimeSignature(12, 8), TimeSignature.fromStorageString("TWELVE_EIGHT"))
        assertEquals(TimeSignature.FOUR_FOUR, TimeSignature.fromStorageString("4/3"))
        assertEquals(TimeSignature.FOUR_FOUR, TimeSignature.fromStorageString("invalid"))

        // Compound meter check (6/8, 9/8, 12/8, 15/8)
        assertTrue(TimeSignature(6, 8).isCompound)
        assertTrue(TimeSignature(12, 8).isCompound)
        assertTrue(!TimeSignature(3, 8).isCompound)
        assertTrue(!TimeSignature(7, 8).isCompound)
        assertTrue(!TimeSignature(6, 4).isCompound)
    }
}
