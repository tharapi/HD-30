package com.example.model

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp
import kotlin.math.pow

enum class TemperamentMode(val label: String) {
    EQUAL("Equal"),
    PURE("Pure (Just)")
}

enum class TranspositionKey(
    val shortLabel: String,
    val fullLabel: String,
    val semitoneOffset: Int
) {
    CONCERT_C("C", "Concert C", 0),
    BB("Bb", "Bb (Clarinet / Trumpet)", -2),
    EB("Eb", "Eb (Alto Sax / Eb Horn)", 3),
    F("F", "F (French Horn / English Horn)", -7)
}

enum class ToneVoice(val label: String, val category: String) {
    PURE_SINE("Pure Sine", "Pitch"),
    ORGAN("Organ", "Harmony"),
    BRASS("Brass", "Orchestra"),
    WOODWIND("Woodwind", "Orchestra"),
    STRINGS("Strings", "Orchestra"),
    PIANO("Piano", "Keyboard")
}

data class TimeSignature(
    val numerator: Int = 4,
    val denominator: Int = 4
) {
    val label: String get() = "$numerator/$denominator"
    val beatsPerMeasure: Int get() = numerator.coerceIn(MIN_NUMERATOR, MAX_NUMERATOR)
    val isCompound: Boolean
        get() = (denominator == 8 || denominator == 16) && numerator >= 6 && numerator % 3 == 0

    fun toStorageString(): String = "$beatsPerMeasure/$denominator"

    companion object {
        const val MIN_NUMERATOR = 1
        const val MAX_NUMERATOR = 16
        val ALLOWED_DENOMINATORS = listOf(1, 2, 4, 8, 16)

        val FOUR_FOUR = TimeSignature(4, 4)

        fun create(numerator: Int, denominator: Int): TimeSignature {
            val safeNum = numerator.coerceIn(MIN_NUMERATOR, MAX_NUMERATOR)
            val safeDen = if (denominator in ALLOWED_DENOMINATORS) denominator else 4
            return TimeSignature(safeNum, safeDen)
        }

        fun fromStorageString(raw: String): TimeSignature {
            when (raw.trim().uppercase()) {
                "TWO_FOUR" -> return TimeSignature(2, 4)
                "THREE_FOUR" -> return TimeSignature(3, 4)
                "FOUR_FOUR" -> return TimeSignature(4, 4)
                "FIVE_FOUR" -> return TimeSignature(5, 4)
                "SIX_EIGHT" -> return TimeSignature(6, 8)
                "NINE_EIGHT" -> return TimeSignature(9, 8)
                "TWELVE_EIGHT" -> return TimeSignature(12, 8)
            }
            val parts = raw.trim().split("/")
            if (parts.size == 2) {
                val num = parts[0].toIntOrNull()
                val den = parts[1].toIntOrNull()
                if (num != null && den != null && num in MIN_NUMERATOR..MAX_NUMERATOR && den in ALLOWED_DENOMINATORS) {
                    return TimeSignature(num, den)
                }
            }
            return FOUR_FOUR
        }
    }
}

enum class MetronomeSound(val label: String) {
    WOODBLOCK("Woodblock"),
    CLICK("Digital Click"),
    BELL("Bell / Chime"),
    RIMSHOT("Rimshot")
}

data class SubdivisionMix(
    val accent: Float = 1.0f,
    val quarter: Float = 0.85f,
    val eighth: Float = 0.0f,
    val triplet: Float = 0.0f,
    val sixteenth: Float = 0.0f
)

data class NotePitchInfo(
    val writtenMidi: Int,
    val soundingMidi: Int,
    val writtenName: String,
    val soundingName: String,
    val frequencyHz: Double,
    val centsOffsetFromEt: Double,
    val intervalName: String
)

object PitchTheory {
    val NOTE_NAMES = listOf(
        "C", "C#", "D", "Eb", "E", "F", "F#", "G", "Ab", "A", "Bb", "B"
    )

    // Standard HD-300 Pure (Just Intonation) harmonic ratios relative to root
    private val JUST_RATIOS = doubleArrayOf(
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
    )

    private val JUST_CENTS_FROM_ET = doubleArrayOf(
        0.0, 11.7, 3.9, 15.6, -13.7, -2.0, -9.8, 2.0, 13.7, -15.6, 17.6, -11.7
    )

    private val INTERVAL_LABELS = listOf(
        "Root", "m2", "M2", "m3", "M3", "P4", "TT", "P5", "m6", "M6", "m7", "M7"
    )

    fun noteNameWithOctave(midiNote: Int): String {
        val pc = ((midiNote % 12) + 12) % 12
        val octave = (midiNote / 12) - 1
        return "${NOTE_NAMES[pc]}$octave"
    }

    fun pitchClassName(pitchClass: Int): String {
        val pc = ((pitchClass % 12) + 12) % 12
        return NOTE_NAMES[pc]
    }

    fun equalTemperamentFrequency(midiNote: Int, referenceA4Hz: Double): Double {
        return referenceA4Hz * 2.0.pow((midiNote - 69) / 12.0)
    }

