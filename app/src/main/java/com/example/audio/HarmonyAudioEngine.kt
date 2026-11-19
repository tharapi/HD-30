package com.example.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.example.model.AudioOutputMode
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
import kotlin.math.round
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
    var attackSamples: Int = 12,
    var phase: Double = 0.0,
    var phaseOver: Double = 0.0,
    var prevNoise: Double = 0.0,
    var hpPrevIn: Double = 0.0,
    var hpPrevOut: Double = 0.0,
    var hpAlpha: Double = 0.89,
    var freqStart: Double = 1480.0,
    var freqEnd: Double = 1180.0,
    var gain: Double = 0.0,
    var sound: MetronomeSound = MetronomeSound.WOODBLOCK,
    var isAccent: Boolean = false
)

class HarmonyAudioEngine {
    companion object {
        // Default 48 kHz matches Android AudioFlinger native output on 3.5mm & Bluetooth A2DP,
        // avoiding resampling and inter-sample peaks.
        const val SAMPLE_RATE = 48000
        const val FRAME_SAMPLES = 512
        private const val TWO_PI = 2.0 * PI

        // Dedicated per-bus headroom budgets for strictly linear summing with generous
        // downstream safety margin:
        // |toneLimited| <= 0.27 (-11.4 dBFS) and |metroLimited| <= 0.20 (-14.0 dBFS)
        // => worst-case mixed peak <= 0.47 (-6.56 dBFS), keeping external 3.5mm AUX / PA /
        // Bluetooth speaker DSP limiters and Android AudioFlinger comfortably below threshold.
        private const val BASE_VOICE_NORM = 0.22
        private const val TONE_BUS_KNEE = 0.23
        private const val TONE_BUS_CEILING = 0.27
        private const val METRO_BUS_KNEE = 0.16
        private const val METRO_BUS_CEILING = 0.20

        private const val GAIN_SMOOTH_COEFF = 0.005
        private const val POLY_NORM_SMOOTH_COEFF = 0.0015
        private const val SPLIT_MIX_SMOOTH_COEFF = 0.003

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
    @Volatile internal var activeSampleRate: Int = SAMPLE_RATE

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

    // Stereo output routing state (0.0 = MIXED L+R, 1.0 = SPLIT L: Harmony / R: Metronome)
    @Volatile private var outputMode: AudioOutputMode = AudioOutputMode.MIXED
    private var smoothedSplitMix: Double = 0.0

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

        val nativeRate = try {
            AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)
        } catch (_: Throwable) {
            SAMPLE_RATE
        }
        val resolvedSampleRate = if (nativeRate in 44100..96000) nativeRate else SAMPLE_RATE
        activeSampleRate = resolvedSampleRate

        val minBufBytes = try {
            AudioTrack.getMinBufferSize(
                resolvedSampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            ).coerceAtLeast(FRAME_SAMPLES * 4)
        } catch (_: Throwable) {
            FRAME_SAMPLES * 4
        }

        // Stereo 16-bit PCM = 4 bytes/frame; keep ~42-64 ms slack to prevent beat-synced
        // underruns on wired 3.5mm & Bluetooth while keeping key-press response snappy.
        val bufferBytes = maxOf(
            minBufBytes,
            minOf(minBufBytes * 2, 12288),
            FRAME_SAMPLES * 8
        )

