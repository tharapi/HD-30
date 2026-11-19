package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import com.example.model.MetronomeSound
import com.example.model.SubdivisionMix
import com.example.model.TimeSignature
import com.example.model.ToneVoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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
    var prevNoise: Double = 0.0,
    var freqStart: Double = 880.0,
    var freqEnd: Double = 600.0,
    var gain: Double = 0.0,
    var sound: MetronomeSound = MetronomeSound.WOODBLOCK,
    var isAccent: Boolean = false
)

class HarmonyAudioEngine {
    companion object {
        internal const val SAMPLE_RATE = 44100
        internal const val FRAME_SAMPLES = 512
        private const val TWO_PI = 2.0 * PI

        // Dedicated per-bus headroom budgets for strictly linear master summing:
        // |toneLimited| <= 0.48 and |percLimited| <= 0.38 => |sum| <= 0.86 (< 1.0).
        // Because the two buses are summed purely linearly with guaranteed headroom,
        // metronome clicks can never duck, compress, or intermodulate sustained tones.
        private const val BASE_VOICE_NORM = 0.38
        private const val TONE_BUS_KNEE = 0.42
        private const val TONE_BUS_CEILING = 0.48
        private const val PERC_BUS_KNEE = 0.32
        private const val PERC_BUS_CEILING = 0.38
        private const val GAIN_SMOOTH_COEFF = 0.005
        private const val POLY_NORM_SMOOTH_COEFF = 0.0015
        private const val PERC_ATTACK_SAMPLES = 10

        internal fun softLimitBus(sample: Double, knee: Double, ceiling: Double): Double {
            val absVal = abs(sample)
            if (absVal <= knee) return sample
            val headroom = ceiling - knee
            val excess = (absVal - knee) / headroom
            val shaped = knee + headroom * tanh(excess)
            return if (sample >= 0.0) shaped else -shaped
        }
    }

    private val engineLock = Any()
    private val stateLock = Any()
    @Volatile private var engineRunning: Boolean = false
    private var renderThread: Thread? = null

    // Polyphonic synth state
    private val voices = mutableListOf<ActiveVoice>()
    @Volatile private var currentToneVoice: ToneVoice = ToneVoice.ORGAN
    @Volatile private var masterToneGain: Double = 0.80
    private var targetPolyNorm: Double = BASE_VOICE_NORM
    private var smoothedPolyNorm: Double = BASE_VOICE_NORM
    private var smoothedToneGain: Double = 0.80

