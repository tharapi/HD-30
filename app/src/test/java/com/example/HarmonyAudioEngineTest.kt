package com.example

import com.example.audio.HarmonyAudioEngine
import com.example.audio.WavSampleDecoder
import com.example.model.AudioOutputMode
import com.example.model.MetronomeSound
import com.example.model.SubdivisionMix
import com.example.model.TimeSignature
import com.example.model.ToneVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin

class HarmonyAudioEngineTest {

    private data class StereoPcm(val left: ShortArray, val right: ShortArray)

    private fun renderStereo(engine: HarmonyAudioEngine, frames: Int): StereoPcm {
        val frameSize = HarmonyAudioEngine.FRAME_SAMPLES
        val totalSamples = frames * frameSize
        val left = ShortArray(totalSamples)
        val right = ShortArray(totalSamples)
        val buf = ShortArray(frameSize * 2)
        for (f in 0 until frames) {
            engine.renderStereoFrame(buf)
            val base = f * frameSize
            for (i in 0 until frameSize) {
                left[base + i] = buf[i * 2]
                right[base + i] = buf[i * 2 + 1]
            }
        }
        return StereoPcm(left, right)
    }

    private fun dftAmplitude(samples: ShortArray, start: Int, length: Int, freqHz: Double): Double {
        val omega = 2.0 * PI * freqHz / HarmonyAudioEngine.SAMPLE_RATE
        var re = 0.0
        var im = 0.0
        for (k in 0 until length) {
            val s = samples[start + k].toDouble()
            val angle = omega * (start + k)
            re += s * cos(angle)
            im += s * sin(angle)
        }
        return 2.0 * hypot(re, im) / length
    }

    @Test
    fun sustainedToneMixesLinearlyWithMetronomeWithoutCrossModulation() {
        val sounds = listOf(MetronomeSound.WOODBLOCK, MetronomeSound.CLICK, MetronomeSound.BELL)
        val voices = listOf(ToneVoice.PURE_SINE, ToneVoice.ORGAN)
        val denseMix = SubdivisionMix(
            accent = 1.0f,
            quarter = 0.9f,
            eighth = 0.8f,
            triplet = 0.7f,
            sixteenth = 0.8f
        )

        for (voice in voices) {
            for (sound in sounds) {
                val toneOnly = HarmonyAudioEngine().apply {
                    setToneVoice(voice)
                    setToneGain(1.0f)
                    updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
                }
                val metroOnly = HarmonyAudioEngine().apply {
                    setMetronomeGain(1.0f)
                    setMetronomeConfig(140, TimeSignature.FOUR_FOUR, sound, denseMix, false)
                    setMetronomePlaying(true)
                }
                val both = HarmonyAudioEngine().apply {
                    setToneVoice(voice)
                    setToneGain(1.0f)
                    setMetronomeGain(1.0f)
                    updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
                    setMetronomeConfig(140, TimeSignature.FOUR_FOUR, sound, denseMix, false)
                    setMetronomePlaying(true)
                }

                // Render ~0.64 seconds at 48 kHz (60 frames = 30,720 stereo frames)
                val pcmTone = renderStereo(toneOnly, 60)
                val pcmMetro = renderStereo(metroOnly, 60)
                val pcmBoth = renderStereo(both, 60)

                var maxDiff = 0
                for (i in pcmBoth.left.indices) {
                    val expectedL = pcmTone.left[i].toInt() + pcmMetro.left[i].toInt()
                    val expectedR = pcmTone.right[i].toInt() + pcmMetro.right[i].toInt()
                    val diffL = abs(pcmBoth.left[i].toInt() - expectedL)
                    val diffR = abs(pcmBoth.right[i].toInt() - expectedR)
                    if (diffL > maxDiff) maxDiff = diffL
                    if (diffR > maxDiff) maxDiff = diffR
                }

                // Isolated dual-bus summing guarantees strictly linear superposition within 1-2 LSB rounding
                assertTrue(
                    "Expected linear superposition (<=2 LSB) for $voice + $sound, got $maxDiff LSB",
                    maxDiff <= 2
                )
            }
        }
    }