        val audioTrack = createAudioTrack(resolvedSampleRate, bufferBytes, lowLatency = true)
            ?: createAudioTrack(resolvedSampleRate, bufferBytes, lowLatency = false)

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
            val pcmBuffer = ShortArray(FRAME_SAMPLES * 2)
            while (engineRunning && !Thread.currentThread().isInterrupted) {
                renderStereoFrame(pcmBuffer)
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

    private fun createAudioTrack(
        sampleRate: Int,
        bufferBytes: Int,
        lowLatency: Boolean
    ): AudioTrack? {
        return try {
            val builder = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)

            if (lowLatency && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            }
            val track = builder.build()
            if (track.state == AudioTrack.STATE_INITIALIZED) {
                track
            } else {
                track.release()
                null
            }
        } catch (_: Throwable) {
            null
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

            // Normalize polyphony based on actively held voices only so releasing a chord
            // never pulls gain upward.
            val heldCount = voices.count { !it.releasing }
            if (heldCount > 0) {
                val nextNorm = BASE_VOICE_NORM / sqrt(heldCount.toDouble())
                targetPolyNorm = nextNorm
                if (wasSilent) {
                    smoothedPolyNorm = nextNorm
                    smoothedToneGain = masterToneGain
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

    fun setOutputMode(mode: AudioOutputMode) {
        outputMode = mode
        val target = if (mode == AudioOutputMode.SPLIT_LR) 1.0 else 0.0
        synchronized(stateLock) {
            if (voices.isEmpty() && !metronomePlaying) {
                smoothedSplitMix = target
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

    /**
     * Renders interleaved stereo PCM frames into [out] (L0, R0, L1, R1, ...).
     * In [AudioOutputMode.MIXED], both Left and Right receive the strictly linear sum of the
     * independently bounded tone and metronome buses.
     * In [AudioOutputMode.SPLIT_LR], Left receives Harmony only and Right receives Metronome only.
     */
    internal fun renderStereoFrame(out: ShortArray) {
        val frameCount = out.size / 2
        val sr = activeSampleRate.toDouble()
        val toneVoice = currentToneVoice
        val targetToneGain = masterToneGain
        val targetMetroGain = masterMetronomeGain
        val targetSplit = if (outputMode == AudioOutputMode.SPLIT_LR) 1.0 else 0.0
        val isMetroRunning = metronomePlaying
        val currentBpm = bpm
        val samplesPerBeat = (sr * 60.0) / currentBpm.toDouble()

        val pendingSnapshot: MetronomeBeatSnapshot? = synchronized(stateLock) {
            var snapshot: MetronomeBeatSnapshot? = null
            val heldCount = voices.count { !it.releasing }
            if (heldCount > 0) {
                targetPolyNorm = BASE_VOICE_NORM / sqrt(heldCount.toDouble())
            }

            for (i in 0 until frameCount) {
                smoothedPolyNorm += (targetPolyNorm - smoothedPolyNorm) * POLY_NORM_SMOOTH_COEFF
                smoothedToneGain += (targetToneGain - smoothedToneGain) * GAIN_SMOOTH_COEFF
                smoothedMetronomeGain += (targetMetroGain - smoothedMetronomeGain) * GAIN_SMOOTH_COEFF

                val splitDiff = targetSplit - smoothedSplitMix
                if (abs(splitDiff) < 1e-5) {
                    smoothedSplitMix = targetSplit
                } else {
                    smoothedSplitMix += splitDiff * SPLIT_MIX_SMOOTH_COEFF
                }

                // 1. Render Isolated Tone Bus
                var toneSample = 0.0
                var idx = voices.size - 1
                while (idx >= 0) {
                    val v = voices[idx]
                    val sampleVal = synthesizeVoiceSample(v, toneVoice, sr)
                    toneSample += sampleVal * v.env * smoothedPolyNorm * smoothedToneGain

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
                        metroSample += synthesizePercussionSample(p, sr) * smoothedMetronomeGain
                    }
                }

                // 4. Independent Bus Soft-Knee Limiting + Strictly Linear Summing / Stereo Split
                val toneLimited = softLimitBus(toneSample, TONE_BUS_KNEE, TONE_BUS_CEILING)
                val metroLimited = softLimitBus(metroSample, METRO_BUS_KNEE, METRO_BUS_CEILING)

                val crossFeed = 1.0 - smoothedSplitMix
                val left = toneLimited + metroLimited * crossFeed
                val right = metroLimited + toneLimited * crossFeed

                val base = i * 2
                out[base] = round(left * 32767.0)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
                out[base + 1] = round(right * 32767.0)
                    .toInt()
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

        val sr = activeSampleRate.toDouble()
        val sound = metronomeSound
        val accentFlag = kind == HitKind.ACCENT
        var fStart = 1480.0
        var fEnd = 1180.0
        var durMs = 12.0
        var hpCutoffHz = 900.0

        // Short, crisp, spectrally elevated transients cut cleanly over ensemble chords while
        // keeping RMS energy low enough to avoid cochlear masking, Stapedius reflex, and
        // downstream speaker limiter pumping.
        when (sound) {
            MetronomeSound.WOODBLOCK -> {
                hpCutoffHz = 900.0
                when (kind) {
                    HitKind.ACCENT -> { fStart = 1950.0; fEnd = 1520.0; durMs = 14.0 }
                    HitKind.QUARTER -> { fStart = 1480.0; fEnd = 1180.0; durMs = 12.0 }
                    HitKind.SECONDARY_COMPOUND -> { fStart = 1320.0; fEnd = 1060.0; durMs = 10.5 }
                    HitKind.EIGHTH -> { fStart = 1680.0; fEnd = 1360.0; durMs = 9.5 }
                    HitKind.TRIPLET -> { fStart = 1580.0; fEnd = 1280.0; durMs = 9.0 }
                    HitKind.SIXTEENTH -> { fStart = 1820.0; fEnd = 1460.0; durMs = 8.0 }
                }
            }
            MetronomeSound.CLICK -> {
                hpCutoffHz = 1400.0
                when (kind) {
                    HitKind.ACCENT -> { fStart = 3300.0; fEnd = 2500.0; durMs = 7.0 }
                    HitKind.QUARTER -> { fStart = 2550.0; fEnd = 1950.0; durMs = 6.0 }
                    HitKind.SECONDARY_COMPOUND -> { fStart = 2250.0; fEnd = 1750.0; durMs = 5.5 }
                    HitKind.EIGHTH -> { fStart = 2850.0; fEnd = 2200.0; durMs = 5.0 }
                    HitKind.TRIPLET -> { fStart = 2700.0; fEnd = 2080.0; durMs = 4.8 }
                    HitKind.SIXTEENTH -> { fStart = 3050.0; fEnd = 2350.0; durMs = 4.5 }
                }
            }
            MetronomeSound.BELL -> {
                hpCutoffHz = 1200.0
                when (kind) {
                    HitKind.ACCENT -> { fStart = 2637.0; fEnd = 2637.0; durMs = 45.0 }
                    HitKind.QUARTER -> { fStart = 2093.0; fEnd = 2093.0; durMs = 32.0 }
                    HitKind.SECONDARY_COMPOUND -> { fStart = 1760.0; fEnd = 1760.0; durMs = 26.0 }
                    HitKind.EIGHTH -> { fStart = 2349.0; fEnd = 2349.0; durMs = 22.0 }
                    HitKind.TRIPLET -> { fStart = 2217.0; fEnd = 2217.0; durMs = 20.0 }
                    HitKind.SIXTEENTH -> { fStart = 2489.0; fEnd = 2489.0; durMs = 18.0 }
                }
            }
            MetronomeSound.RIMSHOT -> {
                hpCutoffHz = 800.0
                when (kind) {
                    HitKind.ACCENT -> { fStart = 1150.0; fEnd = 680.0; durMs = 13.5 }
                    HitKind.QUARTER -> { fStart = 960.0; fEnd = 580.0; durMs = 11.5 }
                    HitKind.SECONDARY_COMPOUND -> { fStart = 860.0; fEnd = 520.0; durMs = 10.0 }
                    HitKind.EIGHTH -> { fStart = 1280.0; fEnd = 780.0; durMs = 9.0 }
                    HitKind.TRIPLET -> { fStart = 1180.0; fEnd = 720.0; durMs = 8.5 }
                    HitKind.SIXTEENTH -> { fStart = 1380.0; fEnd = 860.0; durMs = 7.5 }
                }
            }
        }

        val attackSamples = round(0.00025 * sr).toInt().coerceAtLeast(1)
        val totalSamples = round(durMs * 0.001 * sr).toInt().coerceAtLeast(attackSamples + 8)

        slot.active = true
        slot.ageSamples = 0
        slot.totalSamples = totalSamples
        slot.attackSamples = attackSamples
        slot.phase = 0.0
        slot.phaseOver = 0.0
        slot.prevNoise = 0.0
        slot.hpPrevIn = 0.0
        slot.hpPrevOut = 0.0
        slot.hpAlpha = 1.0 / (1.0 + TWO_PI * hpCutoffHz / sr)
        slot.freqStart = fStart
        slot.freqEnd = fEnd
        // Calibrated so high-passed crisp beats sit cleanly near METRO_BUS_KNEE without squashing
        slot.gain = (level * if (accentFlag) 0.34 else 0.25).coerceIn(0.0, 0.38)
        slot.sound = sound
        slot.isAccent = accentFlag
    }

    private fun synthesizeVoiceSample(v: ActiveVoice, timbre: ToneVoice, sr: Double): Double {
        val inc = TWO_PI * v.frequencyHz / sr
        v.phase += inc
        if (v.phase >= TWO_PI) {
            v.phase -= TWO_PI
        }
        v.ageSamples++

        // Sample-rate-independent envelope timing
        val srScale = 44100.0 / sr
        val attackRate = when (timbre) {
            ToneVoice.STRINGS -> 0.0008
            ToneVoice.WOODWIND -> 0.0022
            ToneVoice.PIANO -> 0.012
            else -> 0.0035
        } * srScale
        val releaseRate = 0.0025 * srScale

        if (v.releasing) {
            v.env = (v.env - releaseRate).coerceAtLeast(0.0)
        } else if (timbre == ToneVoice.PIANO) {
            val pianoAttackSamples = round(0.0032 * sr).toLong().coerceAtLeast(1L)
            if (v.ageSamples < pianoAttackSamples) {
                v.env = (v.env + attackRate).coerceAtMost(1.0)
            } else {
                v.env *= exp(-3.53 / sr)
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
                val ageSec = v.ageSamples.toDouble() / sr
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
                val detuneInc = TWO_PI * (v.frequencyHz * 1.0028) / sr
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
                val ageSec = v.ageSamples.toDouble() / sr
                val overDecay = exp(-ageSec * 3.8)
                0.64 * sin(p) +
                    0.28 * overDecay * sin(2.0 * p) +
                    0.14 * overDecay * sin(3.0 * p) +
                    0.07 * overDecay * sin(4.0 * p)
            }
        }
    }

    private fun synthesizePercussionSample(p: PercussionVoice, sr: Double): Double {
        if (p.ageSamples >= p.totalSamples) {
            p.active = false
            return 0.0
        }
        val t = p.ageSamples.toDouble() / p.totalSamples.toDouble()
        val freq = p.freqStart + (p.freqEnd - p.freqStart) * t
        p.phase += TWO_PI * freq / sr
        if (p.phase >= TWO_PI) {
            p.phase -= TWO_PI
        }

        // Crisp 0.25 ms attack ramp and smooth tail fade-out eliminate DC step thumps
        val attack = if (p.ageSamples < p.attackSamples) {
            p.ageSamples.toDouble() / p.attackSamples.toDouble()
        } else {
            1.0
        }
        val fadeOut = if (t < 0.88) 1.0 else ((1.0 - t) / 0.12).coerceAtLeast(0.0)

        val decayRate = when (p.sound) {
            MetronomeSound.BELL -> 6.5
            MetronomeSound.CLICK -> 8.5
            MetronomeSound.RIMSHOT -> 8.0
            MetronomeSound.WOODBLOCK -> 7.5
        }
        val env = exp(-t * decayRate) * attack * fadeOut

        val rawWave = when (p.sound) {
            MetronomeSound.WOODBLOCK -> {
                0.78 * sin(p.phase) + 0.22 * sin(2.42 * p.phase) * exp(-t * 10.0)
            }
            MetronomeSound.CLICK -> {
                0.80 * sin(p.phase) + 0.20 * sin(2.0 * p.phase)
            }
            MetronomeSound.BELL -> {
                p.phaseOver += TWO_PI * (freq * 2.756) / sr
                if (p.phaseOver >= TWO_PI) {
                    p.phaseOver -= TWO_PI
                }
                0.72 * sin(p.phase) + 0.28 * sin(p.phaseOver) * exp(-t * 8.0)
            }
            MetronomeSound.RIMSHOT -> {
                val rawNoise = Random.nextDouble() * 2.0 - 1.0
                val hpNoise = (rawNoise - p.prevNoise) * 0.5 * exp(-t * 12.0)
                p.prevNoise = rawNoise
                0.62 * sin(p.phase) + 0.38 * hpNoise
            }
        }

        val shaped = rawWave * env * p.gain

        // 1-pole high-pass filter strips DC and low-mid energy so the click floats cleanly
        // above sustained harmony chords without cochlear masking or speaker excursion.
        val filtered = p.hpAlpha * (p.hpPrevOut + shaped - p.hpPrevIn)
        p.hpPrevIn = shaped
        p.hpPrevOut = filtered

        p.ageSamples++
        return filtered
    }
}
