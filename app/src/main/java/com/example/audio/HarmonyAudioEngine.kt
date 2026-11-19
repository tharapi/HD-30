package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.example.model.MetronomeSound
import com.example.model.SubdivisionMix
import com.example.model.TimeSignature
import com.example.model.ToneVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

data class MetronomeBeatSnapshot(
    val beatIndex: Int = 0,
    val totalBeats: Int = 4,
    val isMutedBar: Boolean = false,
    val isPlaying: Boolean = false,
    val tickId: Long = 0L
)

private enum class HitKind {
    ACCENT,
    QUARTER,
    SECONDARY_COMPOUND,
    EIGHTH,
    TRIPLET,
    SIXTEENTH
}

private class ActiveVoice(
    val writtenMidi: Int,
    @Volatile var frequencyHz: Double,
    @Volatile var releasing: Boolean = false,
    var phase: Double = 0.0,
    var phaseDetune: Double = 0.0,
    var env: Double = 0.0,
    var ageSamples: Long = 0L
)

private class PercussionVoice(
    var active: Boolean = false,
    var ageSamples: Int = 0,
    var totalSamples: Int = 0,
    var phase: Double = 0.0,
    var phaseOver: Double = 0.0,
    var freqStart: Double = 880.0,
    var freqEnd: Double = 600.0,
    var gain: Double = 0.0,
    var sound: MetronomeSound = MetronomeSound.WOODBLOCK,
    var isAccent: Boolean = false
)

class HarmonyAudioEngine {
    companion object {
        const val SAMPLE_RATE = 44100
        const val FRAME_SAMPLES = 512
        private const val TWO_PI = 2.0 * PI
        private const val TONE_BUS_KNEE = 0.38
        private const val TONE_BUS_CEILING = 0.48
        private const val METRO_BUS_KNEE = 0.30
        private const val METRO_BUS_CEILING = 0.38
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var renderJob: Job? = null
    private val stateLock = Any()

    // Polyphonic synth state
    private val voices = mutableListOf<ActiveVoice>()
    @Volatile private var currentToneVoice: ToneVoice = ToneVoice.ORGAN
    @Volatile private var masterToneGain: Double = 0.72
    private var smoothedPolyNorm: Double = 0.36

    // Metronome state
    @Volatile private var metronomePlaying: Boolean = false
    @Volatile private var bpm: Int = 120
    @Volatile private var timeSignature: TimeSignature = TimeSignature.FOUR_FOUR
    @Volatile private var metronomeSound: MetronomeSound = MetronomeSound.WOODBLOCK
    @Volatile private var subdivisionMix: SubdivisionMix = SubdivisionMix()
    @Volatile private var alternateMuteBar: Boolean = false
    @Volatile private var masterMetronomeGain: Double = 0.75

    // Beat clock state inside audio thread
    private var samplePosInBeat: Double = 0.0
    private var currentBeatInBar: Int = 0
    private var barCounter: Int = 0
    private var firedEighth: Boolean = false
    private var firedTrip1: Boolean = false
    private var firedTrip2: Boolean = false
    private var firedSixteenth1: Boolean = false
    private var firedSixteenth3: Boolean = false
    private var tickSeq: Long = 0L

    private val percPool = Array(12) { PercussionVoice() }
    private var percPoolCursor = 0

    private val _beatSnapshot = MutableStateFlow(MetronomeBeatSnapshot())
    val beatSnapshot: StateFlow<MetronomeBeatSnapshot> = _beatSnapshot.asStateFlow()

    fun startEngine() {
        if (renderJob?.isActive == true) return
        renderJob = scope.launch {
            val minBufBytes = try {
                AudioTrack.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                ).coerceAtLeast(FRAME_SAMPLES * 4)
            } catch (_: Throwable) {
                FRAME_SAMPLES * 4
            }

            val audioTrack: AudioTrack? = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(minBufBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build()
            } catch (_: Throwable) {
                null
            }

            try {
                audioTrack?.play()
                val pcmBuffer = ShortArray(FRAME_SAMPLES)
                while (isActive) {
                    renderFrame(pcmBuffer)
                    audioTrack?.write(pcmBuffer, 0, pcmBuffer.size)
                }
            } finally {
                try {
                    audioTrack?.pause()
                    audioTrack?.flush()
                    audioTrack?.release()
                } catch (_: Throwable) {
                }
            }
        }
    }