    fun calculatePitchInfo(
        writtenMidi: Int,
        transposition: TranspositionKey,
        referenceA4Hz: Double,
        temperament: TemperamentMode,
        rootPitchClass: Int
    ): NotePitchInfo {
        val soundingMidi = (writtenMidi + transposition.semitoneOffset).coerceIn(21, 108)
        val soundingPc = ((soundingMidi % 12) + 12) % 12
        val interval = ((soundingPc - rootPitchClass) % 12 + 12) % 12

        val frequency: Double
        val centsOffset: Double
        if (temperament == TemperamentMode.EQUAL) {
            frequency = equalTemperamentFrequency(soundingMidi, referenceA4Hz)
            centsOffset = 0.0
        } else {
            // Find the root MIDI pitch closest at or below soundingMidi
            val rootMidi = soundingMidi - interval
            val rootFreq = equalTemperamentFrequency(rootMidi, referenceA4Hz)
            frequency = rootFreq * JUST_RATIOS[interval]
            centsOffset = JUST_CENTS_FROM_ET[interval]
        }

        return NotePitchInfo(
            writtenMidi = writtenMidi,
            soundingMidi = soundingMidi,
            writtenName = noteNameWithOctave(writtenMidi),
            soundingName = noteNameWithOctave(soundingMidi),
            frequencyHz = frequency,
            centsOffsetFromEt = centsOffset,
            intervalName = INTERVAL_LABELS[interval]
        )
    }

    fun detectChordOrNoteLabel(soundingMidis: Collection<Int>): String {
        if (soundingMidis.isEmpty()) return "--"
        val sorted = soundingMidis.sorted()
        if (sorted.size == 1) return noteNameWithOctave(sorted.first())

        val rootPc = ((sorted.first() % 12) + 12) % 12
        val intervals = sorted.map { ((it % 12 - rootPc) + 12) % 12 }.toSet()
        val rootName = NOTE_NAMES[rootPc]

        return when {
            intervals.containsAll(setOf(0, 4, 7, 10)) -> "$rootName 7"
            intervals.containsAll(setOf(0, 4, 7, 11)) -> "$rootName Maj7"
            intervals.containsAll(setOf(0, 3, 7, 10)) -> "$rootName m7"
            intervals.containsAll(setOf(0, 4, 7)) -> "$rootName Maj"
            intervals.containsAll(setOf(0, 3, 7)) -> "$rootName Min"
            intervals.containsAll(setOf(0, 3, 6)) -> "$rootName Dim"
            intervals.containsAll(setOf(0, 4, 8)) -> "$rootName Aug"
            intervals.containsAll(setOf(0, 5, 7)) -> "$rootName Sus4"
            intervals.containsAll(setOf(0, 2, 7)) -> "$rootName Sus2"
            intervals == setOf(0, 7) -> "$rootName P5"
            else -> sorted.joinToString(" ") { pitchClassName(it) }
        }
    }

    fun inferRootPitchClass(
        writtenMidis: Set<Int>,
        transposition: TranspositionKey,
        fallbackRoot: Int
    ): Int {
        val lowest = writtenMidis.minOrNull() ?: return fallbackRoot
        val sounding = (lowest + transposition.semitoneOffset).coerceIn(21, 108)
        return ((sounding % 12) + 12) % 12
    }
}

@Keep
@IgnoreExtraProperties
data class RehearsalPreset(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val notes: String = "",
    val bpm: Long = 120L,
    val timeSignature: String = TimeSignature.FOUR_FOUR.toStorageString(),
    val metronomeSound: String = MetronomeSound.WOODBLOCK.name,
    val accentLevel: Double = 1.0,
    val quarterLevel: Double = 0.85,
    val eighthLevel: Double = 0.0,
    val tripletLevel: Double = 0.0,
    val sixteenthLevel: Double = 0.0,
    val alternateMuteBar: Boolean = false,
    val referencePitchHz: Double = 440.0,
    val temperament: String = TemperamentMode.PURE.name,
    val rootKeyIndex: Long = 0L,
    val autoDetectRoot: Boolean = true,
    val transposition: String = TranspositionKey.CONCERT_C.name,
    val voice: String = ToneVoice.ORGAN.name,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
) {
    fun safeTimeSignature(): TimeSignature =
        TimeSignature.fromStorageString(timeSignature)

    fun safeMetronomeSound(): MetronomeSound =
        enumValues<MetronomeSound>().firstOrNull { it.name == metronomeSound } ?: MetronomeSound.WOODBLOCK

    fun safeTemperament(): TemperamentMode =
        enumValues<TemperamentMode>().firstOrNull { it.name == temperament } ?: TemperamentMode.PURE

    fun safeTransposition(): TranspositionKey =
        enumValues<TranspositionKey>().firstOrNull { it.name == transposition } ?: TranspositionKey.CONCERT_C

    fun safeVoice(): ToneVoice =
        enumValues<ToneVoice>().firstOrNull { it.name == voice } ?: ToneVoice.ORGAN
}