    @Test
    fun pureSineReferenceToneRemainsSteadyAndUndistortedAcrossBeats() {
        val freqHz = 440.0
        val mix = SubdivisionMix(accent = 1.0f, quarter = 0.85f, eighth = 0.6f, triplet = 0.0f, sixteenth = 0.0f)

        val metroOnly = HarmonyAudioEngine().apply {
            setMetronomeGain(1.0f)
            setMetronomeConfig(120, TimeSignature.FOUR_FOUR, MetronomeSound.WOODBLOCK, mix, false)
            setMetronomePlaying(true)
        }
        val both = HarmonyAudioEngine().apply {
            setToneVoice(ToneVoice.PURE_SINE)
            setToneGain(0.80f)
            setMetronomeGain(1.0f)
            updateActiveNotes(mapOf(69 to freqHz))
            setMetronomeConfig(120, TimeSignature.FOUR_FOUR, MetronomeSound.WOODBLOCK, mix, false)
            setMetronomePlaying(true)
        }

        val pcmMetro = renderStereo(metroOnly, 90)
        val pcmBoth = renderStereo(both, 90)

        // Isolate the tone component from the combined output
        val isolatedTone = ShortArray(pcmBoth.left.size) { i ->
            (pcmBoth.left[i].toInt() - pcmMetro.left[i].toInt())
                .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                .toShort()
        }

        // Skip initial attack (~15ms = 720 samples) and measure fundamental amplitude across beats.
        // 2400 samples at 48,000 Hz = exactly 22.0 cycles of 440 Hz (zero spectral leakage).
        val win = 2400
        val amplitudes = mutableListOf<Double>()
        var offset = 2400
        while (offset + win < isolatedTone.size) {
            amplitudes.add(dftAmplitude(isolatedTone, offset, win, freqHz))
            offset += 600
        }

        val minAmp = amplitudes.min()
        val maxAmp = amplitudes.max()
        val rippleDb = 20.0 * log10(maxAmp / minAmp)
        assertTrue("Expected sustained tone ripple < 0.05 dB, got $rippleDb dB", rippleDb < 0.05)

        // Pure Sine below bus knee should have negligible 3rd harmonic distortion (<-70 dB).
        // 4800 samples at 48,000 Hz = exactly 44.0 cycles of 440 Hz and 132.0 cycles of 1320 Hz.
        val h1 = dftAmplitude(isolatedTone, 4800, 4800, freqHz)
        val h3 = dftAmplitude(isolatedTone, 4800, 4800, freqHz * 3.0)
        val h3Db = 20.0 * log10((h3 + 1e-6) / h1)
        assertTrue("Expected 3rd harmonic < -70 dB for PURE_SINE, got $h3Db dB", h3Db < -70.0)
    }

    @Test
    fun denseChordAndAllSubdivisionsStayWithinSafeExternalSpeakerHeadroom() {
        val engine = HarmonyAudioEngine().apply {
            setToneVoice(ToneVoice.ORGAN)
            setToneGain(1.0f)
            setMetronomeGain(1.0f)
            updateActiveNotes(
                mapOf(
                    48 to 130.8128,
                    55 to 195.9977,
                    60 to 261.6256,
                    64 to 329.6276,
                    67 to 391.9954,
                    72 to 523.2511
                )
            )
            setMetronomeConfig(
                bpm = 200,
                timeSignature = TimeSignature.FOUR_FOUR,
                sound = MetronomeSound.BELL,
                mix = SubdivisionMix(1f, 1f, 1f, 1f, 1f),
                alternateMuteBar = false
            )
            setMetronomePlaying(true)
        }

        val pcm = renderStereo(engine, 90)
        val peakL = pcm.left.maxOf { abs(it.toInt()) }
        val peakR = pcm.right.maxOf { abs(it.toInt()) }
        val peak = maxOf(peakL, peakR)
        // Worst-case ceiling is (0.27 + 0.50) * 32767 = 25231 (-2.27 dBFS): clip-free strictly
        // linear summing with 2.5x stronger metronome transient headroom.
        assertTrue("Expected peak <= 25400 (<-2.2 dBFS clip-free headroom), got $peak", peak <= 25400)
    }

