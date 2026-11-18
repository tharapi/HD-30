package com.example

import com.example.model.PitchTheory
import com.example.model.TemperamentMode
import com.example.model.TranspositionKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PitchTheoryUnitTest {
    @Test
    fun equalTemperamentA4MatchesReferencePitch() {
        val info442 = PitchTheory.calculatePitchInfo(
            writtenMidi = 69,
            transposition = TranspositionKey.CONCERT_C,
            referenceA4Hz = 442.0,
            temperament = TemperamentMode.EQUAL,
            rootPitchClass = 0
        )
        assertEquals(442.0, info442.frequencyHz, 0.001)
        assertEquals(0.0, info442.centsOffsetFromEt, 0.001)
    }

    @Test
    fun pureJustIntonationMajorThirdHasMinus13Point7CentsOffset() {
        // C4 (60) root, E4 (64) major 3rd
        val infoE4 = PitchTheory.calculatePitchInfo(
            writtenMidi = 64,
            transposition = TranspositionKey.CONCERT_C,
            referenceA4Hz = 440.0,
            temperament = TemperamentMode.PURE,
            rootPitchClass = 0 // C
        )
        assertEquals(-13.7, infoE4.centsOffsetFromEt, 0.05)
        assertEquals("M3", infoE4.intervalName)

        val rootC4 = PitchTheory.equalTemperamentFrequency(60, 440.0)
        assertTrue(abs(infoE4.frequencyHz - rootC4 * 1.25) < 0.001)
    }

    @Test
    fun transpositionBbSoundsWholeStepLower() {
        val info = PitchTheory.calculatePitchInfo(
            writtenMidi = 60, // Written C4
            transposition = TranspositionKey.BB,
            referenceA4Hz = 440.0,
            temperament = TemperamentMode.EQUAL,
            rootPitchClass = 10
        )
        assertEquals("C4", info.writtenName)
        assertEquals("Bb3", info.soundingName)
        assertEquals(58, info.soundingMidi)
    }

    @Test
    fun detectsTriadsAndIntervals() {
        assertEquals("C Maj", PitchTheory.detectChordOrNoteLabel(listOf(60, 64, 67)))
        assertEquals("A Min", PitchTheory.detectChordOrNoteLabel(listOf(57, 60, 64)))
        assertEquals("G 7", PitchTheory.detectChordOrNoteLabel(listOf(55, 59, 62, 65)))
    }
}
