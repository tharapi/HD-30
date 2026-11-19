package com.example.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import com.example.R
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
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
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
    var ageSamples: Long = 0L,
    var sampleZone: InstrumentSampleZone? = null,
    var sampleVoice: ToneVoice? = null,
    var samplePos: Double = 0.0
)

private class PercussionVoice(
    var active: Boolean = false,
    var sampleData: FloatArray? = null,
    var samplePos: Double = 0.0,
    var sampleStep: Double = 1.0,
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

class HarmonyAudioEngine(context: Context? = null) {
    companion object {
        // Default 48 kHz matches Android AudioFlinger native output on 3.5mm & Bluetooth A2DP,
        // avoiding resampling and inter-sample peaks.
        const val SAMPLE_RATE = 48000
        const val FRAME_SAMPLES = 512
        private const val TWO_PI = 2.0 * PI

        // Dedicated per-bus headroom budgets for strictly linear summing without digital clipping:
        // |toneLimited| <= 0.27 (-11.4 dBFS) and |metroLimited| <= 0.50 (-6.0 dBFS)
        // => worst-case mixed peak <= 0.77 (-2.27 dBFS), keeping sustained Harmony well below
        // downstream limiter thresholds while giving metronome transients strong punch (+8 dB)
        // to cut clearly through ensemble rehearsals.
        private const val BASE_VOICE_NORM = 0.22
        private const val TONE_BUS_KNEE = 0.23
        private const val TONE_BUS_CEILING = 0.27
        private const val METRO_BUS_KNEE = 0.40
        private const val METRO_BUS_CEILING = 0.50

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

        private fun loadBundledMetronomeSamples(context: Context): Map<MetronomeSound, DecodedWavSample> {
            // Bundled acoustic percussion samples from VSCO 2 Community Edition (CC0-1.0),
            // pre-trimmed to onset, gently high-passed, resampled to 48 kHz mono, and peak-matched:
            //   WOODBLOCK -> wood_click_ff_2.wav
            //   CLICK     -> snare3_click_1.wav
            //   BELL      -> Cowbell1_Hit_v3_rr1_Sum.wav
            //   RIMSHOT   -> snare3_rimshot_ff_1.wav
            val resMap = mapOf(
                MetronomeSound.WOODBLOCK to R.raw.metro_woodblock,
                MetronomeSound.CLICK to R.raw.metro_click,
                MetronomeSound.BELL to R.raw.metro_bell,
                MetronomeSound.RIMSHOT to R.raw.metro_rimshot
            )
            val loaded = HashMap<MetronomeSound, DecodedWavSample>(resMap.size)
            val resources = context.resources
            for ((sound, resId) in resMap) {
                try {
                    resources.openRawResource(resId).use { stream ->
                        WavSampleDecoder.decode(stream)?.let { decoded ->
                            if (decoded.pcm.isNotEmpty()) {
                                loaded[sound] = decoded
                            }
                        }
                    }
                } catch (_: Throwable) {
                    // Fall back cleanly to synthesized percussion if resource loading fails
                }
            }
            return loaded
        }

        // Phase-locked harmonic spectra (H1..H9) measured from real acoustic recordings
        // (VSCO-2-CE Organ/Quiet, Tuba/F Horn/Trumpet, Bassoon/Clarinet/Flute NV, and
        // Philharmonia non-vibrato Cello/Viola/Violin) across Low / Mid / High registers.
        // Synthesized via band-limited Chebyshev recurrence for 0.000 dB loop ripple,
        // 0.000 cents pitch drift, zero detune, and -inf dB background noise.
        private const val LN_2 = 0.6931471805599453
        private const val SPECTRAL_TARGET_RMS = 0.48
        private const val HARMONIC_COUNT = 9

        private val ORGAN_LOW = doubleArrayOf(0.88, 0.14, 0.36, 0.09, 0.06, 0.025, 0.015, 0.012, 0.005)
        private val ORGAN_MID = doubleArrayOf(0.94, 0.06, 0.30, 0.035, 0.018, 0.010, 0.005, 0.004, 0.002)
        private val ORGAN_HIGH = doubleArrayOf(0.99, 0.05, 0.04, 0.015, 0.005, 0.002, 0.0, 0.0, 0.0)

        private val BRASS_LOW = doubleArrayOf(0.52, 0.58, 0.48, 0.34, 0.20, 0.12, 0.06, 0.03, 0.015)
        private val BRASS_MID = doubleArrayOf(0.64, 0.68, 0.28, 0.14, 0.07, 0.03, 0.015, 0.008, 0.004)
        private val BRASS_HIGH = doubleArrayOf(0.80, 0.52, 0.22, 0.10, 0.05, 0.02, 0.008, 0.004, 0.0)

        private val WOODWIND_LOW = doubleArrayOf(0.64, 0.30, 0.48, 0.24, 0.22, 0.08, 0.11, 0.03, 0.035)
        private val WOODWIND_MID = doubleArrayOf(0.88, 0.04, 0.40, 0.04, 0.20, 0.025, 0.075, 0.012, 0.025)
        private val WOODWIND_HIGH = doubleArrayOf(0.98, 0.12, 0.05, 0.015, 0.005, 0.0, 0.0, 0.0, 0.0)

        private val STRINGS_LOW = doubleArrayOf(0.54, 0.62, 0.42, 0.28, 0.16, 0.10, 0.065, 0.04, 0.025)
        private val STRINGS_MID = doubleArrayOf(0.68, 0.46, 0.44, 0.24, 0.14, 0.085, 0.05, 0.03, 0.018)
        private val STRINGS_HIGH = doubleArrayOf(0.84, 0.44, 0.24, 0.11, 0.05, 0.025, 0.012, 0.006, 0.0)

        internal fun synthesizeSpectralVoice(
            timbre: ToneVoice,
            freqHz: Double,
            phase: Double,
            ageSec: Double,
            sr: Double
        ): Double {
            val low: DoubleArray
            val mid: DoubleArray
            val high: DoubleArray
            when (timbre) {
                ToneVoice.ORGAN -> { low = ORGAN_LOW; mid = ORGAN_MID; high = ORGAN_HIGH }
                ToneVoice.BRASS -> { low = BRASS_LOW; mid = BRASS_MID; high = BRASS_HIGH }
                ToneVoice.WOODWIND -> { low = WOODWIND_LOW; mid = WOODWIND_MID; high = WOODWIND_HIGH }
                ToneVoice.STRINGS -> { low = STRINGS_LOW; mid = STRINGS_MID; high = STRINGS_HIGH }
                else -> return sin(phase)
            }

            // Smooth register morphing relative to C3 (130.8128 Hz): Low (<=C3) <-> Mid (C4) <-> High (>=C6)
            val safeFreq = freqHz.coerceAtLeast(20.0)
            val octFromC3 = ln(safeFreq / 130.8128) / LN_2
            val profileA: DoubleArray
            val profileB: DoubleArray
            val blend: Double
            if (octFromC3 <= 1.0) {
                profileA = low
                profileB = mid
                blend = ((octFromC3 + 0.5) / 1.5).coerceIn(0.0, 1.0)
            } else {
                profileA = mid
                profileB = high
                blend = ((octFromC3 - 1.0) / 2.0).coerceIn(0.0, 1.0)
            }

            val isBrass = timbre == ToneVoice.BRASS
            val bloom = if (isBrass) (ageSec / 0.095).coerceIn(0.35, 1.0) else 1.0
            val nyqSoft = sr * 0.36
            val nyqCut = sr * 0.45

            // Zero-allocation Chebyshev recurrence for exact phase-locked sin(1*p)..sin(9*p)
            var sPrev = 0.0
            var sCurr = sin(phase)
            val twoCos = 2.0 * cos(phase)
            var acc = 0.0
            var sumSq = 0.0

            for (h in 0 until HARMONIC_COUNT) {
                val fh = safeFreq * (h + 1)
                if (fh >= nyqCut) break
                var w = profileA[h] + (profileB[h] - profileA[h]) * blend
                if (isBrass && h >= 2) {
                    w *= bloom
                }
                if (fh > nyqSoft) {
                    w *= (nyqCut - fh) / (nyqCut - nyqSoft)
                }
                acc += w * sCurr
                sumSq += w * w

                val sNext = twoCos * sCurr - sPrev
                sPrev = sCurr
                sCurr = sNext
            }

            if (sumSq <= 1e-12) return 0.0
            return acc * (SPECTRAL_TARGET_RMS / sqrt(0.5 * sumSq))
        }

        private fun loadBundledInstrumentBanks(context: Context): Map<ToneVoice, List<InstrumentSampleZone>> {
            // One-shot acoustic Upright Piano samples from VSCO 2 Community Edition (CC0-1.0),
            // pitch-calibrated to A4=440 Hz in RIFF 'smpl' (14 zones, C1..G7).
            // Sustained tuning voices (PURE_SINE, ORGAN, BRASS, WOODWIND, STRINGS) use
            // phase-locked spectral synthesis for zero loop wobble and laboratory-grade tuning.
            val dirMap = mapOf(
                ToneVoice.PIANO to "samples/piano"
            )
            val assets = context.assets
            val banks = HashMap<ToneVoice, List<InstrumentSampleZone>>(dirMap.size)
            for ((voice, dir) in dirMap) {
                try {
                    val files = assets.list(dir)?.filter { it.endsWith(".wav", ignoreCase = true) }?.sorted()
                        ?: continue
                    val zones = ArrayList<InstrumentSampleZone>(files.size)
                    for (fileName in files) {
                        val fallbackMidi = fileName.removePrefix("m").removeSuffix(".wav").toIntOrNull() ?: 60
                        assets.open("$dir/$fileName").use { stream ->
                            WavSampleDecoder.decodeInstrumentZone(stream, fallbackMidi)?.let { zone ->
                                if (zone.pcm.isNotEmpty()) {
                                    zones.add(zone)
                                }
                            }
                        }
                    }
                    if (zones.isNotEmpty()) {
                        zones.sortBy { it.rootFreqHz }
                        banks[voice] = zones
                    }
                } catch (_: Throwable) {
                    // Fall back cleanly to synthesized voice if asset loading fails
                }
            }
            return banks
        }

        private fun findNearestZone(zones: List<InstrumentSampleZone>, freqHz: Double): InstrumentSampleZone {
            var best = zones[0]
            val safeFreq = freqHz.coerceAtLeast(16.0)
            var bestDist = abs(ln(safeFreq / best.rootFreqHz))
            for (i in 1 until zones.size) {
                val cand = zones[i]
                val dist = abs(ln(safeFreq / cand.rootFreqHz))
                if (dist < bestDist) {
                    bestDist = dist
                    best = cand
                }
            }
            return best
        }
    }

    private val engineLock = Any()
    private val stateLock = Any()
    @Volatile private var engineRunning: Boolean = false
    private var renderThread: Thread? = null
    @Volatile internal var activeSampleRate: Int = SAMPLE_RATE

    // Bundled acoustic sample banks (immutable maps swapped atomically)
    @Volatile private var metronomeSamples: Map<MetronomeSound, DecodedWavSample> =
        if (context != null) loadBundledMetronomeSamples(context.applicationContext ?: context) else emptyMap()
    @Volatile private var instrumentBanks: Map<ToneVoice, List<InstrumentSampleZone>> = emptyMap()

    init {
        if (context != null) {
            val appCtx = context.applicationContext ?: context
            Thread({
                val loaded = loadBundledInstrumentBanks(appCtx)
                if (loaded.isNotEmpty()) {
                    instrumentBanks = loaded
                }
            }, "HarmonySampleLoader").apply {
                isDaemon = true
                start()
            }
        }
    }

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

    internal fun setMetronomeSamples(samples: Map<MetronomeSound, DecodedWavSample>) {
        metronomeSamples = samples.toMap()
    }

    internal fun setInstrumentBanks(banks: Map<ToneVoice, List<InstrumentSampleZone>>) {
        instrumentBanks = banks.mapValues { (_, list) -> list.sortedBy { it.rootFreqHz } }
    }

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

            // Mark existing voices not in map for release; reset attack if re-striking a releasing voice
            for (voice in voices) {
                val updatedFreq = noteFrequencies[voice.writtenMidi]
                if (updatedFreq != null) {
                    val wasReleasing = voice.releasing
                    voice.frequencyHz = updatedFreq
                    voice.releasing = false
                    if (wasReleasing) {
                        voice.ageSamples = 0L
                        voice.sampleZone = null
                        voice.sampleVoice = null
                        voice.samplePos = 0.0
                    }
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
                        existingReleasing.sampleZone = null
                        existingReleasing.sampleVoice = null
                        existingReleasing.samplePos = 0.0
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

        val bundledSample = metronomeSamples[sound]
        if (bundledSample != null && bundledSample.pcm.isNotEmpty()) {
            val pitchRatio = when (kind) {
                HitKind.ACCENT -> 1.22
                HitKind.QUARTER -> 1.00
                HitKind.SECONDARY_COMPOUND -> 0.90
                HitKind.EIGHTH -> 1.12
                HitKind.TRIPLET -> 1.06
                HitKind.SIXTEENTH -> 1.18
            }
            slot.active = true
            slot.sampleData = bundledSample.pcm
            slot.samplePos = 0.0
            slot.sampleStep = (bundledSample.sampleRate.toDouble() / sr) * pitchRatio
            // Pre-normalized (-0.5 dBFS) acoustic samples calibrated to sit cleanly near
            // METRO_BUS_KNEE (0.40) at default 75% volume without squashing.
            slot.gain = (level * if (accentFlag) 0.56 else 0.40).coerceIn(0.0, 0.62)
            slot.sound = sound
            slot.isAccent = accentFlag
            return
        }

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
        slot.sampleData = null
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
        // Calibrated 2.5x stronger so high-passed crisp beats sit cleanly near METRO_BUS_KNEE
        // without squashing while cutting clearly through sustained Harmony chords.
        slot.gain = (level * if (accentFlag) 0.85 else 0.625).coerceIn(0.0, 0.95)
        slot.sound = sound
        slot.isAccent = accentFlag
    }

    private fun synthesizeVoiceSample(v: ActiveVoice, timbre: ToneVoice, sr: Double): Double {
        v.ageSamples++
        val srScale = 44100.0 / sr

        val zones = instrumentBanks[timbre]
        if (zones != null && zones.isNotEmpty()) {
            var zone = v.sampleZone
            val needsRebind = v.sampleVoice != timbre ||
                zone == null ||
                (!v.releasing && (v.frequencyHz / zone.rootFreqHz !in 0.72..1.38))
            if (needsRebind) {
                zone = findNearestZone(zones, v.frequencyHz)
                v.sampleZone = zone
                v.sampleVoice = timbre
                v.samplePos = 0.0
                if (v.env > 0.0) {
                    v.env = 0.0
                }
            }
            val activeZone = zone!!

            val attackRate = when (timbre) {
                ToneVoice.PIANO -> 0.015
                ToneVoice.STRINGS -> 0.0025
                else -> 0.005
            } * srScale
            val releaseRate = when (timbre) {
                ToneVoice.PIANO -> 0.0018
                ToneVoice.STRINGS -> 0.0010
                else -> 0.0012
            } * srScale
            if (v.releasing) {
                v.env = (v.env - releaseRate).coerceAtLeast(0.0)
            } else {
                v.env = (v.env + attackRate).coerceAtMost(1.0)
            }

            val pcm = activeZone.pcm
            val lastIdx = pcm.size - 1
            if (lastIdx < 1) return 0.0

            val step = (activeZone.sampleRate.toDouble() / sr) * (v.frequencyHz / activeZone.rootFreqHz)
            var pos = v.samplePos
            if (activeZone.hasLoop) {
                val loopStart = activeZone.loopStart.toDouble()
                val loopLen = (activeZone.loopEnd - activeZone.loopStart).toDouble()
                if (pos >= activeZone.loopEnd.toDouble()) {
                    pos = loopStart + ((pos - loopStart) % loopLen)
                }
            } else if (pos >= lastIdx.toDouble()) {
                v.env = 0.0
                v.releasing = true
                return 0.0
            }

            val idx = pos.toInt()
            val frac = pos - idx
            val invScale = 1.0 / 32768.0
            val s0 = pcm[(idx - 1).coerceIn(0, lastIdx)] * invScale
            val s1 = pcm[idx.coerceIn(0, lastIdx)] * invScale
            val s2 = pcm[(idx + 1).coerceIn(0, lastIdx)] * invScale
            val s3 = pcm[(idx + 2).coerceIn(0, lastIdx)] * invScale

            // 4-point Catmull-Rom cubic interpolation for clean pitch shifting & Just Intonation
            val c0 = s1
            val c1 = 0.5 * (s2 - s0)
            val c2 = s0 - 2.5 * s1 + 2.0 * s2 - 0.5 * s3
            val c3 = 0.5 * (s3 - s0) + 1.5 * (s1 - s2)
            val sample = ((c3 * frac + c2) * frac + c1) * frac + c0

            pos += step
            if (activeZone.hasLoop && pos >= activeZone.loopEnd.toDouble()) {
                val loopStart = activeZone.loopStart.toDouble()
                val loopLen = (activeZone.loopEnd - activeZone.loopStart).toDouble()
                pos = loopStart + ((pos - loopStart) % loopLen)
            }
            v.samplePos = pos
            return sample
        }

        // Phase-locked synthesized path (PURE_SINE, ORGAN, BRASS, WOODWIND, STRINGS, and PIANO fallback)
        v.sampleZone = null
        v.sampleVoice = timbre
        val inc = TWO_PI * v.frequencyHz / sr
        v.phase += inc
        if (v.phase >= TWO_PI) {
            v.phase -= TWO_PI
        }

        // Sample-rate-independent envelope timing
        val attackRate = when (timbre) {
            ToneVoice.STRINGS -> 0.0009
            ToneVoice.WOODWIND -> 0.0022
            ToneVoice.PIANO -> 0.012
            else -> 0.0035
        } * srScale
        val releaseRate = 0.0020 * srScale

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
            ToneVoice.ORGAN,
            ToneVoice.BRASS,
            ToneVoice.WOODWIND,
            ToneVoice.STRINGS -> {
                synthesizeSpectralVoice(
                    timbre = timbre,
                    freqHz = v.frequencyHz,
                    phase = p,
                    ageSec = v.ageSamples.toDouble() / sr,
                    sr = sr
                )
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
        val data = p.sampleData
        if (data != null) {
            val idx = p.samplePos.toInt()
            if (idx >= data.size) {
                p.active = false
                p.sampleData = null
                return 0.0
            }
            val frac = p.samplePos - idx
            val s0 = data[idx].toDouble()
            val s1 = if (idx + 1 < data.size) data[idx + 1].toDouble() else 0.0
            val interpolated = s0 + (s1 - s0) * frac
            p.samplePos += p.sampleStep
            return interpolated * p.gain
        }

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