    @Test
    fun releasingChordDoesNotSpikeAmplitude() {
        val engine = HarmonyAudioEngine().apply {
            setToneVoice(ToneVoice.PURE_SINE)
            setToneGain(0.80f)
            updateActiveNotes(mapOf(69 to 440.0, 81 to 880.0, 88 to 1320.0))
        }

        // Warm up steady chord
        val steady = renderStereo(engine, 24)
        val steadyPeak = steady.left.copyOfRange(steady.left.size - 4096, steady.left.size).maxOf { abs(it.toInt()) }

        // Release all notes
        engine.updateActiveNotes(emptyMap())
        val release = renderStereo(engine, 24)
        val releasePeak = release.left.maxOf { abs(it.toInt()) }

        assertTrue(
            "Expected release peak ($releasePeak) not to jump above steady chord peak ($steadyPeak)",
            releasePeak <= steadyPeak + 10
        )
    }

    @Test
    fun splitOutputModeCompletelyIsolatesHarmonyLeftAndMetronomeRight() {
        val mix = SubdivisionMix(accent = 1.0f, quarter = 0.85f, eighth = 0.5f, triplet = 0.0f, sixteenth = 0.0f)

        val toneRef = HarmonyAudioEngine().apply {
            setOutputMode(AudioOutputMode.SPLIT_LR)
            setToneVoice(ToneVoice.ORGAN)
            setToneGain(0.85f)
            updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
        }
        val metroRef = HarmonyAudioEngine().apply {
            setOutputMode(AudioOutputMode.SPLIT_LR)
            setMetronomeGain(0.85f)
            setMetronomeConfig(132, TimeSignature.FOUR_FOUR, MetronomeSound.CLICK, mix, false)
            setMetronomePlaying(true)
        }
        val bothSplit = HarmonyAudioEngine().apply {
            setOutputMode(AudioOutputMode.SPLIT_LR)
            setToneVoice(ToneVoice.ORGAN)
            setToneGain(0.85f)
            setMetronomeGain(0.85f)
            updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
            setMetronomeConfig(132, TimeSignature.FOUR_FOUR, MetronomeSound.CLICK, mix, false)
            setMetronomePlaying(true)
        }

        val pcmTone = renderStereo(toneRef, 60)
        val pcmMetro = renderStereo(metroRef, 60)
        val pcmBoth = renderStereo(bothSplit, 60)

        // Tone-only in SPLIT_LR must produce exact silence on Right channel
        assertEquals("Expected zero Right-channel bleed for tone-only in SPLIT_LR", 0, pcmTone.right.maxOf { abs(it.toInt()) })
        // Metronome-only in SPLIT_LR must produce exact silence on Left channel
        assertEquals("Expected zero Left-channel bleed for metro-only in SPLIT_LR", 0, pcmMetro.left.maxOf { abs(it.toInt()) })

        var maxLeftDiff = 0
        var maxRightDiff = 0
        for (i in pcmBoth.left.indices) {
            val dL = abs(pcmBoth.left[i].toInt() - pcmTone.left[i].toInt())
            val dR = abs(pcmBoth.right[i].toInt() - pcmMetro.right[i].toInt())
            if (dL > maxLeftDiff) maxLeftDiff = dL
            if (dR > maxRightDiff) maxRightDiff = dR
        }
        assertTrue("Expected Left channel = pure Harmony (<=1 LSB), got $maxLeftDiff", maxLeftDiff <= 1)
        assertTrue("Expected Right channel = pure Metronome (<=1 LSB), got $maxRightDiff", maxRightDiff <= 1)
    }

    @Test
    fun metronomeClicksAreShortCrispAndZeroStartWithoutTailArtifacts() {
        for (sound in listOf(MetronomeSound.CLICK, MetronomeSound.WOODBLOCK)) {
            val engine = HarmonyAudioEngine().apply {
                setMetronomeGain(0.75f)
                setMetronomeConfig(
                    bpm = 60,
                    timeSignature = TimeSignature.FOUR_FOUR,
                    sound = sound,
                    mix = SubdivisionMix(accent = 1.0f, quarter = 0.0f, eighth = 0.0f, triplet = 0.0f, sixteenth = 0.0f),
                    alternateMuteBar = false
                )
                setMetronomePlaying(true)
            }

            // Render ~64 ms (6 frames = 3072 samples at 48 kHz) covering a single accent hit
            val pcm = renderStereo(engine, 6)
            assertEquals("Expected click to start at zero without DC step ($sound)", 0, pcm.left[0].toInt())

            val peak = pcm.left.maxOf { abs(it.toInt()) }
            assertTrue("Expected audible transient peak for $sound, got $peak", peak > 6000)
            assertTrue("Expected clean unsaturated peak below ceiling for $sound, got $peak", peak < 16400)

            // Verify crisp decay: after 18 ms (864 samples), residual must be silent (< 1% of peak)
            val tailWindowStart = 864
            val tailMax = pcm.left.copyOfRange(tailWindowStart, pcm.left.size).maxOf { abs(it.toInt()) }
            assertTrue(
                "Expected crisp transient decay within 18 ms for $sound (tailMax=$tailMax, peak=$peak)",
                tailMax <= peak * 0.01 + 2
            )
        }
    }