    fun updateActiveNotes(noteFrequencies: Map<Int, Double>) {
        synchronized(stateLock) {
            // Mark existing voices not in map for release
            for (voice in voices) {
                val updatedFreq = noteFrequencies[voice.writtenMidi]
                if (updatedFreq != null) {
                    voice.frequencyHz = updatedFreq
                    voice.releasing = false
                } else {
                    voice.releasing = true
                }
            }
            // Add newly pressed voices
            for ((midi, freq) in noteFrequencies) {
                if (voices.none { it.writtenMidi == midi && !it.releasing }) {
                    // Reuse releasing voice if same midi or add fresh
                    val existingReleasing = voices.firstOrNull { it.writtenMidi == midi }
                    if (existingReleasing != null) {
                        existingReleasing.frequencyHz = freq
                        existingReleasing.releasing = false
                        existingReleasing.ageSamples = 0L
                    } else {
                        if (voices.size >= 16) {
                            val quietestReleasing = voices
                                .filter { it.releasing }
                                .minByOrNull { it.env }
                            if (quietestReleasing != null) {
                                voices.remove(quietestReleasing)
                            }
                        }
                        if (voices.size < 16) {
                            voices.add(ActiveVoice(writtenMidi = midi, frequencyHz = freq))
                        }
                    }
                }
            }
        }
    }

    fun setToneVoice(voice: ToneVoice) {
        currentToneVoice = voice
    }

    fun setToneGain(gain: Float) {
        masterToneGain = gain.toDouble().coerceIn(0.0, 1.0)
    }

    fun setMetronomeGain(gain: Float) {
        masterMetronomeGain = gain.toDouble().coerceIn(0.0, 1.0)
    }

    fun setMetronomeConfig(
        bpm: Int,
        timeSignature: TimeSignature,
        sound: MetronomeSound,
        mix: SubdivisionMix,
        alternateMuteBar: Boolean
    ) {
        val tsChanged = this.timeSignature != timeSignature
        this.bpm = bpm.coerceIn(32, 280)
        this.timeSignature = timeSignature
        this.metronomeSound = sound
        this.subdivisionMix = mix
        this.alternateMuteBar = alternateMuteBar
        if (tsChanged) {
            synchronized(stateLock) {
                currentBeatInBar = currentBeatInBar.coerceAtMost(timeSignature.beatsPerMeasure - 1)
            }
        }
    }

    fun setMetronomePlaying(playing: Boolean) {
        synchronized(stateLock) {
            if (playing && !metronomePlaying) {
                samplePosInBeat = 0.0
                currentBeatInBar = 0
                barCounter = 0
                firedEighth = false
                firedTrip1 = false
                firedTrip2 = false
                firedSixteenth1 = false
                firedSixteenth3 = false
                triggerBeatBoundary(isFirstStart = true)
            } else if (!playing) {
                _beatSnapshot.value = MetronomeBeatSnapshot(
                    beatIndex = 0,
                    totalBeats = timeSignature.beatsPerMeasure,
                    isMutedBar = false,
                    isPlaying = false,
                    tickId = ++tickSeq
                )
            }
            metronomePlaying = playing
        }
    }

    fun stopAllSound() {
        synchronized(stateLock) {
            for (voice in voices) {
                voice.releasing = true
            }
            metronomePlaying = false
            _beatSnapshot.value = MetronomeBeatSnapshot(
                beatIndex = 0,
                totalBeats = timeSignature.beatsPerMeasure,
                isMutedBar = false,
                isPlaying = false,
                tickId = ++tickSeq
            )
        }
    }

    fun release() {
        renderJob?.cancel()
        scope.cancel()
    }