    // Metronome state
    @Volatile private var metronomePlaying: Boolean = false
    @Volatile private var bpm: Int = 120
    @Volatile private var timeSignature: TimeSignature = TimeSignature.FOUR_FOUR
    @Volatile private var metronomeSound: MetronomeSound = MetronomeSound.WOODBLOCK
    @Volatile private var subdivisionMix: SubdivisionMix = SubdivisionMix()
    @Volatile private var alternateMuteBar: Boolean = false
    @Volatile private var masterMetronomeGain: Double = 0.75
    private var smoothedMetronomeGain: Double = 0.75

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
        synchronized(engineLock) {
            if (engineRunning && renderThread?.isAlive == true) return
            engineRunning = true
            val thread = Thread({ runAudioLoop() }, "HarmonyAudioEngine").apply {
                isDaemon = true
            }
            renderThread = thread
            thread.start()
        }
    }

    private fun runAudioLoop() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            // Safe fallback on non-Android JVM environments
        }

        val minBufBytes = try {
            AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 4)
        } catch (_: Throwable) {
            FRAME_SAMPLES * 4
        }

        // Provide steady buffer slack (~46-80 ms) to avoid beat-synced underruns
        // while keeping key-press response snappy.
        val bufferBytes = maxOf(
            minBufBytes,
            minOf(minBufBytes * 2, 6144),
            FRAME_SAMPLES * 8
        )

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
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        } catch (_: Throwable) {
            null
        }

        if (audioTrack == null || audioTrack.state != AudioTrack.STATE_INITIALIZED) {
            try {
                audioTrack?.release()
            } catch (_: Throwable) {
            }
            synchronized(engineLock) {
                engineRunning = false
                renderThread = null
            }
            return
        }

        try {
            audioTrack.play()
            val pcmBuffer = ShortArray(FRAME_SAMPLES)
            while (engineRunning && !Thread.currentThread().isInterrupted) {
                renderFrame(pcmBuffer)
                val written = audioTrack.write(
                    pcmBuffer,
                    0,
                    pcmBuffer.size,
                    AudioTrack.WRITE_BLOCKING
                )
                if (written < 0) break
            }
        } catch (_: Throwable) {
        } finally {
            try {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.release()
            } catch (_: Throwable) {
            }
            synchronized(engineLock) {
                if (renderThread === Thread.currentThread()) {
                    engineRunning = false
                    renderThread = null
                }
            }
        }
    }

    fun updateActiveNotes(noteFrequencies: Map<Int, Double>) {
        synchronized(stateLock) {
            val wasSilent = voices.isEmpty()

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
                    } else if (voices.size < 14) {
                        voices.add(ActiveVoice(writtenMidi = midi, frequencyHz = freq))
                    }
                }
            }

            val heldCount = voices.count { !it.releasing }
            if (heldCount > 0) {
                val nextNorm = BASE_VOICE_NORM / sqrt(heldCount.toDouble())
                targetPolyNorm = nextNorm
                if (wasSilent) {
                    smoothedPolyNorm = nextNorm
                }
            }
        }
    }

    fun setToneVoice(voice: ToneVoice) {
        currentToneVoice = voice
    }

    fun setToneGain(gain: Float) {
        val clamped = gain.toDouble().coerceIn(0.0, 1.0)
        masterToneGain = clamped
        synchronized(stateLock) {
            if (voices.isEmpty()) {
                smoothedToneGain = clamped
            }
        }
    }

    fun setMetronomeGain(gain: Float) {
        val clamped = gain.toDouble().coerceIn(0.0, 1.0)
        masterMetronomeGain = clamped
        synchronized(stateLock) {
            if (!metronomePlaying) {
                smoothedMetronomeGain = clamped
            }
        }
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
        var idleSnapshot: MetronomeBeatSnapshot? = null
        if (tsChanged) {
            synchronized(stateLock) {
                currentBeatInBar = currentBeatInBar.coerceAtMost(timeSignature.beatsPerMeasure - 1)
                if (!metronomePlaying) {
                    idleSnapshot = MetronomeBeatSnapshot(
                        beatIndex = 0,
                        totalBeats = timeSignature.beatsPerMeasure,
                        isMutedBar = false,
                        isPlaying = false,
                        tickId = ++tickSeq
                    )
                }
            }
        }
        idleSnapshot?.let { _beatSnapshot.value = it }
    }

    fun setMetronomePlaying(playing: Boolean) {
        val pendingSnapshot: MetronomeBeatSnapshot? = synchronized(stateLock) {
            var snapshot: MetronomeBeatSnapshot? = null
            if (playing && !metronomePlaying) {
                samplePosInBeat = 0.0
                currentBeatInBar = 0
                barCounter = 0
                firedEighth = false
                firedTrip1 = false
                firedTrip2 = false
                firedSixteenth1 = false
                firedSixteenth3 = false
                smoothedMetronomeGain = masterMetronomeGain
                snapshot = triggerBeatBoundary()
            } else if (!playing) {
                snapshot = MetronomeBeatSnapshot(
                    beatIndex = 0,
                    totalBeats = timeSignature.beatsPerMeasure,
                    isMutedBar = false,
                    isPlaying = false,
                    tickId = ++tickSeq
                )
            }
            metronomePlaying = playing
            snapshot
        }
        pendingSnapshot?.let { _beatSnapshot.value = it }
    }

    fun stopAllSound() {
        val stoppedSnapshot: MetronomeBeatSnapshot = synchronized(stateLock) {
            for (voice in voices) {
                voice.releasing = true
            }
            metronomePlaying = false
            MetronomeBeatSnapshot(
                beatIndex = 0,
                totalBeats = timeSignature.beatsPerMeasure,
                isMutedBar = false,
                isPlaying = false,
                tickId = ++tickSeq
            )
        }
        _beatSnapshot.value = stoppedSnapshot
    }

    fun release() {
        synchronized(engineLock) {
            engineRunning = false
            renderThread?.interrupt()
            renderThread = null
        }
    }

    internal fun renderFrame(out: ShortArray) {
        val toneVoice = currentToneVoice
        val targetToneGain = masterToneGain
        val targetMetroGain = masterMetronomeGain
        val isMetroRunning = metronomePlaying
        val currentBpm = bpm
        val samplesPerBeat = (SAMPLE_RATE * 60.0) / currentBpm.toDouble()

        val pendingSnapshot: MetronomeBeatSnapshot? = synchronized(stateLock) {
            var snapshot: MetronomeBeatSnapshot? = null
            val heldCount = voices.count { !it.releasing }
            if (heldCount > 0) {
                targetPolyNorm = BASE_VOICE_NORM / sqrt(heldCount.toDouble())
            }

            for (i in out.indices) {
                smoothedPolyNorm += (targetPolyNorm - smoothedPolyNorm) * POLY_NORM_SMOOTH_COEFF
                smoothedToneGain += (targetToneGain - smoothedToneGain) * GAIN_SMOOTH_COEFF
                smoothedMetronomeGain += (targetMetroGain - smoothedMetronomeGain) * GAIN_SMOOTH_COEFF

                // 1. Render Polyphonic Voices on Dedicated Tone Bus
                var toneBus = 0.0
                var idx = voices.size - 1
                while (idx >= 0) {
                    val v = voices[idx]
                    val sampleVal = synthesizeVoiceSample(v, toneVoice)
                    toneBus += sampleVal * v.env * smoothedPolyNorm * smoothedToneGain

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
                        snapshot = triggerBeatBoundary()
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
                                val eighthOrSixteenth = if (mix.eighth > 0.01f) {
                                    mix.eighth.toDouble()
                                } else if (mix.sixteenth > 0.01f) {
                                    mix.sixteenth.toDouble() * 0.85
                                } else 0.0
                                if (eighthOrSixteenth > 0.01) {
                                    spawnPercussion(HitKind.EIGHTH, eighthOrSixteenth)
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

                // 3. Render Percussion Voices on Dedicated Metronome Bus
                var percBus = 0.0
                for (p in percPool) {
                    if (p.active) {
                        percBus += synthesizePercussionSample(p) * smoothedMetronomeGain
                    }
                }

                // 4. Independent Bus Limiting + Strictly Linear Master Sum
                val toneLimited = softLimitBus(toneBus, TONE_BUS_KNEE, TONE_BUS_CEILING)
                val percLimited = softLimitBus(percBus, PERC_BUS_KNEE, PERC_BUS_CEILING)
                val masterSample = toneLimited + percLimited

                out[i] = (masterSample * 32767.0)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }

            snapshot
        }

        pendingSnapshot?.let { _beatSnapshot.value = it }
    }

    private fun triggerBeatBoundary(): MetronomeBeatSnapshot {
        val ts = timeSignature
        val mix = subdivisionMix
        val isMuted = alternateMuteBar && (barCounter % 2 == 1)

        if (!isMuted) {
            when {
                currentBeatInBar == 0 -> {
                    if (mix.accent > 0.01f) {
                        spawnPercussion(HitKind.ACCENT, mix.accent.toDouble())
                    } else if (mix.quarter > 0.01f) {
                        spawnPercussion(HitKind.QUARTER, mix.quarter.toDouble())
                    }
                }
                ts.isCompound && currentBeatInBar % 3 != 0 -> {
                    if (mix.quarter > 0.01f) {
                        spawnPercussion(HitKind.SECONDARY_COMPOUND, mix.quarter.toDouble() * 0.65)
                    }
                }
                else -> {
                    if (mix.quarter > 0.01f) {
                        spawnPercussion(HitKind.QUARTER, mix.quarter.toDouble())
                    }
                }
            }
        }

        return MetronomeBeatSnapshot(
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
        val accentFlag = kind == HitKind.ACCENT
        var fStart = 880.0
        var fEnd = 680.0
        var durSamples = 1200

        when (sound) {
            MetronomeSound.WOODBLOCK -> when (kind) {
                HitKind.ACCENT -> { fStart = 1280.0; fEnd = 960.0; durSamples = 1400 }
                HitKind.QUARTER -> { fStart = 880.0; fEnd = 680.0; durSamples = 1200 }
                HitKind.SECONDARY_COMPOUND -> { fStart = 760.0; fEnd = 580.0; durSamples = 950 }
                HitKind.EIGHTH -> { fStart = 1040.0; fEnd = 820.0; durSamples = 850 }
                HitKind.TRIPLET -> { fStart = 960.0; fEnd = 760.0; durSamples = 750 }
                HitKind.SIXTEENTH -> { fStart = 1140.0; fEnd = 900.0; durSamples = 650 }
            }
            MetronomeSound.CLICK -> when (kind) {
                HitKind.ACCENT -> { fStart = 2400.0; fEnd = 1800.0; durSamples = 650 }
                HitKind.QUARTER -> { fStart = 1650.0; fEnd = 1200.0; durSamples = 520 }
                HitKind.SECONDARY_COMPOUND -> { fStart = 1400.0; fEnd = 1050.0; durSamples = 450 }
                HitKind.EIGHTH -> { fStart = 1950.0; fEnd = 1500.0; durSamples = 400 }
                HitKind.TRIPLET -> { fStart = 1800.0; fEnd = 1400.0; durSamples = 380 }
                HitKind.SIXTEENTH -> { fStart = 2150.0; fEnd = 1650.0; durSamples = 340 }
            }
            MetronomeSound.BELL -> when (kind) {
                HitKind.ACCENT -> { fStart = 1568.0; fEnd = 1568.0; durSamples = 4800 }
                HitKind.QUARTER -> { fStart = 1174.0; fEnd = 1174.0; durSamples = 2600 }
                HitKind.SECONDARY_COMPOUND -> { fStart = 988.0; fEnd = 988.0; durSamples = 1800 }
                HitKind.EIGHTH -> { fStart = 1318.0; fEnd = 1318.0; durSamples = 1400 }
                HitKind.TRIPLET -> { fStart = 1245.0; fEnd = 1245.0; durSamples = 1200 }
                HitKind.SIXTEENTH -> { fStart = 1480.0; fEnd = 1480.0; durSamples = 1000 }
            }
            MetronomeSound.RIMSHOT -> when (kind) {
                HitKind.ACCENT -> { fStart = 460.0; fEnd = 240.0; durSamples = 1100 }
                HitKind.QUARTER -> { fStart = 380.0; fEnd = 200.0; durSamples = 850 }
                HitKind.SECONDARY_COMPOUND -> { fStart = 340.0; fEnd = 190.0; durSamples = 700 }
                HitKind.EIGHTH -> { fStart = 540.0; fEnd = 320.0; durSamples = 600 }
                HitKind.TRIPLET -> { fStart = 500.0; fEnd = 300.0; durSamples = 550 }
                HitKind.SIXTEENTH -> { fStart = 620.0; fEnd = 380.0; durSamples = 500 }
            }
        }

        slot.active = true
        slot.ageSamples = 0
        slot.totalSamples = durSamples
        slot.phase = 0.0
        slot.phaseOver = 0.0
        slot.prevNoise = 0.0
        slot.freqStart = fStart
        slot.freqEnd = fEnd
        slot.gain = (level * if (accentFlag) 0.34 else 0.25).coerceIn(0.0, 0.40)
        slot.sound = sound
        slot.isAccent = accentFlag
    }

    private fun synthesizeVoiceSample(v: ActiveVoice, timbre: ToneVoice): Double {
        val inc = TWO_PI * v.frequencyHz / SAMPLE_RATE
        v.phase += inc
        if (v.phase >= TWO_PI) {
            v.phase -= TWO_PI
        }
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
                v.phaseDetune += detuneInc
                if (v.phaseDetune >= TWO_PI) {
                    v.phaseDetune -= TWO_PI
                }
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
        p.phase += TWO_PI * freq / SAMPLE_RATE
        if (p.phase >= TWO_PI) {
            p.phase -= TWO_PI
        }

        // Crisp 0.23ms attack and smooth tail fade-out eliminate DC step thumps
        val attack = if (p.ageSamples < PERC_ATTACK_SAMPLES) {
            p.ageSamples.toDouble() / PERC_ATTACK_SAMPLES.toDouble()
        } else {
            1.0
        }
        val fadeOut = if (t < 0.90) 1.0 else ((1.0 - t) / 0.10).coerceAtLeast(0.0)

        val env = exp(-t * when (p.sound) {
            MetronomeSound.BELL -> 4.5
            MetronomeSound.CLICK -> 6.5
            else -> 5.5
        }) * attack * fadeOut

        val wave = when (p.sound) {
            MetronomeSound.WOODBLOCK -> {
                sin(p.phase) + 0.35 * sin(2.42 * p.phase) * exp(-t * 9.0)
            }
            MetronomeSound.CLICK -> {
                sin(p.phase) + 0.25 * sin(2.0 * p.phase)
            }
            MetronomeSound.BELL -> {
                p.phaseOver += TWO_PI * (freq * 2.756) / SAMPLE_RATE
                if (p.phaseOver >= TWO_PI) {
                    p.phaseOver -= TWO_PI
                }
                0.68 * sin(p.phase) + 0.32 * sin(p.phaseOver) * exp(-t * 6.0)
            }
            MetronomeSound.RIMSHOT -> {
                // High-pass filtered noise avoids low-frequency speaker excursion
                val rawNoise = Random.nextDouble() * 2.0 - 1.0
                val hpNoise = (rawNoise - p.prevNoise) * 0.5 * exp(-t * 14.0)
                p.prevNoise = rawNoise
                0.65 * sin(p.phase) + 0.50 * hpNoise
            }
        }

        p.ageSamples++
        return wave * env * p.gain
    }
}