    @Test
    fun bundledMetronomeWavsDecodeAndMixLinearlyWithoutClipping() {
        val rawDir = listOf(File("src/main/res/raw"), File("app/src/main/res/raw"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("Expected res/raw directory to exist")

        val fileMap = mapOf(
            MetronomeSound.WOODBLOCK to "metro_woodblock.wav",
            MetronomeSound.CLICK to "metro_click.wav",
            MetronomeSound.BELL to "metro_bell.wav",
            MetronomeSound.RIMSHOT to "metro_rimshot.wav"
        )
        val decodedBank = fileMap.mapValues { (sound, fileName) ->
            val wavFile = File(rawDir, fileName)
            assertTrue("Expected bundled WAV to exist: ${wavFile.path}", wavFile.isFile)
            val decoded = WavSampleDecoder.decode(wavFile.readBytes())
            assertNotNull("Expected WAV to decode cleanly for $sound", decoded)
            assertEquals("Expected 48 kHz sample rate for $sound", HarmonyAudioEngine.SAMPLE_RATE, decoded!!.sampleRate)
            assertTrue("Expected non-empty PCM for $sound", decoded.pcm.isNotEmpty())
            decoded
        }

        val denseMix = SubdivisionMix(accent = 1.0f, quarter = 0.85f, eighth = 0.6f, triplet = 0.0f, sixteenth = 0.5f)
        for (sound in MetronomeSound.entries) {
            val singleHitEngine = HarmonyAudioEngine().apply {
                setMetronomeSamples(decodedBank)
                setMetronomeGain(0.75f)
                setMetronomeConfig(
                    bpm = 60,
                    timeSignature = TimeSignature.FOUR_FOUR,
                    sound = sound,
                    mix = SubdivisionMix(accent = 1.0f, quarter = 0.0f, eighth = 0.0f, triplet = 0.0f, sixteenth = 0.0f),
                    alternateMuteBar = false
                )
                setMetronomePlaying(true)
            }
            // Render ~256 ms (24 frames = 12288 samples) covering the full acoustic hit + silence tail
            val hitPcm = renderStereo(singleHitEngine, 24)
            assertEquals("Expected zero-start transient without DC pop ($sound)", 0, hitPcm.left[0].toInt())
            val hitPeak = hitPcm.left.maxOf { abs(it.toInt()) }
            assertTrue("Expected punchy acoustic peak for $sound, got $hitPeak", hitPeak > 9000)
            assertTrue("Expected unsaturated peak below ceiling for $sound, got $hitPeak", hitPeak < 16400)
            val tailAfter210Ms = hitPcm.left.copyOfRange(10080, hitPcm.left.size).maxOf { abs(it.toInt()) }
            assertEquals("Expected complete decay to silence after 210 ms ($sound)", 0, tailAfter210Ms)

            // Verify strictly linear summing with sustained Organ chord (zero cross-modulation)
            val toneOnly = HarmonyAudioEngine().apply {
                setToneVoice(ToneVoice.ORGAN)
                setToneGain(1.0f)
                updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
            }
            val metroOnly = HarmonyAudioEngine().apply {
                setMetronomeSamples(decodedBank)
                setMetronomeGain(1.0f)
                setMetronomeConfig(140, TimeSignature.FOUR_FOUR, sound, denseMix, false)
                setMetronomePlaying(true)
            }
            val both = HarmonyAudioEngine().apply {
                setMetronomeSamples(decodedBank)
                setToneVoice(ToneVoice.ORGAN)
                setToneGain(1.0f)
                setMetronomeGain(1.0f)
                updateActiveNotes(mapOf(60 to 261.6256, 64 to 329.6276, 67 to 391.9954))
                setMetronomeConfig(140, TimeSignature.FOUR_FOUR, sound, denseMix, false)
                setMetronomePlaying(true)
            }
            val pcmTone = renderStereo(toneOnly, 60)
            val pcmMetro = renderStereo(metroOnly, 60)
            val pcmBoth = renderStereo(both, 60)

            var maxDiff = 0
            for (i in pcmBoth.left.indices) {
                val dL = abs(pcmBoth.left[i].toInt() - (pcmTone.left[i].toInt() + pcmMetro.left[i].toInt()))
                val dR = abs(pcmBoth.right[i].toInt() - (pcmTone.right[i].toInt() + pcmMetro.right[i].toInt()))
                if (dL > maxDiff) maxDiff = dL
                if (dR > maxDiff) maxDiff = dR
            }
            assertTrue("Expected strictly linear summing (<=2 LSB) for WAV $sound, got $maxDiff", maxDiff <= 2)
            val combinedPeak = maxOf(pcmBoth.left.maxOf { abs(it.toInt()) }, pcmBoth.right.maxOf { abs(it.toInt()) })
            assertTrue("Expected clip-free combined peak <= 25400 for WAV $sound, got $combinedPeak", combinedPeak <= 25400)
        }
    }

    @Test
    fun bundledHarmonyInstrumentAssetsDecodeLoopAndMixLinearly() {
        val assetsRoot = listOf(File("src/main/assets/samples"), File("app/src/main/assets/samples"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("Expected assets/samples directory to exist")

        val voiceDirs = mapOf(
            ToneVoice.ORGAN to "organ",
            ToneVoice.BRASS to "brass",
            ToneVoice.WOODWIND to "woodwind",
            ToneVoice.PIANO to "piano"
        )
        val banks = voiceDirs.mapValues { (voice, folderName) ->
            val dir = File(assetsRoot, folderName)
            assertTrue("Expected sample directory for $voice: ${dir.path}", dir.isDirectory)
            val wavFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.sortedBy { it.name }.orEmpty()
            assertTrue("Expected multi-sample WAVs for $voice", wavFiles.size >= 14)
            wavFiles.map { wavFile ->
                val fallbackMidi = wavFile.name.removePrefix("m").removeSuffix(".wav").toIntOrNull() ?: 60
                val zone = WavSampleDecoder.decodeInstrumentZone(wavFile.readBytes(), fallbackMidi)
                assertNotNull("Expected WAV to decode cleanly: ${wavFile.path}", zone)
                val z = zone!!
                assertEquals("Expected 44.1 kHz sample rate for ${wavFile.name}", 44100, z.sampleRate)
                if (voice == ToneVoice.PIANO) {
                    assertTrue("Expected one-shot (no loop) for PIANO ${wavFile.name}", !z.hasLoop)
                } else {
                    assertTrue("Expected valid smpl loop points for $voice ${wavFile.name}", z.hasLoop)
                }
                z
            }
        }

        // Just Intonation C Major triad (C4, E4 -13.7c, G4 +2.0c)
        val justChord = mapOf(60 to 261.6256, 64 to 327.0320, 67 to 392.4383)
        for (voice in voiceDirs.keys) {
            val engine = HarmonyAudioEngine().apply {
                setInstrumentBanks(banks)
                setToneVoice(voice)
                setToneGain(0.85f)
                updateActiveNotes(justChord)
            }
            // Render ~2.13 seconds (200 frames = 102,400 samples @ 48 kHz) covering multiple loop cycles
            val pcm = renderStereo(engine, 200)
            val overallPeak = pcm.left.maxOf { abs(it.toInt()) }
            assertTrue("Expected audible playback for $voice, got peak=$overallPeak", overallPeak > 2000)
            assertTrue("Expected tone ceiling <= 8900 (-11.3 dBFS) for $voice, got $overallPeak", overallPeak <= 8900)

            if (voice != ToneVoice.PIANO) {
                // Sustained voices must remain continuously active well past initial 1.5s loop boundary
                val tailSlicePeak = pcm.left.copyOfRange(80000, pcm.left.size).maxOf { abs(it.toInt()) }
                assertTrue("Expected sustained looping past 1.6s for $voice, got tailPeak=$tailSlicePeak", tailSlicePeak > 1500)
            }
        }
    }
}