    internal fun renderFrame(out: ShortArray) {
        val toneVoice = currentToneVoice
        val toneGain = masterToneGain
        val metroGain = masterMetronomeGain
        val isMetroRunning = metronomePlaying
        val currentBpm = bpm
        val samplesPerBeat = (SAMPLE_RATE * 60.0) / currentBpm.toDouble()

        synchronized(stateLock) {
            for (i in out.indices) {
                // Count all sounding voices (including releasing) so releasing a chord never jumps gain upward
                val soundingCount = voices.size.coerceAtLeast(1)
                val targetPolyNorm = 0.36 / kotlin.math.sqrt(soundingCount.toDouble())
                val normAlpha = if (targetPolyNorm < smoothedPolyNorm) 0.015 else 0.0015
                smoothedPolyNorm += (targetPolyNorm - smoothedPolyNorm) * normAlpha

                // 1. Render Isolated Tone Bus
                var toneSample = 0.0
                var idx = voices.size - 1
                while (idx >= 0) {
                    val v = voices[idx]
                    val sampleVal = synthesizeVoiceSample(v, toneVoice)
                    toneSample += sampleVal * v.env * smoothedPolyNorm * toneGain

                    if (v.releasing && v.env <= 0.0005) {
                        voices.removeAt(idx)
                    }
                    idx--
                }

                // 2. Advance Sample-Accurate Metronome Clock
                if (isMetroRunning) {
                    samplePosInBeat += 1.0
                    val progress = samplePosInBeat / samplesPerBeat

                    if (progress >= 1.0) {
                        samplePosInBeat -= samplesPerBeat
                        firedEighth = false
                        firedTrip1 = false
                        firedTrip2 = false
                        firedSixteenth1 = false
                        firedSixteenth3 = false

                        currentBeatInBar++
                        if (currentBeatInBar >= timeSignature.beatsPerMeasure) {
                            currentBeatInBar = 0
                            barCounter++
                        }
                        triggerBeatBoundary(isFirstStart = false)
                    } else {
                        val isMuted = alternateMuteBar && (barCounter % 2 == 1)
                        if (!isMuted) {
                            val mix = subdivisionMix
                            if (!firedSixteenth1 && progress >= 0.25) {
                                firedSixteenth1 = true
                                if (mix.sixteenth > 0.01f) {
                                    spawnPercussion(HitKind.SIXTEENTH, mix.sixteenth.toDouble())
                                }
                            }
                            if (!firedTrip1 && progress >= (1.0 / 3.0)) {
                                firedTrip1 = true
                                if (mix.triplet > 0.01f) {
                                    spawnPercussion(HitKind.TRIPLET, mix.triplet.toDouble())
                                }
                            }
                            if (!firedEighth && progress >= 0.5) {
                                firedEighth = true
                                if (mix.eighth > 0.01f) {
                                    spawnPercussion(
                                        HitKind.EIGHTH,
                                        maxOf(mix.eighth, mix.sixteenth).toDouble()
                                    )
                                } else if (mix.sixteenth > 0.01f) {
                                    spawnPercussion(HitKind.SIXTEENTH, mix.sixteenth.toDouble())
                                }
                            }
                            if (!firedTrip2 && progress >= (2.0 / 3.0)) {
                                firedTrip2 = true
                                if (mix.triplet > 0.01f) {
                                    spawnPercussion(HitKind.TRIPLET, mix.triplet.toDouble())
                                }
                            }
                            if (!firedSixteenth3 && progress >= 0.75) {
                                firedSixteenth3 = true
                                if (mix.sixteenth > 0.01f) {
                                    spawnPercussion(HitKind.SIXTEENTH, mix.sixteenth.toDouble())
                                }
                            }
                        }
                    }
                }

                // 3. Render Isolated Metronome Bus
                var metroSample = 0.0
                for (p in percPool) {
                    if (p.active) {
                        metroSample += synthesizePercussionSample(p) * metroGain
                    }
                }

                // 4. Independent Bus Soft-Knee Limiting + Strictly Linear Summing
                // Prevents metronome transients from cross-modulating sustained reference tones
                val toneLimited = softLimitBus(toneSample, TONE_BUS_KNEE, TONE_BUS_CEILING)
                val metroLimited = softLimitBus(metroSample, METRO_BUS_KNEE, METRO_BUS_CEILING)
                val combined = toneLimited + metroLimited
                out[i] = round(combined * 32767.0)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
    }

    private fun softLimitBus(x: Double, knee: Double, ceiling: Double): Double {
        val absX = abs(x)
        if (absX <= knee) return x
        val headroom = ceiling - knee
        val compressed = knee + headroom * tanh((absX - knee) / headroom)
        return if (x >= 0.0) compressed else -compressed
    }

    private fun triggerBeatBoundary(isFirstStart: Boolean) {
        val ts = timeSignature
        val mix = subdivisionMix
        val isMuted = alternateMuteBar && (barCounter % 2 == 1)

        if (!isMuted) {
            val maxSub = maxOf(mix.eighth, mix.triplet, mix.sixteenth)
            when {
                currentBeatInBar == 0 && mix.accent > 0.01f -> {
                    spawnPercussion(HitKind.ACCENT, mix.accent.toDouble())
                }
                ts.isCompound && currentBeatInBar % 3 != 0 && mix.quarter > 0.01f -> {
                    spawnPercussion(HitKind.SECONDARY_COMPOUND, mix.quarter.toDouble() * 0.65)
                }
                mix.quarter > 0.01f -> {
                    spawnPercussion(HitKind.QUARTER, maxOf(mix.quarter, maxSub).toDouble())
                }
                mix.eighth > 0.01f -> {
                    spawnPercussion(HitKind.EIGHTH, maxOf(mix.eighth, mix.sixteenth).toDouble())
                }
                mix.triplet > 0.01f -> {
                    spawnPercussion(HitKind.TRIPLET, mix.triplet.toDouble())
                }
                mix.sixteenth > 0.01f -> {
                    spawnPercussion(HitKind.SIXTEENTH, mix.sixteenth.toDouble())
                }
            }
        }

        _beatSnapshot.value = MetronomeBeatSnapshot(
            beatIndex = currentBeatInBar,
            totalBeats = ts.beatsPerMeasure,
            isMutedBar = isMuted,
            isPlaying = true,
            tickId = ++tickSeq
        )
    }

    private fun spawnPercussion(kind: HitKind, level: Double) {
        val slot = percPool[percPoolCursor]
        percPoolCursor = (percPoolCursor + 1) % percPool.size

        val sound = metronomeSound
        val (fStart, fEnd, durSamples, accentFlag) = when (sound) {
            MetronomeSound.WOODBLOCK -> when (kind) {
                HitKind.ACCENT -> Quad(1280.0, 960.0, 1400, true)
                HitKind.QUARTER -> Quad(880.0, 680.0, 1200, false)
                HitKind.SECONDARY_COMPOUND -> Quad(760.0, 580.0, 950, false)
                HitKind.EIGHTH -> Quad(1040.0, 820.0, 850, false)
                HitKind.TRIPLET -> Quad(960.0, 760.0, 750, false)
                HitKind.SIXTEENTH -> Quad(1140.0, 900.0, 650, false)
            }
            MetronomeSound.CLICK -> when (kind) {
                HitKind.ACCENT -> Quad(2400.0, 1800.0, 650, true)
                HitKind.QUARTER -> Quad(1650.0, 1200.0, 520, false)
                HitKind.SECONDARY_COMPOUND -> Quad(1400.0, 1050.0, 450, false)
                HitKind.EIGHTH -> Quad(1950.0, 1500.0, 400, false)
                HitKind.TRIPLET -> Quad(1800.0, 1400.0, 380, false)
                HitKind.SIXTEENTH -> Quad(2150.0, 1650.0, 340, false)
            }
            MetronomeSound.BELL -> when (kind) {
                HitKind.ACCENT -> Quad(1568.0, 1568.0, 4800, true)
                HitKind.QUARTER -> Quad(1174.0, 1174.0, 2600, false)
                HitKind.SECONDARY_COMPOUND -> Quad(988.0, 988.0, 1800, false)
                HitKind.EIGHTH -> Quad(1318.0, 1318.0, 1400, false)
                HitKind.TRIPLET -> Quad(1245.0, 1245.0, 1200, false)
                HitKind.SIXTEENTH -> Quad(1480.0, 1480.0, 1000, false)
            }
            MetronomeSound.RIMSHOT -> when (kind) {
                HitKind.ACCENT -> Quad(460.0, 240.0, 1100, true)
                HitKind.QUARTER -> Quad(380.0, 200.0, 850, false)
                HitKind.SECONDARY_COMPOUND -> Quad(340.0, 190.0, 700, false)
                HitKind.EIGHTH -> Quad(540.0, 320.0, 600, false)
                HitKind.TRIPLET -> Quad(500.0, 300.0, 550, false)
                HitKind.SIXTEENTH -> Quad(620.0, 380.0, 500, false)
            }
        }

        slot.active = true
        slot.ageSamples = 0
        slot.totalSamples = durSamples
        slot.phase = 0.0
        slot.phaseOver = 0.0
        slot.freqStart = fStart
        slot.freqEnd = fEnd
        slot.gain = (level * if (accentFlag) 0.72 else 0.56).coerceIn(0.0, 0.9)
        slot.sound = sound
        slot.isAccent = accentFlag
    }

    private fun synthesizeVoiceSample(v: ActiveVoice, timbre: ToneVoice): Double {
        val inc = TWO_PI * v.frequencyHz / SAMPLE_RATE
        v.phase = (v.phase + inc) % TWO_PI
        v.ageSamples++

        // Envelope update
        val attackRate = when (timbre) {
            ToneVoice.STRINGS -> 0.0008
            ToneVoice.WOODWIND -> 0.0022
            ToneVoice.PIANO -> 0.012
            else -> 0.0035
        }
        val releaseRate = 0.0025

        if (v.releasing) {
            v.env = (v.env - releaseRate).coerceAtLeast(0.0)
        } else if (timbre == ToneVoice.PIANO) {
            if (v.ageSamples < 140) {
                v.env = (v.env + attackRate).coerceAtMost(1.0)
            } else {
                v.env *= 0.99992
            }
        } else {
            v.env = (v.env + attackRate).coerceAtMost(1.0)
        }

        val p = v.phase
        return when (timbre) {
            ToneVoice.PURE_SINE -> sin(p)
            ToneVoice.ORGAN -> {
                0.56 * sin(p) +
                    0.28 * sin(2.0 * p) +
                    0.16 * sin(3.0 * p) +
                    0.12 * sin(4.0 * p) +
                    0.05 * sin(6.0 * p)
            }
            ToneVoice.BRASS -> {
                val ageSec = v.ageSamples.toDouble() / SAMPLE_RATE
                val bloom = (ageSec / 0.11).coerceIn(0.25, 1.0)
                0.52 * sin(p) +
                    0.30 * bloom * sin(2.0 * p) +
                    0.18 * bloom * sin(3.0 * p) +
                    0.11 * bloom * sin(4.0 * p) +
                    0.06 * bloom * sin(5.0 * p)
            }
            ToneVoice.WOODWIND -> {
                0.68 * sin(p) +
                    0.26 * sin(3.0 * p) +
                    0.09 * sin(5.0 * p) +
                    0.03 * sin(7.0 * p)
            }
            ToneVoice.STRINGS -> {
                val detuneInc = TWO_PI * (v.frequencyHz * 1.0028) / SAMPLE_RATE
                v.phaseDetune = (v.phaseDetune + detuneInc) % TWO_PI
                val p2 = v.phaseDetune
                val osc1 = 0.52 * sin(p) + 0.24 * sin(2.0 * p) + 0.12 * sin(3.0 * p) + 0.06 * sin(4.0 * p)
                val osc2 = 0.52 * sin(p2) + 0.24 * sin(2.0 * p2) + 0.12 * sin(3.0 * p2) + 0.06 * sin(4.0 * p2)
                0.55 * (osc1 + osc2)
            }
            ToneVoice.PIANO -> {
                val ageSec = v.ageSamples.toDouble() / SAMPLE_RATE
                val overDecay = exp(-ageSec * 3.8)
                0.64 * sin(p) +
                    0.28 * overDecay * sin(2.0 * p) +
                    0.14 * overDecay * sin(3.0 * p) +
                    0.07 * overDecay * sin(4.0 * p)
            }
        }
    }

    private fun synthesizePercussionSample(p: PercussionVoice): Double {
        if (p.ageSamples >= p.totalSamples) {
            p.active = false
            return 0.0
        }
        val t = p.ageSamples.toDouble() / p.totalSamples.toDouble()
        val freq = p.freqStart + (p.freqEnd - p.freqStart) * t
        p.phase = (p.phase + TWO_PI * freq / SAMPLE_RATE) % TWO_PI

        val env = exp(-t * when (p.sound) {
            MetronomeSound.BELL -> 4.5
            MetronomeSound.CLICK -> 6.5
            else -> 5.5
        })

        val wave = when (p.sound) {
            MetronomeSound.WOODBLOCK -> {
                sin(p.phase) + 0.35 * sin(2.42 * p.phase) * exp(-t * 9.0)
            }
            MetronomeSound.CLICK -> {
                sin(p.phase) + 0.25 * sin(2.0 * p.phase)
            }
            MetronomeSound.BELL -> {
                p.phaseOver = (p.phaseOver + TWO_PI * (freq * 2.756) / SAMPLE_RATE) % TWO_PI
                0.68 * sin(p.phase) + 0.32 * sin(p.phaseOver) * exp(-t * 6.0)
            }
            MetronomeSound.RIMSHOT -> {
                val noise = (Random.nextDouble() * 2.0 - 1.0) * exp(-t * 14.0)
                0.65 * sin(p.phase) + 0.45 * noise
            }
        }

        p.ageSamples++
        return wave * env * p.gain
    }

    private data class Quad(
        val fStart: Double,
        val fEnd: Double,
        val durSamples: Int,
        val isAccent: Boolean
    )
}
